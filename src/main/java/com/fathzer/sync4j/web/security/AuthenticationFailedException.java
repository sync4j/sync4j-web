package com.fathzer.sync4j.web.security;

import java.io.IOException;

/**
 * An exception thrown by an {@link Authenticator} when the credentials are rejected.
 */
public class AuthenticationFailedException extends IOException {
    private static final long serialVersionUID = 1L;
    private final int statusCode;
    private final transient Object details;

    /**
     * Constructor.
     * @param statusCode the HTTP status code sent to the client (typically 401)
     * @param message the message
     * @param details the body of the response sent to the client (serialized to JSON). If null, it is <code>{"message": message}</code>.
     */
    public AuthenticationFailedException(int statusCode, String message, Object details) {
        super(message);
        this.statusCode = statusCode;
        this.details = details;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public Object getDetails() {
        return details;
    }
}
