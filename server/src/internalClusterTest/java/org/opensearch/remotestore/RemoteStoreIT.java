/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.remotestore;

import org.hamcrest.MatcherAssert;
import org.junit.Before;
import org.opensearch.action.admin.cluster.remotestore.restore.RestoreRemoteStoreRequest;
import org.opensearch.action.admin.indices.delete.DeleteIndexRequest;
import org.opensearch.action.admin.indices.recovery.RecoveryResponse;
import org.opensearch.action.index.IndexResponse;
import org.opensearch.action.support.PlainActionFuture;
import org.opensearch.cluster.metadata.IndexMetadata;
import org.opensearch.cluster.routing.RecoverySource;
import org.opensearch.common.UUIDs;
import org.opensearch.common.settings.Settings;
import org.opensearch.index.shard.RemoteStoreRefreshListener;
import org.opensearch.indices.recovery.RecoveryState;
import org.opensearch.plugins.Plugin;
import org.opensearch.test.InternalTestCluster;
import org.opensearch.test.OpenSearchIntegTestCase;
import org.opensearch.test.transport.MockTransportService;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.Matchers.comparesEqualTo;
import static org.hamcrest.Matchers.oneOf;
import static org.hamcrest.Matchers.is;
import static org.opensearch.test.hamcrest.OpenSearchAssertions.assertAcked;
import static org.opensearch.test.hamcrest.OpenSearchAssertions.assertHitCount;

@OpenSearchIntegTestCase.ClusterScope(scope = OpenSearchIntegTestCase.Scope.TEST, numDataNodes = 0)
public class RemoteStoreIT extends RemoteStoreBaseIntegTestCase {

    private static final String INDEX_NAME = "remote-store-test-idx-1";
    private static final String TOTAL_OPERATIONS = "total-operations";
    private static final String REFRESHED_OR_FLUSHED_OPERATIONS = "refreshed-or-flushed-operations";
    private static final String MAX_SEQ_NO_TOTAL = "max-seq-no-total";
    private static final String MAX_SEQ_NO_REFRESHED_OR_FLUSHED = "max-seq-no-refreshed-or-flushed";

    @Override
    protected Collection<Class<? extends Plugin>> nodePlugins() {
        return Arrays.asList(MockTransportService.TestPlugin.class);
    }

    @Before
    public void setup() {
        setupRepo();
    }

    @Override
    public Settings indexSettings() {
        return remoteStoreIndexSettings(0);
    }

    private IndexResponse indexSingleDoc() {
        return client().prepareIndex(INDEX_NAME)
            .setId(UUIDs.randomBase64UUID())
            .setSource(randomAlphaOfLength(5), randomAlphaOfLength(5))
            .get();
    }

    private Map<String, Long> indexData(int numberOfIterations, boolean invokeFlush) {
        long totalOperations = 0;
        long refreshedOrFlushedOperations = 0;
        long maxSeqNo = -1;
        long maxSeqNoRefreshedOrFlushed = -1;
        for (int i = 0; i < numberOfIterations; i++) {
            if (invokeFlush) {
                flush(INDEX_NAME);
            } else {
                refresh(INDEX_NAME);
            }
            maxSeqNoRefreshedOrFlushed = maxSeqNo;
            refreshedOrFlushedOperations = totalOperations;
            int numberOfOperations = randomIntBetween(20, 50);
            for (int j = 0; j < numberOfOperations; j++) {
                IndexResponse response = indexSingleDoc();
                maxSeqNo = response.getSeqNo();
            }
            totalOperations += numberOfOperations;
        }
        Map<String, Long> indexingStats = new HashMap<>();
        indexingStats.put(TOTAL_OPERATIONS, totalOperations);
        indexingStats.put(REFRESHED_OR_FLUSHED_OPERATIONS, refreshedOrFlushedOperations);
        indexingStats.put(MAX_SEQ_NO_TOTAL, maxSeqNo);
        indexingStats.put(MAX_SEQ_NO_REFRESHED_OR_FLUSHED, maxSeqNoRefreshedOrFlushed);
        return indexingStats;
    }

    private void verifyRestoredData(Map<String, Long> indexStats, boolean checkTotal) {
        String statsGranularity = checkTotal ? TOTAL_OPERATIONS : REFRESHED_OR_FLUSHED_OPERATIONS;
        String maxSeqNoGranularity = checkTotal ? MAX_SEQ_NO_TOTAL : MAX_SEQ_NO_REFRESHED_OR_FLUSHED;
        ensureYellowAndNoInitializingShards(INDEX_NAME);
        ensureGreen(INDEX_NAME);
        assertHitCount(client().prepareSearch(INDEX_NAME).setSize(0).get(), indexStats.get(statsGranularity));
        IndexResponse response = indexSingleDoc();
        assertEquals(indexStats.get(maxSeqNoGranularity) + 1, response.getSeqNo());
        refresh(INDEX_NAME);
        assertHitCount(client().prepareSearch(INDEX_NAME).setSize(0).get(), indexStats.get(statsGranularity) + 1);
    }

