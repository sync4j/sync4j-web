package com.fathzer.sync4j.web.rest;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fathzer.sync4j.config.Library;
import com.fathzer.sync4j.web.plugin.LibraryWarning;
import com.fathzer.sync4j.web.plugin.Message;
import com.fathzer.sync4j.web.plugin.SyncRefusedException;
import com.fathzer.sync4j.web.security.LoginManager;
import com.fathzer.sync4j.web.sync.SyncRequest;
import com.fathzer.sync4j.web.sync.SynchronizationManager;

import io.javalin.config.RoutesConfig;
import io.javalin.http.Context;

/**
 * The REST service of the libraries.
 * <ul>
 * <li>GET root: the libraries (id and label)</li>
 * <li>GET root/sync: the statuses of all the libraries</li>
 * <li>GET root/sync/{id}: the status of a library</li>
 * <li>POST root/sync/{id}: starts the synchronization of a library</li>
 * <li>DELETE root/sync/{id}: cancels the synchronization of a library</li>
 * <li>GET root/checks/{id}: the problems that the plugins found in a library, as a list of <code>{"plugin":..., "message":...}</code></li>
 * </ul>
 * All these routes need the user to be logged in (if the security is enabled): the answer is a 401 otherwise.
 */
public class SyncRestService {
    private static final String ERROR_KEY = "error";
    private static final java.security.SecureRandom RANDOM = new java.security.SecureRandom();
    private static final String STATUS_KEY = "status";
    private static final String LIBRARY_ID_KEY = "id";
    private static final String LIBRARY_LABEL_KEY = "label";

    private final SynchronizationManager manager;
    private final LoginManager loginManager;
    private final Function<Library, List<LibraryWarning>> checker;

    private final Logger log = LoggerFactory.getLogger(SyncRestService.class);

    /**
     * Constructor.
     * @param manager the synchronization manager
     * @param loginManager the login manager
     * @param checker checks a library (it returns the problems found by the plugins)
     */
    public SyncRestService(SynchronizationManager manager, LoginManager loginManager, Function<Library, List<LibraryWarning>> checker) {
        this.manager = manager;
        this.loginManager = loginManager;
        this.checker = checker;
    }

    public void registerRoutes(RoutesConfig routes, String root) {
        // Nothing that is under the root can be used without being logged in
        routes.before(root, this::verify);
        routes.before(root + "/*", this::verify);
        routes.get(root, this::getLibraries);
        final String syncPath = root + "/sync";
        routes.get(syncPath, this::getAllSyncStatus);
        final String syncLibraryPath = syncPath + "/{" + LIBRARY_ID_KEY + "}";
        routes.post(syncLibraryPath, this::startSync);
        routes.delete(syncLibraryPath, this::stopSync);
        routes.get(syncLibraryPath, this::getSyncStatus);
        routes.get(root + "/checks/{" + LIBRARY_ID_KEY + "}", this::checkLibrary);
    }

    private void checkLibrary(Context ctx) {
        final String libraryId = ctx.pathParam(LIBRARY_ID_KEY);
        final Optional<Library> library = manager.getLibraries().stream().filter(lib -> lib.id().equals(libraryId)).findFirst();
        if (library.isEmpty()) {
            ctx.status(404).json(Map.of(ERROR_KEY, Message.of("library.notFound", libraryId)));
        } else {
            ctx.json(checker.apply(library.get()));
        }
    }

    private void getLibraries(Context ctx) {
        ctx.json(manager.getLibraries().stream().map(lib -> Map.of(LIBRARY_ID_KEY, lib.id(), LIBRARY_LABEL_KEY, lib.label())).toList());
    }

    private void verify(Context ctx) {
        final String error = loginManager.checkLogin(ctx).error();
        if (error != null) {
            ctx.status(401).json(Map.of(ERROR_KEY, error));
            ctx.skipRemainingHandlers();
        }
    }

