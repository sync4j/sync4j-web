package com.fathzer.sync4j.web.sync;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.fathzer.sync4j.sync.Statistics;

class SyncStatusTest {
    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(StatisticsSerializer.getModule());
    @Test
    void testIdleStatusSerialization() throws JsonProcessingException {
        // Create an idle status
        SyncStatus status = SyncStatus.idle();
        
        // Serialize and deserialize
        String json = mapper.writeValueAsString(status);
        SyncStatus deserialized = mapper.readValue(json, SyncStatus.class);
        
        // Verify
        assertEquals(SyncStatus.Status.IDLE, deserialized.status());
        assertNull(deserialized.lastSyncTime());
        assertNull(deserialized.errorMessage());
        assertNull(deserialized.result());
    }

    @Test
    void testCompletedStatusSerialization() throws JsonProcessingException {
        final Statistics statistics = new Statistics();
        
        // Create a completed status with a result
        statistics.copiedFiles().done().set(2);
        statistics.deletedFiles().done().set(1);
        
        SyncStatus status = SyncStatus.completed(statistics);
        
        // Serialize and deserialize
        String json = mapper.writeValueAsString(status);
        SyncStatus deserialized = mapper.readValue(json, SyncStatus.class);
        
        // Verify
        assertEquals(SyncStatus.Status.COMPLETED, deserialized.status());
        assertNotNull(deserialized.lastSyncTime());
        assertNull(deserialized.errorMessage());
        assertNotNull(deserialized.result());
        
        // Verify the result
        Statistics deserializedResult = deserialized.result();
        assertEquals(1, deserializedResult.deletedFiles().done().get());
        assertEquals(2, deserializedResult.copiedFiles().done().get());
    }

    @Test
    void testFailedStatusSerialization() throws JsonProcessingException {
        // Create a failed status with an error message and result
        Statistics result = new Statistics();
        String errorMessage = "Test error message";
        SyncStatus status = SyncStatus.failed(errorMessage, result);
        
        // Serialize and deserialize
        String json = mapper.writeValueAsString(status);
        SyncStatus deserialized = mapper.readValue(json, SyncStatus.class);
        
        // Verify
        assertEquals(SyncStatus.Status.FAILED, deserialized.status());
        assertNotNull(deserialized.lastSyncTime());
        assertEquals(errorMessage, deserialized.errorMessage());
        assertNotNull(deserialized.result());
    }

    @Test
    void testSyncingStatusSerialization() throws JsonProcessingException {
        // Create a syncing status with a result
        Statistics result = new Statistics();
        result.copiedFiles().total().set(1);
        SyncStatus status = SyncStatus.syncing(result);
        
        // Serialize and deserialize
        String json = mapper.writeValueAsString(status);
        SyncStatus deserialized = mapper.readValue(json, SyncStatus.class);
        
        // Verify
        assertEquals(SyncStatus.Status.SYNCING, deserialized.status());
        assertNull(deserialized.lastSyncTime());
        assertNull(deserialized.errorMessage());
        assertNotNull(deserialized.result());
        
        // Verify the result
        Statistics deserializedResult = deserialized.result();
        assertEquals(0, deserializedResult.deletedFiles().done().get());
        assertEquals(1, deserializedResult.copiedFiles().total().get());
        assertEquals(0, deserializedResult.copiedFiles().done().get());
    }
}
