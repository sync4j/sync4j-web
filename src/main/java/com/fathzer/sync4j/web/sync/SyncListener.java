package com.fathzer.sync4j.web.sync;

import java.io.IOException;

/**
 * A listener of the synchronizations started by a {@link SynchronizationManager}.
 */
public interface SyncListener {
    /**
     * Called before a synchronization starts.
     * <br>It is called by the thread that requests the synchronization and should return quickly.
     * @param request the request
     * @throws IOException to refuse the synchronization. The exception message is sent to the user.
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
