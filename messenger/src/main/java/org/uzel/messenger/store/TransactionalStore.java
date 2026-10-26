package org.uzel.messenger.store;

import java.util.Map;

/** All related cryptographic state and message records belong in one write callback. */
public interface TransactionalStore extends AutoCloseable {
    <T> T read(Work<T> work) throws Exception;
    <T> T write(Work<T> work) throws Exception;
    @Override void close() throws Exception;

    @FunctionalInterface interface Work<T> { T run(Records records) throws Exception; }

    /** Valid only on the callback thread, for the duration of that callback. */
    interface Records {
        byte[] get(String space, String key) throws Exception;
        void put(String space, String key, byte[] value) throws Exception;
        void remove(String space, String key) throws Exception;
        Map<String, byte[]> list(String space) throws Exception;
    }
}
