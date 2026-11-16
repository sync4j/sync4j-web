package com.fathzer.sync4j.web.config;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fathzer.sync4j.config.EnvVarSubstitutor;
import com.fathzer.sync4j.config.ProviderRegistry;
import com.fathzer.sync4j.config.SynchronizationConfig;

/**
 * Loads the configuration of a server from a JSON file.
 * <p>
 * Here is an example:
 * </p>
 * <pre>
 * {
 *   "secret": "${JWT_SECRET}",
 *   "secureCookie": true,
 *   "synchronization": {
 *     "libraries": [
 *       {
 *         "id": "photos",
 *         "label": "My photos",
 *         "source": { "provider": "pCloud", "path": "/Photos", "config": { "zone": "us", "token": "${PCLOUD_TOKEN}" } },
 *         "destination": { "provider": "local", "path": "${LOCAL_PATH_PREFIX}/photos" }
 *       }
 *     ],
 *     "excludePatterns": ["*.tmp"]
 *   },
 *   "plugins": { "myplugin": { "option": "value" } }
 * }
 * </pre>
 * <p>
 * Environment variables can be used in the values of <code>secret</code>, <code>path</code> and in the provider configurations (<code>${VARIABLE}</code>).
 * </p>
 * <p>
 * The configuration of a plugin is the value of its id in <code>plugins</code>. The root object can only have the <code>secret</code>, <code>secureCookie</code>,
 * <code>synchronization</code> and <code>plugins</code> properties: any other property is an error, so a typo is not silently ignored.
 * </p>
 */
public final class ConfigLoader {
    private static final Set<String> ROOT_PROPERTIES = new java.util.TreeSet<>(Set.of("secret", "secureCookie", "synchronization", "plugins"));
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ConfigLoader() {
        // Utility class
    }

    /**
     * Loads a configuration from a file.
     * @param file the file
     * @param providers the available providers
     * @return the configuration
     * @throws IOException if the file can't be read or the configuration is invalid
     */
    public static SyncWebConfig load(Path file, ProviderRegistry providers) throws IOException {
        try {
            return parse(MAPPER.readTree(file.toFile()), providers);
        } catch (IllegalArgumentException e) {
            throw new IOException("Failed to load config from " + file + ": " + e.getMessage(), e);
        }
    }

    /**
     * Parses a configuration.
     * @param root the root of the JSON document
     * @param providers the available providers
     * @return the configuration
     * @throws IOException if the configuration is invalid
     */
    public static SyncWebConfig parse(JsonNode root, ProviderRegistry providers) throws IOException {
        Objects.requireNonNull(providers);
        final List<String> unknown = new ArrayList<>();
        root.fieldNames().forEachRemaining(name -> {
            if (!ROOT_PROPERTIES.contains(name)) {
                unknown.add(name);
            }
        });
        if (!unknown.isEmpty()) {
            throw new IOException("Unknown root properties: " + String.join(", ", unknown)
                    + " (the properties of the root are " + String.join(", ", ROOT_PROPERTIES) + ", the configuration of a plugin is in plugins)");
        }
        final JsonNode secretNode = root.get("secret");
        final String secret = secretNode == null || secretNode.isNull() ? null : EnvVarSubstitutor.replaceStrict(secretNode.asText(), "secret");
        final JsonNode sync = root.get("synchronization");
        if (sync == null) {
            throw new IOException("Missing synchronization attribute");
        }
        final JsonNode secureCookie = root.get("secureCookie");
        if (secureCookie != null && !secureCookie.isBoolean()) {
            throw new IOException("secureCookie should be true or false");
        }
        final Map<String, JsonNode> plugins = new LinkedHashMap<>();
        root.path("plugins").fields().forEachRemaining(e -> plugins.put(e.getKey(), e.getValue()));
        return new SyncWebConfig(secret, secureCookie == null || secureCookie.asBoolean(), SynchronizationConfig.parse(sync, providers), plugins);
    }
}
