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
import com.fathzer.sync4j.web.plugin.Message;
import com.fathzer.sync4j.web.plugin.PluginContext;
import com.fathzer.sync4j.web.plugin.SyncRefusedException;
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
    private static final java.time.Duration REQUEST_TIMEOUT = java.time.Duration.ofSeconds(10);
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
        private volatile IOException beforeSyncFailure;
        private volatile List<Message> warnings = List.of();
        private volatile IOException checkFailure;

        @Override
        public String id() {
            return "test";
        }

        @Override
        public Map<String, String> translations(String language) {
            return switch (language) {
                case "en" -> Map.of("test.notReadyEither", "{0} is not ready either", "test.englishOnly", "Only in English");
                case "fr" -> Map.of("test.notReadyEither", "{0} n'est pas prêt non plus");
                default -> Map.of();
            };
        }

        @Override
        public List<Message> check(com.fathzer.sync4j.config.Library library) throws IOException {
            calls.add("check " + library.id());
            if (checkFailure != null) {
                throw checkFailure;
            }
            return warnings;
        }

        @Override
        public void init(PluginContext context) {
            this.context = context;
            // A route protected by the login manager of the server
            context.routes().get("/api/plugins/test/hello", ctx -> {
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
                throw new SyncRefusedException(refusal);
            }
            if (beforeSyncFailure != null) {
                throw beforeSyncFailure;
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
        // A server that does not answer makes the test fail instead of blocking the build
        final HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url(path))).timeout(REQUEST_TIMEOUT)
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
    void testTitleOfThePage() throws Exception {
        // The title does not need a login (the login form is displayed with it)
        start(config(true, ""));
        HttpResponse<String> response = send("GET", "/api/ui", null, null);
        assertEquals(200, response.statusCode());
        assertEquals("Synchronization Dashboard", MAPPER.readTree(response.body()).get("title").asText());
        server.stop();

        server = SyncServer.builder(config(true, "")).authenticator(AUTHENTICATOR).statusFile(tmp.resolve("statuses.json")).title("My photos").build();
        server.start(0);
        assertEquals("My photos", MAPPER.readTree(send("GET", "/api/ui", null, null).body()).get("title").asText());
        // ... but nothing else does
        assertEquals(401, send("GET", "/api/libraries", null, null).statusCode());
    }

    @Test
    void testStateOfThePluginIsSentToTheClientsThatConnectLater() throws Exception {
        start(config(false, ""));
        // A message with a state is the state of the plugin, a message without is only something that happened
        plugin.context.publish("photos", Map.of("message", "Just a message"));
        plugin.context.publish("photos", Map.of("label", "Plugin", "state", "RUNNING", "message", "Old state"));
        plugin.context.publish("photos", Map.of("label", "Plugin", "state", "DONE", "message", "Current state"));

        final MessageCollector collector = new MessageCollector();
        final WebSocket webSocket = client.newWebSocketBuilder().buildAsync(URI.create("ws://localhost:" + server.port() + "/ws/sync/photos"), collector).get(5, TimeUnit.SECONDS);
        try {
            // The status of the library, then the last state of the plugin (and nothing else)
            assertEquals("IDLE", collector.await("STATUS").get("data").get("status").asText());
            final JsonNode state = collector.await("PLUGIN");
            assertEquals("test", state.get("plugin").asText());
            assertEquals("DONE", state.get("data").get("state").asText());
            assertEquals("Current state", state.get("data").get("message").asText());
            assertNull(collector.messages.poll(300, TimeUnit.MILLISECONDS), "Nothing else should be sent");
        } finally {
            webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(5, TimeUnit.SECONDS);
        }

        // What the plugin said about a synchronization is obsolete when the next one starts
        assertEquals(202, send("POST", "/api/libraries/sync/photos", "{}", null).statusCode());
        assertEquals("COMPLETED", awaitStatusEnd(null).get("status").asText());
        final MessageCollector later = new MessageCollector();
        final WebSocket other = client.newWebSocketBuilder().buildAsync(URI.create("ws://localhost:" + server.port() + "/ws/sync/photos"), later).get(5, TimeUnit.SECONDS);
        try {
            assertEquals("COMPLETED", later.await("STATUS").get("data").get("status").asText());
            // Nothing else is sent (the replay of the states of the plugins would come just after the status)
            final JsonNode next = later.messages.poll(300, TimeUnit.MILLISECONDS);
            assertTrue(next == null || !"PLUGIN".equals(next.get("type").asText()), "The old state should not be sent: " + next);
        } finally {
            other.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void testStateOfThePluginSurvivesARestartOfTheServer() throws Exception {
        start(config(false, ""));
        plugin.context.publish("photos", Map.of("label", "Plugin", "state", "DONE", "message", "Finished", "details", List.of("All good")));
        server.stop();
        // The state is in the same file as the statuses of the libraries
        final JsonNode saved = MAPPER.readTree(tmp.resolve("statuses.json").toFile());
        assertEquals("IDLE", saved.get("libraries").get("photos").get("status").asText());
        assertEquals("DONE", saved.get("plugins").get("photos").get("test").get("state").asText());

        start(config(false, ""));
        assertEquals("DONE", firstPluginMessage().get("data").get("state").asText());
        assertEquals("All good", firstPluginMessage().get("data").get("details").get(0).asText());

        // A plugin that was working when the server stopped is not monitored anymore: its result is unknown
        plugin.context.publish("photos", Map.of("label", "Plugin", "state", "RUNNING", "message", "Working", "progress", Map.of("done", 1, "total", 2)));
        server.stop();
        start(config(false, ""));
        final JsonNode data = firstPluginMessage().get("data");
        assertEquals("UNKNOWN", data.get("state").asText());
        assertEquals("plugin.interrupted", data.get("message").get("key").asText());
        assertFalse(data.get("message").has("fallback"), data.toString());
        assertFalse(data.has("progress"));
    }

    private JsonNode firstPluginMessage() throws Exception {
        final MessageCollector collector = new MessageCollector();
        final WebSocket webSocket = client.newWebSocketBuilder().buildAsync(URI.create("ws://localhost:" + server.port() + "/ws/sync/photos"), collector).get(5, TimeUnit.SECONDS);
        try {
            return collector.await("PLUGIN");
        } finally {
            webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void testTranslationsAreChosenWithTheLanguagesOfTheBrowser() throws Exception {
        server = SyncServer.builder(config(true, "")).authenticator(AUTHENTICATOR).statusFile(tmp.resolve("statuses.json")).plugin(plugin)
                .translations(language -> "fr".equals(language) ? Map.of("app.title", "Mon tableau", "login.title", "Identification") : Map.of()).build();
        server.start(0);

        // No login is needed: the login form is translated too
        final HttpResponse<String> french = sendWithLanguage("/api/i18n", "de;q=0.9,fr-CA;q=0.8,en;q=0.1");
        assertEquals(200, french.statusCode());
        assertEquals("Accept-Language", french.headers().firstValue("Vary").orElse(null));
        final JsonNode messages = MAPPER.readTree(french.body());
        assertEquals("fr", messages.get("language").asText());
        // The translations of the page, of the plugins, and of the application (that replaces the ones of the page)
        assertEquals("Déconnexion", messages.get("messages").get("logout").asText());
        assertEquals("{0} n'est pas prêt non plus", messages.get("messages").get("test.notReadyEither").asText());
        // What is not translated is in English
        assertEquals("Only in English", messages.get("messages").get("test.englishOnly").asText());
        assertEquals("The library can't be checked: {0}", MAPPER.readTree(sendWithLanguage("/api/i18n", "de").body()).get("messages").get("check.failed").asText());
        assertEquals("Identification", messages.get("messages").get("login.title").asText());

        final JsonNode english = MAPPER.readTree(sendWithLanguage("/api/i18n", "en-US,fr;q=0.5").body());
        assertEquals("en", english.get("language").asText());
        assertEquals("{0} is not ready either", english.get("messages").get("test.notReadyEither").asText());
        assertEquals("Logout", english.get("messages").get("logout").asText());
        assertEquals("fr", MAPPER.readTree(send("GET", "/api/i18n?lang=fr", null, null).body()).get("language").asText());

        // The title is the one of the application
        assertEquals("Mon tableau", MAPPER.readTree(sendWithLanguage("/api/ui", "fr").body()).get("title").asText());
        assertEquals(SyncServer.DEFAULT_TITLE, MAPPER.readTree(sendWithLanguage("/api/ui", "en").body()).get("title").asText());
    }

    @Test
    void testThePageCanBeInstalledAsAnApplication() throws Exception {
        // The login is enabled: the browser loads the manifest, the icons and the service worker without it
        server = SyncServer.builder(config(true, "")).authenticator(AUTHENTICATOR).statusFile(tmp.resolve("statuses.json")).build();
        server.start(0);

        final HttpResponse<String> response = sendWithLanguage("/manifest.webmanifest", "fr");
        assertEquals(200, response.statusCode());
        assertEquals("application/manifest+json", response.headers().firstValue("Content-Type").orElse("").split(";")[0]);
        final JsonNode manifest = MAPPER.readTree(response.body());
        // The name is the title of the application, in the language of the user
        assertEquals("Tableau de bord de synchronisation", manifest.get("name").asText());
        assertEquals("Synchro", manifest.get("short_name").asText());
        assertEquals("fr", manifest.get("lang").asText());
        // The URLs are relative: the application does not have to know its domain
        assertEquals("./", manifest.get("start_url").asText());
        assertEquals("standalone", manifest.get("display").asText());
        assertEquals("Sync", MAPPER.readTree(sendWithLanguage("/manifest.webmanifest", "en").body()).get("short_name").asText());
        for (JsonNode icon : manifest.get("icons")) {
            final HttpResponse<byte[]> image = client.send(HttpRequest.newBuilder(URI.create(url("/" + icon.get("src").asText()))).build(), HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, image.statusCode(), icon.toString());
            final java.awt.image.BufferedImage read = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(image.body()));
            assertEquals(icon.get("sizes").asText(), read.getWidth() + "x" + read.getHeight());
        }
        assertEquals(200, send("GET", "/sw.js", null, null).statusCode());
        assertEquals(200, send("GET", "/icons/apple-touch-icon.png", null, null).statusCode());
    }

    @Test
    void testTheNameOfTheApplicationIsTheTitleThatIsSet() throws Exception {
        server = SyncServer.builder(config(false, "")).statusFile(tmp.resolve("statuses.json")).title("My photos").build();
        server.start(0);
        final JsonNode manifest = MAPPER.readTree(sendWithLanguage("/manifest.webmanifest", "fr").body());
        assertEquals("My photos", manifest.get("name").asText());
        assertEquals("My photos", manifest.get("short_name").asText());
    }

    @Test
    void testTheTitleOfThePageIsTranslatedByDefault() throws Exception {
        start(config(false, ""));
        assertEquals("Tableau de bord de synchronisation", MAPPER.readTree(sendWithLanguage("/api/ui", "fr").body()).get("title").asText());
        assertEquals(SyncServer.DEFAULT_TITLE, MAPPER.readTree(sendWithLanguage("/api/ui", "de").body()).get("title").asText());
    }

    private HttpResponse<String> sendWithLanguage(String path, String languages) throws IOException, InterruptedException {
        return client.send(HttpRequest.newBuilder(URI.create(url(path))).timeout(REQUEST_TIMEOUT).header("Accept-Language", languages).build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void testTheApplicationCanReplaceFilesOfThePageWithItsResources() throws Exception {
        // The test resources have a test-www/icons/icon-192.png, as an application can have in its jar
        final Path custom = Files.createDirectory(tmp.resolve("custom"));
        Files.writeString(custom.resolve("styles.css"), "/* the administrator's */");
        server = SyncServer.builder(config(false, "")).statusFile(tmp.resolve("statuses.json")).staticResources("/test-www").staticFiles(custom).build();
        server.start(0);

        assertEquals("application icon", send("GET", "/icons/icon-192.png", null, null).body());
        // The other files are the default ones
        assertEquals(200, send("GET", "/icons/icon-512.png", null, null).statusCode());
        assertTrue(send("GET", "/index.html", null, null).body().contains("manifest.webmanifest"));
        // The files of the staticFiles directory replace the ones of the application
        assertEquals("/* the administrator's */", send("GET", "/styles.css", null, null).body());
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
    void testTheSuffixPathCanNotGoOutOfTheLibrary() throws Exception {
        final Path outside = Files.createDirectory(tmp.resolve("outside"));
        Files.writeString(outside.resolve("precious.txt"), "precious");
        start(config(false, ""));

        for (String suffix : List.of("..", "../outside", "sub/../../outside", "./sub", "sub/.", "sub//other", "..\\\\outside", "sub/ .. /x", " ")) {
            final HttpResponse<String> response = send("POST", "/api/libraries/sync/photos", MAPPER.writeValueAsString(Map.of("suffixPath", suffix)), null);
            assertEquals(400, response.statusCode(), suffix + " -> " + response.body());
        }
        Thread.sleep(300);
        assertEquals(List.of("precious.txt"), Files.list(outside).map(p -> p.getFileName().toString()).toList());
        assertEquals("IDLE", MAPPER.readTree(send("GET", "/api/libraries/sync/photos", null, null).body()).get("status").asText());
    }

    @Test
    void testTheSlashesAroundTheSuffixPathAreTolerated() throws Exception {
        Files.createDirectories(source.resolve("sub/folder"));
        Files.writeString(source.resolve("sub/folder/b.txt"), "b");
        start(config(false, ""));

        assertEquals(202, send("POST", "/api/libraries/sync/photos", "{\"suffixPath\":\" /sub/folder/ \"}", null).statusCode());
        assertEquals("COMPLETED", awaitStatusEnd(null).get("status").asText());
        assertEquals("b", Files.readString(destination.resolve("sub/folder/b.txt")));
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

        // The user is told why
        assertEquals(409, response.statusCode());
        assertEquals("The photo server is not available", MAPPER.readTree(response.body()).get("error").asText());
        assertEquals(List.of("before photos"), plugin.calls);
        assertFalse(Files.exists(destination.resolve("a.txt")));
    }

    @Test
    void testTheErrorsOfTheServerAreNotSentToTheUser() throws Exception {
        start(config(false, ""));
        // For instance a provider that can't open its folder: the message has a path of the server
        plugin.beforeSyncFailure = new IOException("Can't open /srv/private/photos");

        final HttpResponse<String> response = send("POST", "/api/libraries/sync/photos", "{}", null);

        assertEquals(500, response.statusCode());
        assertFalse(response.body().contains("/srv/private"), response.body());
        // The user receives a reference, that is in the log with the error
        final JsonNode error = MAPPER.readTree(response.body()).get("error");
        assertEquals("error.server", error.get("key").asText());
        assertTrue(error.get("args").get(0).asText().matches("[0-9a-f]{6}"), error.toString());
    }

    @Test
    void testTheErrorsOfTheRequestsCanBeTranslated() throws Exception {
        start(config(false, ""));
        assertEquals("library.notFound", MAPPER.readTree(send("POST", "/api/libraries/sync/unknown", "{}", null).body()).get("error").get("key").asText());
        assertEquals("sync.invalidSuffix", MAPPER.readTree(send("POST", "/api/libraries/sync/photos", "{\"suffixPath\":\"..\"}", null).body()).get("error").get("key").asText());
        assertEquals("sync.notRunning", MAPPER.readTree(send("DELETE", "/api/libraries/sync/photos", null, null).body()).get("error").get("key").asText());
    }

    @Test
    void testPluginsCheckTheLibrary() throws Exception {
        start(config(false, ""));

        // No problem
        HttpResponse<String> response = send("GET", "/api/libraries/checks/photos", null, null);
        assertEquals(200, response.statusCode());
        assertEquals("[]", response.body());

        // The problems of the plugin are returned, and the plugin is asked each time (the user can check again after fixing the problem)
        plugin.warnings = List.of(Message.of("test.notReady"), Message.of("test.notReadyEither", "Photos"));
        JsonNode warnings = MAPPER.readTree(send("GET", "/api/libraries/checks/photos", null, null).body());
        assertEquals(2, warnings.size());
        assertEquals("test", warnings.get(0).get("plugin").asText());
        assertEquals("test.notReady", warnings.get(0).get("message").get("key").asText());
        assertEquals("test.notReadyEither", warnings.get(1).get("message").get("key").asText());
        assertEquals("Photos", warnings.get(1).get("message").get("args").get(0).asText());
        plugin.warnings = List.of();
        assertEquals("[]", send("GET", "/api/libraries/checks/photos", null, null).body());
        assertEquals(List.of("check photos", "check photos", "check photos"), plugin.calls);

        // A plugin that can't do its check reports it as a problem
        plugin.checkFailure = new IOException("The photo server is not available");
        warnings = MAPPER.readTree(send("GET", "/api/libraries/checks/photos", null, null).body());
        assertEquals(1, warnings.size());
        assertEquals("check.failed", warnings.get(0).get("message").get("key").asText());
        assertEquals("The photo server is not available", warnings.get(0).get("message").get("args").get(0).asText());

        assertEquals(404, send("GET", "/api/libraries/checks/unknown", null, null).statusCode());
    }

    @Test
    void testChecksNeedTheLogin() throws Exception {
        start(config(true, ""));

        assertEquals(401, send("GET", "/api/libraries/checks/photos", null, null).statusCode());
        assertEquals(200, send("GET", "/api/libraries/checks/photos", null, login()).statusCode());
        assertEquals(List.of("check photos"), plugin.calls);
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
        final SyncServer.Builder noAuthenticatorBuilder = SyncServer.builder(config(true, "")).statusFile(statusFile);
        final IllegalStateException noAuthenticator = assertThrows(IllegalStateException.class, noAuthenticatorBuilder::build);
        assertTrue(noAuthenticator.getMessage().contains("authenticator"));

        // A secret that is not a valid one (a password, for instance) is refused with an explicit message
        final SyncWebConfig passwordAsSecret = ConfigLoader.parse(MAPPER.readTree("{\"secret\":\"my-password\",\"synchronization\":{\"libraries\":[{\"id\":\"photos\"," +
                "\"source\":{\"provider\":\"local\",\"path\":\"" + source + "\"},\"destination\":{\"provider\":\"local\",\"path\":\"" + destination + "\"}}]}}"), ProviderRegistry.withDefaults());
        final SyncServer.Builder badSecretBuilder = SyncServer.builder(passwordAsSecret).authenticator(AUTHENTICATOR).statusFile(statusFile);
        final IllegalArgumentException badSecret = assertThrows(IllegalArgumentException.class, badSecretBuilder::build);
        assertTrue(badSecret.getMessage().contains("The secret is not valid"), badSecret.getMessage());

        // Two plugins with the same id
        final SyncServer.Builder twice = SyncServer.builder(config(false, "")).statusFile(statusFile).plugin(new TestPlugin()).plugin(new TestPlugin());
        assertThrows(IllegalArgumentException.class, twice::build);

        // A library that can't be opened
        Files.delete(source.resolve("a.txt"));
        Files.delete(source);
        final SyncServer.Builder unavailableBuilder = SyncServer.builder(config(false, "")).statusFile(statusFile);
        final IOException unavailable = assertThrows(IOException.class, unavailableBuilder::build);
        assertTrue(unavailable.getMessage().contains("photos"), unavailable.getMessage());
        // ... unless the check is disabled
        server = SyncServer.builder(config(false, "")).statusFile(statusFile).checkLibraries(false).build();

        // No libraries
        final SyncWebConfig empty = ConfigLoader.parse(MAPPER.readTree("{\"synchronization\":{\"libraries\":[]}}"), ProviderRegistry.withDefaults());
        final SyncServer.Builder emptyBuilder = SyncServer.builder(empty).statusFile(statusFile);
        assertThrows(IllegalArgumentException.class, emptyBuilder::build);
    }
}
