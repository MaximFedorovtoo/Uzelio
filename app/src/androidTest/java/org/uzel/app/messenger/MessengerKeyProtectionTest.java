package org.uzel.app.messenger;

import android.test.AndroidTestCase;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import javax.crypto.KeyGenerator;
import org.uzel.messenger.keys.DeviceKeyVault;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Comparator;
import java.util.UUID;

/** Uses only a unique test alias and a directory under the target app's no-backup storage. */
public final class MessengerKeyProtectionTest extends AndroidTestCase {
    private String alias;
    private AndroidKeyProtector protector;
    private Path directory;

    @Override protected void setUp() throws Exception {
        super.setUp();
        alias = "org.uzel.messenger.test." + UUID.randomUUID();
        protector = new AndroidKeyProtector(alias);
        directory = Files.createTempDirectory(getContext().getNoBackupFilesDir().toPath(), "key-test-");
    }

    @Override protected void tearDown() throws Exception {
        try {
            KeyStore store = store();
            if (alias != null) store.deleteEntry(alias);
            if (directory != null) {
                try (var paths = Files.walk(directory)) {
                    for (Path path : paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new)) Files.delete(path);
                }
            }
        } finally { super.tearDown(); }
    }

    public void testKeystoreRoundtripTamperAndNonExportableKey() throws Exception {
        byte[] secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        byte[] envelope = protector.protect(secret);
        assertFalse(Arrays.equals(envelope, protector.protect(secret)));
        assertTrue(Arrays.equals(secret, new AndroidKeyProtector(alias).unprotect(envelope)));
        assertNull(store().getKey(alias, null).getEncoded());
        envelope[envelope.length - 1] ^= 1;
        expectFailure(() -> protector.unprotect(envelope));
    }

    public void testMissingAliasFailsWithoutRegeneration() throws Exception {
        byte[] envelope = protector.protect(new byte[32]);
        store().deleteEntry(alias);
        expectFailure(() -> protector.unprotect(envelope));
        assertFalse(store().containsAlias(alias));
    }

    public void testVaultRestartMissingAndCorruptKey() throws Exception {
        byte[] key = DeviceKeyVault.loadOrCreate(directory, protector, false);
        assertEquals(32, key.length);
        assertTrue(Arrays.equals(key, DeviceKeyVault.loadOrCreate(directory, new AndroidKeyProtector(alias), true)));
        Path keyFile = directory.resolve(DeviceKeyVault.KEY_FILE);
        byte[] file = Files.readAllBytes(keyFile);
        for (int i = 0; i <= file.length - key.length; i++) {
            assertFalse(Arrays.equals(key, Arrays.copyOfRange(file, i, i + key.length)));
        }
        file[file.length - 1] ^= 1;
        Files.write(keyFile, file);
        expectFailure(() -> DeviceKeyVault.loadOrCreate(directory, protector, true));
        Files.delete(keyFile);
        expectFailure(() -> DeviceKeyVault.loadOrCreate(directory, protector, true));
        assertFalse(Files.exists(keyFile));
    }

    public void testMalformedEnvelopeNeverCreatesAlias() throws Exception {
        expectFailure(() -> protector.unprotect(new byte[0]));
        expectFailure(() -> protector.unprotect(new byte[128 * 1024]));
        assertFalse(store().containsAlias(alias));
    }

    public void testExistingWrongSizeAliasFailsClosed() throws Exception {
        KeyGenerator generator = KeyGenerator.getInstance("AES", "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(alias,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(128).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
        generator.generateKey();
        expectFailure(() -> protector.protect(new byte[32]));
    }

    private static KeyStore store() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        return store;
    }

    private static void expectFailure(Checked action) throws Exception {
        try { action.run(); }
        catch (Exception expected) { return; }
        fail("Expected fail-closed rejection");
    }
    private interface Checked { void run() throws Exception; }
}
