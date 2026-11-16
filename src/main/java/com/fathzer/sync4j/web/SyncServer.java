package com.fathzer.sync4j.web;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fathzer.sync4j.config.Library;
import com.fathzer.sync4j.web.config.SyncWebConfig;
import com.fathzer.sync4j.web.plugin.LibraryWarning;
import com.fathzer.sync4j.web.plugin.PluginContext;
import com.fathzer.sync4j.config.PluginDiscovery;
import com.fathzer.sync4j.web.plugin.SyncPlugin;
import com.fathzer.sync4j.web.plugin.SyncPluginFactory;
import com.fathzer.sync4j.web.rest.LoginRestService;
import com.fathzer.sync4j.web.rest.SyncRestService;
import com.fathzer.sync4j.web.security.Authenticator;
import com.fathzer.sync4j.web.security.JwtLoginManager;
import com.fathzer.sync4j.web.security.LoginManager;
import com.fathzer.sync4j.web.sync.StatisticsSerializer;
import com.fathzer.sync4j.web.sync.SyncListener;
import com.fathzer.sync4j.web.sync.SyncRequest;
import com.fathzer.sync4j.web.sync.SyncStatus;
import com.fathzer.sync4j.web.sync.SynchronizationManager;
import com.fathzer.sync4j.web.ws.SyncWsService;

import io.javalin.Javalin;
import io.javalin.config.RoutesConfig;
import io.javalin.http.UnauthorizedResponse;
import io.javalin.http.staticfiles.Location;
import io.javalin.json.JavalinJackson;

/**
 * A web server that runs and monitors the synchronizations of the libraries of a configuration.
 * <p>
 * It exposes:
 * </p>
 * <ul>
 * <li>a REST API to list the libraries, start/cancel a synchronization and get the statuses (see {@link SyncRestService})</li>
 * <li>a WebSocket endpoint that sends the progress of a library (see {@link SyncWsService})</li>
 * <li>a REST API to log in (see {@link LoginRestService})</li>
 * <li>a web page that uses these services</li>
 * </ul>
 * <p>
 * If the security is enabled (the configuration has a secret), only the web page and the login API can be used without being logged in.
 * </p>
 * <p>
 * Plugins can extend it, see {@link SyncPlugin}.
 * </p>
 */
public class SyncServer implements AutoCloseable {
    /** The default port. */
    public static final int DEFAULT_PORT = 8080;

    private static final Logger log = LoggerFactory.getLogger(SyncServer.class);

    /**
     * A builder of servers.
     */
    public static class Builder {
        private final SyncWebConfig config;
        private Authenticator authenticator;
        private Boolean secureCookie;
        private boolean checkLibraries = true;
        private boolean discoverPlugins = true;
        private Path pluginsDirectory;
        private Path statusFile = Paths.get("syncStatuses.json");
        private Path staticFiles;
        private final List<SyncPlugin> plugins = new ArrayList<>();

        private Builder(SyncWebConfig config) {
            this.config = Objects.requireNonNull(config);
        }

        /**
         * Sets the authenticator that checks the credentials of the users. It is required if the configuration has a secret.
         * @param authenticator the authenticator
         * @return this
         */
        public Builder authenticator(Authenticator authenticator) {
            this.authenticator = authenticator;
            return this;
        }

        /**
         * Sets if the session cookie has the Secure attribute. It overrides the <code>secureCookie</code> property of the configuration (true by default).
         * <br>Browsers don't send such a cookie over http (except to localhost). Use false only if the server is not served over https.
         * @param secureCookie true to set the Secure attribute
         * @return this
         */
        public Builder secureCookie(boolean secureCookie) {
            this.secureCookie = secureCookie;
            return this;
        }

        /**
         * Sets if the libraries are checked when the server is built (default is true).
         * <br>The check opens the source and destination of each library (it creates the destination if it does not exist).
         * @param checkLibraries true to check the libraries
         * @return this
         */
        public Builder checkLibraries(boolean checkLibraries) {
            this.checkLibraries = checkLibraries;
            return this;
        }

        /**
         * Adds a plugin.
         * @param plugin the plugin, it is always active
         * @return this
         */
        public Builder plugin(SyncPlugin plugin) {
            this.plugins.add(Objects.requireNonNull(plugin));
            return this;
        }

        /**
         * Sets if the plugins that are discovered (see {@link PluginDiscovery}) are activated (default is true).
         * <br>A discovered plugin is activated only if the configuration has a section for its id.
         * @param discoverPlugins false to ignore the discovered plugins
         * @return this
         */
        public Builder discoverPlugins(boolean discoverPlugins) {
            this.discoverPlugins = discoverPlugins;
            return this;
        }

