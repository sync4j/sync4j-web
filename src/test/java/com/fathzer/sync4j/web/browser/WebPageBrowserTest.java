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
import com.fathzer.sync4j.web.plugin.PluginContext;
import com.fathzer.sync4j.web.plugin.SyncPlugin;
import com.fathzer.sync4j.web.security.AuthenticationFailedException;
import com.fathzer.sync4j.web.security.SecretGenerator;
import com.fathzer.sync4j.web.sync.SyncRequest;
import com.fathzer.sync4j.web.sync.SyncStatus;

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
        final Path otherSource = Files.createDirectory(tmp.resolve("otherSource"));
        final Path otherDestination = Files.createDirectory(tmp.resolve("otherDestination"));
        final String json = "{\"secret\":\"" + SecretGenerator.generate() + "\",\"synchronization\":{\"libraries\":[{\"id\":\"photos\",\"label\":\"Photos\","
                + "\"source\":{\"provider\":\"local\",\"path\":\"" + source + "\"},\"destination\":{\"provider\":\"local\",\"path\":\"" + destination + "\"}},"
                + "{\"id\":\"other\",\"label\":\"Other\",\"source\":{\"provider\":\"local\",\"path\":\"" + otherSource + "\"},\"destination\":{\"provider\":\"local\",\"path\":\"" + otherDestination + "\"}}]}}";
        final SyncWebConfig config = ConfigLoader.parse(MAPPER.readTree(json), ProviderRegistry.withDefaults());
        // Each test has its own server (and its own origin), so a test does not depend on what the previous one did
        server = SyncServer.builder(config).authenticator((user, password) -> {
            if ("alice".equals(user) && "secret".equals(password)) {
                return user;
            }
            throw new AuthenticationFailedException(401, "Bad credentials", null);
        }).plugin(new SyncPlugin() {
            private volatile PluginContext context;

            @Override
            public String id() {
                return "checker";
            }

            @Override
            public void init(PluginContext context) {
                this.context = context;
            }

            @Override
            public void afterSync(SyncRequest request, SyncStatus status) {
                // For instance, a plugin that tells what it does after a synchronization
                context.publish(request.library().id(), java.util.Map.of("message", "Scan requested", "label", "Checker", "state", "RUNNING",
                        "details", List.of("Thumbnails: 3 left"), "progress", java.util.Map.of("done", 1, "total", 2)));
            }

            @Override
            public List<String> check(Library library) {
                return libraryWarnings.get();
            }
        }).statusFile(tmp.resolve("statuses.json")).title("Test dashboard").build();
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
        // ... and the title, that is public (the login form is displayed with it)
        assertEquals(List.of("GET /api/login", "GET /api/ui"), browser.requestsSince(mark, base).stream().filter(r -> r.contains("/api/")).sorted().toList());
        assertEquals(List.of("200 /api/ui", "401 /api/login"), browser.responsesSince(mark, base).stream().filter(r -> r.contains("/api/")).sorted().toList());
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

        // The page sends the two requests at the same time: the order of the answers is not defined
        assertEquals(List.of("200 /api/libraries", "200 /api/libraries/sync"), browser.responsesSince(mark, base).stream().filter(r -> r.contains("/api/libraries")).sorted().toList());
        assertEquals(List.of("Select a library...", "Photos", "Other"), MAPPER.convertValue(browser.evaluate("Array.from(document.getElementById('librarySelect').options).map(o => o.textContent)"), List.class));
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
    void testStartIsNotAvailableBeforeTheConnectionToTheServer() {
        open();
        loginAndWaitForTheLibraries();

        // The buttons are disabled as soon as a library is selected: a synchronization started now would not be followed (the page is not connected yet)
        assertTrue(browser.isTrue("(() => { const s = document.getElementById('librarySelect'); s.value = 'photos'; s.dispatchEvent(new Event('change'));"
                + " return document.getElementById('startBtn').disabled && document.getElementById('stopBtn').disabled; })()"));

        // ... until the server sends the status of the library
        browser.waitFor("!document.getElementById('startBtn').disabled", "the start button");
        assertTrue(browser.isTrue("document.getElementById('stopBtn').disabled"), "Nothing is running");
        assertNotEquals("", browser.text("document.getElementById('lastSyncTime').textContent"));

        // Selecting no library disables the start button again
        selectLibrary("");
        assertTrue(browser.isTrue("document.getElementById('startBtn').disabled"));
        // ... and the user can still choose a library
        assertFalse(browser.isTrue("document.getElementById('librarySelect').disabled"), "The list of the libraries should not stay disabled");
    }

    @Test
    void testTheCardOfAPluginAndTheTechnicalLog() {
        open();
        loginAndWaitForTheLibraries();
        selectTheLibrary();
        // The page is connected when it displays the status that the server sends to the new connections (the messages published before are not sent again)
        browser.waitFor("document.getElementById('lastSyncTime').textContent !== ''", "the connection to the server");
        assertFalse(browser.isTrue(visible("logSection")), "There is nothing to log yet");

        browser.evaluate("document.getElementById('startBtn').click()");

        // What the plugin does is displayed for the end user, in a card
        browser.waitFor(visible("pluginCards") + " && document.querySelector('#pluginCards .plugin-card') !== null", "the card of the plugin", SYNCHRONIZATION_TIMEOUT);
        assertEquals("Checker", browser.text("document.querySelector('#pluginCards .plugin-card h2').textContent"));
        assertEquals("In progress", browser.text("document.querySelector('#pluginCards .badge').textContent"));
        assertEquals("Scan requested", browser.text("document.querySelector('#pluginCards .plugin-card-message').textContent"));
        assertEquals(List.of("Thumbnails: 3 left"), MAPPER.convertValue(browser.evaluate("Array.from(document.querySelectorAll('#pluginCards .plugin-card-details li')).map(e => e.textContent)"), List.class));
        assertEquals("1 / 2", browser.text("document.querySelector('#pluginCards .plugin-card-progress-text').textContent"));
        assertTrue(browser.isTrue("document.querySelector('#pluginCards .progress-fill').style.width === '50%'"));

        // The technical log is in a card of its own, that is collapsed by default
        browser.waitFor("document.getElementById('logOutput').textContent.includes('[checker] Scan requested')", "the message of the plugin in the log");
        assertTrue(browser.isTrue(visible("logSection")));
        assertFalse(browser.isTrue(visible("logOutput") + " && document.getElementById('logOutput').getBoundingClientRect().height > 0"), "The log should be collapsed");
        browser.evaluate("document.getElementById('toggleLogSection').click()");
        assertTrue(browser.isTrue("document.getElementById('logOutput').getBoundingClientRect().height > 0"), "The log should be displayed when the user expands it");
        browser.evaluate("document.getElementById('toggleLogSection').click()");
        assertFalse(browser.isTrue("document.getElementById('logOutput').getBoundingClientRect().height > 0"), "... and hidden when the user collapses it");
    }

    @Test
    void testTheCardOfTheSynchronizationShowsItsStateAndFoldsWhenItSucceeded() {
        open();
        loginAndWaitForTheLibraries();
        selectTheLibrary();
        browser.waitFor("document.getElementById('lastSyncTime').textContent !== ''", "the connection to the server");
        assertFalse(browser.isTrue("document.querySelector('.progress-section').classList.contains('collapsed')"));

        browser.evaluate("document.getElementById('startBtn').click()");

        browser.waitFor("document.getElementById('progressBadge').textContent === 'Done'", "the end of the synchronization", SYNCHRONIZATION_TIMEOUT);
        assertTrue(browser.isTrue("document.querySelector('.progress-section').classList.contains('state-done')"));
        assertEquals("rgb(46, 204, 112)", browser.text("getComputedStyle(document.querySelector('.progress-section')).borderLeftColor"));
        assertTrue(browser.isTrue("document.querySelector('.progress-section').classList.contains('collapsed')"), "The card should be folded");
        assertFalse(browser.isTrue("document.querySelector('.progress-content').getBoundingClientRect().height > 0"));

        // The user can unfold it
        browser.evaluate("document.getElementById('toggleProgressSection').click()");
        assertTrue(browser.isTrue("document.querySelector('.progress-content').getBoundingClientRect().height > 0"));
    }

    @Test
    void testTheCardOfTheSynchronizationIsDisplayedAgainWhenThePageIsLoaded() {
        open();
        loginAndWaitForTheLibraries();
        selectTheLibrary();
        browser.waitFor("document.getElementById('lastSyncTime').textContent !== ''", "the connection to the server");
        assertFalse(browser.isTrue(visible("progressBadge") + " && document.querySelector('.progress-section').style.display !== 'none'"), "Nothing to show before a synchronization");
        browser.evaluate("document.getElementById('startBtn').click()");
        browser.waitFor("document.getElementById('progressBadge').textContent === 'Done'", "the end of the synchronization", SYNCHRONIZATION_TIMEOUT);

        browser.navigate(base + "/");
        browser.waitFor("document.querySelector('.progress-section').style.display === 'block' && document.getElementById('progressBadge').textContent === 'Done'", "the card of the synchronization after the page was loaded again", SYNCHRONIZATION_TIMEOUT);
        assertTrue(browser.isTrue("document.querySelector('.progress-section').classList.contains('collapsed')"));
    }

    @Test
    void testNoCardOfTheSynchronizationForALibraryThatWasNeverSynchronized() {
        open();
        loginAndWaitForTheLibraries();
        selectTheLibrary();
        browser.waitFor("document.getElementById('lastSyncTime').textContent !== ''", "the connection to the server");
        browser.evaluate("document.getElementById('startBtn').click()");
        browser.waitFor("document.getElementById('progressBadge').textContent === 'Done'", "the end of the synchronization", SYNCHRONIZATION_TIMEOUT);
        assertTrue(browser.isTrue("getComputedStyle(document.querySelector('.progress-section')).display !== 'none'"));

        // The other library has nothing to show: the card of the first one should not be displayed for it
        browser.evaluate("document.getElementById('lastSyncTime').textContent = ''");
        selectLibrary("other");
        browser.waitFor("document.getElementById('lastSyncTime').textContent !== ''", "the status of the other library");
        assertFalse(browser.isTrue("getComputedStyle(document.querySelector('.progress-section')).display !== 'none'"), "There is no synchronization to show");
    }

    @Test
    void testThePageReconnectsWhenTheConnectionIsLost() {
        open();
        loginAndWaitForTheLibraries();
        // Records the WebSockets of the page
        browser.evaluate("(() => { window.sockets = []; const Original = WebSocket; window.WebSocket = class extends Original { constructor(url) { super(url); window.sockets.push(this); } }; })()");
        selectTheLibrary();
        browser.waitFor("window.sockets.length === 1 && window.sockets[0].readyState === 1", "the connection to the server");

        // The connection is lost although the page did not start the synchronization (for instance, it was loaded while another user was synchronizing)
        browser.evaluate("window.sockets[0].close()");
        browser.waitFor("window.sockets.length === 2 && window.sockets[1].readyState === 1", "the new connection to the server", SYNCHRONIZATION_TIMEOUT);
    }

    @Test
    void testTheCardOfAPluginIsDisplayedAgainWhenThePageIsLoaded() throws IOException {
        open();
        loginAndWaitForTheLibraries();
        selectTheLibrary();
        browser.waitFor("document.getElementById('lastSyncTime').textContent !== ''", "the connection to the server");
        browser.evaluate("document.getElementById('startBtn').click()");
        browser.waitFor("document.querySelector('#pluginCards .plugin-card') !== null", "the card of the plugin", SYNCHRONIZATION_TIMEOUT);

        // The user comes back later (the session is still valid and the library is selected again): the server tells what the plugin said
        browser.navigate(base + "/");
        browser.waitFor("document.querySelector('#pluginCards .plugin-card') !== null", "the card of the plugin after the page was loaded again", SYNCHRONIZATION_TIMEOUT);
        assertEquals("Checker", browser.text("document.querySelector('#pluginCards .plugin-card h2').textContent"));
        assertEquals("Scan requested", browser.text("document.querySelector('#pluginCards .plugin-card-message').textContent"));
    }

    @Test
    void testTheTitleIsTheOneOfTheServer() {
        open();
        // The title is known before the login
        browser.waitFor("document.title === 'Test dashboard'", "the title of the page");
        loginAndWaitForTheLibraries();
        assertEquals("Test dashboard", browser.text("document.getElementById('appTitle').textContent"));
    }

    @Test
    void testTheDarkThemeFollowsTheSystem() {
        // The light theme
        open();
        final double lightPage = luminance("body", "backgroundColor");
        final double lightLogin = luminance("#login-form", "backgroundColor");
        assertTrue(lightPage > 200 && lightLogin > 200, "Light theme: " + lightPage + " " + lightLogin);
        loginAndWaitForTheLibraries();
        final double lightCard = luminance(".sync-form", "backgroundColor");
        final double lightText = luminance("body", "color");
        assertTrue(lightCard > 200 && lightText < 100, "Light theme: " + lightCard + " " + lightText);

        // The system asks for a dark theme
        setColorScheme("dark");
        try {
            assertTrue(luminance("body", "backgroundColor") < 60, "The page should be dark");
            assertTrue(luminance(".sync-form", "backgroundColor") < 80, "The cards should be dark");
            assertTrue(luminance("#librarySelect", "backgroundColor") < 80, "The fields should be dark");
            assertTrue(luminance("body", "color") > 150, "The text should be light");
            // The login form is dark too
            browser.evaluate("document.getElementById('login-form').style.display = 'block'");
            assertTrue(luminance("#login-form", "backgroundColor") < 80, "The login form should be dark");
            assertTrue(luminance("#login-form input", "backgroundColor") < 80, "The fields of the login form should be dark");
        } finally {
            setColorScheme("light");
        }
        assertTrue(luminance("body", "backgroundColor") > 200, "The light theme should come back");
    }

    @Test
    void testTheLogoutButtonStaysBesideTheTitleOnAPhone() {
        open();
        loginAndWaitForTheLibraries();
        // A long title, as it can be on a phone
        browser.evaluate("document.getElementById('appTitle').textContent = 'The synchronization of the photos of the whole family'");
        browser.send("Emulation.setDeviceMetricsOverride", MAPPER.createObjectNode().put("width", 375).put("height", 700).put("deviceScaleFactor", 1).put("mobile", true));
        try {
            browser.waitFor("window.innerWidth <= 400", "the size of a phone");
            final String title = "document.getElementById('appTitle').getBoundingClientRect()";
            final String logout = "document.getElementById('logout-button').getBoundingClientRect()";
            // On the same row (it was above the title) and the title does not go under the button
            assertTrue(browser.isTrue(logout + ".top < " + title + ".bottom && " + logout + ".bottom > " + title + ".top"), "The button should be beside the title");
            assertTrue(browser.isTrue(title + ".right <= " + logout + ".left + 1"), "The title should not go under the button");
            assertTrue(browser.isTrue(logout + ".right <= window.innerWidth"), "The button should be in the page");
            assertTrue(browser.isTrue("document.documentElement.scrollWidth <= window.innerWidth"), "The page should not scroll horizontally");
        } finally {
            browser.send("Emulation.clearDeviceMetricsOverride");
        }
    }

    private double luminance(String selector, String property) {
        return browser.evaluate("(() => { const c = getComputedStyle(document.querySelector('" + selector + "'))['" + property + "'].match(/[0-9.]+/g).map(Number);"
                + " return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2]; })()").asDouble();
    }

    /** Emulates the preferred color scheme of the system. */
    private void setColorScheme(String scheme) {
        browser.send("Emulation.setEmulatedMedia", MAPPER.createObjectNode().set("features", MAPPER.createArrayNode().add(MAPPER.createObjectNode().put("name", "prefers-color-scheme").put("value", scheme))));
    }

    @Test
    void testTheEndOfAFastSynchronizationIsNotReplacedByItsStart() throws IOException {
        final int mark = browser.mark();
        open();
        loginAndWaitForTheLibraries();
        selectTheLibrary();
        browser.waitFor("!document.getElementById('startBtn').disabled", "the start button");
        // The answer to the request that starts the synchronization is received long after the end of the (short) synchronization
        browser.evaluate("(() => { const original = window.fetch; window.__startAnswered = false;"
                + " window.fetch = (url, options) => original(url, options).then(response => options?.method === 'POST'"
                + " ? new Promise(resolve => setTimeout(() => { window.__startAnswered = true; resolve(response); }, 2000)) : response); })()");

        browser.evaluate("document.getElementById('startBtn').click()");

        browser.waitFor("document.getElementById('logOutput').textContent.includes('Sync completed successfully')", "the end of the synchronization", SYNCHRONIZATION_TIMEOUT);
        assertFalse(browser.isTrue("window.__startAnswered"), "The synchronization should be over before the answer to the request");
        browser.waitFor("window.__startAnswered", "the answer to the request that starts the synchronization");
        browser.settle();
        assertEquals("Sync completed successfully", browser.text("document.getElementById('statusMessage').textContent"));
        assertEquals("photo one", Files.readString(destination.resolve("a.jpg")));
        assertEquals(List.of(), browser.javascriptErrorsSince(mark));
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