    private void testRestoreFlow(boolean remoteTranslog, int numberOfIterations, boolean invokeFlush) throws IOException {
        internalCluster().startDataOnlyNodes(3);
        if (remoteTranslog) {
            createIndex(INDEX_NAME, remoteTranslogIndexSettings(0));
        } else {
            createIndex(INDEX_NAME, remoteStoreIndexSettings(0));
        }
        ensureYellowAndNoInitializingShards(INDEX_NAME);
        ensureGreen(INDEX_NAME);

        Map<String, Long> indexStats = indexData(numberOfIterations, invokeFlush);

        internalCluster().stopRandomNode(InternalTestCluster.nameFilter(primaryNodeName(INDEX_NAME)));
        assertAcked(client().admin().indices().prepareClose(INDEX_NAME));

        client().admin().cluster().restoreRemoteStore(new RestoreRemoteStoreRequest().indices(INDEX_NAME), PlainActionFuture.newFuture());
        ensureGreen(INDEX_NAME);

        if (remoteTranslog) {
            verifyRestoredData(indexStats, true);
        } else {
            verifyRestoredData(indexStats, false);
        }
    }

    public void testRemoteSegmentStoreRestoreWithNoDataPostCommit() throws IOException {
        testRestoreFlow(false, 1, true);
    }

    public void testRemoteSegmentStoreRestoreWithNoDataPostRefresh() throws IOException {
        testRestoreFlow(false, 1, false);
    }

    public void testRemoteSegmentStoreRestoreWithRefreshedData() throws IOException {
        testRestoreFlow(false, randomIntBetween(2, 5), false);
    }

    public void testRemoteSegmentStoreRestoreWithCommittedData() throws IOException {
        testRestoreFlow(false, randomIntBetween(2, 5), true);
    }

    @AwaitsFix(bugUrl = "https://github.com/opensearch-project/OpenSearch/issues/6188")
    public void testRemoteTranslogRestoreWithNoDataPostCommit() throws IOException {
        testRestoreFlow(true, 1, true);
    }

    public void testRemoteTranslogRestoreWithNoDataPostRefresh() throws IOException {
        testRestoreFlow(true, 1, false);
    }

    public void testRemoteTranslogRestoreWithRefreshedData() throws IOException {
        testRestoreFlow(true, randomIntBetween(2, 5), false);
    }

    public void testRemoteTranslogRestoreWithCommittedData() throws IOException {
        testRestoreFlow(true, randomIntBetween(2, 5), true);
    }

    private void testPeerRecovery(boolean remoteTranslog, int numberOfIterations, boolean invokeFlush) throws Exception {
        internalCluster().startDataOnlyNodes(3);
        if (remoteTranslog) {
            createIndex(INDEX_NAME, remoteTranslogIndexSettings(0));
        } else {
            createIndex(INDEX_NAME, remoteStoreIndexSettings(0));
        }
        ensureYellowAndNoInitializingShards(INDEX_NAME);
        ensureGreen(INDEX_NAME);

        Map<String, Long> indexStats = indexData(numberOfIterations, invokeFlush);

        client().admin()
            .indices()
            .prepareUpdateSettings(INDEX_NAME)
            .setSettings(Settings.builder().put(IndexMetadata.SETTING_NUMBER_OF_REPLICAS, 1))
            .get();
        ensureYellowAndNoInitializingShards(INDEX_NAME);
        ensureGreen(INDEX_NAME);

        refresh(INDEX_NAME);
        String replicaNodeName = replicaNodeName(INDEX_NAME);
        assertBusy(
            () -> assertHitCount(client(replicaNodeName).prepareSearch(INDEX_NAME).setSize(0).get(), indexStats.get(TOTAL_OPERATIONS)),
            30,
            TimeUnit.SECONDS
        );

        RecoveryResponse recoveryResponse = client(replicaNodeName).admin().indices().prepareRecoveries().get();

        Optional<RecoveryState> recoverySource = recoveryResponse.shardRecoveryStates()
            .get(INDEX_NAME)
            .stream()
            .filter(rs -> rs.getRecoverySource().getType() == RecoverySource.Type.PEER)
            .findFirst();
        assertFalse(recoverySource.isEmpty());
        if (numberOfIterations == 1 && invokeFlush) {
            // segments_N file is copied to new replica
            assertEquals(1, recoverySource.get().getIndex().recoveredFileCount());
        } else {
            assertEquals(0, recoverySource.get().getIndex().recoveredFileCount());
        }

        IndexResponse response = indexSingleDoc();
        assertEquals(indexStats.get(MAX_SEQ_NO_TOTAL) + 1, response.getSeqNo());
        refresh(INDEX_NAME);
        assertBusy(
            () -> assertHitCount(client(replicaNodeName).prepareSearch(INDEX_NAME).setSize(0).get(), indexStats.get(TOTAL_OPERATIONS) + 1),
            30,
            TimeUnit.SECONDS
        );
    }

