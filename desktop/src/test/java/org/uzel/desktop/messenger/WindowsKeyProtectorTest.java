package org.uzel.desktop.messenger;

import org.junit.Test;
import java.security.SecureRandom;
import java.util.Arrays;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import org.uzel.messenger.keys.DeviceKeyVault;
import org.uzel.messenger.keys.ProtectedFile;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

public class WindowsKeyProtectorTest {
    @Test public void realDpapiSurvivesNewProtectorAndRejectsTampering() throws Exception {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"));
        byte[] secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        WindowsKeyProtector protector = new WindowsKeyProtector();
        byte[] wrapped = protector.protect(secret);
        assertFalse(Arrays.equals(secret, wrapped));
        assertArrayEquals(secret, new WindowsKeyProtector().unprotect(wrapped));
        wrapped[wrapped.length - 1] ^= 1;
        assertThrows(Exception.class, () -> protector.unprotect(wrapped));
    }

    @Test public void rejectsMalformedAndUnboundedInput() {
        WindowsKeyProtector protector = new WindowsKeyProtector();
        assertThrows(Exception.class, () -> protector.unprotect(new byte[0]));
        assertThrows(Exception.class, () -> protector.unprotect(new byte[128 * 1024]));
        assertThrows(Exception.class, () -> protector.protect(new byte[0]));
        assertThrows(Exception.class, () -> protector.protect(new byte[128 * 1024]));
    }

    @Test public void vaultUsesOnlyOwnerAclAndDpapiProtectedContents() throws Exception {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"));
        Path root = Files.createTempDirectory("uzel-key-acl-test");
        try {
            byte[] key = DeviceKeyVault.loadOrCreate(root, new WindowsKeyProtector(), false);
            assertArrayEquals(key, DeviceKeyVault.loadOrCreate(root, new WindowsKeyProtector(), true));
            Path file = root.resolve(DeviceKeyVault.KEY_FILE);
            AclFileAttributeView acl = Files.getFileAttributeView(file, AclFileAttributeView.class);
            assertFalse(acl.getAcl().isEmpty());
            for (var entry : acl.getAcl()) {
                assertEquals(AclEntryType.ALLOW, entry.type());
                assertEquals(acl.getOwner(), entry.principal());
            }
            byte[] bytes = Files.readAllBytes(file);
            for (int i = 0; i <= bytes.length - key.length; i++) {
                assertFalse(Arrays.equals(key, Arrays.copyOfRange(bytes, i, i + key.length)));
            }
            Arrays.fill(key, (byte) 0);
        } finally {
            Files.deleteIfExists(root.resolve(DeviceKeyVault.KEY_FILE));
            Files.deleteIfExists(root.resolve("messenger-db-key.lock"));
            Files.delete(root);
        }
    }

    @Test public void protectedFileAtomicReplacementPreservesBoundsAndPermissions() throws Exception {
        Path root = Files.createTempDirectory("uzel-protected-file-test");
        Path target = root.resolve("anchor");
        try {
            ProtectedFile.atomicWrite(target, new byte[] {1, 2, 3});
            assertArrayEquals(new byte[] {1, 2, 3}, ProtectedFile.readBounded(target, 3));
            assertThrows(Exception.class, () -> ProtectedFile.readBounded(target, 2));
            ProtectedFile.atomicWrite(target, new byte[] {4, 5});
            assertArrayEquals(new byte[] {4, 5}, ProtectedFile.readBounded(target, 3));
        } finally { Files.deleteIfExists(target); Files.delete(root); }
    }
}
