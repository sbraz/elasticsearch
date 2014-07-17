/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.discovery;

import com.google.common.base.Predicate;
import org.apache.lucene.util.LuceneTestCase;
import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.action.admin.cluster.health.ClusterHealthResponse;
import org.elasticsearch.action.get.GetResponse;
import org.elasticsearch.action.index.IndexResponse;
import org.elasticsearch.client.Client;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.block.ClusterBlockLevel;
import org.elasticsearch.cluster.metadata.IndexMetaData;
import org.elasticsearch.cluster.node.DiscoveryNodes;
import org.elasticsearch.cluster.routing.operation.hash.djb.DjbHashFunction;
import org.elasticsearch.common.Nullable;
import org.elasticsearch.common.Priority;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.settings.ImmutableSettings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.discovery.zen.elect.ElectMasterService;
import org.elasticsearch.test.ElasticsearchIntegrationTest;
import org.elasticsearch.test.InternalTestCluster;
import org.elasticsearch.test.disruption.*;
import org.elasticsearch.test.junit.annotations.TestLogging;
import org.elasticsearch.test.transport.MockTransportService;
import org.elasticsearch.transport.TransportModule;
import org.junit.Before;
import org.junit.Test;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.elasticsearch.test.ElasticsearchIntegrationTest.ClusterScope;
import static org.elasticsearch.test.ElasticsearchIntegrationTest.Scope;
import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertAcked;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

/**
 */
@ClusterScope(scope = Scope.TEST, numDataNodes = 0, transportClientRatio = 0)
@TestLogging("discovery.zen:TRACE")
public class DiscoveryWithNetworkFailuresTests extends ElasticsearchIntegrationTest {

    private static final TimeValue DISRUPTION_HEALING_OVERHEAD = TimeValue.timeValueSeconds(40); // we use 30s as timeout in many places.

    private ClusterDiscoveryConfiguration discoveryConfig;


    @Override
    protected Settings nodeSettings(int nodeOrdinal) {
        return discoveryConfig.settings(nodeOrdinal);
    }

    @Before
    public void clearConfig() {
        discoveryConfig = null;
    }

    @Override
    protected int numberOfShards() {
        return 3;
    }

    @Override
    protected int numberOfReplicas() {
        return 1;
    }

    private List<String> startCluster(int numberOfNodes) throws ExecutionException, InterruptedException {
        Settings settings = ImmutableSettings.builder()
                // TODO: this is a temporary solution so that nodes will not base their reaction to a partition based on previous successful results
                .put("discovery.zen.ping_timeout", "0.5s")
                        // end of temporary solution
                .put("discovery.zen.fd.ping_timeout", "1s") // <-- for hitting simulated network failures quickly
                .put("discovery.zen.fd.ping_retries", "1") // <-- for hitting simulated network failures quickly
                .put(DiscoverySettings.PUBLISH_TIMEOUT, "1s") // <-- for hitting simulated network failures quickly
                .put("http.enabled", false) // just to make test quicker
                .put(TransportModule.TRANSPORT_SERVICE_TYPE_KEY, MockTransportService.class.getName())
                .put(ElectMasterService.DISCOVERY_ZEN_MINIMUM_MASTER_NODES, numberOfNodes / 2 + 1).build();

        if (discoveryConfig == null) {
            if (randomBoolean()) {
                discoveryConfig = new ClusterDiscoveryConfiguration.UnicastZen(numberOfNodes, numberOfNodes, settings);
            } else {
                discoveryConfig = new ClusterDiscoveryConfiguration(numberOfNodes, settings);
            }
        }
        List<String> nodes = internalCluster().startNodesAsync(numberOfNodes).get();
        ensureStableCluster(numberOfNodes);

        return nodes;
    }

