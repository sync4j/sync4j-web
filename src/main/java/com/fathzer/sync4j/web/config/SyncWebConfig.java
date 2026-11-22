package com.fathzer.sync4j.web.config;

import java.util.Map;
import java.util.Objects;

import com.fasterxml.jackson.databind.JsonNode;
import com.fathzer.sync4j.config.SynchronizationConfig;

/**
 * The configuration of a synchronization web server.
 * @param secret the secret (base64) used to sign the JWT tokens of the authentication. If null, the authentication is disabled.
 * @param secureCookie true if the session cookie has the Secure attribute: browsers only send it over https (and to localhost).
 * It should be false only if the server is served over http (for instance on a local network). The default is true.
 * @param synchronization the libraries
 * @param plugins the configuration of the plugins, indexed by plugin id. Never null.
 */
public record SyncWebConfig(String secret, boolean secureCookie, SynchronizationConfig synchronization, Map<String, JsonNode> plugins) {
    public SyncWebConfig {
        Objects.requireNonNull(synchronization);
        plugins = plugins == null ? Map.of() : Map.copyOf(plugins);
    }

    /**
     * Creates a configuration whose session cookie is secure.
     * @param secret the secret
     * @param synchronization the libraries
     * @param plugins the configuration of the plugins
     */
    public SyncWebConfig(String secret, SynchronizationConfig synchronization, Map<String, JsonNode> plugins) {
        this(secret, true, synchronization, plugins);
    }
}
