package com.fathzer.sync4j.web;

import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fathzer.sync4j.web.plugin.SyncPlugin;
import com.fathzer.sync4j.web.plugin.SyncPluginFactory;

/**
 * A plugin factory declared in META-INF/services (see {@link SyncServerTest}).
 */
public class DiscoveredPluginFactory implements SyncPluginFactory {
    static final AtomicInteger CREATED = new AtomicInteger();
    static volatile JsonNode lastConfig;

    @Override
    public String id() {
        return "discovered";
    }

    @Override
    public SyncPlugin create(JsonNode config) {
        CREATED.incrementAndGet();
        lastConfig = config;
        return () -> "discovered";
    }
}