        /**
         * Sets a directory whose jar files are searched for plugins, in addition to the classpath (see {@link PluginDiscovery}).
         * @param pluginsDirectory a directory (it is ignored if it does not exist)
         * @return this
         */
        public Builder pluginsDirectory(Path pluginsDirectory) {
            this.pluginsDirectory = pluginsDirectory;
            return this;
        }

        /**
         * Sets the file where the statuses of the last synchronizations are saved (default is syncStatuses.json in the working directory).
         * @param statusFile the file
         * @return this
         */
        public Builder statusFile(Path statusFile) {
            this.statusFile = Objects.requireNonNull(statusFile);
            return this;
        }

        /**
         * Sets a directory that contains static files that replace the files of the default web page (for instance to customize the page).
         * @param staticFiles a directory
         * @return this
         */
        public Builder staticFiles(Path staticFiles) {
            this.staticFiles = staticFiles;
            return this;
        }

        /**
         * Builds the server (it is not started).
         * @return a new server
         * @throws IOException if a library can't be opened or a plugin can't be initialized
         */
        public SyncServer build() throws IOException {
            return new SyncServer(this);
        }
    }

    private class Context implements PluginContext {
        private final String pluginId;
        private final JsonNode pluginConfig;
        private final RoutesConfig routes;

        Context(String pluginId, JsonNode pluginConfig, RoutesConfig routes) {
            this.pluginId = pluginId;
            this.routes = routes;
            this.pluginConfig = pluginConfig == null ? MissingNode.getInstance() : pluginConfig;
        }

        @Override
        public JsonNode config() {
            return pluginConfig;
        }

        @Override
        public List<Library> libraries() {
            return config.synchronization().libraries();
        }

        @Override
        public RoutesConfig routes() {
            return routes;
        }

        @Override
        public LoginManager loginManager() {
            return loginManager;
        }

        @Override
        public void publish(String libraryId, Object data) {
            wsService.broadcast(libraryId, pluginId, data);
        }
    }

    private final SyncWebConfig config;
    private final Javalin app;
    private final LoginManager loginManager;
    private final SynchronizationManager manager;
    private final SyncWsService wsService;
    private final List<SyncPlugin> plugins = new ArrayList<>();
    private boolean closed;

    /**
     * Creates a builder.
     * @param config the configuration of the server
     * @return a builder
     */
    public static Builder builder(SyncWebConfig config) {
        return new Builder(config);
    }

    private SyncServer(Builder builder) throws IOException {
        this.config = builder.config;
        if (config.synchronization().libraries().isEmpty()) {
            throw new IllegalArgumentException("No libraries specified");
        }
        if (builder.checkLibraries) {
            for (Library library : config.synchronization().libraries()) {
                log.info("Checking library {}", library.id());
                try {
                    library.check();
                } catch (java.io.UncheckedIOException e) {
                    throw new IOException("Library " + library.id() + " can't be opened: " + e.getCause().getMessage(), e.getCause());
                }
            }
        }
        try {
            this.loginManager = new JwtLoginManager(builder.authenticator, config.secret(), builder.secureCookie == null ? config.secureCookie() : builder.secureCookie);
        } catch (NullPointerException e) {
            throw new IllegalStateException("The configuration has a secret, an authenticator is required", e);
        }
        this.manager = new SynchronizationManager(config.synchronization(), builder.statusFile);
        this.wsService = new SyncWsService(manager);
        manager.setBroadcaster(wsService);
        // Javalin needs the routes to be registered when the application is created, the plugins are initialized at the same time
        final List<Exception> failures = new ArrayList<>();
        this.app = createApp(builder, failures);
        if (!failures.isEmpty()) {
            close();
            final Exception failure = failures.get(0);
            if (failure instanceof IOException e) {
                throw e;
            }
            throw (RuntimeException) failure;
        }
        manager.setListener(new PluginsListener());
    }

    private Javalin createApp(Builder builder, List<Exception> failures) {
        return Javalin.create(conf -> {
            conf.jsonMapper(new JavalinJackson().updateMapper(mapper -> mapper.registerModule(StatisticsSerializer.getModule())));
            if (builder.staticFiles != null) {
                // Registered first, so it overrides the default files
                conf.staticFiles.add(builder.staticFiles.toString(), Location.EXTERNAL);
            }
            conf.staticFiles.add("/sync4j-web/www", Location.CLASSPATH);
            conf.jetty.modifyWebSocketServletFactory(factory -> factory.setIdleTimeout(Duration.ofMinutes(10)));
            try {
                registerRoutes(conf.routes);
                initPlugins(builder, conf.routes);
            } catch (IOException | RuntimeException e) {
                failures.add(e);
            }
        });
    }