    /**
     * Test that no split brain occurs under partial network partition. See https://github.com/elasticsearch/elasticsearch/issues/2488
     *
     * @throws Exception
     */
    @Test
    public void failWithMinimumMasterNodesConfigured() throws Exception {

        List<String> nodes = startCluster(3);

        // Figure out what is the elected master node
        final String masterNode = internalCluster().getMasterName();
        logger.info("---> legit elected master node=" + masterNode);

        // Pick a node that isn't the elected master.
        Set<String> nonMasters = new HashSet<>(nodes);
        nonMasters.remove(masterNode);
        final String unluckyNode = randomFrom(nonMasters.toArray(Strings.EMPTY_ARRAY));


        // Simulate a network issue between the unlucky node and elected master node in both directions.

        NetworkDisconnectPartition networkDisconnect = new NetworkDisconnectPartition(masterNode, unluckyNode, getRandom());
        setDisruptionScheme(networkDisconnect);
        networkDisconnect.startDisrupting();

        // Wait until elected master has removed that the unlucky node...
        ensureStableCluster(2, masterNode);

        // The unlucky node must report *no* master node, since it can't connect to master and in fact it should
        // continuously ping until network failures have been resolved. However
        // It may a take a bit before the node detects it has been cut off from the elected master
        boolean success = awaitBusy(new Predicate<Object>() {
            @Override
            public boolean apply(Object input) {
                ClusterState localClusterState = getNodeClusterState(unluckyNode);
                DiscoveryNodes localDiscoveryNodes = localClusterState.nodes();
                logger.info("localDiscoveryNodes=" + localDiscoveryNodes.prettyPrint());
                return localDiscoveryNodes.masterNode() == null;
            }
        }, 10, TimeUnit.SECONDS);
        assertThat(success, is(true));

        networkDisconnect.stopDisrupting();

        // Wait until the master node sees all 3 nodes again.
        ensureStableCluster(3);

        for (String node : nodes) {
            ClusterState state = getNodeClusterState(node);
            assertThat(state.nodes().size(), equalTo(3));
            // The elected master shouldn't have changed, since the unlucky node never could have elected himself as
            // master since m_m_n of 2 could never be satisfied.
            assertThat(state.nodes().masterNode().name(), equalTo(masterNode));
        }
    }

