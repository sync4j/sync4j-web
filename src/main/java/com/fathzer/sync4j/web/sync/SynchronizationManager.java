package com.fathzer.sync4j.web.sync;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fathzer.sync4j.FileProvider;
import com.fathzer.sync4j.Folder;
import com.fathzer.sync4j.HashAlgorithm;
import com.fathzer.sync4j.sync.Statistics;
import com.fathzer.sync4j.sync.Synchronization;
import com.fathzer.sync4j.sync.parameters.FileComparator;
import com.fathzer.sync4j.sync.parameters.SyncParameters;
import com.fathzer.sync4j.util.FilterRules;
import com.fathzer.sync4j.config.Library;
import com.fathzer.sync4j.config.SynchronizationConfig;
import com.fathzer.sync4j.web.plugin.Message;

/**
 * Runs the synchronizations of the libraries of a configuration (one at a time for each library), keeps their statuses and notifies the interested parties.
 */
public class SynchronizationManager implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(SynchronizationManager.class);
    private static final String LIBRARIES_KEY = "libraries";
    private static final String PLUGINS_KEY = "plugins";
    private static final ObjectMapper MAPPER;

    static {
        MAPPER = new ObjectMapper();
        MAPPER.registerModule(StatisticsSerializer.getModule());
    }

    /**
     * Receives the progress and the status of the synchronizations.
     */
    public interface SyncBroadcaster {
        void broadcast(String libraryId, Statistics progress);
        void broadcast(String libraryId, SyncStatus status);

        /**
         * Broadcasts a message of a plugin.
         * @param libraryId the library id
         * @param pluginId the plugin id
         * @param data the message
         */
        default void broadcast(String libraryId, String pluginId, Object data) {
            // Does nothing by default
        }
    }

    /**
     * The exception thrown when a synchronization is requested for a library that is already being synchronized.
     */
    public static class ConflictException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public ConflictException(String library) {
            super("A sync is already running for library: " + library);
        }
    }

    /** A running synchronization and the providers it uses (they have to be closed when it is over). */
    private record Running(SyncRequest request, Synchronization synchronization, FileProvider source, FileProvider destination) {
        void close() {
            closeQuietly(synchronization);
            closeQuietly(source);
            closeQuietly(destination);
        }

        private static void closeQuietly(AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception e) {
                log.warn("Failed to close {}", closeable, e);
            }
        }
    }

    private final Map<String, Running> runningSyncs = new ConcurrentHashMap<>();
    private final Map<String, SyncStatus> syncStatuses = new ConcurrentHashMap<>();
    /** The last message with a state of each plugin, by library id then plugin id (see {@link #setPluginState}). */
    private final Map<String, Map<String, Map<String, Object>>> pluginStates = new ConcurrentHashMap<>();
    private final ExecutorService executorService;
    private final ScheduledExecutorService statsScheduler;
    private final AtomicInteger threadCount = new AtomicInteger(0);
    private final SynchronizationConfig config;
    private final Path statusFile;
    private volatile SyncBroadcaster broadcaster;
    private volatile SyncListener listener = new SyncListener() {};
    // Lets tests change the parameters of the synchronizations (for instance, to slow them down)
    private Consumer<SyncParameters> parametersCustomizer = p -> {};

    /**
     * Constructor.
     * @param config the configuration
     * @param statusFile the file where the statuses of the last synchronizations and the states of the plugins are saved
     */
    public SynchronizationManager(SynchronizationConfig config, Path statusFile) {
        this.config = Objects.requireNonNull(config);
        this.statusFile = Objects.requireNonNull(statusFile);

        loadStatuses();
        // Initialize status for any new libraries not in the saved statuses
        config.libraries().forEach(lib -> syncStatuses.putIfAbsent(lib.id(), SyncStatus.idle()));

        this.executorService = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "sync-" + threadCount.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
        this.statsScheduler = Executors.newScheduledThreadPool(1, r -> {
            Thread t = new Thread(r, "sync-stats-broadcaster");
            t.setDaemon(true);
            return t;
        });
    }

    private void loadStatuses() {
        if (!Files.exists(statusFile)) {
            log.info("No existing status file found, starting with empty statuses");
            return;
        }
        try {
            final JsonNode root = MAPPER.readTree(statusFile.toFile());
            final Map<String, SyncStatus> savedStatuses = root.has(LIBRARIES_KEY)
                    ? MAPPER.convertValue(root.get(LIBRARIES_KEY), new TypeReference<Map<String, SyncStatus>>() {}) : Map.of();
            // Only keep statuses for libraries that exist in the current config
            savedStatuses.forEach((libraryId, status) -> {
                if (isLibrary(libraryId)) {
                    syncStatuses.put(libraryId, status);
                }
            });
            log.info("Loaded sync statuses for {} libraries", savedStatuses.size());
            final Map<String, Map<String, Map<String, Object>>> savedPlugins = root.has(PLUGINS_KEY)
                    ? MAPPER.convertValue(root.get(PLUGINS_KEY), new TypeReference<Map<String, Map<String, Map<String, Object>>>>() {}) : Map.of();
            savedPlugins.forEach((libraryId, states) -> {
                if (isLibrary(libraryId)) {
                    states.forEach((pluginId, data) -> setLoadedPluginState(libraryId, pluginId, data));
                }
            });
        } catch (IOException | IllegalArgumentException e) {
            log.error("Failed to load sync statuses", e);
        }
    }

    private boolean isLibrary(String libraryId) {
        return config.libraries().stream().anyMatch(lib -> lib.id().equals(libraryId));
    }

    private void setLoadedPluginState(String libraryId, String pluginId, Map<String, Object> data) {
        final Map<String, Object> state = new LinkedHashMap<>(data);
        if ("RUNNING".equals(state.get("state"))) {
            // Nobody monitors the plugin anymore: what it was doing is unknown
            state.put("state", "UNKNOWN");
            state.put("message", Message.of("plugin.interrupted", "The server was restarted while this was in progress: the result is unknown."));
            state.remove("progress");
        }
        pluginStates.computeIfAbsent(libraryId, k -> new ConcurrentHashMap<>()).put(pluginId, state);
    }

    private synchronized void saveStatuses() {
        if (syncStatuses.isEmpty()) {
            return;
        }
        try {
            final Map<String, Object> root = new LinkedHashMap<>();
            root.put(LIBRARIES_KEY, syncStatuses);
            root.put(PLUGINS_KEY, pluginStates);
            // The file is written then moved, so a reader (or a crash) never sees a partial file
            final Path temp = statusFile.resolveSibling(statusFile.getFileName() + ".tmp");
            MAPPER.writeValue(temp.toFile(), root);
            try {
                Files.move(temp, statusFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, statusFile, StandardCopyOption.REPLACE_EXISTING);
            }
            log.debug("Successfully saved sync statuses");
        } catch (IOException e) {
            log.error("Failed to save sync statuses", e);
        }
    }

    /**
     * Records the state of a plugin for a library, and saves it with the statuses.
     * <br>This state is what a user that looks at the library later (even after the server restarted) should see about this plugin.
     * It is forgotten when the next synchronization of the library starts.
     * @param libraryId the library id
     * @param pluginId the plugin id
     * @param data the data published by the plugin (a JSON object with a <code>state</code>)
     */
    public void setPluginState(String libraryId, String pluginId, Map<String, Object> data) {
        pluginStates.computeIfAbsent(libraryId, k -> new ConcurrentHashMap<>()).put(pluginId, new LinkedHashMap<>(data));
        saveStatuses();
    }

    /**
     * Gets the states of the plugins for a library.
     * @param libraryId the library id
     * @return the data last published with a state, by plugin id (never null)
     */
    public Map<String, Map<String, Object>> getPluginStates(String libraryId) {
        return pluginStates.getOrDefault(libraryId, Map.of());
    }

    public void setBroadcaster(SyncBroadcaster broadcaster) {
        this.broadcaster = broadcaster;
    }

    /**
     * Sets the listener of the synchronizations.
     * @param listener the listener
     */
    public void setListener(SyncListener listener) {
        this.listener = Objects.requireNonNull(listener);
    }

    /**
     * Starts a synchronization.
     * @param request the request
     * @throws ConflictException if the library is already being synchronized
     * @throws IOException if the synchronization can't start (a folder can't be opened, a listener refused it, ...)
     */
    public synchronized void run(SyncRequest request) throws IOException {
        final String libraryId = request.library().id();
        if (runningSyncs.containsKey(libraryId)) {
            throw new ConflictException(libraryId);
        }
        listener.beforeSync(request);
        final Running running = build(request);
        final Synchronization synchronization = running.synchronization();
        // Register the synchronization before starting it, so it can be cancelled as soon as it runs
        final SyncStatus syncing = SyncStatus.syncing(synchronization.getStatistics());
        syncStatuses.put(libraryId, syncing);
        // What the plugins said about the previous synchronization is obsolete
        pluginStates.remove(libraryId);
        runningSyncs.put(libraryId, running);
        try {
            synchronization.start();
        } catch (RuntimeException e) {
            runningSyncs.remove(libraryId);
            syncStatuses.put(libraryId, SyncStatus.idle());
            running.close();
            throw e;
        }
        broadcast(libraryId, syncing);

        final CompletableFuture<Synchronization> syncFuture = CompletableFuture.supplyAsync(() -> this.waitFor(synchronization), executorService);

        // Schedule periodic statistics updates
        final ScheduledFuture<?> statsUpdater = statsScheduler.scheduleAtFixedRate(() -> {
            final SyncBroadcaster current = broadcaster;
            if (current != null) {
                current.broadcast(libraryId, synchronization.getStatistics());
            }
        }, 1, 1, TimeUnit.SECONDS);

        // Handle completion
        syncFuture.whenComplete((result, throwable) -> {
            // Stop the statistics updater when sync completes or fails
            statsUpdater.cancel(false);

            // Compute the status before releasing the resources (the statistics and errors come from the synchronization).
            // Warning: result is null if throwable is not.
            final SyncStatus syncStatus = toStatus(synchronization, throwable);
            runningSyncs.remove(libraryId);
            running.close();
            syncStatuses.put(libraryId, syncStatus);
            try {
                listener.afterSync(request, syncStatus);
            } catch (RuntimeException e) {
                log.warn("A listener failed at the end of the synchronization of {}", libraryId, e);
            }
            broadcast(libraryId, syncStatus);
            saveStatuses();
        });
    }

    private void broadcast(String libraryId, SyncStatus status) {
        final SyncBroadcaster current = broadcaster;
        if (current != null) {
            current.broadcast(libraryId, status);
        }
    }

    /**
     * Computes the status of a synchronization that is over.
     * @param synchronization the synchronization
     * @param throwable the error that prevented the synchronization to complete, null if there is none
     * @return the status
     */
    static SyncStatus toStatus(Synchronization synchronization, Throwable throwable) {
        final Statistics statistics = synchronization.getStatistics();
        if (throwable != null) {
            final Throwable cause = throwable instanceof CompletionException && throwable.getCause() != null ? throwable.getCause() : throwable;
            log.warn("Synchronization failed", cause);
            return SyncStatus.failed(String.valueOf(cause.getMessage()), statistics);
        }
        if (synchronization.isCancelled()) {
            log.debug("Synchronization cancelled");
            return SyncStatus.cancelled(statistics);
        }
        final List<Throwable> errors = synchronization.getErrors();
        if (errors.isEmpty()) {
            log.debug("Synchronization completed successfully");
            return SyncStatus.completed(statistics);
        }
        log.warn("Synchronization completed with {} error(s)", errors.size());
        return SyncStatus.failed("Synchronization failed with " + errors.size() + " error(s), the first one is: " + errors.get(0).getMessage(), statistics);
    }

    /**
     * Builds a synchronization (it is not started).
     * <br>The providers are closed if the synchronization can't be built.
     */
    private Running build(SyncRequest request) throws IOException {
        final Folder sourceEntry = request.source().entry(true);
        Folder destinationEntry = null;
        try {
            destinationEntry = request.destination().entry(false);
            final SyncParameters syncParameters = new SyncParameters();
            if (request.checksum()) {
                final HashAlgorithm hash = FileComparator.hash(sourceEntry.getFileProvider(), destinationEntry.getFileProvider());
                if (hash == null) {
                    // Refused now, otherwise the comparison of each file would fail
                    throw new IOException("The checksums can't be compared: the source and the destination have no hash algorithm in common");
                }
                syncParameters.fileComparator(FileComparator.of(List.of(FileComparator.SIZE, FileComparator.MOD_DATE, FileComparator.hash(hash))));
            }
            // The rules of the library, then the global ones
            final FilterRules filters = FilterRules.of(config.filters(request.library()));
            if (!filters.isEmpty()) {
                syncParameters.filter(filters.toEntryFilter());
            }
            syncParameters.errorManager((e, action) -> {
                log.warn("action {} failed", action, e);
                // Do not cancel the synchronization: continue with the other entries, the failed ones will be retried by a new pass if the error can be transient
                return false;
            });
            syncParameters.performance().fastList(true).maxWalkThreads(4).maxComparisonThreads(8).maxCopyThreads(5);
            syncParameters.eventListener(event -> log.debug("event: {} {}", event.action().getClass().getSimpleName(), event.status()));
            parametersCustomizer.accept(syncParameters);
            final Synchronization synchronization = new Synchronization(sourceEntry, destinationEntry, syncParameters);
            return new Running(request, synchronization, sourceEntry.getFileProvider(), destinationEntry.getFileProvider());
        } catch (IOException | RuntimeException e) {
            sourceEntry.getFileProvider().close();
            if (destinationEntry != null) {
                destinationEntry.getFileProvider().close();
            }
            throw e;
        }
    }

    // For tests
    void customizeParameters(Consumer<SyncParameters> customizer) {
        this.parametersCustomizer = customizer;
    }

    /**
     * An unchecked {@link InterruptedException}.
     */
    public static class UncheckedInterruptedException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public UncheckedInterruptedException(InterruptedException e) {
            super(e);
        }
    }

    private Synchronization waitFor(Synchronization synchronization) {
        try {
            synchronization.waitFor();
            return synchronization;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            synchronization.cancel();
            throw new UncheckedInterruptedException(e);
        }
    }

    /**
     * Gets the libraries.
     * @return the libraries
     */
    public List<Library> getLibraries() {
        return config.libraries();
    }

    public Map<String, SyncStatus> getSyncStatuses() {
        return syncStatuses;
    }

    public SyncStatus getSyncStatus(String libraryId) {
        return syncStatuses.getOrDefault(libraryId, SyncStatus.idle());
    }

    /**
     * Cancels the synchronization of a library.
     * @param libraryId the id of the library
     * @return true if a synchronization was running
     */
    public boolean cancel(String libraryId) {
        final Running running = runningSyncs.get(libraryId);
        if (running == null) {
            return false;
        }
        running.synchronization().cancel();
        return true;
    }

    @Override
    public void close() {
        log.info("Shutting down the synchronization manager");
        // Cancel all running syncs
        runningSyncs.values().forEach(r -> r.synchronization().cancel());
        // Shutdown the executor services
        executorService.shutdownNow();
        statsScheduler.shutdownNow();
    }
}
