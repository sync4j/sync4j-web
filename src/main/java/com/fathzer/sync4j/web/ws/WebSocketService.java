package com.fathzer.sync4j.web.ws;

import java.util.function.Consumer;

import io.javalin.websocket.WsConfig;

/**
 * A service that exposes a WebSocket endpoint.
 */
public interface WebSocketService {
    /**
     * Returns a consumer that configures the WebSocket endpoint.
     * @return a consumer
     */
    Consumer<WsConfig> setConfig();

    /**
     * Starts the service.
     */
    void start();

    /**
     * Stops the service and cleans up its resources.
     */
    void stop();
}
