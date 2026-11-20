package com.fathzer.sync4j.web.sync;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fathzer.sync4j.file.LocalProvider;
import com.fathzer.sync4j.sync.Statistics;
import com.fathzer.sync4j.sync.Synchronization;
import com.fathzer.sync4j.sync.parameters.SyncParameters;
import com.fathzer.sync4j.config.FolderLocation;
import com.fathzer.sync4j.config.Library;
import com.fathzer.sync4j.config.SynchronizationConfig;
import com.fathzer.sync4j.web.sync.SynchronizationManager.ConflictException;

class SynchronizationManagerTest {
    private static final String LIBRARY_ID = "lib";

    @TempDir
    Path tmp;
    private Path source;
    private Path destination;
    private Path statusFile;
    private final AtomicInteger opened = new AtomicInteger();
    private final AtomicInteger closed = new AtomicInteger();
    private final BlockingQueue<SyncStatus> statuses = new LinkedBlockingQueue<>();
    private SynchronizationManager manager;

    @BeforeEach
    void setUp() throws IOException {
        source = Files.createDirectory(tmp.resolve("source"));
        destination = Files.createDirectory(tmp.resolve("destination"));
        statusFile = tmp.resolve("statuses.json");
        manager = newManager();
    }

    @AfterEach
    void tearDown() {
        manager.close();
    }

    /** A location whose providers are counted when they are opened and closed. */
    private FolderLocation location(Path path) {
        return new FolderLocation(r -> {
            opened.incrementAndGet();
            return new LocalProvider(Paths.get(r)) {
                @Override
                public void close() {
                    closed.incrementAndGet();
                    super.close();
                }
            };
        }, path.toString());
    }

    /** A location whose provider supports no hash algorithm. */
    private FolderLocation locationWithoutHash(Path path) {
        return new FolderLocation(r -> {
            opened.incrementAndGet();
            return new LocalProvider(Paths.get(r)) {
                @Override
                public java.util.List<com.fathzer.sync4j.HashAlgorithm> getSupportedHash() {
                    return List.of();
                }

                @Override
                public void close() {
                    closed.incrementAndGet();
                    super.close();
                }
            };
        }, path.toString());
    }

    private Library library() {
        return new Library(LIBRARY_ID, "Test library", location(source), location(destination));
    }

    private SynchronizationManager newManager() {
        return newManager(library());
    }

    private SynchronizationManager newManager(Library library) {
        final SynchronizationManager result = new SynchronizationManager(new SynchronizationConfig(List.of(library), List.of()), statusFile);
        result.setBroadcaster(new SynchronizationManager.SyncBroadcaster() {
            @Override
            public void broadcast(String libraryId, Statistics progress) {
                // Not needed
            }

            @Override
            public void broadcast(String libraryId, SyncStatus status) {
                statuses.add(status);
            }
        });
        return result;
    }

    private SyncRequest request() {
        return new SyncRequest(manager.getLibraries().get(0), false);
    }

