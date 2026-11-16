package com.fathzer.sync4j.web.security;

import io.javalin.http.Context;

/**
 * Manages the login of the users.
 */
public interface LoginManager {
    /**
     * The result of a login check.
     * @param user the user, null if the login is not valid
     * @param error the reason why the login is not valid, null if it is
     */
    public record CheckLoginResponse(String user, String error) {}

    void login(Context ctx);
    CheckLoginResponse checkLogin(Context ctx);
    void logout(Context ctx);
}
