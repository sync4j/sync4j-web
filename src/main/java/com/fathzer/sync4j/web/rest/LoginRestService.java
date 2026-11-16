package com.fathzer.sync4j.web.rest;

import java.util.Map;

import com.fathzer.sync4j.web.security.LoginManager;
import com.fathzer.sync4j.web.security.LoginManager.CheckLoginResponse;

import io.javalin.Javalin;
import io.javalin.http.Context;

/**
 * The REST service of the login: POST logs in, GET checks the login (it answers <code>{"user": "..."}</code> or a 401) and DELETE logs out.
 */
public class LoginRestService {
    private final LoginManager loginManager;

    public LoginRestService(LoginManager loginManager) {
        this.loginManager = loginManager;
    }

    public void registerRoutes(Javalin app, String root) {
        app.post(root, loginManager::login);
        app.get(root, this::verifyToken);
        app.delete(root, loginManager::logout);
    }

    private void verifyToken(Context ctx) {
        final CheckLoginResponse result = loginManager.checkLogin(ctx);
        if (result.error() != null) {
            ctx.status(401).json(Map.of("error", result.error()));
        } else {
            ctx.json(Map.of("user", result.user()));
        }
    }
}
