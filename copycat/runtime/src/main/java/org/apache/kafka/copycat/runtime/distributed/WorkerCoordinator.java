/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 **/
package org.apache.kafka.copycat.runtime.distributed;

import org.apache.kafka.clients.consumer.internals.AbstractCoordinator;
import org.apache.kafka.clients.consumer.internals.ConsumerNetworkClient;
import org.apache.kafka.common.MetricName;
import org.apache.kafka.common.metrics.Measurable;
import org.apache.kafka.common.metrics.MetricConfig;
import org.apache.kafka.common.metrics.Metrics;
import org.apache.kafka.common.utils.CircularIterator;
import org.apache.kafka.common.utils.Time;
import org.apache.kafka.copycat.storage.KafkaConfigStorage;
import org.apache.kafka.copycat.util.ConnectorTaskId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * This class manages the coordination process with the Kafka group coordinator on the broker for managing Copycat assignments to workers.
 */
public final class WorkerCoordinator extends AbstractCoordinator implements Closeable {
    private static final Logger log = LoggerFactory.getLogger(WorkerCoordinator.class);

    // Currently Copycat doesn't support multiple task assignment strategies, so we currently just fill in a default value
    public static final String DEFAULT_SUBPROTOCOL = "default";

    private final String restUrl;
    private final KafkaConfigStorage configStorage;
    private CopycatProtocol.Assignment assignmentSnapshot;
    private final CopycatWorkerCoordinatorMetrics sensors;
    private ClusterConfigState configSnapshot;
    private final WorkerRebalanceListener listener;

    private boolean rejoinRequested;

    /**
     * Initialize the coordination manager.
     */
    public WorkerCoordinator(ConsumerNetworkClient client,
                             String groupId,
                             int sessionTimeoutMs,
                             int heartbeatIntervalMs,
                             Metrics metrics,
                             String metricGrpPrefix,
                             Map<String, String> metricTags,
                             Time time,
                             long requestTimeoutMs,
                             long retryBackoffMs,
                             String restUrl,
                             KafkaConfigStorage configStorage,
                             WorkerRebalanceListener listener) {
        super(client,
                groupId,
                sessionTimeoutMs,
                heartbeatIntervalMs,
                metrics,
                metricGrpPrefix,
                metricTags,
                time,
                requestTimeoutMs,
                retryBackoffMs);
        this.restUrl = restUrl;
        this.configStorage = configStorage;
        this.assignmentSnapshot = null;
        this.sensors = new CopycatWorkerCoordinatorMetrics(metrics, metricGrpPrefix, metricTags);
        this.listener = listener;
        this.rejoinRequested = false;
    }

    public void requestRejoin() {
        rejoinRequested = true;
    }

    @Override
    public String protocolType() {
        return "copycat";
    }

    @Override
    public LinkedHashMap<String, ByteBuffer> metadata() {
        LinkedHashMap<String, ByteBuffer> metadata = new LinkedHashMap<>();
        configSnapshot = configStorage.snapshot();
        CopycatProtocol.WorkerState workerState = new CopycatProtocol.WorkerState(restUrl, configSnapshot.offset());
        metadata.put(DEFAULT_SUBPROTOCOL, CopycatProtocol.serializeMetadata(workerState));
        return metadata;
    }

    @Override
    protected void onJoinComplete(int generation, String memberId, String protocol, ByteBuffer memberAssignment) {
        assignmentSnapshot = CopycatProtocol.deserializeAssignment(memberAssignment);
        // At this point we always consider ourselves to be a member of the cluster, even if there was an assignment
        // error (the leader couldn't make the assignment) or we are behind the config and cannot yet work on our assigned
        // tasks. It's the responsibility of the code driving this process to decide how to react (e.g. trying to get
        // up to date, try to rejoin again, leaving the group and backing off, etc.).
        rejoinRequested = false;
        listener.onAssigned(assignmentSnapshot);
    }

    @Override
    protected Map<String, ByteBuffer> performAssignment(String leaderId, String protocol, Map<String, ByteBuffer> allMemberMetadata) {
        log.debug("Performing task assignment");

        Map<String, CopycatProtocol.WorkerState> allConfigs = new HashMap<>();
        for (Map.Entry<String, ByteBuffer> entry : allMemberMetadata.entrySet())
            allConfigs.put(entry.getKey(), CopycatProtocol.deserializeMetadata(entry.getValue()));

        long maxOffset = findMaxMemberConfigOffset(allConfigs);
        Long leaderOffset = ensureLeaderConfig(maxOffset);
        if (leaderOffset == null)
            return fillAssignmentsAndSerialize(allConfigs.keySet(), CopycatProtocol.Assignment.CONFIG_MISMATCH,
                    leaderId, allConfigs.get(leaderId).url(), maxOffset,
                    new HashMap<String, List<String>>(), new HashMap<String, List<ConnectorTaskId>>());
        return performTaskAssignment(leaderId, leaderOffset, allConfigs);
    }

    private long findMaxMemberConfigOffset(Map<String, CopycatProtocol.WorkerState> allConfigs) {
        // The new config offset is the maximum seen by any member. We always perform assignment using this offset,
        // even if some members have fallen behind. The config offset used to generate the assignment is included in
        // the response so members that have fallen behind will not use the assignment until they have caught up.
        Long maxOffset = null;
        for (Map.Entry<String, CopycatProtocol.WorkerState> stateEntry : allConfigs.entrySet()) {
            long memberRootOffset = stateEntry.getValue().offset();
            if (maxOffset == null)
                maxOffset = memberRootOffset;
            else
                maxOffset = Math.max(maxOffset, memberRootOffset);
        }

        log.debug("Max config offset root: {}, local snapshot config offsets root: {}",
                maxOffset, configSnapshot.offset());
        return maxOffset;
    }

