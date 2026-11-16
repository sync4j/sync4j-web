package com.fathzer.sync4j.web.ws;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.javalin.websocket.WsCloseContext;
import io.javalin.websocket.WsConfig;
import io.javalin.websocket.WsConnectContext;
import io.javalin.websocket.WsContext;
import io.javalin.websocket.WsErrorContext;
import io.javalin.websocket.WsMessageContext;

/**
 * A base class for WebSocket services: it keeps the connected clients.
 */
public abstract class AbstractWebSocketService implements WebSocketService {
    protected final Map<String, WsContext> connectedClients = new ConcurrentHashMap<>();
    protected final Logger log = LoggerFactory.getLogger(getClass());

    @Override
    public Consumer<WsConfig> setConfig() {
        return ws -> {
            ws.onConnect(ctx -> {
                connectedClients.put(ctx.sessionId(), ctx);
                ctx.enableAutomaticPings();
                onConnect(ctx);
            });

            ws.onMessage(this::onMessage);

            ws.onClose(ctx -> {
                connectedClients.remove(ctx.sessionId());
                onClose(ctx);
            });

            ws.onError(ctx -> {
                connectedClients.remove(ctx.sessionId());
                onError(ctx);
            });
        };
    }

    protected void onConnect(WsConnectContext ctx) {
        log.debug("WebSocket connected: {}", ctx.sessionId());
    }

    protected void onMessage(WsMessageContext ctx) {
        // The clients are not expected to send messages
        log.debug("Ignored message from {}: {}", ctx.sessionId(), ctx.message());
    }

    protected void onClose(WsCloseContext ctx) {
        log.debug("WebSocket disconnected: {} (Code: {}, Reason: {})", ctx.sessionId(), ctx.status(), ctx.reason());
    }

    protected void onError(WsErrorContext ctx) {
        log.warn("WebSocket error for {}: {}", ctx.sessionId(), ctx.error());
    }

    @Override
    public void start() {
        // Does nothing by default
    }

    @Override
    public void stop() {
        // Close all WebSocket connections gracefully
        connectedClients.values().forEach(ctx -> {
            try {
                if (ctx.session != null && ctx.session.isOpen()) {
                    ctx.session.close();
                }
            } catch (Exception e) {
                log.debug("Error closing WebSocket connection during shutdown", e);
            }
        });
        connectedClients.clear();
    }
}
