package com.fathzer.sync4j.web.security;

import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.Map;
import java.util.Objects;

import javax.crypto.SecretKey;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.annotation.JsonAlias;

import io.javalin.http.Context;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.WeakKeyException;

/**
 * A login manager that stores the session of the users in a signed JWT, in a cookie.
 * <br>The credentials are checked by an {@link Authenticator}.
 * <br>If there is no secret, the security is disabled: everyone is considered as logged in as "anonymous".
 */
public class JwtLoginManager implements LoginManager {
    private static final String JWT_COOKIE_NAME = "auth_token";
    private static final String MESSAGE = "message";
    private static final Duration JWT_EXPIRATION = Duration.ofDays(7);

    private final Logger log = LoggerFactory.getLogger(JwtLoginManager.class);
    private final Authenticator authenticator;
    private final SecretKey secret;
    private final boolean secureCookie;

    private record LoginRequest(@JsonAlias("email") String user, String password) {}

    /**
     * Constructor.
     * @param authenticator the authenticator that checks the credentials (can be null only if secret is null)
     * @param secret the secret (base64, at least 32 bytes) used to sign the tokens, see {@link #buildSecret()}. If null or blank, the security is disabled.
     * @throws IllegalArgumentException if the secret is not valid
     * @param secureCookie true to send the cookie with the Secure attribute (browsers only send it over https, with the exception of localhost).
     * It should be false only if the server is not served over https.
     */
    public JwtLoginManager(Authenticator authenticator, String secret, boolean secureCookie) {
        this.secret = secret == null || secret.isBlank() ? null : toKey(secret);
        if (this.secret != null) {
            Objects.requireNonNull(authenticator, "An authenticator is required if the security is enabled");
        }
        this.authenticator = authenticator;
        this.secureCookie = secureCookie;
    }

    /**
     * Tests if the session cookie has the Secure attribute.
     * @return true if it has
     */
    public boolean isSecureCookie() {
        return secureCookie;
    }

    /**
     * Tests if the security is enabled.
     * @return true if the users have to log in
     */
    public boolean isSecured() {
        return secret != null;
    }

    @Override
    public CheckLoginResponse checkLogin(Context ctx) {
        if (secret == null) {
            return new CheckLoginResponse("anonymous", null);
        }
        try {
            final String token = ctx.cookie(JWT_COOKIE_NAME);
            if (token == null || token.isBlank()) {
                return new CheckLoginResponse(null, "Missing token");
            }
            final Claims claims = Jwts.parser().verifyWith(secret).build().parseSignedClaims(token).getPayload();
            return new CheckLoginResponse(claims.getSubject(), null);
        } catch (Exception e) {
            log.debug("Token verification failed: {}", e.getMessage(), e);
            return new CheckLoginResponse(null, "Invalid token (" + e.getMessage() + ")");
        }
    }

    @Override
    public void logout(Context ctx) {
        // Create an expired cookie to remove the client-side cookie
        ctx.header("Set-Cookie", String.format("%s=; Path=/; Expires=Thu, 01 Jan 1970 00:00:00 GMT; HttpOnly; %s", JWT_COOKIE_NAME, cookieAttributes()));
        ctx.status(200).result("Logged out successfully");
    }

    @Override
    public void login(Context ctx) {
        if (secret == null) {
            ctx.status(200).result("Security is disabled");
            return;
        }
        try {
            final LoginRequest request = ctx.bodyAsClass(LoginRequest.class);
            if (request.user() == null || request.user().isBlank() || request.password() == null) {
                ctx.status(400).json(Map.of(MESSAGE, "User and password are required"));
                return;
            }
            final String user = authenticator.authenticate(request.user(), request.password());
            final String token = Jwts.builder()
                .subject(user)
                .issuedAt(Date.from(Instant.now()))
                .expiration(Date.from(Instant.now().plus(JWT_EXPIRATION)))
                .signWith(secret)
                .compact();
            ctx.header("Set-Cookie", String.format("%s=%s; Path=/; Max-Age=%d; HttpOnly; %s", JWT_COOKIE_NAME, token, JWT_EXPIRATION.toSeconds(), cookieAttributes()));
            ctx.status(200).result("Authentication successful");
        } catch (AuthenticationFailedException e) {
            log.info("Authentication failed", e);
            ctx.status(e.getStatusCode()).json(e.getDetails() == null ? Map.of(MESSAGE, String.valueOf(e.getMessage())) : e.getDetails());
        } catch (Exception e) {
            log.warn("An error occurred during authentication", e);
            ctx.status(500).json(Map.of(MESSAGE, "An error occurred during authentication: " + e.getMessage()));
        }
    }

    private String cookieAttributes() {
        return (secureCookie ? "Secure; " : "") + "SameSite=Strict";
    }

    /**
     * Builds a new secret.
     * <br>The same secret can be generated without code, see {@link SecretGenerator}.
     * @return a random secret (base64) that can be used to sign the tokens
     */
    public static String buildSecret() {
        return SecretGenerator.generate();
    }

    private static SecretKey toKey(String keyString) {
        try {
            return Keys.hmacShaKeyFor(Base64.getDecoder().decode(keyString.trim()));
        } catch (IllegalArgumentException | WeakKeyException e) {
            // The messages of the libraries ("Illegal base64 character 2d") are meaningless to someone who put a password in the configuration
            throw new IllegalArgumentException("The secret is not valid: it should be base64 text that encodes at least 32 random bytes, a password is not a valid secret", e);
        }
    }
}
