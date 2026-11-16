package com.fathzer.sync4j.web.rest;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fathzer.sync4j.web.security.LoginManager;

import io.javalin.Javalin;
import io.javalin.http.Context;

/**
 * Checks the answers of the login service. The front end (www/js/auth.js) relies on them.
 */
class LoginRestServiceTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static class StubLoginManager implements LoginManager {
        private CheckLoginResponse response = new CheckLoginResponse("bob@example.com", null);

        @Override
        public void login(Context ctx) {
            ctx.status(200).result("Authentication successful");
        }

        @Override
        public CheckLoginResponse checkLogin(Context ctx) {
            return response;
        }

        @Override
        public void logout(Context ctx) {
            ctx.status(200).result("Logged out successfully");
        }
    }

    private StubLoginManager loginManager;
    private Javalin app;
    private HttpClient client;

    @BeforeEach
    void setUp() {
        loginManager = new StubLoginManager();
        app = Javalin.create();
        new LoginRestService(loginManager).registerRoutes(app, "/api/login");
        app.start(0);
        client = HttpClient.newHttpClient();
    }

    @AfterEach
    void tearDown() {
        client.close();
        app.stop();
    }

    private HttpResponse<String> get() throws IOException, InterruptedException {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + app.port() + "/api/login")).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void testValidToken() throws Exception {
        final HttpResponse<String> response = get();

        assertEquals(200, response.statusCode());
        final JsonNode json = MAPPER.readTree(response.body());
        // auth.js reads the user from this field (there is no "valid" field)
        assertEquals("bob@example.com", json.get("user").asText());
        assertFalse(json.has("valid"));
    }

    @Test
    void testInvalidToken() throws Exception {
        loginManager.response = new LoginManager.CheckLoginResponse(null, "Missing token");

        final HttpResponse<String> response = get();

        assertEquals(401, response.statusCode());
        assertEquals("Missing token", MAPPER.readTree(response.body()).get("error").asText());
    }
}
