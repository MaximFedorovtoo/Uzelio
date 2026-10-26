package org.uzel.messenger.keys;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.io.File;
import java.security.MessageDigest;
import static org.junit.Assert.*;

public class DeviceKeyVaultTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private final SecretProtector protector = new TestProtector();

    @Test public void restartReturnsSameRandomKeyAndFileHasNoPlaintext() throws Exception {
        Path root = temporary.newFolder().toPath();
        byte[] key = DeviceKeyVault.loadOrCreate(root, protector, false);
        assertEquals(32, key.length);
        assertFalse(Arrays.equals(new byte[32], key));
        assertArrayEquals(key, DeviceKeyVault.loadOrCreate(root, protector, true));
        byte[] file = Files.readAllBytes(root.resolve(DeviceKeyVault.KEY_FILE));
        assertFalse(contains(file, key));
    }

    @Test public void missingKeyForExistingDatabaseNeverGenerates() throws Exception {
        Path root = temporary.newFolder().toPath();
        assertThrows(Exception.class, () -> DeviceKeyVault.loadOrCreate(root, protector, true));
        assertFalse(Files.exists(root.resolve(DeviceKeyVault.KEY_FILE)));
    }

    @Test public void corruptKeyNeverRegeneratesEvenWithoutDatabase() throws Exception {
        Path root = temporary.newFolder().toPath();
        DeviceKeyVault.loadOrCreate(root, protector, false);
        Path file = root.resolve(DeviceKeyVault.KEY_FILE);
        byte[] corrupt = Files.readAllBytes(file);
        corrupt[corrupt.length - 1] ^= 1;
        Files.write(file, corrupt);
        assertThrows(Exception.class, () -> DeviceKeyVault.loadOrCreate(root, protector, true));
        assertThrows(Exception.class, () -> DeviceKeyVault.loadOrCreate(root, protector, false));
        assertArrayEquals(corrupt, Files.readAllBytes(file));
    }

    @Test public void oversizedAndTruncatedEnvelopeFailClosed() throws Exception {
        Path root = temporary.newFolder().toPath();
        Path file = root.resolve(DeviceKeyVault.KEY_FILE);
        Files.write(file, new byte[128 * 1024]);
        assertThrows(Exception.class, () -> DeviceKeyVault.loadOrCreate(root, protector, false));
        Files.write(file, new byte[] {1, 2, 3});
        assertThrows(Exception.class, () -> DeviceKeyVault.loadOrCreate(root, protector, false));
    }

    @Test public void protectorFailureDoesNotPublishKey() throws Exception {
        Path root = temporary.newFolder().toPath();
        SecretProtector failing = new SecretProtector() {
            public byte[] protect(byte[] p) throws Exception { throw new Exception("locked"); }
            public byte[] unprotect(byte[] e) throws Exception { throw new Exception("locked"); }
        };
        assertThrows(Exception.class, () -> DeviceKeyVault.loadOrCreate(root, failing, false));
        assertFalse(Files.exists(root.resolve(DeviceKeyVault.KEY_FILE)));
    }

    @Test public void concurrentCreationKeepsOneKey() throws Exception {
        Path root = temporary.newFolder().toPath();
        var executor = Executors.newFixedThreadPool(4);
        try {
            Callable<byte[]> create = () -> DeviceKeyVault.loadOrCreate(root, protector, false);
            var results = executor.invokeAll(java.util.Collections.nCopies(12, create));
            byte[] first = results.get(0).get();
            for (var result : results) assertArrayEquals(first, result.get());
        } finally { executor.shutdownNow(); }
    }

    @Test public void independentProcessesCreateOneVault() throws Exception {
        Path root = temporary.newFolder().toPath();
        String classpath = String.join(File.pathSeparator,
                location(DeviceKeyVault.class), location(DeviceKeyVaultTest.class),
                location(org.junit.Test.class), location(org.hamcrest.Matcher.class));
        var children = new java.util.ArrayList<Process>();
        try {
            for (int i = 0; i < 4; i++) {
                children.add(new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                        "-cp", classpath, CreationProcess.class.getName(), root.toString())
                        .redirectError(ProcessBuilder.Redirect.INHERIT).start());
            }
            String first = null;
            for (Process child : children) {
                assertTrue("Vault process timed out", child.waitFor(30, TimeUnit.SECONDS));
                assertEquals(0, child.exitValue());
                String digest = new String(child.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.US_ASCII).trim();
                assertEquals(64, digest.length());
                if (first == null) first = digest;
                else assertEquals(first, digest);
            }
        } finally { for (Process child : children) if (child.isAlive()) child.destroyForcibly(); }
    }

    private static String location(Class<?> type) throws Exception {
        return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
    }

    public static final class CreationProcess {
        public static void main(String[] args) throws Exception {
            TestProtector protector = new TestProtector();
            // Deliberately fixed wrapping key exists only in this test process fixture.
            Arrays.fill(protector.wrappingKey, (byte) 0x24);
            byte[] key = DeviceKeyVault.loadOrCreate(Path.of(args[0]), protector, false);
            System.out.print(java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key)));
            Arrays.fill(key, (byte) 0);
        }
    }

    private static boolean contains(byte[] haystack, byte[] needle) {
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            if (Arrays.equals(Arrays.copyOfRange(haystack, i, i + needle.length), needle)) return true;
        }
        return false;
    }

    private static final class TestProtector implements SecretProtector {
        private final byte[] wrappingKey = new byte[32];
        private TestProtector() { new SecureRandom().nextBytes(wrappingKey); }
        public byte[] protect(byte[] plain) throws Exception {
            byte[] iv = new byte[12];
            new SecureRandom().nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(wrappingKey, "AES"), new GCMParameterSpec(128, iv));
            byte[] encrypted = cipher.doFinal(plain);
            return ByteBuffer.allocate(12 + encrypted.length).put(iv).put(encrypted).array();
        }
        public byte[] unprotect(byte[] wrapped) throws Exception {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(wrappingKey, "AES"),
                    new GCMParameterSpec(128, Arrays.copyOf(wrapped, 12)));
            return cipher.doFinal(wrapped, 12, wrapped.length - 12);
        }
    }
}