    /**
     * Verify that the proper block is applied when nodes loose their master
     */
    @Test
    @TestLogging(value = "cluster.service:TRACE,indices.recovery:TRACE")
    public void testVerifyApiBlocksDuringPartition() throws Exception {
        startCluster(3);

        // Makes sure that the get request can be executed on each node locally:
        assertAcked(prepareCreate("test").setSettings(ImmutableSettings.builder()
                        .put(IndexMetaData.SETTING_NUMBER_OF_SHARDS, 1)
                        .put(IndexMetaData.SETTING_NUMBER_OF_REPLICAS, 2)
        ));

        // Everything is stable now, it is now time to simulate evil...
        // but first make sure we have no initializing shards and all is green
        // (waiting for green here, because indexing / search in a yellow index is fine as long as no other nodes go down)
        ensureGreen("test");

        NetworkPartition networkPartition = addRandomPartition();

        final String isolatedNode = networkPartition.getMinoritySide().get(0);
        final String nonIsolatedNode = networkPartition.getMajoritySide().get(0);

        // Simulate a network issue between the unlucky node and the rest of the cluster.
        networkPartition.startDisrupting();


        // The unlucky node must report *no* master node, since it can't connect to master and in fact it should
        // continuously ping until network failures have been resolved. However
        // It may a take a bit before the node detects it has been cut off from the elected master
        logger.info("waiting for isolated node [{}] to have no master", isolatedNode);
        final ClusterState[] lastState = new ClusterState[1];
        boolean success = awaitBusy(new Predicate<Object>() {
            @Override
            public boolean apply(Object input) {
                lastState[0] = getNodeClusterState(isolatedNode);
                DiscoveryNodes localDiscoveryNodes = lastState[0].nodes();
                logger.info("localDiscoveryNodes=" + localDiscoveryNodes.prettyPrint());
                if (localDiscoveryNodes.masterNode() == null) {
                    return false;
                }
                for (ClusterBlockLevel level : DiscoverySettings.NO_MASTER_BLOCK_WRITES.levels()) {
                    if (lastState[0].getBlocks().hasGlobalBlock(level)) {
                        return false;
                    }
                }
                return true;
            }
        }, 10, TimeUnit.SECONDS);
        if (!success) {
            fail("isolated node still has a master or the wrong blocks. Cluster state:\n" + lastState[0].prettyPrint());
        }


        logger.info("wait until elected master has been removed and a new 2 node cluster was from (via [{}])", isolatedNode);
        ensureStableCluster(2, nonIsolatedNode);

        for (String node : networkPartition.getMajoritySide()) {
            ClusterState nodeState = getNodeClusterState(node);
            success = true;
            if (nodeState.nodes().getMasterNode() == null) {
                success = false;
            }
            if (!nodeState.blocks().global().isEmpty()) {
                success = false;
            }
            if (!success) {
                fail("node [" + node + "] has no master or has blocks, despite of being on the right side of the partition. State dump:\n"
                        + nodeState.prettyPrint());
            }
        }


        networkPartition.stopDisrupting();

        // Wait until the master node sees al 3 nodes again.
        ensureStableCluster(3, new TimeValue(DISRUPTION_HEALING_OVERHEAD.millis() + networkPartition.expectedTimeToHeal().millis()));

        logger.info("Verify no master block with {} set to {}", DiscoverySettings.NO_MASTER_BLOCK, "all");
        client().admin().cluster().prepareUpdateSettings()
                .setTransientSettings(ImmutableSettings.builder().put(DiscoverySettings.NO_MASTER_BLOCK, "all"))
                .get();

        networkPartition.startDisrupting();


        // The unlucky node must report *no* master node, since it can't connect to master and in fact it should
        // continuously ping until network failures have been resolved. However
        // It may a take a bit before the node detects it has been cut off from the elected master
        logger.info("waiting for isolated node [{}] to have no master", isolatedNode);
        success = awaitBusy(new Predicate<Object>() {
            @Override
            public boolean apply(Object input) {
                lastState[0] = getNodeClusterState(isolatedNode);
                DiscoveryNodes localDiscoveryNodes = lastState[0].nodes();
                logger.info("localDiscoveryNodes=" + localDiscoveryNodes.prettyPrint());
                if (localDiscoveryNodes.masterNode() == null) {
                    return false;
                }
                for (ClusterBlockLevel level : DiscoverySettings.NO_MASTER_BLOCK_ALL.levels()) {
                    if (lastState[0].getBlocks().hasGlobalBlock(level)) {
                        return false;
                    }
                }
                return true;
            }
        }, 10, TimeUnit.SECONDS);
        if (!success) {
            fail("isolated node still has a master or the wrong blocks (expected 'all' block). Cluster state:\n" + lastState[0].prettyPrint());
        }

        // make sure we have stable cluster & cross partition recoveries are canceled by the removal of the missing node
        // the unresponsive partition causes recoveries to only time out after 15m (default) and these will cause
        // the test to fail due to unfreed resources
        ensureStableCluster(2, nonIsolatedNode);

    }

    /**
     * This test isolates the master from rest of the cluster, waits for a new master to be elected, restores the partition
     * and verifies that all node agree on the new cluster state
     */
    @Test
    @TestLogging("discovery.zen:TRACE,action:TRACE,cluster.service:TRACE,indices.recovery:TRACE,indices.cluster:TRACE")
    public void testIsolateMasterAndVerifyClusterStateConsensus() throws Exception {
        final List<String> nodes = startCluster(3);

        assertAcked(prepareCreate("test")
                .setSettings(ImmutableSettings.builder()
                                .put(IndexMetaData.SETTING_NUMBER_OF_SHARDS, 1 + randomInt(2))
                                .put(IndexMetaData.SETTING_NUMBER_OF_REPLICAS, randomInt(2))
                ));

        ensureGreen();
        String isolatedNode = internalCluster().getMasterName();
        NetworkPartition networkPartition = addRandomIsolation(isolatedNode);
        networkPartition.startDisrupting();

        String nonIsolatedNode = networkPartition.getMajoritySide().get(0);

        // make sure cluster reforms
        ensureStableCluster(2, nonIsolatedNode);

        // restore isolation
        networkPartition.stopDisrupting();

        ensureStableCluster(3, new TimeValue(DISRUPTION_HEALING_OVERHEAD.millis() + networkPartition.expectedTimeToHeal().millis()));

        logger.info("issue a reroute");
        // trigger a reroute now, instead of waiting for the background reroute of RerouteService
        assertAcked(client().admin().cluster().prepareReroute());
        // and wait for it to finish and for the cluster to stabilize
        ensureGreen("test");

        // verify all cluster states are the same
        ClusterState state = null;
        for (String node : nodes) {
            ClusterState nodeState = getNodeClusterState(node);
            if (state == null) {
                state = nodeState;
                continue;
            }
            // assert nodes are identical
            try {
                assertEquals("unequal versions", state.version(), nodeState.version());
                assertEquals("unequal node count", state.nodes().size(), nodeState.nodes().size());
                assertEquals("different masters ", state.nodes().masterNodeId(), nodeState.nodes().masterNodeId());
                assertEquals("different meta data version", state.metaData().version(), nodeState.metaData().version());
                if (!state.routingTable().prettyPrint().equals(nodeState.routingTable().prettyPrint())) {
                    fail("different routing");
                }
            } catch (AssertionError t) {
                fail("failed comparing cluster state: " + t.getMessage() + "\n" +
                        "--- cluster state of node [" + nodes.get(0) + "]: ---\n" + state.prettyPrint() +
                        "\n--- cluster state [" + node + "]: ---\n" + nodeState.prettyPrint());
            }

        }
    }

