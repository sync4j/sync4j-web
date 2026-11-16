package com.fathzer.sync4j.web;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fathzer.sync4j.web.config.ConfigLoader;
import com.fathzer.sync4j.config.ProviderRegistry;
import com.fathzer.sync4j.web.config.SyncWebConfig;
import com.fathzer.sync4j.web.plugin.PluginContext;
import com.fathzer.sync4j.web.plugin.SyncPlugin;
import com.fathzer.sync4j.web.security.AuthenticationFailedException;
import com.fathzer.sync4j.web.security.Authenticator;
import com.fathzer.sync4j.web.security.JwtLoginManager;
import com.fathzer.sync4j.web.sync.SyncRequest;
import com.fathzer.sync4j.web.sync.SyncStatus;

/**
 * Tests the server with real HTTP and WebSocket clients.
 */
class SyncServerTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Authenticator AUTHENTICATOR = (user, password) -> {
        if ("alice".equals(user) && "secret".equals(password)) {
            return "alice";
        }
        throw new AuthenticationFailedException(401, "Bad credentials", null);
    };

    /** A plugin that records what happens and uses the extension points. */
    private static class TestPlugin implements SyncPlugin {
        private final List<String> calls = new CopyOnWriteArrayList<>();
        private final AtomicBoolean closed = new AtomicBoolean();
        private volatile PluginContext context;
        private volatile String refusal;

        @Override
        public String id() {
            return "test";
        }

        @Override
        public void init(PluginContext context) {
            this.context = context;
            // A route protected by the login manager of the server
            context.app().get("/api/plugins/test/hello", ctx -> {
                final var login = context.loginManager().checkLogin(ctx);
                if (login.error() != null) {
                    ctx.status(401).json(Map.of("error", login.error()));
                } else {
                    ctx.json(Map.of("hello", login.user(), "libraries", context.libraries().size()));
                }
            });
        }

        @Override
        public void beforeSync(SyncRequest request) throws IOException {
            calls.add("before " + request.library().id());
            if (refusal != null) {
                throw new IOException(refusal);
            }
        }

        @Override
        public void afterSync(SyncRequest request, SyncStatus status) {
            calls.add("after " + request.library().id() + " " + status.status());
            context.publish(request.library().id(), Map.of("message", "Scan requested", "label", "Scan", "progress", Map.of("done", 1, "total", 2)));
        }

        @Override
        public void close() {
            closed.set(true);
        }
    }

    /** Collects the messages sent by a WebSocket. */
    private static class MessageCollector implements WebSocket.Listener {
        final BlockingQueue<JsonNode> messages = new LinkedBlockingQueue<>();
        private final StringBuilder current = new StringBuilder();

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            current.append(data);
            if (last) {
                try {
                    messages.add(MAPPER.readTree(current.toString()));
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                } finally {
                    current.setLength(0);
                }
            }
            webSocket.request(1);
            return null;
        }

        /** Waits for the first message of a type. */
        JsonNode await(String type) throws InterruptedException {
            final long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (System.nanoTime() < end) {
                final JsonNode message = messages.poll(100, TimeUnit.MILLISECONDS);
                if (message != null && type.equals(message.get("type").asText())) {
                    return message;
                }
            }
            return fail("No " + type + " message received");
        }
    }

    @TempDir
    Path tmp;
    private Path source;
    private Path destination;
    private HttpClient client;
    private SyncServer server;
    private TestPlugin plugin;

    @BeforeEach
    void setUp() throws IOException {
        source = Files.createDirectory(tmp.resolve("source"));
        destination = Files.createDirectory(tmp.resolve("destination"));
        Files.writeString(source.resolve("a.txt"), "a");
        client = HttpClient.newHttpClient();
        plugin = new TestPlugin();
        DiscoveredPluginFactory.CREATED.set(0);
        DiscoveredPluginFactory.lastConfig = null;
    }

    @AfterEach
    void tearDown() {
        client.close();
        if (server != null) {
            server.stop();
        }
    }

    private SyncWebConfig config(boolean secured, String extra) throws IOException {
        final String json = "{" + (secured ? "\"secret\":\"" + JwtLoginManager.buildSecret() + "\"," : "") + extra +
                "\"synchronization\":{\"libraries\":[{\"id\":\"photos\",\"label\":\"Photos\"," +
                "\"source\":{\"provider\":\"local\",\"path\":\"" + source + "\"},\"destination\":{\"provider\":\"local\",\"path\":\"" + destination + "\"}}]}}";
        return ConfigLoader.parse(MAPPER.readTree(json), ProviderRegistry.withDefaults());
    }

    private SyncServer start(SyncWebConfig config) throws IOException {
        server = SyncServer.builder(config).authenticator(AUTHENTICATOR).statusFile(tmp.resolve("statuses.json")).plugin(plugin).build();
        server.start(0);
        return server;
    }

    private String url(String path) {
        return "http://localhost:" + server.port() + path;
    }

    private HttpResponse<String> send(String method, String path, String body, String cookie) throws IOException, InterruptedException {
        final HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url(path)))
                .header("Content-Type", "application/json")
                .method(method, body == null ? BodyPublishers.noBody() : BodyPublishers.ofString(body));
        if (cookie != null) {
            builder.header("Cookie", cookie);
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String login() throws IOException, InterruptedException {
        final HttpResponse<String> response = send("POST", "/api/login", "{\"user\":\"alice\",\"password\":\"secret\"}", null);
        assertEquals(200, response.statusCode());
        final String header = response.headers().firstValue("Set-Cookie").orElseThrow();
        return header.substring(0, header.indexOf(';'));
    }

    private String loginSetCookie() throws IOException, InterruptedException {
        final HttpResponse<String> response = send("POST", "/api/login", "{\"user\":\"alice\",\"password\":\"secret\"}", null);
        assertEquals(200, response.statusCode());
        return response.headers().firstValue("Set-Cookie").orElseThrow();
    }

    private JsonNode awaitStatusEnd(String cookie) throws Exception {
        final long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < end) {
            final JsonNode status = MAPPER.readTree(send("GET", "/api/libraries/sync/photos", null, cookie).body());
            if (!"SYNCING".equals(status.get("status").asText())) {
                return status;
            }
            Thread.sleep(50);
        }
        return fail("The synchronization did not finish");
    }

    @Test
    void testWebPageIsServed() throws Exception {
        start(config(false, ""));

        final HttpResponse<String> page = send("GET", "/", null, null);
        assertEquals(200, page.statusCode());
        assertTrue(page.body().contains("Synchronization Dashboard"));
        assertEquals(200, send("GET", "/js/sync-progress.js", null, null).statusCode());
        assertEquals(404, send("GET", "/js/missing.js", null, null).statusCode());
    }

    @Test
    void testCustomStaticFilesOverrideTheDefaultPage() throws Exception {
        final Path custom = Files.createDirectory(tmp.resolve("custom"));
        Files.writeString(custom.resolve("index.html"), "<html>My page</html>");
        server = SyncServer.builder(config(false, "")).statusFile(tmp.resolve("statuses.json")).staticFiles(custom).build();
        server.start(0);

        assertEquals("<html>My page</html>", send("GET", "/", null, null).body());
        // The other files are still the default ones
        assertEquals(200, send("GET", "/styles.css", null, null).statusCode());
    }

    @Test
    void testSessionCookie() throws Exception {
        // The cookie is secure by default
        start(config(true, ""));
        final String cookie = loginSetCookie();
        assertTrue(cookie.contains("Secure"), cookie);
        assertTrue(cookie.contains("HttpOnly"), cookie);
        assertTrue(cookie.contains("SameSite=Strict"), cookie);
        server.stop();

        // The configuration can make it usable over http
        start(config(true, "\"secureCookie\":false,"));
        final String insecure = loginSetCookie();
        assertFalse(insecure.contains("Secure"), insecure);
        assertTrue(insecure.contains("HttpOnly"), insecure);
        assertTrue(insecure.contains("SameSite=Strict"), insecure);
        server.stop();

        // The builder has the last word
        server = SyncServer.builder(config(true, "\"secureCookie\":false,")).authenticator(AUTHENTICATOR).statusFile(tmp.resolve("statuses.json")).secureCookie(true).build();
        server.start(0);
        assertTrue(loginSetCookie().contains("Secure"));
    }

    @Test
    void testLibraries() throws Exception {
        start(config(true, ""));
        final String cookie = login();

        final JsonNode libraries = MAPPER.readTree(send("GET", "/api/libraries", null, cookie).body());
        assertEquals(1, libraries.size());
        assertEquals("photos", libraries.get(0).get("id").asText());
        assertEquals("Photos", libraries.get(0).get("label").asText());
        assertEquals("IDLE", MAPPER.readTree(send("GET", "/api/libraries/sync/photos", null, cookie).body()).get("status").asText());
    }

    @Test
    void testReadRoutesRequireLogin() throws Exception {
        start(config(true, ""));
        final String cookie = login();
        final String forged = cookie.substring(0, cookie.length() - 3) + "abc";

        for (String path : List.of("/api/libraries", "/api/libraries/sync", "/api/libraries/sync/photos", "/api/libraries/sync/unknown", "/api/libraries/other")) {
            final HttpResponse<String> anonymous = send("GET", path, null, null);
            assertEquals(401, anonymous.statusCode(), path);
            assertEquals("Missing token", MAPPER.readTree(anonymous.body()).get("error").asText(), path);
            assertEquals(401, send("GET", path, null, forged).statusCode(), path + " with a forged token");
        }
        // The answer of the logged in user is not an authentication error
        for (String path : List.of("/api/libraries", "/api/libraries/sync", "/api/libraries/sync/photos")) {
            assertEquals(200, send("GET", path, null, cookie).statusCode(), path);
        }
    }

    @Test
    void testWebPageAndLoginDoNotRequireLogin() throws Exception {
        start(config(true, ""));

        // The page has to be available to display the login form
        assertEquals(200, send("GET", "/", null, null).statusCode());
        assertEquals(200, send("GET", "/js/auth.js", null, null).statusCode());
        // The login API tells that the user is not logged in, it does not refuse the request
        assertEquals(401, send("GET", "/api/login", null, null).statusCode());
        assertEquals(200, send("POST", "/api/login", "{\"user\":\"alice\",\"password\":\"secret\"}", null).statusCode());
    }

    @Test
    void testWebSocketRequiresLogin() throws Exception {
        start(config(true, ""));
        final URI uri = URI.create("ws://localhost:" + server.port() + "/ws/sync/photos");

        // The connection is refused (HTTP 401), the WebSocket is not even opened
        final ExecutionException refused = assertThrows(ExecutionException.class, () -> client.newWebSocketBuilder().buildAsync(uri, new MessageCollector()).get(5, TimeUnit.SECONDS));
        final WebSocketHandshakeException handshake = assertInstanceOf(WebSocketHandshakeException.class, refused.getCause());
        assertEquals(401, handshake.getResponse().statusCode());
        final ExecutionException forged = assertThrows(ExecutionException.class,
                () -> client.newWebSocketBuilder().header("Cookie", "auth_token=forged").buildAsync(uri, new MessageCollector()).get(5, TimeUnit.SECONDS));
        assertEquals(401, assertInstanceOf(WebSocketHandshakeException.class, forged.getCause()).getResponse().statusCode());

        // A logged in user receives the status
        final MessageCollector collector = new MessageCollector();
        final WebSocket webSocket = client.newWebSocketBuilder().header("Cookie", login()).buildAsync(uri, collector).get(5, TimeUnit.SECONDS);
        try {
            assertEquals("IDLE", collector.await("STATUS").get("data").get("status").asText());
        } finally {
            webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void testSynchronizationRequiresLogin() throws Exception {
        start(config(true, ""));

        // Not logged in
        assertEquals(401, send("POST", "/api/libraries/sync/photos", "{}", null).statusCode());
        assertEquals(401, send("DELETE", "/api/libraries/sync/photos", null, null).statusCode());
        assertFalse(Files.exists(destination.resolve("a.txt")));

        // Logged in
        final String cookie = login();
        assertEquals(404, send("POST", "/api/libraries/sync/unknown", "{}", cookie).statusCode());
        assertEquals(400, send("POST", "/api/libraries/sync/photos", "{\"suffixPath\":\"  \"}", cookie).statusCode());
        assertEquals(202, send("POST", "/api/libraries/sync/photos", "{\"useChecksum\":true}", cookie).statusCode());
        final JsonNode status = awaitStatusEnd(cookie);

        assertEquals("COMPLETED", status.get("status").asText(), status.toString());
        assertEquals("a", Files.readString(destination.resolve("a.txt")));
        assertEquals(1, status.get("result").get("copiedFiles").get("done").asInt());
    }

    @Test
    void testSynchronizationOfASubFolder() throws Exception {
        Files.createDirectory(source.resolve("sub"));
        Files.writeString(source.resolve("sub/b.txt"), "b");
        start(config(false, ""));

        assertEquals(202, send("POST", "/api/libraries/sync/photos", "{\"suffixPath\":\"sub\"}", null).statusCode());
        assertEquals("COMPLETED", awaitStatusEnd(null).get("status").asText());

        assertEquals("b", Files.readString(destination.resolve("sub/b.txt")));
        assertFalse(Files.exists(destination.resolve("a.txt")), "Only the sub folder should be synchronized");
    }

    @Test
    void testPluginHooksAndMessages() throws Exception {
        start(config(false, ""));
        final MessageCollector collector = new MessageCollector();
        final WebSocket webSocket = client.newWebSocketBuilder().buildAsync(URI.create("ws://localhost:" + server.port() + "/ws/sync/photos"), collector).get(5, TimeUnit.SECONDS);
        try {
            // The client receives the current status when it connects
            assertEquals("IDLE", collector.await("STATUS").get("data").get("status").asText());

            assertEquals(202, send("POST", "/api/libraries/sync/photos", "{}", null).statusCode());
            final JsonNode pluginMessage = collector.await("PLUGIN");

            assertEquals("test", pluginMessage.get("plugin").asText());
            assertEquals("photos", pluginMessage.get("library").asText());
            assertEquals("Scan requested", pluginMessage.get("data").get("message").asText());
            assertEquals(2, pluginMessage.get("data").get("progress").get("total").asInt());
            assertEquals(List.of("before photos", "after photos COMPLETED"), plugin.calls);
        } finally {
            webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void testPluginCanRefuseASynchronization() throws Exception {
        start(config(false, ""));
        plugin.refusal = "The photo server is not available";

        final HttpResponse<String> response = send("POST", "/api/libraries/sync/photos", "{}", null);

        assertEquals(400, response.statusCode());
        assertTrue(MAPPER.readTree(response.body()).get("error").asText().contains("The photo server is not available"));
        assertEquals(List.of("before photos"), plugin.calls);
        assertFalse(Files.exists(destination.resolve("a.txt")));
    }

    @Test
    void testPluginRoutesCanBeProtectedByTheServerLogin() throws Exception {
        start(config(true, ""));

        assertEquals(401, send("GET", "/api/plugins/test/hello", null, null).statusCode());
        final JsonNode hello = MAPPER.readTree(send("GET", "/api/plugins/test/hello", null, login()).body());
        assertEquals("alice", hello.get("hello").asText());
        assertEquals(1, hello.get("libraries").asInt());
    }

    @Test
    void testDiscoveredPluginIsActivatedByItsConfiguration() throws Exception {
        // Without configuration section, the discovered plugin is ignored
        start(config(false, ""));
        assertEquals(0, DiscoveredPluginFactory.CREATED.get());
        server.stop();

        start(config(false, "\"plugins\":{\"discovered\":{\"option\":\"value\"}},"));
        assertEquals(1, DiscoveredPluginFactory.CREATED.get());
        assertEquals("value", DiscoveredPluginFactory.lastConfig.get("option").asText());
        server.stop();

        // ... unless the discovery is disabled
        DiscoveredPluginFactory.CREATED.set(0);
        server = SyncServer.builder(config(false, "\"plugins\":{\"discovered\":{}},")).statusFile(tmp.resolve("statuses.json")).discoverPlugins(false).build();
        assertEquals(0, DiscoveredPluginFactory.CREATED.get());
    }

    @Test
    void testPluginReceivesItsConfigurationAndIsClosed() throws Exception {
        final AtomicReference<JsonNode> received = new AtomicReference<>();
        final AtomicBoolean closed = new AtomicBoolean();
        final SyncPlugin configured = new SyncPlugin() {
            @Override
            public String id() {
                return "configured";
            }

            @Override
            public void init(PluginContext context) {
                received.set(context.config());
            }

            @Override
            public void close() {
                closed.set(true);
            }
        };
        server = SyncServer.builder(config(false, "\"plugins\":{\"configured\":{\"host\":\"h\"}},")).statusFile(tmp.resolve("statuses.json")).plugin(configured).plugin(plugin).build();
        assertEquals("h", received.get().get("host").asText());
        // A plugin without section has a missing configuration node, not null
        assertTrue(plugin.context.config().isMissingNode());

        server.stop();
        assertTrue(closed.get());
        assertTrue(plugin.closed.get());
        // Stopping twice is harmless
        assertDoesNotThrow(server::stop);
    }

    @Test
    void testInvalidServers() throws Exception {
        final Path statusFile = tmp.resolve("statuses.json");
        // A secret requires an authenticator
        final IllegalStateException noAuthenticator = assertThrows(IllegalStateException.class, () -> SyncServer.builder(config(true, "")).statusFile(statusFile).build());
        assertTrue(noAuthenticator.getMessage().contains("authenticator"));

        // A secret that is not a valid one (a password, for instance) is refused with an explicit message
        final SyncWebConfig passwordAsSecret = ConfigLoader.parse(MAPPER.readTree("{\"secret\":\"my-password\",\"synchronization\":{\"libraries\":[{\"id\":\"photos\"," +
                "\"source\":{\"provider\":\"local\",\"path\":\"" + source + "\"},\"destination\":{\"provider\":\"local\",\"path\":\"" + destination + "\"}}]}}"), ProviderRegistry.withDefaults());
        final IllegalArgumentException badSecret = assertThrows(IllegalArgumentException.class, () -> SyncServer.builder(passwordAsSecret).authenticator(AUTHENTICATOR).statusFile(statusFile).build());
        assertTrue(badSecret.getMessage().contains("The secret is not valid"), badSecret.getMessage());

        // Two plugins with the same id
        assertThrows(IllegalArgumentException.class, () -> SyncServer.builder(config(false, "")).statusFile(statusFile).plugin(new TestPlugin()).plugin(new TestPlugin()).build());

        // A library that can't be opened
        Files.delete(source.resolve("a.txt"));
        Files.delete(source);
        final IOException unavailable = assertThrows(IOException.class, () -> SyncServer.builder(config(false, "")).statusFile(statusFile).build());
        assertTrue(unavailable.getMessage().contains("photos"), unavailable.getMessage());
        // ... unless the check is disabled
        server = SyncServer.builder(config(false, "")).statusFile(statusFile).checkLibraries(false).build();

        // No libraries
        final SyncWebConfig empty = ConfigLoader.parse(MAPPER.readTree("{\"synchronization\":{\"libraries\":[]}}"), ProviderRegistry.withDefaults());
        assertThrows(IllegalArgumentException.class, () -> SyncServer.builder(empty).statusFile(statusFile).build());
    }
}
