package org.uzel.messenger.store;

import org.junit.Test;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import static org.junit.Assert.*;

public class AbstractTransactionalStoreTest {
    @Test public void failedCommitNeverReturnsResultAndClosesStore() throws Exception {
        var store = new FailingBackend(); store.failCommit = true;
        assertThrows(IOException.class, () -> store.write(r -> { r.put("a", "b", new byte[]{1}); return "ack"; }));
        assertTrue(store.closed);
        assertThrows(IllegalStateException.class, () -> store.read(r -> null));
        assertTrue(store.data.isEmpty());
    }

    @Test public void caughtBackendErrorStillRollsBackAndClosesStore() throws Exception {
        var store = new FailingBackend(); store.failLoad = true;
        assertThrows(IllegalStateException.class, () -> store.write(r -> {
            r.put("a", "b", new byte[]{1});
            try { r.get("a", "b"); } catch (IOException caughtInsideCallback) { }
            return "ack";
        }));
        assertTrue(store.data.isEmpty());
        assertTrue("Backend errors invalidate the handle even if caught by caller", store.closed);
    }

    @Test public void rollbackFailurePreservesCauseAndClosesStore() throws Exception {
        var store = new FailingBackend(); store.failRollback = true;
        Exception problem = new Exception("callback failure");
        Exception found = assertThrows(Exception.class, () -> store.write(r -> { throw problem; }));
        assertSame(problem, found); assertEquals(1, found.getSuppressed().length);
        assertTrue(store.closed);
    }

    private static final class FailingBackend extends AbstractTransactionalStore {
        Map<String, byte[]> data = new LinkedHashMap<>();
        Map<String, byte[]> before;
        boolean failCommit, failRollback, failLoad, closed;
        @Override protected void begin(boolean write) { before = new LinkedHashMap<>(data); }
        @Override protected void commit() throws Exception { if (failCommit) throw new IOException("commit failure"); }
        @Override protected void rollback() throws Exception { if (failRollback) throw new IOException("rollback failure"); data = before; }
        @Override protected void closeBackend() { closed = true; }
        @Override protected byte[] load(String space, String key) throws Exception { if (failLoad) throw new IOException("read failure"); return data.get(key); }
        @Override protected void save(String space, String key, byte[] value) { data.put(key, value); }
        @Override protected void delete(String space, String key) { data.remove(key); }
        @Override protected Map<String, byte[]> scan(String space) { return data; }
    }
}
