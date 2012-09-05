/*
 * Copyright (c) 2008-2012, Hazel Bilisim Ltd. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hazelcast.impl.partition;

import com.hazelcast.core.Member;
import com.hazelcast.impl.MemberImpl;
import com.hazelcast.impl.Node;
import com.hazelcast.impl.ThreadContext;
import com.hazelcast.impl.base.SystemLogService;
import com.hazelcast.impl.spi.AbstractOperation;
import com.hazelcast.impl.spi.Invocation;
import com.hazelcast.impl.spi.NodeService;
import com.hazelcast.impl.spi.NodeServiceImpl;
import com.hazelcast.logging.ILogger;
import com.hazelcast.nio.Address;
import com.hazelcast.nio.Data;
import com.hazelcast.nio.IOUtil;
import com.hazelcast.partition.MigrationEvent;
import com.hazelcast.partition.MigrationListener;
import com.hazelcast.partition.PartitionService;
import com.hazelcast.util.Clock;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;

import static com.hazelcast.impl.spi.MigrationEndpoint.DESTINATION;
import static com.hazelcast.impl.spi.MigrationEndpoint.SOURCE;

public class PartitionServiceImpl {
    public static final String PARTITION_SERVICE_NAME = "hz:PartitionService";

    private static final long REPARTITIONING_CHECK_INTERVAL = TimeUnit.SECONDS.toMillis(300); // 5 MINUTES
    private static final int REPARTITIONING_TASK_COUNT_THRESHOLD = 20;
    private static final int REPARTITIONING_TASK_REPLICA_THRESHOLD = 2;

    final Node node;
    final NodeServiceImpl nodeService;
    private final ILogger logger;
    private final int partitionCount;
    private final PartitionInfo[] partitions;
    private final Lock lock = new ReentrantLock();
    private volatile boolean initialized = false; // updates will be done under lock, but reads will be multithreaded.
    private volatile MigrationInfo activeMigrationInfo; // updates will be done under lock, but reads will be multithreaded.
    private final AtomicInteger version = new AtomicInteger();
//    private final List<PartitionListener> lsPartitionListeners = new CopyOnWriteArrayList<PartitionListener>();
    private final MigrationService migrationService;
    private final int partitionMigrationInterval;
    private final long partitionMigrationTimeout;
    private final int immediateBackupInterval;
    private final BlockingQueue<Runnable> immediateTasksQueue = new LinkedBlockingQueue<Runnable>();
    private final Queue<Runnable> scheduledTasksQueue = new LinkedBlockingQueue<Runnable>();
    private final AtomicBoolean sendingDiffs = new AtomicBoolean(false);
    private final AtomicBoolean migrationActive = new AtomicBoolean(true);
    private final AtomicLong lastRepartitionTime = new AtomicLong();
    private final SystemLogService systemLogService;
    private final List<MigrationListener> migrationListeners = new CopyOnWriteArrayList<MigrationListener>();

    public PartitionServiceImpl(final Node node) {
        this.partitionCount = node.groupProperties.PARTITION_COUNT.getInteger();
        this.node = node;
        this.nodeService = node.nodeService;
        this.logger = this.node.getLogger(PartitionServiceImpl.class.getName());
        this.partitions = new PartitionInfo[partitionCount];
        this.systemLogService = node.getSystemLogService();
        for (int i = 0; i < partitionCount; i++) {
//            this.partitionLocks[i] = new AtomicLong(-1L);
            this.partitions[i] = new PartitionInfo(i, new PartitionListener() {
                public void replicaChanged(PartitionReplicaChangeEvent event) {
//                    for (PartitionListener partitionListener : lsPartitionListeners) {
//                        partitionListener.replicaChanged(event);
//                    }
                    if (event.getReplicaIndex() == 0 && event.getNewAddress() == null
                            && node.isActive() && node.joined()) {
                        final String warning = "Owner of partition is being removed! " +
                                "Possible data loss for partition[" + event.getPartitionId() + "]. "
                                + event;
                        logger.log(Level.WARNING, warning);
                        systemLogService.logPartition(warning);
                    }
                    if (node.isMaster()) {
                        version.incrementAndGet();
                    }
                }
            });
        }

        partitionMigrationInterval = node.groupProperties.PARTITION_MIGRATION_INTERVAL.getInteger() * 1000;
        // partitionMigrationTimeout is 1.5 times of real timeout
        partitionMigrationTimeout = (long) (node.groupProperties.PARTITION_MIGRATION_TIMEOUT.getLong() * 1.5f);
        immediateBackupInterval = node.groupProperties.IMMEDIATE_BACKUP_INTERVAL.getInteger() * 1000;

        migrationService = new MigrationService(node);
        migrationService.start();

        int partitionTableSendInterval = node.groupProperties.PARTITION_TABLE_SEND_INTERVAL.getInteger();
        if (partitionTableSendInterval <= 0) {
            partitionTableSendInterval = 1;
        }

        nodeService.getScheduledExecutorService().scheduleAtFixedRate(new SendClusterStateTask(),
                partitionTableSendInterval, partitionTableSendInterval, TimeUnit.SECONDS);

        nodeService.getScheduledExecutorService().scheduleAtFixedRate(new Runnable() {
            public void run() {
                if (node.isMaster() && node.isActive()
                        && initialized && shouldCheckRepartitioning()) {
                    logger.log(Level.FINEST, "Checking partition table for repartitioning...");
                    immediateTasksQueue.add(new CheckRepartitioningTask());
                }
            }
        }, 180, 180, TimeUnit.SECONDS);

        nodeService.registerService(PARTITION_SERVICE_NAME, this);
    }

    public Address getPartitionOwner(int partitionId) {
        if (!initialized) {
            firstArrangement();
        }
        if (partitions[partitionId].getOwner() == null && !node.isMaster() && node.getMasterAddress() != null) {
            nodeService.getExecutorService().execute(new Runnable() {
                public void run() {
                    nodeService.createSingleInvocation(PARTITION_SERVICE_NAME, new AssignPartitions(), -1)
                            .setTarget(node.getMasterAddress()).setTryCount(1).build().invoke();
                }
            });
        }
        return partitions[partitionId].getOwner();
    }

    public void firstArrangement() {
        if (!node.isMaster() || !node.isActive() || !hasStorageMember()) {
            return;
        }
        if (!initialized) {
            lock.lock();
            try {
                if (initialized) {
                    return;
                }
                PartitionStateGenerator psg = getPartitionStateGenerator();
                logger.log(Level.INFO, "Initializing cluster partition table first arrangement...");
                PartitionInfo[] newState = psg.initialize(node.getClusterService().getMembers(), partitionCount);
                if (newState != null) {
                    for (PartitionInfo partitionInfo : newState) {
                        partitions[partitionInfo.getPartitionId()].setPartitionInfo(partitionInfo);
                        for (int index = 0; index < PartitionInfo.MAX_REPLICA_COUNT; index++) {
                            Address to = partitionInfo.getReplicaAddress(index);
                            if (to != null) {
                                MigrationInfo mp = new MigrationInfo(partitionInfo.getPartitionId(),
                                        index, null, to);
                                sendMigrationEvent(mp, MigrationStatus.STARTED);
                                sendMigrationEvent(mp, MigrationStatus.COMPLETED);
                            }
                        }
                    }
                }
                initialized = true;
                sendPartitionRuntimeState();
            } finally {
                lock.unlock();
            }
        }
    }

    private void sendPartitionRuntimeState() {
        if (!node.isMaster() || !node.isActive() || !node.joined()) {
            return;
        }
        if (!migrationActive.get()) {
            // migration is disabled because of a member leave, wait till enabled!
            return;
        }
        if (!initialized) {
            // do not send partition state until initialized!
            return;
        }
        lock.lock();
        try {
            final long clusterTime = node.getClusterService().getClusterTime();
            final Collection<MemberImpl> members = node.clusterService.getMemberList();
            PartitionStateOperation op = new PartitionStateOperation(members, partitions, activeMigrationInfo,
                    clusterTime, version.get());
            for (MemberImpl member : members) {
                if (!member.localMember()) {
                    nodeService.createSingleInvocation(PARTITION_SERVICE_NAME, op, -1)
                            .setTarget(member.getAddress()).setTryCount(1).build().invoke();
                }
            }
        } finally {
            lock.unlock();
        }
    }

    public void syncForDead(final MemberImpl deadMember) {
        final Address deadAddress = deadMember.getAddress();
        final Address thisAddress = node.getThisAddress();
        if (deadAddress == null || deadAddress.equals(thisAddress)) {
            return;
        }
        if (!hasStorageMember()) {
            reset();
        }
        if (!deadMember.isLiteMember()) {
            clearTaskQueues();
            lock.lock();
            try {
                // inactivate migration and sending of PartitionRuntimeState (@see #sendPartitionRuntimeState)
                // let all members notice the dead and fix their own records and indexes.
                // otherwise new master may take action fast and send new partition state
                // before other members realize the dead one and fix their records.
                final boolean migrationStatus = migrationActive.getAndSet(false);
                // list of partitions those have dead member in their replicas
                // !! this should be calculated before dead member is removed from partition table !!
                int[] indexesOfDead = new int[partitions.length];
                for (PartitionInfo partition : partitions) {
                    indexesOfDead[partition.getPartitionId()] = partition.getReplicaIndexOf(deadAddress);
                }
                // shift partition table up.
                for (PartitionInfo partition : partitions) {
                    // safe removal of dead address from partition table.
                    // there might be duplicate dead address in partition table
                    // during migration tasks' execution (when there are multiple backups and
                    // copy backup tasks; see MigrationRequestOperation selfCopyReplica.)
                    // or because of a bug.
                    while (partition.onDeadAddress(deadAddress)) ;
                }
                //        fixCMapsForDead(deadAddress, indexesOfDead);
                fixReplicasAndPartitionsForDead(deadMember, indexesOfDead);
                // activate migration back after connectionDropTime x 10 milliseconds,
                // thinking optimistically that all nodes notice the dead one in this period.
                final long waitBeforeMigrationActivate = node.groupProperties.CONNECTION_MONITOR_INTERVAL.getLong()
                        * node.groupProperties.CONNECTION_MONITOR_MAX_FAULTS
                        .getInteger() * 10;
                nodeService.getScheduledExecutorService().schedule(new Runnable() {
                    public void run() {
                        migrationActive.compareAndSet(false, migrationStatus);
                    }
                }, waitBeforeMigrationActivate, TimeUnit.MILLISECONDS);
            } finally {
                lock.unlock();
            }
        }
    }

    private void fixReplicasAndPartitionsForDead(final MemberImpl deadMember, final int[] indexesOfDead) {
        if (!deadMember.isLiteMember() && node.isMaster() && node.isActive()) {
            lock.lock();
            try {
                sendingDiffs.set(true);
                logger.log(Level.INFO, "Starting to send partition replica diffs..." + sendingDiffs.get());
                int diffCount = 0;
                final int maxBackupCount = getMaxBackupCount();
                for (int partitionId = 0; partitionId < indexesOfDead.length; partitionId++) {
                    int indexOfDead = indexesOfDead[partitionId];
                    if (indexOfDead != -1) {
                        PartitionInfo partition = partitions[partitionId];
                        Address owner = partition.getOwner();
                        if (owner == null) {
                            logger.log(Level.FINEST, "Owner of one of the replicas of Partition[" +
                                    partitionId + "] is dead, but partition owner " +
                                    "could not be found either!");
                            logger.log(Level.FINEST, partition.toString());
                            continue;
                        }
                        // send replica diffs to new replica owners after partition table shift.
                        for (int replicaIndex = indexOfDead; replicaIndex < maxBackupCount; replicaIndex++) {
                            Address target = partition.getReplicaAddress(replicaIndex);
                            if (target != null && !target.equals(owner)) {
                                if (getMember(target) != null) {
                                    MigrationRequestOperation mrt = new MigrationRequestOperation(partitionId, owner,
                                            target, replicaIndex, false, true);
                                    immediateTasksQueue.offer(new Migrator(mrt));
                                    diffCount++;
                                } else {
                                    logger.log(Level.WARNING, "Target member of replica diff task couldn't found! "
                                            + "Replica: " + replicaIndex + ", Dead: " + deadMember +
                                            "\n" + partition);
                                }
                            }
                        }
                        // if index of dead member is equal to or less than maxBackupCount
                        // clear indexes of equal to and greater than maxBackupCount of partition.
                        if (indexOfDead <= maxBackupCount) {
                            for (int index = maxBackupCount; index < PartitionInfo.MAX_REPLICA_COUNT; index++) {
                                partition.setReplicaAddress(index, null);
                            }
                        }
                    }
                }
                sendPartitionRuntimeState();
                final int totalDiffCount = diffCount;
                immediateTasksQueue.offer(new Runnable() {
                    public void run() {
                        logger.log(Level.INFO,
                                "Total " + totalDiffCount + " partition replica diffs have been processed.");
                        sendingDiffs.set(false);
                    }
                });
                immediateTasksQueue.offer(new PrepareRepartitioningTask());
            } finally {
                lock.unlock();
            }
        }
    }

    private int getMaxBackupCount() {
//        final Collection<CMap> cmaps = node.concurrentMapManager.maps.values();
//        if (!cmaps.isEmpty()) {
//            int maxBackupCount = 0;
//            for (final CMap cmap : cmaps) {
//                maxBackupCount = Math.max(maxBackupCount, cmap.getTotalBackupCount());
//            }
//            return maxBackupCount;
//        }
        return 1; // if there is no map, avoid extra processing.
    }

    public void syncForAdd() {
        if (node.isMaster() && node.isActive()) {
            if (sendingDiffs.get()) {
                logger.log(Level.INFO, "MigrationService is already sending diffs for dead member, " +
                        "no need to initiate task!");
            } else {
                // to avoid repartitioning during a migration process.
                clearTaskQueues();
                immediateTasksQueue.offer(new PrepareRepartitioningTask());
            }
        }
    }

    void processPartitionRuntimeState(PartitionRuntimeState partitionState) {
        lock.lock();
        try {
            final Address sender = partitionState.getEndpoint();
            if (node.isMaster()) {
                logger.log(Level.WARNING, "This is the master node and received a PartitionRuntimeState from "
                        + sender + ". Ignoring incoming state! ");
                return;
            } else {
                final Address master = node.getMasterAddress();
                if (sender == null || master == null || !master.equals(sender)) {
                    logger.log(Level.WARNING, "Received a PartitionRuntimeState, but its sender doesn't seem master!" +
                            " => Sender: " + sender + ", Master: " + master + "! " +
                            "(Ignore if master node has changed recently.)");
                }
            }
            PartitionInfo[] newPartitions = partitionState.getPartitions();
            for (PartitionInfo newPartition : newPartitions) {
                PartitionInfo currentPartition = partitions[newPartition.getPartitionId()];
                for (int index = 0; index < PartitionInfo.MAX_REPLICA_COUNT; index++) {
                    Address address = newPartition.getReplicaAddress(index);
                    if (address != null && getMember(address) == null) {
                        logger.log(Level.WARNING,
                                "Unknown " + address + " is found in received partition table from master "
                                        + sender + ". Probably it is dead. Partition: " + newPartition);
                    }
                }
                currentPartition.setPartitionInfo(newPartition);
            }
            final MigrationInfo masterMigrationInfo = partitionState.getMigrationInfo();
            if (masterMigrationInfo == null && activeMigrationInfo != null) {
                checkAndFinalizeActiveMigration();
            }
            initialized = true;
            version.set(partitionState.getVersion());
        } finally {
            lock.unlock();
        }
    }

    private void checkAndFinalizeActiveMigration() {
        lock.lock();
        try {
            final MigrationInfo migrationInfo = activeMigrationInfo;
            if (migrationInfo != null) {
                try {
                    final Address thisAddress = node.getThisAddress();
                    final boolean source = thisAddress.equals(migrationInfo.getFromAddress());
                    final boolean destination = thisAddress.equals(migrationInfo.getToAddress());
                    if (source || destination) {
                        final int partitionId = migrationInfo.getPartitionId();
                        final int replicaIndex = migrationInfo.getReplicaIndex();
                        PartitionInfo migratingPartition = getPartition(partitionId);
                        final Address address = migratingPartition.getReplicaAddress(replicaIndex);
                        final boolean success = migrationInfo.getToAddress().equals(address);
                        final FinalizeMigrationOperation op = new FinalizeMigrationOperation(source ? SOURCE : DESTINATION,
                                success);
                        Invocation inv = nodeService.createSingleInvocation(PARTITION_SERVICE_NAME, op, partitionId)
                                .setReplicaIndex(replicaIndex).setTarget(node.getThisAddress())
                                .setTryCount(1)
                                .build();
                        inv.invoke().get();
                    }
                } catch (Exception e) {
                    logger.log(Level.WARNING, e.getMessage(), e);
                } finally {
                    setActiveMigration(null);
                }
            }
        } finally {
            lock.unlock();
        }
    }

    public boolean isPartitionLocked(int partitionId) {
        final MigrationInfo migrationInfo = activeMigrationInfo;
        return migrationInfo != null && migrationInfo.getPartitionId() == partitionId;
    }

    void setActiveMigration(MigrationInfo migrationInfo) {
        lock.lock();
        try {
            activeMigrationInfo = migrationInfo;
        } finally {
            lock.unlock();
        }
    }

    private PartitionStateGenerator getPartitionStateGenerator() {
        return PartitionStateGeneratorFactory.newConfigPartitionStateGenerator(
                node.getConfig().getPartitionGroupConfig());
    }

    private boolean hasStorageMember() {
        for (Member member : node.getClusterService().getMembers()) {
            if (!member.isLiteMember()) {
                return true;
            }
        }
        return false;
    }

    public PartitionInfo[] getPartitions() {
        return partitions;
    }

    public MemberImpl getMember(Address address) {
        return node.clusterService.getMember(address);
    }

    public int getVersion() {
        return version.get();
    }

    public PartitionInfo getPartition(int partitionId) {
        return partitions[partitionId];
    }

    public boolean hasActiveBackupTask() {
        if (!initialized) return false;
        if (node.isLiteMember()) return false;
        int maxBackupCount = getMaxBackupCount();
        if (maxBackupCount == 0) return false;
        MemberGroupFactory mgf = PartitionStateGeneratorFactory.newMemberGroupFactory(
                node.config.getPartitionGroupConfig());
        if (mgf.createMemberGroups(node.getClusterService().getMembers()).size() < 2) return false;
        boolean needBackup = false;
        if (immediateTasksQueue.isEmpty()) {
            for (PartitionInfo partition : partitions) {
                if (partition.getReplicaAddress(1) == null) {
                    needBackup = true;
                    logger.log(Level.WARNING, node.getThisAddress()
                            + " still has no replica for partitionId:" + partition.getPartitionId());
                    break;
                }
            }
        }
        return needBackup || !immediateTasksQueue.isEmpty();
    }

    private boolean shouldCheckRepartitioning() {
        return immediateTasksQueue.isEmpty() && scheduledTasksQueue.isEmpty()
                && lastRepartitionTime.get() < (Clock.currentTimeMillis() - REPARTITIONING_CHECK_INTERVAL);
    }

    public final int getPartitionId(Data key) {
        int hash = key.getPartitionHash();
        return (hash == Integer.MIN_VALUE) ? 0 : Math.abs(hash) % partitionCount;
    }

    public final int getPartitionId(Object key) {
        return getPartitionId(nodeService.toData(key));
    }

    public int getPartitionCount() {
        return partitionCount;
    }

    public static class AssignPartitions extends AbstractOperation {
        public void run() {
            final PartitionServiceImpl service = getService();
            service.firstArrangement();
        }
    }

    private class SendClusterStateTask implements Runnable {
        public void run() {
            if (node.isMaster() && node.isActive()) {
                if ((!scheduledTasksQueue.isEmpty() || !immediateTasksQueue.isEmpty()) && migrationActive.get()) {
                    logger.log(Level.INFO, "Remaining migration tasks in queue => Immediate-Tasks: " + immediateTasksQueue.size()
                                + ", Scheduled-Tasks: " + scheduledTasksQueue.size());
                }
                sendPartitionRuntimeState();
            }
        }
    }

    private class PrepareRepartitioningTask implements Runnable {
        final List<MigrationRequestOperation> lostQ = new ArrayList<MigrationRequestOperation>();
        final List<MigrationRequestOperation> scheduledQ = new ArrayList<MigrationRequestOperation>(partitionCount);
        final List<MigrationRequestOperation> immediateQ = new ArrayList<MigrationRequestOperation>(partitionCount * 2);

        private PrepareRepartitioningTask() {
        }

        public final void run() {
            if (node.isMaster() && node.isActive() && initialized) {
                doRun();
            }
        }

        void doRun() {
            prepareMigrationTasks();
            logger.log(Level.INFO, "Re-partitioning cluster data... Immediate-Tasks: "
                    + immediateQ.size() + ", Scheduled-Tasks: " + scheduledQ.size());
            fillMigrationQueues();
        }

        void prepareMigrationTasks() {
            PartitionStateGenerator psg = getPartitionStateGenerator();
            psg.reArrange(partitions, node.getClusterService().getMembers(),
                    partitionCount, lostQ, immediateQ, scheduledQ);
        }

        void fillMigrationQueues() {
            lastRepartitionTime.set(Clock.currentTimeMillis());
            if (!lostQ.isEmpty()) {
                immediateTasksQueue.offer(new AssignLostPartitions(lostQ));
                logger.log(Level.WARNING, "Assigning new owners for " + lostQ.size() +
                        " LOST partitions!");
            }
            for (MigrationRequestOperation migrationRequestTask : immediateQ) {
                immediateTasksQueue.offer(new Migrator(migrationRequestTask));
            }
            immediateQ.clear();
            for (MigrationRequestOperation migrationRequestTask : scheduledQ) {
                scheduledTasksQueue.offer(new Migrator(migrationRequestTask));
            }
            scheduledQ.clear();
        }
    }

    private class AssignLostPartitions implements Runnable {
        final List<MigrationRequestOperation> lostQ;

        private AssignLostPartitions(final List<MigrationRequestOperation> lostQ) {
            this.lostQ = lostQ;
        }

        public void run() {
            if (!node.isMaster() || !node.isActive()) return;
            lock.lock();
            try {
                for (MigrationRequestOperation migrationRequestOp : lostQ) {
                    int partitionId = migrationRequestOp.getPartitionId();
                    int replicaIndex = migrationRequestOp.getReplicaIndex();
                    if (replicaIndex != 0 || partitionId >= partitionCount) {
                        logger.log(Level.WARNING, "Wrong task for lost partitions assignment process" +
                                " => " + migrationRequestOp);
                        continue;
                    }
                    PartitionInfo partition = partitions[partitionId];
                    Address newOwner = migrationRequestOp.getToAddress();
                    MemberImpl ownerMember = node.clusterService.getMember(newOwner);
                    if (ownerMember != null) {
                        partition.setReplicaAddress(replicaIndex, newOwner);
                        final MigrationInfo mp = migrationRequestOp.createMigrationInfo();
                        sendMigrationEvent(mp, MigrationStatus.STARTED);
                        sendMigrationEvent(mp, MigrationStatus.COMPLETED);
                    }
                }
                sendPartitionRuntimeState();
            } finally {
                lock.unlock();
            }
        }
    }

    private class CheckRepartitioningTask extends PrepareRepartitioningTask implements Runnable {
        void doRun() {
            if (shouldCheckRepartitioning()) {
                final int v = version.get();
                prepareMigrationTasks();
                int totalTasks = 0;
                for (MigrationRequestOperation task : immediateQ) {
                    if (task.getReplicaIndex() <= REPARTITIONING_TASK_REPLICA_THRESHOLD) {
                        totalTasks++;
                    }
                }
                for (MigrationRequestOperation task : scheduledQ) {
                    if (task.getReplicaIndex() <= REPARTITIONING_TASK_REPLICA_THRESHOLD) {
                        totalTasks++;
                    }
                }
                if (!lostQ.isEmpty() || totalTasks > REPARTITIONING_TASK_COUNT_THRESHOLD) {
                    logger.log(Level.WARNING, "Something weird! Migration task queues are empty," +
                            " last repartitioning executed on " + lastRepartitionTime.get() +
                            " but repartitioning check resulted " + totalTasks + " tasks" +
                            " and " + lostQ.size() + " lost partitions!");
                    if (version.get() == v && shouldCheckRepartitioning()) {
                        fillMigrationQueues();
                    }
                }
            }
        }
    }

    private class Migrator implements Runnable {
        final MigrationRequestOperation migrationRequestOp;

        Migrator(MigrationRequestOperation migrationRequestOperation) {
            this.migrationRequestOp = migrationRequestOperation;
        }

        public void run() {
            try {
                if (!node.isActive() || !node.isMaster()) {
                    return;
                }
                if (migrationRequestOp.isMigration() && migrationRequestOp.getReplicaIndex() == 0) {
                    sendMigrationEvent(migrationRequestOp.createMigrationInfo(), MigrationStatus.STARTED);
                }
                if (migrationRequestOp.getToAddress() == null) {
                    // A member is dead, this replica should not have an owner!
                    logger.log(Level.FINEST, "Fixing partition, " + migrationRequestOp.getReplicaIndex()
                            + ". replica of partition[" + migrationRequestOp.getPartitionId() + "] should be removed.");
                    removeReplicaOwner();
                } else {
                    MemberImpl fromMember = null;
                    Boolean result = Boolean.FALSE;
                    // ignore fromAddress of task and get actual owner from partition table
                    final int partitionId = migrationRequestOp.getPartitionId();
                    fromMember = getMember(partitions[partitionId].getOwner());
                    logger.log(Level.FINEST, "Started Migration : " + migrationRequestOp);
                    systemLogService.logPartition("Started Migration : " + migrationRequestOp);
                    if (fromMember != null) {
                        setActiveMigration(migrationRequestOp.createMigrationInfo());
                        migrationRequestOp.setFromAddress(fromMember.getAddress());
                        Invocation inv = node.nodeService.createSingleInvocation(PARTITION_SERVICE_NAME,
                                migrationRequestOp, migrationRequestOp.getPartitionId())
                                .setTryCount(3).setTryPauseMillis(1000).setTarget(migrationRequestOp.getFromAddress())
                                .setReplicaIndex(migrationRequestOp.getReplicaIndex()).build();

                        Future future = inv.invoke();
                        try {
                            result = (Boolean) IOUtil.toObject(future.get(partitionMigrationTimeout, TimeUnit.SECONDS));
                        } catch (Throwable e) {
                            logger.log(Level.WARNING, "Failed migrating from " + fromMember, e);
                        }
                    } else {
                        // Partition is lost! Assign new owner and exit.
                        result = Boolean.TRUE;
                    }
                    logger.log(Level.FINEST, "Finished Migration : " + migrationRequestOp);
                    systemLogService.logPartition("Finished Migration : " + migrationRequestOp);
                    if (Boolean.TRUE.equals(result)) {
                        processMigrationResult();
                    } else {
                        // remove active partition migration
                        logger.log(Level.WARNING, "Migration task has failed => " + migrationRequestOp);
                        migrationTaskFailed();
                    }
                }
            } catch (Throwable t) {
                logger.log(Level.WARNING, "Error [" + t.getClass() + ": " + t.getMessage() + "] " +
                        "while executing " + migrationRequestOp);
                logger.log(Level.FINEST, t.getMessage(), t);
                migrationTaskFailed();
            }
        }

        private void migrationTaskFailed() {
            systemLogService.logPartition("Migration task has failed => " + migrationRequestOp);
            checkAndFinalizeActiveMigration();
            sendPartitionRuntimeState();
            if (migrationRequestOp.getReplicaIndex() == 0) {
                sendMigrationEvent(migrationRequestOp.createMigrationInfo(), MigrationStatus.FAILED);
            }
        }

        private void processMigrationResult() {
            int partitionId = migrationRequestOp.getPartitionId();
            int replicaIndex = migrationRequestOp.getReplicaIndex();
            PartitionInfo partition = partitions[partitionId];
            if (PartitionInfo.MAX_REPLICA_COUNT < replicaIndex) {
                String msg = "Migrated [" + partitionId + ":" + replicaIndex
                        + "] but cannot assign. Length:" + PartitionInfo.MAX_REPLICA_COUNT;
                logger.log(Level.WARNING, msg);
            } else {
                lock.lock();
                try {
                    Address newOwner = migrationRequestOp.getToAddress();
                    MemberImpl ownerMember = node.clusterService.getMember(newOwner);
                    if (ownerMember == null) return;
                    partition.setReplicaAddress(replicaIndex, newOwner);
                    // if this partition should be copied back,
                    // just set partition's replica address
                    // before data is cleaned up.
                    if (migrationRequestOp.getSelfCopyReplicaIndex() > -1) {
                        partition.setReplicaAddress(migrationRequestOp.getSelfCopyReplicaIndex(),
                                migrationRequestOp.getFromAddress());
                    }
                    checkAndFinalizeActiveMigration();
                    sendPartitionRuntimeState();
                    if (migrationRequestOp.getReplicaIndex() == 0) {
                        sendMigrationEvent(migrationRequestOp.createMigrationInfo(), MigrationStatus.COMPLETED);
                    }
                } finally {
                    lock.unlock();
                }
            }
        }

        private void removeReplicaOwner() {
            lock.lock();
            try {
                int partitionId = migrationRequestOp.getPartitionId();
                int replicaIndex = migrationRequestOp.getReplicaIndex();
                PartitionInfo partition = partitions[partitionId];
                partition.setReplicaAddress(replicaIndex, null);
            } finally {
                lock.unlock();
            }
        }
    }

    private class MigrationService implements Runnable {
        private final Thread thread;
        private boolean running = true;

        MigrationService(Node node) {
            thread = new Thread(node.threadGroup, this, node.getThreadNamePrefix("MigrationThread"));
        }

        public void run() {
            ThreadContext.get().setCurrentInstance(node.hazelcastInstance);
            try {
                while (running) {
                    Runnable r = null;
                    while (isActive() && (r = immediateTasksQueue.poll()) != null) {
                        safeRunImmediate(r);
                    }
                    if (!running) {
                        break;
                    }
                    // wait for partitionMigrationInterval before executing scheduled tasks
                    // and poll immediate tasks occasionally during wait time.
                    long totalWait = 0L;
                    while (isActive() && (r != null || totalWait < partitionMigrationInterval)) {
                        long start = Clock.currentTimeMillis();
                        r = immediateTasksQueue.poll(1, TimeUnit.SECONDS);
                        safeRunImmediate(r);
                        totalWait += (Clock.currentTimeMillis() - start);
                    }
                    if (isActive()) {
                        r = scheduledTasksQueue.poll();
                        safeRun(r);
                    }
                    if (!migrationActive.get() || hasNoTasks()) {
                        Thread.sleep(250);
                        continue;
                    }
                }
            } catch (InterruptedException e) {
                logger.log(Level.FINEST, "MigrationService is interrupted: " + e.getMessage(), e);
                running = false;
            } finally {
                clearTaskQueues();
            }
        }

        private boolean hasNoTasks() {
            return (immediateTasksQueue.isEmpty() && scheduledTasksQueue.isEmpty());
        }

        private boolean isActive() {
            return running && !thread.isInterrupted() && migrationActive.get();
        }

        private boolean safeRun(final Runnable r) {
            if (r == null || !running) return false;
            try {
                r.run();
            } catch (Throwable t) {
                logger.log(Level.WARNING, t.getMessage(), t);
            }
            return true;
        }

        private void safeRunImmediate(final Runnable r) throws InterruptedException {
            if (safeRun(r) && immediateBackupInterval > 0) {
                Thread.sleep(immediateBackupInterval);
            }
        }

        private void start() {
            thread.start();
        }

        private void stop() {
            clearTaskQueues();
            try {
                final CountDownLatch stopLatch = new CountDownLatch(1);
                immediateTasksQueue.offer(new Runnable() {
                    public void run() {
                        running = false;
                        stopLatch.countDown();
                    }
                });
                stopLatch.await(1, TimeUnit.SECONDS);
            } catch (Throwable ignore) {
            }
        }

        private void stopNow() {
            clearTaskQueues();
            immediateTasksQueue.offer(new Runnable() {
                public void run() {
                    running = false;
                }
            });
            thread.interrupt();
        }

        private void checkAccess() {
            if (Thread.currentThread() != thread) {
                String msg = "Only migration thread can access this method. " + Thread.currentThread();
                logger.log(Level.SEVERE, msg);
                throw new Error(msg);
            }
        }
    }

    public void reset() {
        clearTaskQueues();
        lock.lock();
        try {
            initialized = false;
            for (PartitionInfo partition : partitions) {
                for (int i = 0; i < PartitionInfo.MAX_REPLICA_COUNT; i++) {
                    partition.setReplicaAddress(i, null);
                }
            }
            version.set(0);
        } finally {
            lock.unlock();
        }
    }

    public void onRestart() {
        reset();
    }

    public void shutdown() {
        logger.log(Level.FINEST, "Shutting down the partition manager");
        migrationService.stopNow();
        reset();
    }

    private void clearTaskQueues() {
        immediateTasksQueue.clear();
        scheduledTasksQueue.clear();
    }

    // for testing purposes only
    void forcePartitionOwnerMigration(int partitionId, int replicaIndex, Address from, Address to) {
        MigrationRequestOperation mrt = new MigrationRequestOperation(partitionId, from, to, replicaIndex, true);
        immediateTasksQueue.offer(new Migrator(mrt));
    }

    public PartitionService getPartitionServiceProxy() {
        return new PartitionServiceProxy(this);
    }

    private void sendMigrationEvent(final MigrationInfo migrationInfo, final MigrationStatus status) {
        final Collection<MemberImpl> members = node.clusterService.getMemberList();
        for (MemberImpl member : members) {
            if (!member.localMember()) {
                nodeService.createSingleInvocation(PARTITION_SERVICE_NAME,
                        new MigrationEventOperation(status, migrationInfo), NodeService.EVENT_THREAD_ID)
                        .setTarget(member.getAddress()).setTryCount(1).build().invoke();
            }
        }
        nodeService.getEventService().execute(new Runnable() {
            public void run() {
                fireMigrationEvent(migrationInfo, status);
            }
        });
    }

    void fireMigrationEvent(MigrationInfo migrationInfo, final MigrationStatus status) {
        final MemberImpl current = node.clusterService.getMember(migrationInfo.getFromAddress());
        final MemberImpl newOwner = node.clusterService.getMember(migrationInfo.getToAddress());
        final MigrationEvent migrationEvent = new MigrationEvent(node, migrationInfo.partitionId, current, newOwner);
        systemLogService.logPartition("MigrationEvent [" + status + "] " + migrationEvent);
        callListeners(status, migrationEvent);
    }

    private void callListeners(final MigrationStatus status, final MigrationEvent migrationEvent) {
        if (migrationEvent == null) {
            throw new IllegalArgumentException("MigrationEvent is null.");
        }
        for (final MigrationListener migrationListener : migrationListeners) {
            switch (status) {
                case STARTED:
                    migrationListener.migrationStarted(migrationEvent);
                    break;
                case COMPLETED:
                    migrationListener.migrationCompleted(migrationEvent);
                    break;
                case FAILED:
                    migrationListener.migrationFailed(migrationEvent);
                    break;
            }
        }
    }

    public void addMigrationListener(MigrationListener migrationListener) {
        migrationListeners.add(migrationListener);
    }

    public void removeMigrationListener(MigrationListener migrationListener) {
        migrationListeners.remove(migrationListener);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("PartitionManager[" + version + "] {\n");
        sb.append("\n");
        sb.append("immediateQ: ").append(immediateTasksQueue.size());
        sb.append(", scheduledQ: ").append(scheduledTasksQueue.size());
        sb.append("\n}");
        return sb.toString();
    }
}