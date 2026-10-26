package org.uzel.messenger.store;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Shared lifetime and failure rules; subclasses keep native handles private. */
public abstract class AbstractTransactionalStore implements TransactionalStore {
    public static final int MAX_VALUE_BYTES = 4 * 1024 * 1024;
    private boolean closed;
    private boolean active;

    protected abstract void begin(boolean write) throws Exception;
    protected abstract void commit() throws Exception;
    protected abstract void rollback() throws Exception;
    protected abstract void closeBackend() throws Exception;
    protected abstract byte[] load(String space, String key) throws Exception;
    protected abstract void save(String space, String key, byte[] value) throws Exception;
    protected abstract void delete(String space, String key) throws Exception;
    protected abstract Map<String, byte[]> scan(String space) throws Exception;

    @Override public final synchronized <T> T read(Work<T> work) throws Exception { return transact(work, false); }
    @Override public final synchronized <T> T write(Work<T> work) throws Exception { return transact(work, true); }

    private <T> T transact(Work<T> work, boolean write) throws Exception {
        Objects.requireNonNull(work, "work");
        if (closed) throw new IllegalStateException("Store is closed");
        if (active) throw new IllegalStateException("Nested store callbacks are forbidden");
        active = true;
        Scope scope = new Scope(write);
        boolean begun = false;
        boolean committing = false;
        try {
            begin(write); begun = true;
            T result = work.run(scope);
            scope.valid = false;
            if (scope.failure != null) throw new IllegalStateException("Record operation failed", scope.failure);
            committing = true;
            commit();
            return result;
        } catch (Exception | Error failure) {
            scope.valid = false;
            boolean rollbackFailed = false;
            if (begun) try { rollback(); } catch (Exception | Error rollbackFailure) {
                failure.addSuppressed(rollbackFailure); rollbackFailed = true;
            }
            if (committing || rollbackFailed || !begun || scope.failure != null) {
                closed = true;
                try { closeBackend(); } catch (Exception | Error closeFailure) { failure.addSuppressed(closeFailure); }
            }
            throw failure;
        } finally { scope.valid = false; active = false; }
    }

    @Override public final synchronized void close() throws Exception {
        if (active) throw new IllegalStateException("Cannot close inside a callback");
        if (!closed) { closed = true; closeBackend(); }
    }

    public static void validateKey(byte[] key) {
        if (key == null || key.length != 32) throw new IllegalArgumentException("Database key must contain 32 bytes");
    }

    /** A random key is encoded as a passphrase identically by both backends. */
    public static String hexPassphrase(byte[] key) {
        validateKey(key);
        char[] out = new char[64]; char[] alphabet = "0123456789abcdef".toCharArray();
        for (int i = 0; i < key.length; i++) { out[i * 2] = alphabet[(key[i] & 255) >>> 4]; out[i * 2 + 1] = alphabet[key[i] & 15]; }
        return new String(out);
    }

    private static void name(String value, int max) {
        if (value == null || value.isEmpty() || value.length() > max || value.indexOf('\0') >= 0)
            throw new IllegalArgumentException("Invalid record name");
    }

    private final class Scope implements Records {
        private final boolean write;
        private final Thread thread = Thread.currentThread();
        private volatile boolean valid = true;
        private Throwable failure;
        private Scope(boolean write) { this.write = write; }
        private void check(boolean mutation) {
            if (!valid || Thread.currentThread() != thread) throw new IllegalStateException("Record callback has ended or changed thread");
            if (mutation && !write) throw new IllegalStateException("Read callback cannot change records");
        }
        private <T> T operation(Checked<T> operation) throws Exception {
            try { return operation.run(); } catch (Exception | Error e) { failure = e; throw e; }
        }
        @Override public byte[] get(String space, String key) throws Exception {
            check(false); name(space, 128); name(key, 512);
            return operation(() -> { byte[] value = load(space, key); return value == null ? null : value.clone(); });
        }
        @Override public void put(String space, String key, byte[] value) throws Exception {
            check(true); name(space, 128); name(key, 512);
            if (value == null || value.length > MAX_VALUE_BYTES) throw new IllegalArgumentException("Invalid record value size");
            byte[] copy = value.clone();
            operation(() -> { save(space, key, copy); return null; });
        }
        @Override public void remove(String space, String key) throws Exception {
            check(true); name(space, 128); name(key, 512);
            operation(() -> { delete(space, key); return null; });
        }
        @Override public Map<String, byte[]> list(String space) throws Exception {
            check(false); name(space, 128);
            return operation(() -> { Map<String, byte[]> result = new LinkedHashMap<>();
                for (var entry : scan(space).entrySet()) result.put(entry.getKey(), entry.getValue().clone());
                return result;
            });
        }
    }
    @FunctionalInterface private interface Checked<T> { T run() throws Exception; }
}