    /**
     * Test the we do not loose document whose indexing request was successful, under a randomly selected disruption scheme
     * We also collect & report the type of indexing failures that occur.
     */
    @Test
    @LuceneTestCase.AwaitsFix(bugUrl = "MvG will fix")
    @TestLogging("action.index:TRACE,action.get:TRACE,discovery:TRACE,cluster.service:TRACE,indices.recovery:TRACE,indices.cluster:TRACE")
    public void testAckedIndexing() throws Exception {
        final List<String> nodes = startCluster(3);

        assertAcked(prepareCreate("test")
                .setSettings(ImmutableSettings.builder()
                                .put(IndexMetaData.SETTING_NUMBER_OF_SHARDS, 1 + randomInt(2))
                                .put(IndexMetaData.SETTING_NUMBER_OF_REPLICAS, randomInt(2))
                ));
        ensureGreen();

        ServiceDisruptionScheme disruptionScheme = addRandomDisruptionScheme();
        logger.info("disruption scheme [{}] added", disruptionScheme);

        final ConcurrentHashMap<String, String> ackedDocs = new ConcurrentHashMap<>(); // id -> node sent.

        final AtomicBoolean stop = new AtomicBoolean(false);
        List<Thread> indexers = new ArrayList<>(nodes.size());
        List<Semaphore> semaphores = new ArrayList<>(nodes.size());
        final AtomicInteger idGenerator = new AtomicInteger(0);
        final AtomicReference<CountDownLatch> countDownLatchRef = new AtomicReference<>();
        final List<Exception> exceptedExceptions = Collections.synchronizedList(new ArrayList<Exception>());

        logger.info("starting indexers");
        try {
            for (final String node : nodes) {
                final Semaphore semaphore = new Semaphore(0);
                semaphores.add(semaphore);
                final Client client = client(node);
                final String name = "indexer_" + indexers.size();
                final int numPrimaries = getNumShards("test").numPrimaries;
                Thread thread = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        while (!stop.get()) {
                            String id = null;
                            try {
                                if (!semaphore.tryAcquire(10, TimeUnit.SECONDS)) {
                                    continue;
                                }
                                logger.info("[{}] Acquired semaphore and it has {} permits left", name, semaphore.availablePermits());
                                try {
                                    id = Integer.toString(idGenerator.incrementAndGet());
                                    int shard = ((InternalTestCluster) cluster()).getInstance(DjbHashFunction.class).hash(id) % numPrimaries;
                                    logger.trace("[{}] indexing id [{}] through node [{}] targeting shard [{}]", name, id, node, shard);
                                    IndexResponse response = client.prepareIndex("test", "type", id).setSource("{}").setTimeout("1s").get();
                                    assertThat(response.getVersion(), equalTo(1l));
                                    ackedDocs.put(id, node);
                                    logger.trace("[{}] indexed id [{}] through node [{}]", name, id, node);
                                } catch (ElasticsearchException e) {
                                    exceptedExceptions.add(e);
                                    logger.trace("[{}] failed id [{}] through node [{}]", e, name, id, node);
                                } finally {
                                    countDownLatchRef.get().countDown();
                                    logger.trace("[{}] decreased counter : {}", name, countDownLatchRef.get().getCount());
                                }
                            } catch (InterruptedException e) {
                                // fine - semaphore interrupt
                            } catch (Throwable t) {
                                logger.info("unexpected exception in background thread of [{}]", t, node);
                            }
                        }
                    }
                });

