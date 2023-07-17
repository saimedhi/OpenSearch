/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.index.store.remote.metadata;

import org.apache.lucene.index.SegmentInfos;
import org.apache.lucene.store.ByteBuffersDataOutput;
import org.apache.lucene.store.ByteBuffersIndexOutput;
import org.apache.lucene.store.OutputStreamIndexOutput;
import org.junit.After;
import org.junit.Before;
import org.opensearch.cluster.metadata.IndexMetadata;
import org.opensearch.common.UUIDs;
import org.opensearch.core.common.bytes.BytesReference;
import org.opensearch.common.io.stream.BytesStreamOutput;
import org.opensearch.common.lucene.store.ByteArrayIndexInput;
import org.opensearch.common.settings.Settings;
import org.opensearch.index.engine.NRTReplicationEngineFactory;
import org.opensearch.index.shard.IndexShard;
import org.opensearch.index.shard.IndexShardTestCase;
import org.opensearch.index.store.Store;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Unit Tests for {@link RemoteSegmentMetadataHandler}
 */
public class RemoteSegmentMetadataHandlerTests extends IndexShardTestCase {
    private RemoteSegmentMetadataHandler remoteSegmentMetadataHandler;
    private IndexShard indexShard;
    private SegmentInfos segmentInfos;

    @Before
    public void setup() throws IOException {
        remoteSegmentMetadataHandler = new RemoteSegmentMetadataHandler();

        Settings indexSettings = Settings.builder().put(IndexMetadata.SETTING_VERSION_CREATED, org.opensearch.Version.CURRENT).build();

        indexShard = newStartedShard(false, indexSettings, new NRTReplicationEngineFactory());
        try (Store store = indexShard.store()) {
            segmentInfos = store.readLastCommittedSegmentsInfo();
        }
    }

    @After
    public void tearDown() throws Exception {
        indexShard.close("test tearDown", true, false);
        super.tearDown();
    }

    public void testReadContentNoSegmentInfos() throws IOException {
        BytesStreamOutput output = new BytesStreamOutput();
        OutputStreamIndexOutput indexOutput = new OutputStreamIndexOutput("dummy bytes", "dummy stream", output, 4096);
        Map<String, String> expectedOutput = getDummyData();
        indexOutput.writeMapOfStrings(expectedOutput);
        indexOutput.writeLong(1234);
        indexOutput.writeLong(1234);
        indexOutput.writeLong(0);
        indexOutput.writeBytes(new byte[0], 0);
        indexOutput.close();
        RemoteSegmentMetadata metadata = remoteSegmentMetadataHandler.readContent(
            new ByteArrayIndexInput("dummy bytes", BytesReference.toBytes(output.bytes()))
        );
        assertEquals(expectedOutput, metadata.toMapOfStrings());
        assertEquals(1234, metadata.getGeneration());
    }

    public void testReadContentWithSegmentInfos() throws IOException {
        BytesStreamOutput output = new BytesStreamOutput();
        OutputStreamIndexOutput indexOutput = new OutputStreamIndexOutput("dummy bytes", "dummy stream", output, 4096);
        Map<String, String> expectedOutput = getDummyData();
        indexOutput.writeMapOfStrings(expectedOutput);
        indexOutput.writeLong(1234);
        indexOutput.writeLong(1234);
        ByteBuffersIndexOutput segmentInfosOutput = new ByteBuffersIndexOutput(new ByteBuffersDataOutput(), "test", "resource");
        segmentInfos.write(segmentInfosOutput);
        byte[] segmentInfosBytes = segmentInfosOutput.toArrayCopy();
        indexOutput.writeLong(segmentInfosBytes.length);
        indexOutput.writeBytes(segmentInfosBytes, 0, segmentInfosBytes.length);
        indexOutput.close();
        RemoteSegmentMetadata metadata = remoteSegmentMetadataHandler.readContent(
            new ByteArrayIndexInput("dummy bytes", BytesReference.toBytes(output.bytes()))
        );
        assertEquals(expectedOutput, metadata.toMapOfStrings());
        assertEquals(1234, metadata.getGeneration());
        assertArrayEquals(segmentInfosBytes, metadata.getSegmentInfosBytes());
    }

    public void testWriteContent() throws IOException {
        BytesStreamOutput output = new BytesStreamOutput();
        OutputStreamIndexOutput indexOutput = new OutputStreamIndexOutput("dummy bytes", "dummy stream", output, 4096);

        Map<String, String> expectedOutput = getDummyData();
        ByteBuffersIndexOutput segmentInfosOutput = new ByteBuffersIndexOutput(new ByteBuffersDataOutput(), "test", "resource");
        segmentInfos.write(segmentInfosOutput);
        byte[] segmentInfosBytes = segmentInfosOutput.toArrayCopy();

        RemoteSegmentMetadata remoteSegmentMetadata = new RemoteSegmentMetadata(
            RemoteSegmentMetadata.fromMapOfStrings(expectedOutput),
            segmentInfosBytes,
            1234,
            1234
        );
        remoteSegmentMetadataHandler.writeContent(indexOutput, remoteSegmentMetadata);
        indexOutput.close();

        RemoteSegmentMetadata metadata = remoteSegmentMetadataHandler.readContent(
            new ByteArrayIndexInput("dummy bytes", BytesReference.toBytes(output.bytes()))
        );
        assertEquals(expectedOutput, metadata.toMapOfStrings());
        assertEquals(1234, metadata.getGeneration());
        assertEquals(1234, metadata.getPrimaryTerm());
        assertArrayEquals(segmentInfosBytes, metadata.getSegmentInfosBytes());
    }

    private Map<String, String> getDummyData() {
        Map<String, String> expectedOutput = new HashMap<>();
        String prefix = "_0";
        expectedOutput.put(
            prefix + ".cfe",
            prefix
                + ".cfe::"
                + prefix
                + ".cfe__"
                + UUIDs.base64UUID()
                + "::"
                + randomIntBetween(1000, 5000)
                + "::"
                + randomIntBetween(1024, 2048)
        );
        expectedOutput.put(
            prefix + ".cfs",
            prefix
                + ".cfs::"
                + prefix
                + ".cfs__"
                + UUIDs.base64UUID()
                + "::"
                + randomIntBetween(1000, 5000)
                + "::"
                + randomIntBetween(1024, 2048)
        );
        return expectedOutput;
    }
}
