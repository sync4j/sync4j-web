package com.fathzer.sync4j.web.browser;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fathzer.sync4j.config.ProviderRegistry;
import com.fathzer.sync4j.web.SyncServer;
import com.fathzer.sync4j.web.config.ConfigLoader;
import com.fathzer.sync4j.web.config.SyncWebConfig;
import com.fathzer.sync4j.config.Library;
import com.fathzer.sync4j.web.plugin.SyncPlugin;
import com.fathzer.sync4j.web.security.AuthenticationFailedException;
import com.fathzer.sync4j.web.security.SecretGenerator;

/**
 * Tests the web page in a real browser (Chrome, headless), with a real server.
 * <br>These tests are skipped if Chrome can't be found. It is searched in the PATH (google-chrome, chromium, ...), or given by the
 * <code>sync4j.chrome</code> system property or the <code>CHROME_BIN</code> environment variable.
 * <br>They can be disabled with <code>-Dsync4j.browser.tests=false</code>.
 */
class WebPageBrowserTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration SYNCHRONIZATION_TIMEOUT = Duration.ofSeconds(30);

    private static Path profile;
    private static ChromeBrowser browser;

    @TempDir
    Path tmp;
    private Path destination;
    private SyncServer server;
    private String base;
    private final AtomicReference<List<String>> libraryWarnings = new AtomicReference<>(List.of());

    @BeforeAll
    static void startBrowser() throws IOException, InterruptedException {
        final Optional<Path> chrome = ChromeBrowser.find();
        assumeTrue(chrome.isPresent(), "Chrome is not available (see " + ChromeBrowser.CHROME_PROPERTY + ")");
        profile = Files.createTempDirectory("sync4j-web-chrome");
        browser = new ChromeBrowser(chrome.get(), profile);
    }

    @AfterAll
    static void stopBrowser() throws IOException {
        if (browser != null) {
            browser.close();
        }
        if (profile != null) {
            try (var files = Files.walk(profile)) {
                files.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    @BeforeEach
    void startServer() throws IOException {
        final Path source = Files.createDirectory(tmp.resolve("source"));
        destination = Files.createDirectory(tmp.resolve("destination"));
        Files.writeString(source.resolve("a.jpg"), "photo one");
        Files.writeString(source.resolve("b.jpg"), "photo two");
        final String json = "{\"secret\":\"" + SecretGenerator.generate() + "\",\"synchronization\":{\"libraries\":[{\"id\":\"photos\",\"label\":\"Photos\","
                + "\"source\":{\"provider\":\"local\",\"path\":\"" + source + "\"},\"destination\":{\"provider\":\"local\",\"path\":\"" + destination + "\"}}]}}";
        final SyncWebConfig config = ConfigLoader.parse(MAPPER.readTree(json), ProviderRegistry.withDefaults());
        // Each test has its own server (and its own origin), so a test does not depend on what the previous one did
        server = SyncServer.builder(config).authenticator((user, password) -> {
            if ("alice".equals(user) && "secret".equals(password)) {
                return user;
            }
            throw new AuthenticationFailedException(401, "Bad credentials", null);
        }).plugin(new SyncPlugin() {
            @Override
            public String id() {
                return "checker";
            }

            @Override
            public List<String> check(Library library) {
                return libraryWarnings.get();
            }
        }).statusFile(tmp.resolve("statuses.json")).build();
        server.start(0);
        base = "http://localhost:" + server.port();
        browser.reset(base);
    }

    @AfterEach
    void stopServer() {
        server.stop();
    }

    private static String visible(String id) {
        return "(() => { const e = document.getElementById('" + id + "'); return !!e && getComputedStyle(e).display !== 'none'; })()";
    }

    private void open() {
        browser.navigate(base + "/");
        browser.waitFor(visible("login-form"), "the login form");
    }

    private void login(String user, String password) {
        browser.evaluate("document.getElementById('user').value = '" + user + "'; document.getElementById('password').value = '" + password + "'; document.getElementById('login-button').click();");
    }

    private void loginAndWaitForTheLibraries() {
        login("alice", "secret");
        browser.waitFor(visible("app-content"), "the application");
        browser.waitFor("document.getElementById('librarySelect').options.length > 1", "the libraries");
    }

    private void selectTheLibrary() {
        selectLibrary("photos");
    }

    private void selectLibrary(String id) {
        browser.evaluate("(() => { const s = document.getElementById('librarySelect'); s.value = '" + id + "'; s.dispatchEvent(new Event('change')); })()");
    }

    @Test
    void testNothingIsLoadedBeforeTheLogin() {
        final int mark = browser.mark();
        open();
        // The page checks if the user is logged in, but does not ask anything else to a server that would refuse it
        browser.settle();
        assertFalse(browser.isTrue(visible("app-content")), "The application should not be displayed");
        assertEquals(List.of("GET /api/login"), browser.requestsSince(mark, base).stream().filter(r -> r.contains("/api/")).toList());
        assertEquals(List.of("401 /api/login"), browser.responsesSince(mark, base).stream().filter(r -> r.contains("/api/")).toList());
        assertEquals(List.of(), browser.webSocketsSince(mark));
        assertEquals(List.of(), browser.javascriptErrorsSince(mark));
    }

    @Test
    void testWrongPassword() {
        open();

        login("alice", "wrong");

        browser.waitFor("document.getElementById('login-error').textContent.length > 0", "the error message");
        assertEquals("Bad credentials", browser.text("document.getElementById('login-error').textContent"));
        assertFalse(browser.isTrue(visible("app-content")));
        assertTrue(browser.isTrue(visible("login-form")));
    }

    @Test
    void testLoginLoadsTheLibraries() {
        open();
        final int mark = browser.mark();

        loginAndWaitForTheLibraries();

        assertEquals(List.of("200 /api/libraries", "200 /api/libraries/sync"), browser.responsesSince(mark, base).stream().filter(r -> r.contains("/api/libraries")).toList());
        assertEquals(List.of("Select a library...", "Photos"), MAPPER.convertValue(browser.evaluate("Array.from(document.getElementById('librarySelect').options).map(o => o.textContent)"), List.class));
        assertEquals(List.of(), browser.javascriptErrorsSince(mark));
    }

    @Test
    void testTheWebSocketIsOpenedWithTheSession() {
        open();
        loginAndWaitForTheLibraries();
        final int mark = browser.mark();

        selectTheLibrary();

        browser.waitUntil(() -> !browser.webSocketHandshakesSince(mark).isEmpty(), "the answer to the WebSocket connection");
        assertEquals(List.of("ws://localhost:" + server.port() + "/ws/sync/photos"), browser.webSocketsSince(mark));
        assertEquals(List.of(101), browser.webSocketHandshakesSince(mark), "The connection should be accepted");
    }

    @Test
    void testSynchronization() throws IOException {
        final int mark = browser.mark();
        open();
        loginAndWaitForTheLibraries();
        selectTheLibrary();
        browser.waitFor("!document.getElementById('startBtn').disabled", "the start button");

        browser.evaluate("document.getElementById('startBtn').click()");

        browser.waitFor("document.getElementById('statusMessage').textContent.includes('completed')", "the end of the synchronization", SYNCHRONIZATION_TIMEOUT);
        assertEquals("photo one", Files.readString(destination.resolve("a.jpg")));
        assertEquals("photo two", Files.readString(destination.resolve("b.jpg")));
        assertEquals(List.of(), browser.javascriptErrorsSince(mark));
    }

    @Test
    void testTheWarningsOfThePluginsAreDisplayedAndCanBeChecked() {
        libraryWarnings.set(List.of("The library is not ready", "Something else"));
        open();
        loginAndWaitForTheLibraries();
        final int mark = browser.mark();
        // Nothing to say as long as no library is selected
        assertFalse(browser.isTrue(visible("libraryWarnings")));

        selectTheLibrary();

        browser.waitFor(visible("libraryWarnings"), "the warnings");
        assertEquals(List.of("The library is not ready", "Something else"), MAPPER.convertValue(browser.evaluate("Array.from(document.querySelectorAll('#libraryWarningsList li')).map(e => e.textContent)"), List.class));

        // The problem is fixed: the user checks again
        libraryWarnings.set(List.of());
        browser.evaluate("document.getElementById('recheckBtn').click()");
        browser.waitFor("!" + visible("libraryWarnings"), "the end of the warnings");

        // Another problem, then the user selects no library any more
        libraryWarnings.set(List.of("Again"));
        selectLibrary("");
        selectLibrary("photos");
        browser.waitFor(visible("libraryWarnings"), "the new warning");
        assertEquals("Again", browser.text("document.querySelector('#libraryWarningsList li').textContent"));
        selectLibrary("");
        browser.waitFor("!" + visible("libraryWarnings"), "the warnings to be hidden");
        assertEquals(List.of(), browser.javascriptErrorsSince(mark));
    }

    @Test
    void testNoWarningWhenThePluginsFindNoProblem() {
        open();
        final int mark = browser.mark();
        loginAndWaitForTheLibraries();
        selectTheLibrary();

        browser.waitUntil(() -> browser.responsesSince(mark, base).contains("200 /api/libraries/checks/photos"), "the check of the library");
        assertFalse(browser.isTrue(visible("libraryWarnings")));
    }

    @Test
    void testExpiredSession() {
        open();
        loginAndWaitForTheLibraries();
        selectTheLibrary();
        browser.waitFor("!document.getElementById('startBtn').disabled", "the start button");

        // The session is not valid any more (for instance, it expired)
        browser.send("Network.deleteCookies", MAPPER.createObjectNode().put("name", "auth_token").put("url", base));
        browser.evaluate("document.getElementById('startBtn').click()");

        browser.waitFor(visible("login-form"), "the login form");
        assertFalse(browser.isTrue(visible("app-content")));
        assertEquals("Your session has expired, please log in again", browser.text("document.getElementById('login-error').textContent"));
        assertFalse(Files.exists(destination.resolve("a.jpg")), "Nothing should be synchronized");

        // The user can log in again, and the application loads its data again
        final int mark = browser.mark();
        loginAndWaitForTheLibraries();
        assertTrue(browser.responsesSince(mark, base).contains("200 /api/libraries"));
    }

    @Test
    void testLogout() {
        open();
        loginAndWaitForTheLibraries();

        browser.evaluate("document.getElementById('logout-button').click()");

        browser.waitFor(visible("login-form"), "the login form");
        assertFalse(browser.isTrue(visible("app-content")));
        // The server does not know the user any more
        assertEquals(401, browser.evaluate("fetch('/api/libraries').then(r => r.status)").asInt());
    }

    @Test
    void testTheServerRefusesTheDataWithoutLogin() {
        open();

        // Not only the page: the server itself refuses
        assertEquals(401, browser.evaluate("fetch('/api/libraries').then(r => r.status)").asInt());
        assertEquals(401, browser.evaluate("fetch('/api/libraries/sync').then(r => r.status)").asInt());
        assertEquals(200, browser.evaluate("fetch('/').then(r => r.status)").asInt());
    }
}