                thread.setName(name);
                thread.setDaemon(true);
                thread.start();
                indexers.add(thread);
            }

            int docsPerIndexer = randomInt(3);
            logger.info("indexing " + docsPerIndexer + " docs per indexer before partition");
            countDownLatchRef.set(new CountDownLatch(docsPerIndexer * indexers.size()));
            for (Semaphore semaphore : semaphores) {
                semaphore.release(docsPerIndexer);
            }
            assertTrue(countDownLatchRef.get().await(1, TimeUnit.MINUTES));

            for (int iter = 1 + randomInt(2); iter > 0; iter--) {
                logger.info("starting disruptions & indexing (iteration [{}])", iter);
                disruptionScheme.startDisrupting();

                docsPerIndexer = 1 + randomInt(5);
                logger.info("indexing " + docsPerIndexer + " docs per indexer during partition");
                countDownLatchRef.set(new CountDownLatch(docsPerIndexer * indexers.size()));
                Collections.shuffle(semaphores);
                for (Semaphore semaphore : semaphores) {
                    assertThat(semaphore.availablePermits(), equalTo(0));
                    semaphore.release(docsPerIndexer);
                }
                assertTrue(countDownLatchRef.get().await(60000 + disruptionScheme.expectedTimeToHeal().millis() * (docsPerIndexer * indexers.size()), TimeUnit.MILLISECONDS));

                logger.info("stopping disruption");
                disruptionScheme.stopDisrupting();
                ensureStableCluster(3, TimeValue.timeValueMillis(disruptionScheme.expectedTimeToHeal().millis() + DISRUPTION_HEALING_OVERHEAD.millis()));
                ensureGreen("test");

                logger.info("validating successful docs");
                for (String node : nodes) {
                    try {
                        logger.debug("validating through node [{}]", node);
                        for (String id : ackedDocs.keySet()) {
                            assertTrue("doc [" + id + "] indexed via node [" + ackedDocs.get(id) + "] not found",
                                    client(node).prepareGet("test", "type", id).setPreference("_local").get().isExists());
                        }
                    } catch (AssertionError e) {
                        throw new AssertionError(e.getMessage() + " (checked via node [" + node + "]", e);
                    }
                }

                logger.info("done validating (iteration [{}])", iter);
            }
        } finally {
            if (exceptedExceptions.size() > 0) {
                StringBuilder sb = new StringBuilder("Indexing exceptions during disruption:");
                for (Exception e : exceptedExceptions) {
                    sb.append("\n").append(e.getMessage());
                }
                logger.debug(sb.toString());
            }
            logger.info("shutting down indexers");
            stop.set(true);
            for (Thread indexer : indexers) {
                indexer.interrupt();
                indexer.join(60000);
            }
        }
    }

    /**
     * Test that a document which is indexed on the majority side of a partition, is available from the minory side,
     * once the partition is healed
     *
     * @throws Exception
     */
    @Test
    @TestLogging("discovery.zen:TRACE,action:TRACE,cluster.service:TRACE,indices.recovery:TRACE,indices.cluster:TRACE")
    public void testRejoinDocumentExistsInAllShardCopies() throws Exception {
        List<String> nodes = startCluster(3);

        assertAcked(prepareCreate("test")
                .setSettings(ImmutableSettings.builder()
                                .put(IndexMetaData.SETTING_NUMBER_OF_SHARDS, 1)
                                .put(IndexMetaData.SETTING_NUMBER_OF_REPLICAS, 2)
                )
                .get());
        ensureGreen("test");

        nodes = new ArrayList<>(nodes);
        Collections.shuffle(nodes, getRandom());
        String isolatedNode = nodes.get(0);
        String notIsolatedNode = nodes.get(1);

        ServiceDisruptionScheme scheme = addRandomIsolation(isolatedNode);
        scheme.startDisrupting();
        ensureStableCluster(2, notIsolatedNode);
        assertFalse(client(notIsolatedNode).admin().cluster().prepareHealth("test").setWaitForYellowStatus().get().isTimedOut());


        IndexResponse indexResponse = internalCluster().client(notIsolatedNode).prepareIndex("test", "type").setSource("field", "value").get();
        assertThat(indexResponse.getVersion(), equalTo(1l));

        logger.info("Verifying if document exists via node[" + notIsolatedNode + "]");
        GetResponse getResponse = internalCluster().client(notIsolatedNode).prepareGet("test", "type", indexResponse.getId())
                .setPreference("_local")
                .get();
        assertThat(getResponse.isExists(), is(true));
        assertThat(getResponse.getVersion(), equalTo(1l));
        assertThat(getResponse.getId(), equalTo(indexResponse.getId()));

        scheme.stopDisrupting();

        ensureStableCluster(3);
        ensureGreen("test");

        for (String node : nodes) {
            logger.info("Verifying if document exists after isolating node[" + isolatedNode + "] via node[" + node + "]");
            getResponse = internalCluster().client(node).prepareGet("test", "type", indexResponse.getId())
                    .setPreference("_local")
                    .get();
            assertThat(getResponse.isExists(), is(true));
            assertThat(getResponse.getVersion(), equalTo(1l));
            assertThat(getResponse.getId(), equalTo(indexResponse.getId()));
        }
    }

    protected NetworkPartition addRandomPartition() {
        NetworkPartition partition;
        if (randomBoolean()) {
            partition = new NetworkUnresponsivePartition(getRandom());
        } else {
            partition = new NetworkDisconnectPartition(getRandom());
        }

        setDisruptionScheme(partition);

        return partition;
    }

    protected NetworkPartition addRandomIsolation(String isolatedNode) {
        Set<String> side1 = new HashSet<>();
        Set<String> side2 = new HashSet<>(Arrays.asList(internalCluster().getNodeNames()));
        side1.add(isolatedNode);
        side2.remove(isolatedNode);

        NetworkPartition partition;
        if (randomBoolean()) {
            partition = new NetworkUnresponsivePartition(side1, side2, getRandom());
        } else {
            partition = new NetworkDisconnectPartition(side1, side2, getRandom());
        }

        internalCluster().setDisruptionScheme(partition);

        return partition;
    }

    private ServiceDisruptionScheme addRandomDisruptionScheme() {
        List<ServiceDisruptionScheme> list = Arrays.asList(
                new NetworkUnresponsivePartition(getRandom()),
                new NetworkDelaysPartition(getRandom()),
                new NetworkDisconnectPartition(getRandom()),
                new SlowClusterStateProcessing(getRandom())
        );
        Collections.shuffle(list);
        setDisruptionScheme(list.get(0));
        return list.get(0);
    }

    private void ensureStableCluster(int nodeCount) {
        ensureStableCluster(nodeCount, TimeValue.timeValueSeconds(30), null);
    }

    private void ensureStableCluster(int nodeCount, TimeValue timeValue) {
        ensureStableCluster(nodeCount, timeValue, null);
    }

    private void ensureStableCluster(int nodeCount, @Nullable String viaNode) {
        ensureStableCluster(nodeCount, TimeValue.timeValueSeconds(30), viaNode);
    }

    private void ensureStableCluster(int nodeCount, TimeValue timeValue, @Nullable String viaNode) {
        logger.debug("ensuring cluster is stable with [{}] nodes. access node: [{}]. timeout: [{}]", nodeCount, viaNode, timeValue);
        ClusterHealthResponse clusterHealthResponse = client(viaNode).admin().cluster().prepareHealth()
                .setWaitForEvents(Priority.LANGUID)
                .setWaitForNodes(Integer.toString(nodeCount))
                .setTimeout(timeValue)
                .setWaitForRelocatingShards(0)
                .get();
        assertThat(clusterHealthResponse.isTimedOut(), is(false));
    }

    private ClusterState getNodeClusterState(String node) {
        return client(node).admin().cluster().prepareState().setLocal(true).get().getState();
    }

}
