package com.fathzer.sync4j.web.plugin;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fathzer.sync4j.config.Library;
import com.fathzer.sync4j.web.security.LoginManager;

import io.javalin.config.RoutesConfig;

/**
 * What a plugin can use to interact with the server.
 */
public interface PluginContext {
    /**
     * Gets the configuration of the plugin.
     * @return the configuration (a missing node if the plugin has no configuration, never null)
     */
    JsonNode config();

    /**
     * Gets the libraries of the server.
     * @return the libraries
     */
    List<Library> libraries();

    /**
     * Gets the routes of the Javalin application, to register the ones of the plugin (<code>routes().get("/api/plugins/id/hello", ctx -> ...)</code>).
     * <br>Javalin needs the routes to be registered before the server starts: they can only be registered during {@link SyncPlugin#init(PluginContext)}.
     * @return the routes configuration
     */
    RoutesConfig routes();

    /**
     * Gets the login manager, to protect the routes registered by the plugin (see {@link LoginManager#checkLogin(io.javalin.http.Context)}).
     * @return the login manager
     */
    LoginManager loginManager();

    /**
     * Publishes a message to the users that watch a library.
     * <br>The client receives <code>{"type":"PLUGIN","plugin":"&lt;plugin id&gt;","library":"&lt;library id&gt;","data":&lt;data&gt;}</code>.
     * The web page of the server displays the messages whose data has the following optional attributes: <code>message</code> (a text added to the log),
     * <code>label</code> and <code>progress</code> (an object with <code>done</code> and <code>total</code>, displayed as a progress bar titled with <code>label</code>).
     * @param libraryId the id of the library
     * @param data the data, it should be serializable to JSON
     */
    void publish(String libraryId, Object data);
}
