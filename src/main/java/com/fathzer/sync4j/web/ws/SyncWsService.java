package com.fathzer.sync4j.web.ws;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

import com.fathzer.sync4j.sync.Statistics;
import com.fathzer.sync4j.web.sync.SyncStatus;
import com.fathzer.sync4j.web.sync.SynchronizationManager;
import com.fathzer.sync4j.web.sync.SynchronizationManager.SyncBroadcaster;

import io.javalin.websocket.WsCloseContext;
import io.javalin.websocket.WsConnectContext;
import io.javalin.websocket.WsContext;

/**
 * The WebSocket endpoint that sends the progress, the status of the synchronization of a library and the messages of the plugins to the clients that watch it.
 * <br>The endpoint has a <code>libraryId</code> path parameter.
 * <br>The messages have a <code>type</code> attribute: PROGRESS (data is the statistics), STATUS (data is the status) or PLUGIN (see {@link com.fathzer.sync4j.web.plugin.PluginContext#publish(String, Object)}).
 */
public class SyncWsService extends AbstractWebSocketService implements SyncBroadcaster {
    private enum MessageType {
        PROGRESS,
        STATUS,
        PLUGIN
    }

    /** The name of the path parameter that contains the library id. */
    public static final String LIBRARY_ID_PARAM = "libraryId";

    private final Map<String, Set<WsContext>> libraryConnections = new ConcurrentHashMap<>();
    private final SynchronizationManager manager;

    /**
     * Constructor.
     * @param manager the manager that provides the current status of a library when a client connects
     */
    public SyncWsService(SynchronizationManager manager) {
        this.manager = manager;
    }

    @Override
    protected void onConnect(WsConnectContext ctx) {
        final String libraryId = ctx.pathParam(LIBRARY_ID_PARAM);
        libraryConnections.computeIfAbsent(libraryId, k -> new CopyOnWriteArraySet<>()).add(ctx);
        log.debug("WebSocket connected for library: {}", libraryId);
        ctx.send(message(MessageType.STATUS, manager.getSyncStatus(libraryId)));
    }

    @Override
    protected void onClose(WsCloseContext ctx) {
        final String libraryId = ctx.pathParam(LIBRARY_ID_PARAM);
        final Set<WsContext> connections = libraryConnections.get(libraryId);
        if (connections != null) {
            connections.remove(ctx);
            if (connections.isEmpty()) {
                libraryConnections.remove(libraryId);
            }
        }
        log.debug("WebSocket disconnected for library: {}", libraryId);
    }

    @Override
    public void broadcast(String libraryId, Statistics progress) {
        log.trace("Broadcasting progress for library: {} -> {}", libraryId, progress);
        send(libraryId, message(MessageType.PROGRESS, progress));
    }

    @Override
    public void broadcast(String libraryId, SyncStatus status) {
        log.debug("Broadcasting status for library: {} -> {}", libraryId, status);
        send(libraryId, message(MessageType.STATUS, status));
    }

    @Override
    public void broadcast(String libraryId, String pluginId, Object data) {
        final Map<String, Object> message = message(MessageType.PLUGIN, data);
        message.put("plugin", pluginId);
        message.put("library", libraryId);
        send(libraryId, message);
    }

    private void send(String libraryId, Map<String, Object> message) {
        final Set<WsContext> connections = libraryConnections.get(libraryId);
        if (connections != null) {
            // Remove failed connections
            connections.removeIf(ctx -> {
                try {
                    ctx.send(message);
                    return false;
                } catch (Exception e) {
                    log.warn("Failed to send WebSocket message", e);
                    return true;
                }
            });
        }
    }

    private static Map<String, Object> message(MessageType type, Object content) {
        final Map<String, Object> result = new HashMap<>();
        result.put("type", type);
        result.put("data", content);
        return result;
    }

    @Override
    public void stop() {
        super.stop();
        libraryConnections.clear();
    }
}
