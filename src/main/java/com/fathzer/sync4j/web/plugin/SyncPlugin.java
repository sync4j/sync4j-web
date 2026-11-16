package com.fathzer.sync4j.web.plugin;

import java.io.IOException;

import com.fathzer.sync4j.web.sync.SyncListener;

/**
 * A plugin of a synchronization server.
 * <br>A plugin is notified of the synchronizations (see {@link SyncListener}), can register its own routes and can publish messages to the users.
 */
public interface SyncPlugin extends SyncListener, AutoCloseable {
    /**
     * Gets the id of the plugin.
     * @return the id, it is the key of the plugin configuration in the configuration file
     */
    String id();

    /**
     * Initializes the plugin.
     * <br>It is called once, before the server starts.
     * @param context the context
     * @throws IOException if the plugin can't be initialized (for instance, because its configuration is invalid). The server does not start.
     */
    default void init(PluginContext context) throws IOException {
        // Does nothing by default
    }

    /**
     * Releases the resources of the plugin, it is called when the server stops.
     */
    @Override
    default void close() {
        // Does nothing by default
    }
}
