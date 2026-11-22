package com.fathzer.sync4j.web.plugin;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fathzer.sync4j.config.PluginDiscovery;
import com.fathzer.sync4j.web.DiscoveredPluginFactory;
import com.fathzer.sync4j.web.SyncServer;
import com.fathzer.sync4j.web.TestJars;
import com.fathzer.sync4j.web.config.ConfigLoader;
import com.fathzer.sync4j.config.ProviderRegistry;
import com.fathzer.sync4j.web.config.SyncWebConfig;

class PluginDiscoveryTest {
    private static final String PLUGIN_PROPERTY = "sync4j.web.test.jarPlugin";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String PLUGIN_FACTORY = "package jarplugin;\n"
            + "public class JarPluginFactory implements com.fathzer.sync4j.web.plugin.SyncPluginFactory {\n"
            + "  public String id() { return \"jarplugin\"; }\n"
            + "  public com.fathzer.sync4j.web.plugin.SyncPlugin create(com.fasterxml.jackson.databind.JsonNode config) {\n"
            + "    System.setProperty(\"" + PLUGIN_PROPERTY + "\", config.toString());\n"
            + "    return () -> \"jarplugin\";\n"
            + "  }\n"
            + "}\n";

    @TempDir
    Path tmp;

    @AfterEach
    void tearDown() {
        System.clearProperty(PLUGIN_PROPERTY);
    }

    private Path pluginJar(String name) throws IOException {
        final Path directory = Files.createDirectories(tmp.resolve("plugins"));
        final Path jar = directory.resolve(name);
        TestJars.build(jar, Map.of("jarplugin.JarPluginFactory", PLUGIN_FACTORY),
                Map.of(SyncPluginFactory.class.getName(), List.of("jarplugin.JarPluginFactory")));
        return jar;
    }

    @Test
    void testClasspath() {
        // The factory declared in the META-INF/services of the tests
        final List<SyncPluginFactory> factories = PluginDiscovery.discover(SyncPluginFactory.class, null);

        assertEquals(1, factories.size());
        assertInstanceOf(DiscoveredPluginFactory.class, factories.get(0));
    }

    @Test
    void testJarDirectory() throws IOException {
        pluginJar("a-plugin.jar");

        final List<SyncPluginFactory> factories = PluginDiscovery.discover(SyncPluginFactory.class, tmp.resolve("plugins"));

        // The factories of the classpath come first
        assertEquals(List.of("discovered", "jarplugin"), factories.stream().map(SyncPluginFactory::id).toList());
    }

    @Test
    void testMissingDirectoryAndOtherFilesAreIgnored() throws IOException {
        assertEquals(1, PluginDiscovery.discover(SyncPluginFactory.class, tmp.resolve("missing")).size());

        final Path directory = Files.createDirectories(tmp.resolve("plugins"));
        Files.writeString(directory.resolve("readme.txt"), "Not a jar");
        assertEquals(1, PluginDiscovery.discover(SyncPluginFactory.class, directory).size());
    }

    @Test
    void testBrokenJarsDoNotPreventTheDiscovery() throws IOException {
        final Path directory = Files.createDirectories(tmp.resolve("plugins"));
        // A file that is not a jar, a jar that declares a class that does not exist, a jar that declares a class that is not a factory
        Files.writeString(directory.resolve("0-broken.jar"), "Not a jar");
        TestJars.build(directory.resolve("1-missing-class.jar"), Map.of(), Map.of(SyncPluginFactory.class.getName(), List.of("does.not.Exist")));
        TestJars.build(directory.resolve("2-wrong-type.jar"), Map.of("jarplugin.NotAFactory", "package jarplugin; public class NotAFactory {}"),
                Map.of(SyncPluginFactory.class.getName(), List.of("jarplugin.NotAFactory")));
        pluginJar("3-valid.jar");

        final List<SyncPluginFactory> factories = PluginDiscovery.discover(SyncPluginFactory.class, directory);

        assertEquals(List.of("discovered", "jarplugin"), factories.stream().map(SyncPluginFactory::id).toList());
    }

    @Test
    void testPluginOfAJarIsActivatedByItsConfiguration() throws IOException {
        pluginJar("plugin.jar");
        final Path source = Files.createDirectory(tmp.resolve("source"));
        final Path destination = Files.createDirectory(tmp.resolve("destination"));
        final SyncWebConfig config = ConfigLoader.parse(MAPPER.readTree("{\"plugins\":{\"jarplugin\":{\"option\":42}},\"synchronization\":{\"libraries\":[{\"id\":\"a\"," +
                "\"source\":{\"provider\":\"local\",\"path\":\"" + source + "\"},\"destination\":{\"provider\":\"local\",\"path\":\"" + destination + "\"}}]}}"), ProviderRegistry.withDefaults());

        // Without the directory, the section of the plugin is not used
        try (SyncServer server = SyncServer.builder(config).statusFile(tmp.resolve("statuses.json")).build()) {
            assertNull(System.getProperty(PLUGIN_PROPERTY));
        }

        // With the directory, the plugin is created with its configuration
        try (SyncServer server = SyncServer.builder(config).statusFile(tmp.resolve("statuses.json")).pluginsDirectory(tmp.resolve("plugins")).build()) {
            assertEquals("{\"option\":42}", System.getProperty(PLUGIN_PROPERTY));
        }
    }
}
