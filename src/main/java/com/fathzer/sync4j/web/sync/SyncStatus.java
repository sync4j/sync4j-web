package com.fathzer.sync4j.web.sync;

import java.time.Instant;
import java.time.format.DateTimeFormatter;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fathzer.sync4j.sync.Statistics;

public record SyncStatus(
    Status status, 
    String lastSyncTime,
    @JsonInclude(JsonInclude.Include.NON_NULL)
    String errorMessage,
    Statistics result
) {
    public enum Status {
        IDLE, SYNCING, COMPLETED, FAILED, CANCELLED
    }
    
    public static String formatInstant(Instant instant) {
        if (instant == null) {
            return null;
        }
        return DateTimeFormatter.ISO_INSTANT.format(instant);
    }
    
    public static Instant parseInstant(String instantStr) {
        if (instantStr == null) {
            return null;
        }
        return Instant.from(DateTimeFormatter.ISO_INSTANT.parse(instantStr));
    }

    public static SyncStatus idle() {
        return new SyncStatus(Status.IDLE, null, null, null);
    }

    public static SyncStatus syncing(Statistics result) {
        return new SyncStatus(Status.SYNCING, null, null, result);
    }

    public static SyncStatus completed(Statistics result) {
        return new SyncStatus(Status.COMPLETED, formatInstant(Instant.now()), null, result);
    }

    public static SyncStatus failed(String errorMessage, Statistics result) {
        return new SyncStatus(Status.FAILED, formatInstant(Instant.now()), errorMessage, result);
    }

    public static SyncStatus cancelled(Statistics result) {
        return new SyncStatus(Status.CANCELLED, formatInstant(Instant.now()), "Sync was cancelled by user", result);
    }
}
