package com.fathzer.sync4j.web.plugin;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import com.fathzer.sync4j.config.Library;
import com.fathzer.sync4j.web.sync.SyncListener;

/**
 * A plugin of a synchronization server.
 * <br>A plugin is notified of the synchronizations (see {@link SyncListener}), can register its own routes and can publish messages to the users.
 */
public interface SyncPlugin extends SyncListener, AutoCloseable {
    /**
     * Gets the id of the plugin.
     * @return the id, it is the key of the plugin configuration in the configuration file
     */
    String id();

    /**
     * Initializes the plugin.
     * <br>It is called once, before the server starts.
     * @param context the context
     * @throws IOException if the plugin can't be initialized (for instance, because its configuration is invalid). The server does not start.
     */
    default void init(PluginContext context) throws IOException {
        // Does nothing by default
    }

    /**
     * Checks that the plugin can do its job for a library.
     * <br>It is called when a user selects the library in the web page, and each time the user asks to check it again, so the result should not be cached for long.
     * It is called by the thread that handles the request: it should return quickly (use time limits when it calls another server).
     * @param library the library
     * @return the problems found, that are displayed to the user (an empty list if there is none, as by default)
     * @throws IOException if the check can't be done (for instance, because a server is not available). The message is displayed to the user as a problem.
     */
    default List<Message> check(Library library) throws IOException {
        return List.of();
    }

    /**
     * Gets the translations of the texts of the plugin (see {@link Message}).
     * <br>It is called when the page asks for its translations, with the language chosen for the user (for instance <code>fr</code>).
     * The keys are shared with the ones of the page and of the other plugins: start them with the id of the plugin.
     * The English translations are required: they complete the ones of the other languages (a key that is not translated in French is displayed in English).
     * {@link com.fathzer.sync4j.web.i18n.Translations#load} reads them from <code>.properties</code> files.
     * @param language the language, in lowercase (<code>fr</code>)
     * @return the translations by key (empty by default)
     */
    default Map<String, String> translations(String language) {
        return Map.of();
    }

    /**
     * Releases the resources of the plugin, it is called when the server stops.
     */
    @Override
    default void close() {
        // Does nothing by default
    }
}
