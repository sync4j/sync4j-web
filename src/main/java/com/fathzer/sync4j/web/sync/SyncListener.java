package com.fathzer.sync4j.web.sync;

import java.io.IOException;

/**
 * A listener of the synchronizations started by a {@link SynchronizationManager}.
 * <br>Its methods are called by the threads of the synchronizations and of the requests: it has to be thread-safe.
 */
public interface SyncListener {
    /**
     * Called before a synchronization starts.
     * <br>It is called by the thread that requests the synchronization and should return quickly.
     * @param request the request
     * @throws IOException to refuse the synchronization. Throw a {@link com.fathzer.sync4j.web.plugin.SyncRefusedException} to tell the user why (its message is sent to him).
     * Any other exception is an error of the server: it is logged, and the user only receives a reference to find it in the log (its message can have details that the users should not see, the paths of the server for instance).
     */
    default void beforeSync(SyncRequest request) throws IOException {
        // Does nothing by default
    }

    /**
     * Called when a synchronization is over (completed, failed or cancelled), after its resources are released and before the new status is published.
     * <br>It should return quickly: a long job (for instance, a post-processing of the synchronized files) should be run in another thread.
     * An exception thrown by this method is logged and ignored.
     * @param request the request
     * @param status the final status of the synchronization
     */
    default void afterSync(SyncRequest request, SyncStatus status) {
        // Does nothing by default
    }
}