    /** Waits for the first status that is not "syncing" */
    private SyncStatus awaitEnd() throws InterruptedException {
        final long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < end) {
            final SyncStatus status = statuses.poll(100, TimeUnit.MILLISECONDS);
            if (status != null && status.status() != SyncStatus.Status.SYNCING) {
                return status;
            }
        }
        return fail("The synchronization did not finish");
    }

    private static long syncThreads() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(t -> t.isAlive() && (t.getName().startsWith("check-") || t.getName().startsWith("copy-")))
                .count();
    }

    @Test
    void testSuccessfulSynchronizationReleasesResources() throws Exception {
        Files.writeString(source.resolve("a.txt"), "a");
        Files.createDirectory(source.resolve("sub"));
        Files.writeString(source.resolve("sub/b.txt"), "b");
        final long threadsBefore = syncThreads();

        manager.run(request());
        final SyncStatus status = awaitEnd();

        assertEquals(SyncStatus.Status.COMPLETED, status.status(), status.errorMessage());
        assertEquals("a", Files.readString(destination.resolve("a.txt")));
        assertEquals("b", Files.readString(destination.resolve("sub/b.txt")));
        assertEquals(new Statistics.Counter(2, 2), status.result().copiedFiles());
        assertEquals(SyncStatus.Status.COMPLETED, manager.getSyncStatus(LIBRARY_ID).status());

        // The providers are closed
        assertEquals(opened.get(), closed.get(), "All the providers should have been closed");
        // ... and so are the thread pools of the synchronization (they are not reused by the next one)
        final long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (syncThreads() > threadsBefore && System.nanoTime() < end) {
            Thread.sleep(50);
        }
        assertEquals(threadsBefore, syncThreads(), "The threads of the synchronization should be stopped");
    }

    @Test
    void testFiltersAreGlobalAndOfTheLibrary() throws Exception {
        Files.createDirectory(source.resolve("keep"));
        for (String name : List.of("a.txt", "b.tmp", "c.xmp", "keep/d.tmp", "keep/e.xmp")) {
            Files.writeString(source.resolve(name), name);
        }
        final Library base = library();
        final Library library = new Library(base.id(), base.label(), base.source(), base.destination(), List.of("+ /keep/**.xmp", "- *.xmp"));
        final SynchronizationManager withPatterns = new SynchronizationManager(new SynchronizationConfig(List.of(library), List.of("- *.tmp")), statusFile);
        withPatterns.setBroadcaster(new SynchronizationManager.SyncBroadcaster() {
            @Override
            public void broadcast(String libraryId, Statistics progress) {
                // Not needed
            }

            @Override
            public void broadcast(String libraryId, SyncStatus status) {
                statuses.add(status);
            }
        });

        withPatterns.run(new SyncRequest(library, false));
        final SyncStatus status = awaitEnd();

        assertEquals(SyncStatus.Status.COMPLETED, status.status(), status.errorMessage());
        assertTrue(Files.exists(destination.resolve("a.txt")));
        assertFalse(Files.exists(destination.resolve("b.tmp")), "The global pattern should apply");
        assertFalse(Files.exists(destination.resolve("c.xmp")), "The pattern of the library should apply");
        assertFalse(Files.exists(destination.resolve("keep/d.tmp")), "The global pattern should apply in sub folders");
        assertTrue(Files.exists(destination.resolve("keep/e.xmp")), "The library rules are tried first");
    }

    @Test
    void testSynchronizationContinuesAfterAnError() throws Exception {
        Files.writeString(source.resolve("a.txt"), "a");
        final Path unreadable = Files.writeString(source.resolve("b.txt"), "b");
        Files.setPosixFilePermissions(unreadable, Set.<PosixFilePermission>of());
        assumeFalse(Files.isReadable(unreadable), "The test can't run if file permissions are not enforced (for instance as root)");

        manager.run(request());
        final SyncStatus status = awaitEnd();

        // The error does not prevent to copy the other files
        assertEquals(SyncStatus.Status.FAILED, status.status());
        assertTrue(status.errorMessage().startsWith("Synchronization failed with 1 error(s)"), status.errorMessage());
        assertEquals("a", Files.readString(destination.resolve("a.txt")));
        assertFalse(Files.exists(destination.resolve("b.txt")));
        assertEquals(opened.get(), closed.get());
    }

    @Test
    void testStatusOfAnInterruptedSynchronization() throws Exception {
        try (Synchronization synchronization = new Synchronization(location(source).entry(true), location(destination).entry(false), new SyncParameters())) {
            // When the future fails, no result is available: the status is built from the synchronization
            SyncStatus status = SynchronizationManager.toStatus(synchronization, new CompletionException(new IOException("boom")));
            assertEquals(SyncStatus.Status.FAILED, status.status());
            assertEquals("boom", status.errorMessage());
            assertSame(synchronization.getStatistics(), status.result());

            assertEquals(SyncStatus.Status.COMPLETED, SynchronizationManager.toStatus(synchronization, null).status());
            synchronization.cancel();
            assertEquals(SyncStatus.Status.CANCELLED, SynchronizationManager.toStatus(synchronization, null).status());
        }
    }

    @Test
    void testRunningSynchronizationCanBeCancelled() throws Exception {
        // The synchronization is blocked by the listener of its events
        final CountDownLatch gate = new CountDownLatch(1);
        manager.customizeParameters(p -> p.eventListener(e -> {
            try {
                gate.await();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }));
        Files.writeString(source.resolve("a.txt"), "a");
        try {
            manager.run(request());

            // The synchronization is registered as soon as it runs
            assertEquals(SyncStatus.Status.SYNCING, manager.getSyncStatus(LIBRARY_ID).status());
            assertThrows(ConflictException.class, () -> manager.run(request()));
            assertFalse(manager.cancel("unknown"));
            assertTrue(manager.cancel(LIBRARY_ID));
        } finally {
            gate.countDown();
        }
        assertEquals(SyncStatus.Status.CANCELLED, awaitEnd().status());
        assertEquals(opened.get(), closed.get());
        // Once finished, another synchronization can be started
        assertDoesNotThrow(() -> manager.run(request()));
        awaitEnd();
    }

    @Test
    void testProvidersAreClosedIfTheSynchronizationCanNotBeBuilt() throws IOException {
        final FolderLocation failingDestination = new FolderLocation(r -> {
            throw new IOException("Destination is not available");
        }, destination.toString());
        final Library library = new Library("broken", "Broken", location(source), failingDestination);

        final IOException e = assertThrows(IOException.class, () -> manager.run(new SyncRequest(library, false)));

        assertEquals("Destination is not available", e.getMessage());
        assertEquals(opened.get(), closed.get(), "The source provider should be closed");
        assertEquals(SyncStatus.Status.IDLE, manager.getSyncStatus("broken").status());
        // The failed library is not considered as running
        assertThrows(IOException.class, () -> manager.run(new SyncRequest(library, false)));
    }

    @Test
    void testChecksumsComparisonIsRefusedIfThereIsNoCommonHashAlgorithm() throws Exception {
        Files.writeString(source.resolve("a.txt"), "a");
        Files.writeString(destination.resolve("a.txt"), "xx");
        final Library library = new Library("nohash", "No hash", location(source), locationWithoutHash(destination));

        // With the checksums, it can't be compared: it is refused before anything starts, with a message that tells why
        final IOException e = assertThrows(IOException.class, () -> manager.run(new SyncRequest(library, true)));

        assertTrue(e.getMessage().contains("no hash algorithm in common"), e.getMessage());
        assertEquals(opened.get(), closed.get(), "The providers should be closed");
        assertEquals(SyncStatus.Status.IDLE, manager.getSyncStatus("nohash").status());
        assertEquals("xx", Files.readString(destination.resolve("a.txt")), "Nothing should be synchronized");

        // Without the checksums, the same library can be synchronized
        final SynchronizationManager other = newManager(library);
        other.run(new SyncRequest(library, false));
        assertEquals(SyncStatus.Status.COMPLETED, awaitEnd().status());
        assertEquals("a", Files.readString(destination.resolve("a.txt")));
        other.close();
    }

    @Test
    void testChecksumsDetectAFileThatHasTheSameSizeAndDate() throws Exception {
        final Path sourceFile = Files.writeString(source.resolve("a.txt"), "aaa");
        final Path destinationFile = Files.writeString(destination.resolve("a.txt"), "bbb");
        Files.setLastModifiedTime(destinationFile, Files.getLastModifiedTime(sourceFile));

        // Without the checksums, the files look the same
        manager.run(request());
        assertEquals(SyncStatus.Status.COMPLETED, awaitEnd().status());
        assertEquals("bbb", Files.readString(destinationFile));

        // ... but not with them
        manager.run(new SyncRequest(manager.getLibraries().get(0), true));
        assertEquals(SyncStatus.Status.COMPLETED, awaitEnd().status());
        assertEquals("aaa", Files.readString(destinationFile));
    }

    @Test
    void testStatusesAreSaved() throws Exception {
        Files.writeString(source.resolve("a.txt"), "a");
        manager.run(request());
        assertEquals(SyncStatus.Status.COMPLETED, awaitEnd().status());
        // The statuses are saved after they are broadcast
        final long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!Files.exists(statusFile) && System.nanoTime() < end) {
            Thread.sleep(20);
        }

        try (SynchronizationManager other = newManager()) {
            final SyncStatus status = other.getSyncStatus(LIBRARY_ID);
            assertEquals(SyncStatus.Status.COMPLETED, status.status());
            assertEquals(new Statistics.Counter(1, 1), status.result().copiedFiles());
        }
    }

    @Test
    void testLibraryWithSuffixDoesNotChangeTheConfiguration() throws Exception {
        Files.createDirectory(source.resolve("sub"));
        Files.writeString(source.resolve("sub/a.txt"), "a");
        Files.writeString(source.resolve("root.txt"), "root");
        final Library library = manager.getLibraries().get(0);

        // A first synchronization with a suffix...
        manager.run(new SyncRequest(library, library.source().withSuffix("sub"), library.destination().withSuffix("sub"), false));
        assertEquals(SyncStatus.Status.COMPLETED, awaitEnd().status());
        assertTrue(Files.exists(destination.resolve("sub/a.txt")));
        assertFalse(Files.exists(destination.resolve("root.txt")));

        // ... does not prevent the next one, without suffix, to synchronize the whole library
        manager.run(request());
        assertEquals(SyncStatus.Status.COMPLETED, awaitEnd().status());
        assertTrue(Files.exists(destination.resolve("root.txt")));
    }

    @Test
    void testListenerCanRefuseASynchronization() throws Exception {
        manager.setListener(new SyncListener() {
            @Override
            public void beforeSync(SyncRequest request) throws IOException {
                throw new IOException("Not now");
            }
        });

        final IOException e = assertThrows(IOException.class, () -> manager.run(request()));

        assertEquals("Not now", e.getMessage());
        // Nothing was opened and the library is not running
        assertEquals(0, opened.get());
        assertEquals(SyncStatus.Status.IDLE, manager.getSyncStatus(LIBRARY_ID).status());
        manager.setListener(new SyncListener() {});
        assertDoesNotThrow(() -> manager.run(request()));
        awaitEnd();
    }

    @Test
    void testListenerIsNotifiedAfterTheResourcesAreReleased() throws Exception {
        Files.writeString(source.resolve("a.txt"), "a");
        final List<String> calls = new CopyOnWriteArrayList<>();
        final List<SyncStatus> notified = new ArrayList<>();
        final AtomicInteger closedWhenNotified = new AtomicInteger(-1);
        final AtomicInteger openedWhenNotified = new AtomicInteger(-1);
        manager.setListener(new SyncListener() {
            @Override
            public void beforeSync(SyncRequest request) {
                calls.add("before " + request.library().id());
            }

            @Override
            public void afterSync(SyncRequest request, SyncStatus status) {
                calls.add("after " + request.library().id());
                synchronized (notified) {
                    notified.add(status);
                }
                closedWhenNotified.set(closed.get());
                openedWhenNotified.set(opened.get());
            }
        });

        manager.run(request());
        final SyncStatus status = awaitEnd();

        assertEquals(List.of("before lib", "after lib"), calls);
        assertEquals(SyncStatus.Status.COMPLETED, status.status());
        synchronized (notified) {
            assertEquals(1, notified.size());
            // The listener receives the final status
            assertEquals(SyncStatus.Status.COMPLETED, notified.get(0).status());
        }
        assertEquals(openedWhenNotified.get(), closedWhenNotified.get(), "The resources should be released when the listener is notified");
    }

    @Test
    void testFailingListenerDoesNotHideTheStatus() throws Exception {
        manager.setListener(new SyncListener() {
            @Override
            public void afterSync(SyncRequest request, SyncStatus status) {
                throw new IllegalStateException("Listener failure");
            }
        });

        manager.run(request());

        assertEquals(SyncStatus.Status.COMPLETED, awaitEnd().status());
        assertEquals(SyncStatus.Status.COMPLETED, manager.getSyncStatus(LIBRARY_ID).status());
        assertEquals(opened.get(), closed.get());
    }
}
