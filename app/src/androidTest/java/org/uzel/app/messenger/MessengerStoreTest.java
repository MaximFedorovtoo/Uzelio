package org.uzel.app.messenger;

import android.test.InstrumentationTestCase;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;
import org.uzel.messenger.store.TransactionalStore;

/** Runs only when explicitly dispatched to an emulator by the coordinator. */
public final class MessengerStoreTest extends InstrumentationTestCase {
    public void testEncryptedTupleRollbackReopenAndWrongKey() throws Exception {
        File dir = new File(getInstrumentation().getTargetContext().getCacheDir(), "store-" + UUID.randomUUID());
        assertTrue(dir.mkdir()); File file = new File(dir, "messages.db"); byte[] key = new byte[32];
        byte[] marker = "UZEL_ANDROID_STORE_SENTINEL_341caeec".getBytes(StandardCharsets.UTF_8);
        try {
            try (var store = new AndroidMessageStore(file, key)) {
                store.write(r -> { for (String s : new String[]{"session", "event", "receipt"}) r.put(s, "1", marker); return null; });
                try { store.write(r -> { for (String s : new String[]{"session", "event", "receipt"}) r.put(s, "2", marker); throw new Exception("inject rollback"); }); fail(); }
                catch (Exception expected) { assertEquals("inject rollback", expected.getMessage()); }
                store.read(r -> { for (String s : new String[]{"session", "event", "receipt"}) {
                    assertTrue(java.util.Arrays.equals(marker, r.get(s, "1"))); assertNull(r.get(s, "2"));
                } return null; });
                for (File f : dir.listFiles()) if (!f.getName().endsWith("-shm") && !f.getName().endsWith(".lock"))
                    assertFalse(new String(Files.readAllBytes(f.toPath()), StandardCharsets.ISO_8859_1).contains(new String(marker, StandardCharsets.UTF_8)));
                try { new AndroidMessageStore(file, key).close(); fail("second open"); } catch (java.io.IOException expected) { }
            }
            try (var store = new AndroidMessageStore(file, key)) { assertTrue(java.util.Arrays.equals(marker, store.read(r -> r.get("event", "1")))); }
            byte[] wrong = key.clone(); wrong[0] = 1;
            try { new AndroidMessageStore(file, wrong).close(); fail("wrong key"); } catch (android.database.sqlite.SQLiteException expected) { }
            try (var store = new AndroidMessageStore(file, key)) { assertNotNull(store.read(r -> r.get("event", "1"))); }
            byte[] damaged = Files.readAllBytes(file.toPath()); damaged[4096 + 128] ^= 1; Files.write(file.toPath(), damaged);
            try { try (var store = new AndroidMessageStore(file, key)) { store.read(r -> r.get("event", "1")); } fail("tampered page"); }
            catch (android.database.sqlite.SQLiteException expected) { }
        } finally { for (File f : dir.listFiles()) Files.delete(f.toPath()); Files.delete(dir.toPath()); }
    }

    public void testCallbackLifetimeAndReadOnlyScope() throws Exception {
        File dir = new File(getInstrumentation().getTargetContext().getCacheDir(), "scope-" + UUID.randomUUID());
        assertTrue(dir.mkdir()); TransactionalStore.Records[] escaped = new TransactionalStore.Records[1];
        try {
            try (var store = new AndroidMessageStore(new File(dir, "messages.db"), new byte[32])) {
                store.read(r -> { escaped[0] = r;
                    try { r.put("a", "b", new byte[]{1}); fail(); } catch (IllegalStateException expected) { }
                    return null;
                });
                try { escaped[0].get("a", "b"); fail(); } catch (IllegalStateException expected) { }
            }
        } finally { for (File f : dir.listFiles()) Files.delete(f.toPath()); Files.delete(dir.toPath()); }
    }
}
