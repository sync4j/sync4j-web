package com.fathzer.sync4j.web.browser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * A headless Chrome, driven with the Chrome DevTools Protocol (the JDK web socket client is enough to talk to it).
 * <br>It is only meant to test the web page, there is no attempt to be a complete driver.
 */
final class ChromeBrowser implements AutoCloseable {
    /** The system property that contains the path of the Chrome executable. */
    static final String CHROME_PROPERTY = "sync4j.chrome";
    /** The system property that disables the tests that use a browser, if it is "false". */
    static final String ENABLED_PROPERTY = "sync4j.browser.tests";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final List<String> EXECUTABLES = List.of("google-chrome", "google-chrome-stable", "chromium", "chromium-browser", "chrome");

    private final Process process;
    private final Path profile;
    private final HttpClient http = HttpClient.newHttpClient();
    private final WebSocket socket;
    private final AtomicInteger lastId = new AtomicInteger();
    private final Map<Integer, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private final List<JsonNode> events = new CopyOnWriteArrayList<>();

    /**
     * Finds Chrome.
     * @return the path of Chrome, empty if the tests that use a browser are disabled or if Chrome can't be found.
     * It is the <code>sync4j.chrome</code> system property, the <code>CHROME_BIN</code> environment variable, or one of the usual names in the PATH.
     */
    static Optional<Path> find() {
        if ("false".equalsIgnoreCase(System.getProperty(ENABLED_PROPERTY))) {
            return Optional.empty();
        }
        final String configured = System.getProperty(CHROME_PROPERTY, System.getenv("CHROME_BIN"));
        if (configured != null && !configured.isBlank()) {
            return Optional.of(Path.of(configured)).filter(Files::isExecutable);
        }
        final String path = System.getenv("PATH");
        if (path == null) {
            return Optional.empty();
        }
        return Stream.of(path.split(java.io.File.pathSeparator))
                .flatMap(dir -> EXECUTABLES.stream().map(name -> Path.of(dir, name)))
                .filter(Files::isExecutable)
                .findFirst();
    }

    /**
     * Starts Chrome.
     * @param chrome the path of the executable
     * @param profile a directory that Chrome can use (it is not deleted)
     */
    ChromeBrowser(Path chrome, Path profile) throws IOException, InterruptedException {
        this.profile = profile;
        // The port 0 lets Chrome choose a free port, that it writes in the DevToolsActivePort file
        process = new ProcessBuilder(chrome.toString(), "--headless=new", "--no-sandbox", "--disable-gpu", "--disable-extensions", "--no-first-run",
                "--no-default-browser-check", "--remote-debugging-port=0", "--user-data-dir=" + profile, "about:blank")
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        try {
            final int port = readPort();
            final JsonNode targets = MAPPER.readTree(http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/json")).build(), HttpResponse.BodyHandlers.ofString()).body());
            String url = null;
            for (JsonNode target : targets) {
                if ("page".equals(target.path("type").asText())) {
                    url = target.get("webSocketDebuggerUrl").asText();
                    break;
                }
            }
            if (url == null) {
                throw new IOException("Chrome has no page");
            }
            socket = http.newWebSocketBuilder().buildAsync(URI.create(url), new Listener()).get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            for (String domain : List.of("Page", "Runtime", "Network", "Log")) {
                send(domain + ".enable");
            }
        } catch (IOException | InterruptedException | RuntimeException | java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
            kill();
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw e instanceof IOException io ? io : new IOException("Chrome can't be driven: " + e, e);
        }
    }

    private int readPort() throws IOException, InterruptedException {
        final Path file = profile.resolve("DevToolsActivePort");
        final long end = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < end) {
            if (!process.isAlive()) {
                throw new IOException("Chrome stopped (exit code " + process.exitValue() + ")");
            }
            if (Files.exists(file)) {
                final List<String> lines = Files.readAllLines(file);
                if (!lines.isEmpty() && !lines.get(0).isBlank()) {
                    return Integer.parseInt(lines.get(0).trim());
                }
            }
            Thread.sleep(50);
        }
        throw new IOException("Chrome did not tell its debugging port");
    }

