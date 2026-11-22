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
     * The web page of the server displays the messages whose data has the following optional attributes:
     * <ul>
     * <li><code>message</code>: a sentence, added to the technical log (a card of the page, collapsed by default) and displayed in the card of the plugin.</li>
     * <li><code>label</code>: the title of the card of the plugin (the id of the plugin by default).</li>
     * <li><code>state</code>: <code>RUNNING</code>, <code>DONE</code>, <code>FAILED</code> or <code>UNKNOWN</code>, displayed as a badge of the card.</li>
     * <li><code>details</code>: an array of sentences, displayed as a list in the card.</li>
     * <li><code>progress</code>: an object with <code>done</code> and <code>total</code>, displayed as a progress bar in the card.</li>
     * </ul>
     * <br>The texts (<code>message</code>, <code>label</code> and the items of <code>details</code>) are strings, or {@link Message}s that the page translates into the language of the user
     * (see {@link SyncPlugin#translations(String)}). A message can have messages as arguments.
     * <br>The card of a plugin is displayed as soon as a message has a <code>state</code>, <code>details</code> or a <code>progress</code>. It is meant for the end user:
     * it tells what the plugin does. The technical log is for the details that only the administrator needs.
     * <br>A message that has a <code>state</code> is the current state of the plugin for the library: the last one is kept, and sent to the clients that connect later
     * (for instance, a user that loads the page again) until the next synchronization of the library starts.
     * @param libraryId the id of the library
     * @param data the data, it should be serializable to JSON
     */
    void publish(String libraryId, Object data);
}
