package org.uzel.desktop.messenger;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.uzel.messenger.store.TransactionalStore;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

public class JdbcMessageStoreTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private static final byte[] KEY = new byte[32];
    private static final byte[] MARK = "UZEL_ENCRYPTED_MESSAGE_SENTINEL_7304f749".getBytes(StandardCharsets.UTF_8);

    @Test public void commitReopenAndRollbackEntireTuple() throws Exception {
        Path db = temp.getRoot().toPath().resolve("messages.db");
        try (var store = new JdbcMessageStore(db, KEY)) {
            store.write(r -> { for (String s : new String[]{"session", "event", "receipt"}) r.put(s, "1", MARK); return null; });
            assertThrows(Exception.class, () -> store.write(r -> {
                for (String s : new String[]{"session", "event", "receipt"}) r.put(s, "2", MARK);
                throw new Exception("injected callback failure");
            }));
            store.read(r -> { for (String s : new String[]{"session", "event", "receipt"}) {
                assertArrayEquals(MARK, r.get(s, "1")); assertNull(r.get(s, "2"));
            } return null; });
            try (var files = Files.list(db.getParent())) {
                for (Path p : files.filter(p -> !p.toString().endsWith("-shm") && !p.toString().endsWith(".lock")).toList())
                    assertFalse("plaintext in " + p, new String(Files.readAllBytes(p), StandardCharsets.ISO_8859_1)
                            .contains(new String(MARK, StandardCharsets.UTF_8)));
            }
        }
        try (var store = new JdbcMessageStore(db, KEY)) {
            assertArrayEquals(MARK, store.read(r -> r.get("event", "1")));
        }
        byte[] wrong = KEY.clone(); wrong[0] = 1;
        assertThrows(Exception.class, () -> new JdbcMessageStore(db, wrong));
        try (var store = new JdbcMessageStore(db, KEY)) { assertNotNull(store.read(r -> r.get("event", "1"))); }
    }

    @Test public void callbackIsThreadBoundReadOnlyAndCannotEscape() throws Exception {
        try (var store = new JdbcMessageStore(temp.getRoot().toPath().resolve("scope.db"), KEY)) {
            AtomicReference<TransactionalStore.Records> escaped = new AtomicReference<>();
            store.read(r -> {
                escaped.set(r);
                assertThrows(IllegalStateException.class, () -> r.put("a", "b", MARK));
                assertThrows(IllegalStateException.class, () -> r.remove("a", "b"));
                var check = new java.util.concurrent.FutureTask<>(() -> {
                    assertThrows(IllegalStateException.class, () -> r.get("a", "b")); return null;
                });
                Thread thread = new Thread(check);
                thread.start(); thread.join(); check.get();
                assertThrows(IllegalStateException.class, () -> store.read(inner -> null));
                return null;
            });
            assertThrows(IllegalStateException.class, () -> escaped.get().get("a", "b"));
            byte[] bytes = {1, 2};
            store.write(r -> { r.put("a", "b", bytes); bytes[0] = 9;
                byte[] found = r.get("a", "b"); found[0] = 7;
                r.list("a").get("b")[0] = 8;
                assertArrayEquals(new byte[]{1, 2}, r.get("a", "b")); return null; });
            assertThrows(IllegalArgumentException.class, () -> store.read(r -> r.get("a", "x".repeat(513))));
            store.write(r -> { r.remove("a", "b"); return null; });
            assertNull(store.read(r -> r.get("a", "b")));
        }
    }

    @Test public void exclusiveOpenAndTamperedDataFailClosed() throws Exception {
        Path db = temp.getRoot().toPath().resolve("lock.db");
        try (var store = new JdbcMessageStore(db, KEY)) {
            assertThrows(Exception.class, () -> new JdbcMessageStore(db, KEY));
            store.write(r -> { byte[] large = new byte[12000]; Arrays.fill(large, (byte) 77); r.put("a", "b", large); return null; });
        }
        byte[] encrypted = Files.readAllBytes(db); encrypted[4096 + 128] ^= 1; Files.write(db, encrypted);
        assertThrows(Exception.class, () -> {
            try (var store = new JdbcMessageStore(db, KEY)) { store.read(r -> r.get("a", "b")); }
        });
        assertThrows(IllegalArgumentException.class, () -> new JdbcMessageStore(db, new byte[0]));
    }

    @Test public void processCrashBeforeAndAfterCommitPreservesWholeTuple() throws Exception {
        Path db = temp.getRoot().toPath().resolve("crash.db");
        try (var store = new JdbcMessageStore(db, KEY)) {
            assertEquals(74, child("lock", db));
        }
        assertEquals(73, child("before", db));
        try (var store = new JdbcMessageStore(db, KEY)) {
            store.read(r -> { for (String s : new String[]{"session", "event", "receipt"}) assertNull(r.get(s, "crash")); return null; });
        }
        assertEquals(73, child("after", db));
        try (var store = new JdbcMessageStore(db, KEY)) {
            store.read(r -> { for (String s : new String[]{"session", "event", "receipt"}) assertArrayEquals(MARK, r.get(s, "crash")); return null; });
        }
    }

    private int child(String mode, Path database) throws Exception {
        java.util.LinkedHashSet<String> entries = new java.util.LinkedHashSet<>();
        entries.add(System.getProperty("java.class.path"));
        for (Class<?> type : new Class<?>[]{getClass(), TransactionalStore.class, JdbcMessageStore.class,
                org.sqlite.JDBC.class, org.slf4j.Logger.class, org.junit.Test.class})
            entries.add(Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString());
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                "-cp", String.join(java.io.File.pathSeparator, entries), getClass().getName(), mode, database.toString())
                .redirectErrorStream(true).start();
        if (!process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)) { process.destroyForcibly(); fail("Storage child timed out"); }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue("Unexpected storage child exit: " + output, process.exitValue() == 73 || process.exitValue() == 74);
        return process.exitValue();
    }

    public static void main(String[] arguments) throws Exception {
        if (arguments[0].equals("lock")) {
            try (var ignored = new JdbcMessageStore(Path.of(arguments[1]), KEY)) { System.exit(1); }
            catch (java.io.IOException expected) { System.exit(74); }
        }
        var store = new JdbcMessageStore(Path.of(arguments[1]), KEY);
        store.write(r -> {
            for (String s : new String[]{"session", "event", "receipt"}) r.put(s, "crash", MARK);
            if (arguments[0].equals("before")) Runtime.getRuntime().halt(73);
            return null;
        });
        Runtime.getRuntime().halt(73);
    }
}