    private class Listener implements WebSocket.Listener {
        private final StringBuilder current = new StringBuilder();

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            current.append(data);
            if (last) {
                try {
                    final JsonNode message = MAPPER.readTree(current.toString());
                    final CompletableFuture<JsonNode> future = message.has("id") ? pending.remove(message.get("id").asInt()) : null;
                    if (future != null) {
                        future.complete(message);
                    } else if (!message.has("id")) {
                        events.add(message);
                    }
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                } finally {
                    current.setLength(0);
                }
            }
            webSocket.request(1);
            return null;
        }
    }

    /** Sends a command and returns its result. */
    JsonNode send(String method) {
        return send(method, MAPPER.createObjectNode());
    }

    JsonNode send(String method, ObjectNode params) {
        final int id = lastId.incrementAndGet();
        final CompletableFuture<JsonNode> future = new CompletableFuture<>();
        pending.put(id, future);
        final ObjectNode message = MAPPER.createObjectNode().put("id", id).put("method", method);
        message.set("params", params);
        socket.sendText(message.toString(), true);
        try {
            final JsonNode response = future.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            if (response.has("error")) {
                throw new IllegalStateException(method + " failed: " + response.get("error"));
            }
            return response.path("result");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
            throw new IllegalStateException(method + " did not answer", e);
        }
    }

    /** Goes to a page and waits for it to be loaded. */
    void navigate(String url) {
        final int mark = mark();
        send("Page.navigate", MAPPER.createObjectNode().put("url", url));
        waitFor(() -> eventsSince(mark).stream().anyMatch(e -> "Page.loadEventFired".equals(e.path("method").asText())), "the load of " + url, TIMEOUT);
    }

    /** Evaluates a JavaScript expression in the page (a promise is awaited) and returns its value. */
    JsonNode evaluate(String expression) {
        final ObjectNode params = MAPPER.createObjectNode().put("expression", expression).put("awaitPromise", true).put("returnByValue", true);
        final JsonNode result = send("Runtime.evaluate", params);
        if (result.has("exceptionDetails")) {
            throw new IllegalStateException("The script failed: " + result.get("exceptionDetails").path("exception").path("description").asText(result.get("exceptionDetails").toString()));
        }
        return result.path("result").path("value");
    }

    String text(String expression) {
        return evaluate(expression).asText();
    }

    boolean isTrue(String expression) {
        return evaluate(expression).asBoolean();
    }

    /** Waits until a JavaScript condition is true. */
    void waitFor(String jsCondition, String description) {
        waitFor(jsCondition, description, TIMEOUT);
    }

    void waitFor(String jsCondition, String description, Duration timeout) {
        waitFor(() -> isTrue(jsCondition), description + " (the page says: " + abbreviate(text("document.body.innerText")) + ")", timeout);
    }

    private static String abbreviate(String text) {
        final String oneLine = text.replaceAll("\\s+", " ");
        return oneLine.length() > 200 ? oneLine.substring(0, 200) + "..." : oneLine;
    }

    private static void waitFor(java.util.function.BooleanSupplier condition, String description, Duration timeout) {
        final long end = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < end) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        throw new AssertionError("Timeout waiting for " + description);
    }

    /** The number of events received so far (see {@link #eventsSince(int)}). */
    int mark() {
        return events.size();
    }

    List<JsonNode> eventsSince(int mark) {
        // The events keep coming while they are read: work on a snapshot
        final List<JsonNode> snapshot = new ArrayList<>(events);
        return snapshot.subList(Math.min(mark, snapshot.size()), snapshot.size());
    }

    /** Waits until a condition on what the browser did is true. */
    void waitUntil(java.util.function.BooleanSupplier condition, String description) {
        waitFor(condition, description, TIMEOUT);
    }

    /** Lets the page work for a while (what it does without being asked, like requests, is then in the events). */
    void settle() {
        evaluate("new Promise(resolve => setTimeout(resolve, 300))");
    }

    /** The requests sent by the page since a mark, as "METHOD path". */
    List<String> requestsSince(int mark, String base) {
        return eventsSince(mark).stream().filter(e -> "Network.requestWillBeSent".equals(e.path("method").asText()))
                .map(e -> e.path("params").path("request")).filter(r -> r.path("url").asText().startsWith(base))
                .map(r -> r.path("method").asText() + " " + r.path("url").asText().substring(base.length())).toList();
    }

    /** The statuses of the answers received since a mark, as "status path". */
    List<String> responsesSince(int mark, String base) {
        return eventsSince(mark).stream().filter(e -> "Network.responseReceived".equals(e.path("method").asText()))
                .map(e -> e.path("params").path("response")).filter(r -> r.path("url").asText().startsWith(base))
                .map(r -> r.path("status").asInt() + " " + r.path("url").asText().substring(base.length())).toList();
    }

    List<String> webSocketsSince(int mark) {
        return eventsSince(mark).stream().filter(e -> "Network.webSocketCreated".equals(e.path("method").asText())).map(e -> e.path("params").path("url").asText()).toList();
    }

    /** The HTTP statuses of the answers to the web socket connections since a mark (101 if it is accepted). */
    List<Integer> webSocketHandshakesSince(int mark) {
        return eventsSince(mark).stream().filter(e -> "Network.webSocketHandshakeResponseReceived".equals(e.path("method").asText()))
                .map(e -> e.path("params").path("response").path("status").asInt()).toList();
    }

    /** The errors that were not caught by the scripts of the page. */
    List<String> javascriptErrorsSince(int mark) {
        return eventsSince(mark).stream().filter(e -> "Runtime.exceptionThrown".equals(e.path("method").asText()))
                .map(e -> e.path("params").path("exceptionDetails").path("exception").path("description").asText(e.path("params").path("exceptionDetails").path("text").asText())).toList();
    }

    /** Sets the languages that the browser asks the servers for (the Accept-Language header), as the language of the system does. Chrome adds the weights (q=...) itself. */
    void setAcceptLanguage(String languages) {
        send("Network.setUserAgentOverride", MAPPER.createObjectNode().put("userAgent", text("navigator.userAgent")).put("acceptLanguage", languages));
    }

    /** Forgets the cookies and the local storage, and goes back to English, so a test does not depend on the previous one. */
    void reset(String origin) {
        navigate("about:blank");
        setAcceptLanguage("en-US,en");
        send("Network.clearBrowserCookies");
        send("Storage.clearDataForOrigin", MAPPER.createObjectNode().put("origin", origin).put("storageTypes", "all"));
    }

    private void kill() {
        process.descendants().forEach(ProcessHandle::destroy);
        process.destroy();
        try {
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        try {
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "done");
        } catch (RuntimeException e) {
            // The browser is stopped anyway
        }
        http.close();
        kill();
    }
}
