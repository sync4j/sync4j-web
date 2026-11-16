package com.fathzer.sync4j.web.sync;

import com.fathzer.sync4j.config.FolderLocation;
import com.fathzer.sync4j.config.Library;

/**
 * A request to synchronize a library.
 * @param library the library
 * @param source the source (the source of the library, or one of its sub folders)
 * @param destination the destination (the destination of the library, or one of its sub folders)
 * @param checksum true to compare the checksums of the files
 */
public record SyncRequest(Library library, FolderLocation source, FolderLocation destination, boolean checksum) {
    /**
     * Creates a request to synchronize the whole library.
     * @param library the library
     * @param checksum true to compare the checksums of the files
     */
    public SyncRequest(Library library, boolean checksum) {
        this(library, library.source(), library.destination(), checksum);
    }
}
