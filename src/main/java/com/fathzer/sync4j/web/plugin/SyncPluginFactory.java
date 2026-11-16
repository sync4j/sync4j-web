package com.fathzer.sync4j.web.plugin;

import java.io.IOException;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Creates a plugin. Factories are discovered using the {@link java.util.ServiceLoader} mechanism.
 * <br>A plugin discovered this way is only activated if the configuration contains a section for its id.
 */
public interface SyncPluginFactory {
    /**
     * Gets the id of the plugins created by this factory.
     * @return the id of the plugin
     */
    String id();

    /**
     * Creates the plugin.
     * @param config the configuration of the plugin
     * @return a new plugin
     * @throws IOException if the configuration is invalid
     */
    SyncPlugin create(JsonNode config) throws IOException;
}