    private void registerRoutes(RoutesConfig routes) {
        new LoginRestService(loginManager).registerRoutes(routes, "/api/login");
        new SyncRestService(manager, loginManager, this::check).registerRoutes(routes, "/api/libraries");
        final String wsPath = "/ws/sync/{" + SyncWsService.LIBRARY_ID_PARAM + "}";
        // The connection is refused (HTTP 401) before the WebSocket is opened if the user is not logged in
        routes.wsBeforeUpgrade(wsPath, ctx -> {
            final String error = loginManager.checkLogin(ctx).error();
            if (error != null) {
                throw new UnauthorizedResponse(error);
            }
        });
        routes.ws(wsPath, wsService.setConfig());
        wsService.start();
    }

    private void initPlugins(Builder builder, RoutesConfig routes) throws IOException {
        final List<SyncPlugin> all = new ArrayList<>(builder.plugins);
        if (builder.discoverPlugins) {
            for (SyncPluginFactory factory : PluginDiscovery.discover(SyncPluginFactory.class, builder.pluginsDirectory)) {
                if (config.plugins().containsKey(factory.id())) {
                    all.add(factory.create(config.plugins().get(factory.id())));
                }
            }
        }
        final Set<String> ids = new HashSet<>();
        for (SyncPlugin plugin : all) {
            if (!ids.add(plugin.id())) {
                throw new IllegalArgumentException("Plugin " + plugin.id() + " is registered twice");
            }
            plugins.add(plugin);
            log.info("Initializing plugin {}", plugin.id());
            plugin.init(new Context(plugin.id(), config.plugins().get(plugin.id()), routes));
        }
        // A section that no plugin uses is probably a mistake (a typo, a plugin that can't be loaded, ...)
        config.plugins().keySet().stream().filter(id -> !ids.contains(id))
                .forEach(id -> log.warn("The configuration has a section '{}' but there is no plugin with this id", id));
    }

    /** Asks the plugins if they found a problem in a library. */
    private List<LibraryWarning> check(Library library) {
        final List<LibraryWarning> warnings = new ArrayList<>();
        for (SyncPlugin plugin : plugins) {
            try {
                plugin.check(library).forEach(message -> warnings.add(new LibraryWarning(plugin.id(), message)));
            } catch (IOException | RuntimeException e) {
                log.warn("Plugin {} can't check the library {}", plugin.id(), library.id(), e);
                warnings.add(new LibraryWarning(plugin.id(), "The library can't be checked: " + e.getMessage()));
            }
        }
        return warnings;
    }

    private class PluginsListener implements SyncListener {
        @Override
        public void beforeSync(SyncRequest request) throws IOException {
            for (SyncPlugin plugin : plugins) {
                plugin.beforeSync(request);
            }
        }

        @Override
        public void afterSync(SyncRequest request, SyncStatus status) {
            for (SyncPlugin plugin : plugins) {
                try {
                    plugin.afterSync(request, status);
                } catch (RuntimeException e) {
                    log.warn("Plugin {} failed at the end of the synchronization of {}", plugin.id(), request.library().id(), e);
                }
            }
        }
    }

    /**
     * Starts the server on the port defined by the <code>server.port</code> system property (8080 if it is not defined).
     */
    public void start() {
        start(Integer.getInteger("server.port", DEFAULT_PORT));
    }

    /**
     * Starts the server.
     * @param port the port (0 to use a free port, see {@link #port()})
     */
    public void start(int port) {
        app.start(port);
        log.info("Server started on port {}", app.port());
        if (loginManager instanceof JwtLoginManager jwt && jwt.isSecured() && !jwt.isSecureCookie()) {
            log.warn("The session cookie is not secure (secureCookie is false): anybody who listens to the network can steal a login. Use https if you can.");
        }
        if (config.secret() == null) {
            log.warn("No secret found in config, security is disabled. Don't do that in production. Use JwtLoginManager.buildSecret() to generate a secret.");
        }
    }

    /**
     * Gets the port of the started server.
     * @return the port
     */
    public int port() {
        return app.port();
    }

    /**
     * Gets the manager of the synchronizations.
     * @return the manager
     */
    public SynchronizationManager manager() {
        return manager;
    }

    /**
     * Stops the server, the running synchronizations are cancelled and the plugins are closed.
     */
    public synchronized void stop() {
        if (closed) {
            return;
        }
        closed = true;
        log.info("Shutting down server...");
        wsService.stop();
        try {
            app.stop();
        } catch (RuntimeException e) {
            log.error("Error while stopping server", e);
        }
        manager.close();
        for (SyncPlugin plugin : plugins) {
            try {
                plugin.close();
            } catch (RuntimeException e) {
                log.warn("Error while closing plugin {}", plugin.id(), e);
            }
        }
    }

    @Override
    public void close() {
        stop();
    }
}