    public void testPeerRecoveryWithRemoteStoreNoRemoteTranslogNoDataFlush() throws Exception {
        testPeerRecovery(false, 1, true);
    }

    public void testPeerRecoveryWithRemoteStoreNoRemoteTranslogFlush() throws Exception {
        testPeerRecovery(false, randomIntBetween(2, 5), true);
    }

    public void testPeerRecoveryWithRemoteStoreNoRemoteTranslogNoDataRefresh() throws Exception {
        testPeerRecovery(false, 1, false);
    }

    public void testPeerRecoveryWithRemoteStoreNoRemoteTranslogRefresh() throws Exception {
        testPeerRecovery(false, randomIntBetween(2, 5), false);
    }

    public void testPeerRecoveryWithRemoteStoreAndRemoteTranslogNoDataFlush() throws Exception {
        testPeerRecovery(true, 1, true);
    }

    public void testPeerRecoveryWithRemoteStoreAndRemoteTranslogFlush() throws Exception {
        testPeerRecovery(true, randomIntBetween(2, 5), true);
    }

    public void testPeerRecoveryWithRemoteStoreAndRemoteTranslogNoDataRefresh() throws Exception {
        testPeerRecovery(true, 1, false);
    }

    public void testPeerRecoveryWithRemoteStoreAndRemoteTranslogRefresh() throws Exception {
        testPeerRecovery(true, randomIntBetween(2, 5), false);
    }

    private void verifyRemoteStoreCleanup(boolean remoteTranslog) throws Exception {
        internalCluster().startDataOnlyNodes(3);
        if (remoteTranslog) {
            createIndex(INDEX_NAME, remoteTranslogIndexSettings(1));
        } else {
            createIndex(INDEX_NAME, remoteStoreIndexSettings(1));
        }

        indexData(5, randomBoolean());
        String indexUUID = client().admin()
            .indices()
            .prepareGetSettings(INDEX_NAME)
            .get()
            .getSetting(INDEX_NAME, IndexMetadata.SETTING_INDEX_UUID);
        Path indexPath = Path.of(String.valueOf(absolutePath), indexUUID);
        assertTrue(getFileCount(indexPath) > 0);
        assertAcked(client().admin().indices().delete(new DeleteIndexRequest(INDEX_NAME)).get());
        // Delete is async. Give time for it
        assertBusy(() -> {
            try {
                assertThat(getFileCount(indexPath), comparesEqualTo(0));
            } catch (Exception e) {}
        }, 30, TimeUnit.SECONDS);
    }

    public void testRemoteSegmentCleanup() throws Exception {
        verifyRemoteStoreCleanup(false);
    }

    public void testRemoteTranslogCleanup() throws Exception {
        verifyRemoteStoreCleanup(true);
    }

    public void testStaleCommitDeletionWithInvokeFlush() throws Exception {
        internalCluster().startDataOnlyNodes(3);
        createIndex(INDEX_NAME, remoteStoreIndexSettings(1, 10000l));
        int numberOfIterations = randomIntBetween(5, 15);
        indexData(numberOfIterations, true);
        String indexUUID = client().admin()
            .indices()
            .prepareGetSettings(INDEX_NAME)
            .get()
            .getSetting(INDEX_NAME, IndexMetadata.SETTING_INDEX_UUID);
        Path indexPath = Path.of(String.valueOf(absolutePath), indexUUID, "/0/segments/metadata");
        // Delete is async.
        assertBusy(() -> {
            int actualFileCount = getFileCount(indexPath);
            if (numberOfIterations <= RemoteStoreRefreshListener.LAST_N_METADATA_FILES_TO_KEEP) {
                MatcherAssert.assertThat(actualFileCount, is(oneOf(numberOfIterations, numberOfIterations + 1)));
            } else {
                // As delete is async its possible that the file gets created before the deletion or after
                // deletion.
                MatcherAssert.assertThat(actualFileCount, is(oneOf(10, 11)));
            }
        }, 30, TimeUnit.SECONDS);
    }

    public void testStaleCommitDeletionWithoutInvokeFlush() throws Exception {
        internalCluster().startDataOnlyNodes(3);
        createIndex(INDEX_NAME, remoteStoreIndexSettings(1, 10000l));
        int numberOfIterations = randomIntBetween(5, 15);
        indexData(numberOfIterations, false);
        String indexUUID = client().admin()
            .indices()
            .prepareGetSettings(INDEX_NAME)
            .get()
            .getSetting(INDEX_NAME, IndexMetadata.SETTING_INDEX_UUID);
        Path indexPath = Path.of(String.valueOf(absolutePath), indexUUID, "/0/segments/metadata");
        int actualFileCount = getFileCount(indexPath);
        // We also allow (numberOfIterations + 1) as index creation also triggers refresh.
        MatcherAssert.assertThat(actualFileCount, is(oneOf(numberOfIterations, numberOfIterations + 1)));
    }
}
