package com.fathzer.sync4j.web.config;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fathzer.sync4j.Folder;
import com.fathzer.sync4j.config.Library;
import com.fathzer.sync4j.config.ProviderRegistry;

class ConfigLoaderTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static SyncWebConfig parse(String json) throws IOException {
        return ConfigLoader.parse(MAPPER.readTree(json), ProviderRegistry.withDefaults());
    }

    private static String library(String id, String src, String dest) {
        return "{\"id\":\"" + id + "\",\"source\":{\"provider\":\"local\",\"path\":\"" + src + "\"},\"destination\":{\"provider\":\"local\",\"path\":\"" + dest + "\"}}";
    }

    @Test
    void testLoad(@TempDir Path tmp) throws IOException {
        final Path src = Files.createDirectory(tmp.resolve("src"));
        final String json = "{\"secret\":\"abc\",\"synchronization\":{\"libraries\":[" +
                "{\"id\":\"lib-1\",\"label\":\"Library 1\",\"source\":{\"provider\":\"local\",\"path\":\"" + src + "\"},\"destination\":{\"provider\":\"local\",\"path\":\"" + tmp.resolve("dst") + "\"}}" +
                "],\"excludePatterns\":[\"*.tmp\"]},\"plugins\":{\"photos\":{\"host\":\"http://localhost\"}}}";
        final Path file = Files.writeString(tmp.resolve("config.json"), json);

        final SyncWebConfig config = ConfigLoader.load(file, ProviderRegistry.withDefaults());

        assertEquals("abc", config.secret());
        assertEquals(java.util.List.of("*.tmp"), config.synchronization().excludePatterns());
        assertEquals(1, config.synchronization().libraries().size());
        final Library library = config.synchronization().libraries().get(0);
        assertEquals("lib-1", library.id());
        assertEquals("Library 1", library.label());
        assertEquals(src.toString(), library.source().path());
        // The provider is usable
        final Folder opened = library.source().entry(true);
        assertTrue(opened.isFolder());
        opened.getFileProvider().close();
        assertEquals("http://localhost", config.plugins().get("photos").get("host").asText());
    }

    @Test
    void testDefaults() throws IOException {
        final SyncWebConfig config = parse("{\"synchronization\":{\"libraries\":[" + library("a", "/src", "/dst") + "]}}");

        assertNull(config.secret());
        assertTrue(config.secureCookie(), "The session cookie is secure by default");
        assertTrue(config.synchronization().excludePatterns().isEmpty());
        assertTrue(config.plugins().isEmpty());
        // The label is the id by default
        assertEquals("a", config.synchronization().libraries().get(0).label());
    }

    @Test
    void testSecureCookie() throws IOException {
        final String sync = "\"synchronization\":{\"libraries\":[]}";

        assertTrue(parse("{\"secureCookie\":true," + sync + "}").secureCookie());
        assertFalse(parse("{\"secureCookie\":false," + sync + "}").secureCookie());
        assertThrows(IOException.class, () -> parse("{\"secureCookie\":\"false\"," + sync + "}"), "A string is not a boolean");
        assertThrows(IOException.class, () -> parse("{\"secureCookie\":0," + sync + "}"));
    }

    @Test
    void testPluginSections() throws IOException {
        final SyncWebConfig config = parse("{\"synchronization\":{\"libraries\":[" + library("a", "/src", "/dst") + "]},\"plugins\":{\"myplugin\":{\"host\":\"h\"},\"other\":{}}}");

        assertEquals("h", config.plugins().get("myplugin").get("host").asText());
        assertEquals(java.util.Set.of("myplugin", "other"), config.plugins().keySet());
    }

    @Test
    void testUnknownRootProperties() {
        // A plugin section at the root (the format of the first versions) or a typo is an error, not an ignored property
        final IOException e = assertThrows(IOException.class, () -> parse("{\"myplugin\":{\"host\":\"h\"},\"synchronisation\":{},\"synchronization\":{\"libraries\":[]}}"));

        assertTrue(e.getMessage().contains("myplugin"), e.getMessage());
        assertTrue(e.getMessage().contains("synchronisation"), e.getMessage());
        assertTrue(e.getMessage().contains("plugins"), e.getMessage());
    }

    @Test
    void testInvalidConfigurations() {
        assertThrows(IOException.class, () -> parse("{}"), "synchronization is required");
        assertThrows(IOException.class, () -> parse("{\"synchronization\":{\"libraries\":[{\"label\":\"no id\"}]}}"));
        assertThrows(IOException.class, () -> parse("{\"synchronization\":{\"libraries\":[{\"id\":\"a\",\"destination\":{\"provider\":\"local\",\"path\":\"/d\"}}]}}"), "source is required");
        assertThrows(IOException.class, () -> parse("{\"synchronization\":{\"libraries\":[{\"id\":\"bad id\"," +
                "\"source\":{\"provider\":\"local\",\"path\":\"/s\"},\"destination\":{\"provider\":\"local\",\"path\":\"/d\"}}]}}"));
        final IOException unknown = assertThrows(IOException.class, () -> parse("{\"synchronization\":{\"libraries\":[{\"id\":\"a\"," +
                "\"source\":{\"provider\":\"ftp\",\"path\":\"/s\"},\"destination\":{\"provider\":\"local\",\"path\":\"/d\"}}]}}"));
        assertEquals("Unknown provider: ftp", unknown.getMessage());
        final IOException twice = assertThrows(IOException.class, () -> parse("{\"synchronization\":{\"libraries\":[" + library("a", "/s", "/d") + "," + library("a", "/s2", "/d2") + "]}}"));
        assertTrue(twice.getMessage().contains("defined twice"));
    }

    @Test
    void testUndefinedEnvironmentVariable() {
        final IOException e = assertThrows(IOException.class, () -> parse("{\"secret\":\"${SYNC4J_WEB_SURELY_UNDEFINED}\",\"synchronization\":{\"libraries\":[]}}"));
        assertTrue(e.getMessage().contains("SYNC4J_WEB_SURELY_UNDEFINED"));
        assertThrows(IOException.class, () -> parse("{\"synchronization\":{\"libraries\":[" + library("a", "${SYNC4J_WEB_SURELY_UNDEFINED}/x", "/d") + "]}}"));
    }
}