    private Long ensureLeaderConfig(long maxOffset) {
        // If this leader is behind some other members, we can't do assignment
        if (configSnapshot.offset() < maxOffset) {
            // We might be able to take a new snapshot to catch up immediately and avoid another round of syncing here.
            // Alternatively, if this node has already passed the maximum reported by any other member of the group, it
            // is also safe to use this newer state.
            ClusterConfigState updatedSnapshot = configStorage.snapshot();
            if (updatedSnapshot.offset() < maxOffset) {
                log.info("Was selected to perform assignments, but do not have latest config found in sync request. " +
                        "Returning an empty configuration to trigger re-sync.");
                return null;
            } else {
                configSnapshot = updatedSnapshot;
                return configSnapshot.offset();
            }
        }

        return maxOffset;
    }

    private Map<String, ByteBuffer> performTaskAssignment(String leaderId, long maxOffset, Map<String, CopycatProtocol.WorkerState> allConfigs) {
        Map<String, List<String>> connectorAssignments = new HashMap<>();
        Map<String, List<ConnectorTaskId>> taskAssignments = new HashMap<>();

        // Perform round-robin task assignment
        CircularIterator<String> memberIt = new CircularIterator<>(sorted(allConfigs.keySet()));
        for (String connectorId : sorted(configSnapshot.connectors())) {
            String connectorAssignedTo = memberIt.next();
            log.trace("Assigning connector {} to {}", connectorId, connectorAssignedTo);
            List<String> memberConnectors = connectorAssignments.get(connectorAssignedTo);
            if (memberConnectors == null) {
                memberConnectors = new ArrayList<>();
                connectorAssignments.put(connectorAssignedTo, memberConnectors);
            }
            memberConnectors.add(connectorId);

            for (ConnectorTaskId taskId : sorted(configSnapshot.tasks(connectorId))) {
                String taskAssignedTo = memberIt.next();
                log.trace("Assigning task {} to {}", taskId, taskAssignedTo);
                List<ConnectorTaskId> memberTasks = taskAssignments.get(taskAssignedTo);
                if (memberTasks == null) {
                    memberTasks = new ArrayList<>();
                    taskAssignments.put(taskAssignedTo, memberTasks);
                }
                memberTasks.add(taskId);
            }
        }

        return fillAssignmentsAndSerialize(allConfigs.keySet(), CopycatProtocol.Assignment.NO_ERROR,
                leaderId, allConfigs.get(leaderId).url(), maxOffset, connectorAssignments, taskAssignments);
    }

    private Map<String, ByteBuffer> fillAssignmentsAndSerialize(Collection<String> members,
                                                                short error,
                                                                String leaderId,
                                                                String leaderUrl,
                                                                long maxOffset,
                                                                Map<String, List<String>> connectorAssignments,
                                                                Map<String, List<ConnectorTaskId>> taskAssignments) {

        Map<String, ByteBuffer> groupAssignment = new HashMap<>();
        for (String member : members) {
            List<String> connectors = connectorAssignments.get(member);
            if (connectors == null)
                connectors = Collections.emptyList();
            List<ConnectorTaskId> tasks = taskAssignments.get(member);
            if (tasks == null)
                tasks = Collections.emptyList();
            CopycatProtocol.Assignment assignment = new CopycatProtocol.Assignment(error, leaderId, leaderUrl, maxOffset, connectors, tasks);
            log.debug("Assignment: {} -> {}", member, assignment);
            groupAssignment.put(member, CopycatProtocol.serializeAssignment(assignment));
        }
        log.debug("Finished assignment");
        return groupAssignment;
    }

    @Override
    protected void onJoinPrepare(int generation, String memberId) {
        log.debug("Revoking previous assignment {}", assignmentSnapshot);
        if (assignmentSnapshot != null && !assignmentSnapshot.failed())
            listener.onRevoked(assignmentSnapshot.leader(), assignmentSnapshot.connectors(), assignmentSnapshot.tasks());
    }

    @Override
    public boolean needRejoin() {
        return super.needRejoin() || (assignmentSnapshot == null || assignmentSnapshot.failed()) || rejoinRequested;
    }

    @Override
    public void close() {
    }

    public String memberId() {
        return this.memberId;
    }

    private class CopycatWorkerCoordinatorMetrics {
        public final Metrics metrics;
        public final String metricGrpName;

        public CopycatWorkerCoordinatorMetrics(Metrics metrics, String metricGrpPrefix, Map<String, String> tags) {
            this.metrics = metrics;
            this.metricGrpName = metricGrpPrefix + "-coordinator-metrics";

            Measurable numConnectors = new Measurable() {
                public double measure(MetricConfig config, long now) {
                    return assignmentSnapshot.connectors().size();
                }
            };

            Measurable numTasks = new Measurable() {
                public double measure(MetricConfig config, long now) {
                    return assignmentSnapshot.tasks().size();
                }
            };

            metrics.addMetric(new MetricName("assigned-connectors",
                            this.metricGrpName,
                            "The number of connector instances currently assigned to this consumer",
                            tags),
                    numConnectors);
            metrics.addMetric(new MetricName("assigned-tasks",
                            this.metricGrpName,
                            "The number of tasks currently assigned to this consumer",
                            tags),
                    numTasks);
        }
    }

    private static <T extends Comparable<T>> List<T> sorted(Collection<T> members) {
        List<T> res = new ArrayList<>(members);
        Collections.sort(res);
        return res;
    }

}
