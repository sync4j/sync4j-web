package com.fathzer.sync4j.web.security;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fathzer.sync4j.web.rest.LoginRestService;

import io.javalin.Javalin;

class JwtLoginManagerTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Javalin app;
    private HttpClient client;

    @BeforeEach
    void setUp() {
        client = HttpClient.newHttpClient();
    }

    @AfterEach
    void tearDown() {
        client.close();
        if (app != null) {
            app.stop();
        }
    }

    private static Authenticator authenticator() {
        return (user, password) -> {
            if ("alice".equals(user) && "secret".equals(password)) {
                return "alice@example.com";
            } else if ("broken".equals(user)) {
                throw new IOException("The authentication service is down");
            }
            throw new AuthenticationFailedException(401, "Bad credentials", java.util.Map.of("message", "Bad credentials", "code", 42));
        };
    }

    private void start(JwtLoginManager manager) {
        app = Javalin.create();
        new LoginRestService(manager).registerRoutes(app, "/api/login");
        app.start(0);
    }

    private HttpResponse<String> send(String method, String body, String cookie) throws IOException, InterruptedException {
        final HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + app.port() + "/api/login"))
                .header("Content-Type", "application/json")
                .method(method, body == null ? BodyPublishers.noBody() : BodyPublishers.ofString(body));
        if (cookie != null) {
            builder.header("Cookie", cookie);
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** Extracts the cookie (name=value) from the Set-Cookie header of a response. */
    private static String cookie(HttpResponse<String> response) {
        final String header = response.headers().firstValue("Set-Cookie").orElseThrow();
        return header.substring(0, header.indexOf(';'));
    }

    @Test
    void testLoginLifecycle() throws Exception {
        start(new JwtLoginManager(authenticator(), JwtLoginManager.buildSecret(), true));

        // Not logged in
        HttpResponse<String> response = send("GET", null, null);
        assertEquals(401, response.statusCode());
        assertEquals("Missing token", MAPPER.readTree(response.body()).get("error").asText());

        // Log in
        response = send("POST", "{\"user\":\"alice\",\"password\":\"secret\"}", null);
        assertEquals(200, response.statusCode());
        final String setCookie = response.headers().firstValue("Set-Cookie").orElseThrow();
        assertTrue(setCookie.contains("HttpOnly"));
        assertTrue(setCookie.contains("Secure"));
        assertTrue(setCookie.contains("SameSite=Strict"));
        final String cookie = cookie(response);

        // The session is valid and the user is the one returned by the authenticator
        response = send("GET", null, cookie);
        assertEquals(200, response.statusCode());
        assertEquals("alice@example.com", MAPPER.readTree(response.body()).get("user").asText());

        // A forged token is rejected
        response = send("GET", null, cookie.substring(0, cookie.length() - 3) + "abc");
        assertEquals(401, response.statusCode());

        // Log out
        response = send("DELETE", null, cookie);
        assertEquals(200, response.statusCode());
        assertTrue(response.headers().firstValue("Set-Cookie").orElseThrow().contains("Expires=Thu, 01 Jan 1970"));
    }

    @Test
    void testLegacyEmailAttribute() throws Exception {
        start(new JwtLoginManager(authenticator(), JwtLoginManager.buildSecret(), true));
        // The previous version of the web page sent the user name as "email"
        assertEquals(200, send("POST", "{\"email\":\"alice\",\"password\":\"secret\"}", null).statusCode());
    }

    @Test
    void testLoginErrors() throws Exception {
        start(new JwtLoginManager(authenticator(), JwtLoginManager.buildSecret(), true));

        // The details of the authenticator are sent to the client
        HttpResponse<String> response = send("POST", "{\"user\":\"alice\",\"password\":\"wrong\"}", null);
        assertEquals(401, response.statusCode());
        assertEquals(42, MAPPER.readTree(response.body()).get("code").asInt());
        assertTrue(response.headers().firstValue("Set-Cookie").isEmpty());

        response = send("POST", "{\"user\":\"\",\"password\":\"secret\"}", null);
        assertEquals(400, response.statusCode());
        assertEquals("User and password are required", MAPPER.readTree(response.body()).get("message").asText());

        response = send("POST", "{\"user\":\"broken\",\"password\":\"x\"}", null);
        assertEquals(500, response.statusCode());
        assertTrue(MAPPER.readTree(response.body()).get("message").asText().contains("The authentication service is down"));
    }

    @Test
    void testInsecureCookie() throws Exception {
        start(new JwtLoginManager(authenticator(), JwtLoginManager.buildSecret(), false));
        final HttpResponse<String> response = send("POST", "{\"user\":\"alice\",\"password\":\"secret\"}", null);
        assertFalse(response.headers().firstValue("Set-Cookie").orElseThrow().contains("Secure"));
    }

    @Test
    void testSecurityDisabled() throws Exception {
        final JwtLoginManager manager = new JwtLoginManager(null, null, true);
        assertFalse(manager.isSecured());
        start(manager);

        final HttpResponse<String> response = send("GET", null, null);
        assertEquals(200, response.statusCode());
        assertEquals("anonymous", MAPPER.readTree(response.body()).get("user").asText());
    }

    @Test
    void testInvalidSecrets() {
        // A password is not base64 text
        final IllegalArgumentException password = assertThrows(IllegalArgumentException.class, () -> new JwtLoginManager(authenticator(), "my-password", true));
        assertTrue(password.getMessage().contains("The secret is not valid"), password.getMessage());
        assertTrue(password.getMessage().contains("password is not a valid secret"), password.getMessage());
        // base64 text is not enough: it should encode enough bytes
        assertThrows(IllegalArgumentException.class, () -> new JwtLoginManager(authenticator(), java.util.Base64.getEncoder().encodeToString("short".getBytes()), true));
    }

    @Test
    void testSecretFormats() throws Exception {
        final String secret = JwtLoginManager.buildSecret();
        // A generated secret decodes to 64 bytes
        assertEquals(64, java.util.Base64.getDecoder().decode(secret).length);
        // 32 bytes are enough
        assertDoesNotThrow(() -> new JwtLoginManager(authenticator(), java.util.Base64.getEncoder().encodeToString(new byte[32]), true));
        // The spaces and line breaks around the secret (typically in an environment variable or a file) are ignored
        start(new JwtLoginManager(authenticator(), "  " + secret + "\n", true));
        assertEquals(200, send("POST", "{\"user\":\"alice\",\"password\":\"secret\"}", null).statusCode());
    }

    @Test
    void testSecretRequiresAnAuthenticator() {
        final String secret = JwtLoginManager.buildSecret();
        assertThrows(NullPointerException.class, () -> new JwtLoginManager(null, secret, true));
    }
}