    private void startSync(Context ctx) {
        final String libraryId = ctx.pathParam(LIBRARY_ID_KEY);
        try {
            final SyncBody body = ctx.bodyAsClass(SyncBody.class);
            final Optional<Library> library = manager.getLibraries().stream().filter(lib -> lib.id().equals(libraryId)).findFirst();
            if (library.isEmpty()) {
                ctx.status(404).json(Map.of(ERROR_KEY, Message.of("library.notFound", libraryId)));
                return;
            }

            final SyncRequest request;
            if (body.suffixPath() == null) {
                request = new SyncRequest(library.get(), body.useChecksum());
            } else {
                final String suffix = toSuffix(body.suffixPath());
                if (suffix == null) {
                    ctx.status(400).json(Map.of(ERROR_KEY, Message.of("sync.invalidSuffix")));
                    return;
                }
                request = new SyncRequest(library.get(), library.get().source().withSuffix(suffix), library.get().destination().withSuffix(suffix), body.useChecksum());
            }
            manager.run(request);
            ctx.status(202).json(Map.of(LIBRARY_ID_KEY, libraryId, STATUS_KEY, "started"));
        } catch (SynchronizationManager.ConflictException e) {
            ctx.status(409).json(Map.of(ERROR_KEY, Message.of("sync.running")));
        } catch (SyncRefusedException e) {
            // A plugin refused the synchronization, and tells the user why
            log.info("The synchronization of {} is refused: {}", libraryId, e.getMessage());
            ctx.status(409).json(Map.of(ERROR_KEY, e.getUserMessage()));
        } catch (Exception e) {
            serverError(ctx, "Failed to start the synchronization of " + libraryId, e);
        }
    }

    /**
     * Answers that an error occurred on the server.
     * <br>The message of the exception can have details that the users should not see (the paths of the server, for instance): it is only logged, with a
     * reference that the user receives, so that the administrator can find it.
     */
    private void serverError(Context ctx, String description, Exception e) {
        final String reference = String.format("%06x", RANDOM.nextInt(0x1000000));
        log.error("{} (reference {})", description, reference, e);
        ctx.status(500).json(Map.of(ERROR_KEY, Message.of("error.server", reference)));
    }

    /**
     * Checks the suffix path of a request: it has to be a sub folder of the library.
     * <br>A path that could go out of the library (<code>..</code>, <code>.</code>, empty names, backslashes) is refused: the user could otherwise
     * synchronize (and delete) any folder that the server can reach.
     * @param path the path sent by the client
     * @return the suffix (<code>/my/folder</code>), null if it is not valid
     */
    static String toSuffix(String path) {
        String suffix = path.trim();
        // Leading and trailing slashes are tolerated: "/my/folder/" is "my/folder"
        while (suffix.startsWith("/")) {
            suffix = suffix.substring(1);
        }
        while (suffix.endsWith("/")) {
            suffix = suffix.substring(0, suffix.length() - 1);
        }
        if (suffix.isEmpty() || suffix.contains("\\") || suffix.indexOf('\0') >= 0) {
            return null;
        }
        for (String name : suffix.split("/")) {
            if (name.isBlank() || ".".equals(name.strip()) || "..".equals(name.strip())) {
                return null;
            }
        }
        return "/" + suffix;
    }

    private void stopSync(Context ctx) {
        final String libraryId = ctx.pathParam(LIBRARY_ID_KEY);
        if (manager.cancel(libraryId)) {
            ctx.status(202).json(Map.of(LIBRARY_ID_KEY, libraryId, STATUS_KEY, "cancelled"));
        } else {
            ctx.status(404).json(Map.of(ERROR_KEY, Message.of("sync.notRunning")));
        }
    }

    private void getSyncStatus(Context ctx) {
        ctx.json(manager.getSyncStatus(ctx.pathParam(LIBRARY_ID_KEY)));
    }

    private void getAllSyncStatus(Context ctx) {
        ctx.json(manager.getSyncStatuses());
    }

    /**
     * The body of a request to start a synchronization.
     * @param suffixPath a sub folder to synchronize (null to synchronize the whole library)
     * @param useChecksum true to compare the checksums of the files
     */
    public record SyncBody(String suffixPath, boolean useChecksum) {}
}
