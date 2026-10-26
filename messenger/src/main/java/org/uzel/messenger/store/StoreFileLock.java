package org.uzel.messenger.store;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Exclusive OS lock remains held until the native database connection closes. */
public final class StoreFileLock implements AutoCloseable {
    private final Path database;
    private final FileChannel channel;
    private final FileLock lock;

    public StoreFileLock(Path path) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        Files.createDirectories(absolute.getParent());
        database = Files.exists(absolute) ? absolute.toRealPath()
                : absolute.getParent().toRealPath().resolve(absolute.getFileName());
        channel = FileChannel.open(database.resolveSibling(database.getFileName() + ".lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            FileLock acquired;
            try { acquired = channel.tryLock(); } catch (OverlappingFileLockException e) {
                throw new IOException("Message store is already open", e);
            }
            if (acquired == null) throw new IOException("Message store is already open");
            lock = acquired;
        } catch (IOException | RuntimeException e) {
            try { channel.close(); } catch (IOException closeFailure) { e.addSuppressed(closeFailure); }
            throw e;
        }
    }
    public Path database() { return database; }
    @Override public void close() throws IOException {
        IOException failure = null;
        try { lock.release(); } catch (IOException e) { failure = e; }
        try { channel.close(); } catch (IOException e) { if (failure == null) failure = e; else failure.addSuppressed(e); }
        if (failure != null) throw failure;
    }
}
