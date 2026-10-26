package org.uzel.messenger.keys;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Objects;

public final class DeviceKeyVault {
    public static final String KEY_FILE = "messenger-db-key.v1";
    private static final int MAGIC = 0x555a4d4b; // UZMK
    private static final int VERSION = 1;
    private static final int MAX_WRAPPED = 65536;
    private DeviceKeyVault() { }

    /**
     * Returns caller-owned key bytes. Caller must clear after use. The lock serializes
     * vault creation, not database access. Database implementations need a separate lifetime lock.
     * Java/JNI/provider copies cannot be reliably wiped; arrays here are cleared best effort.
     */
    public static synchronized byte[] loadOrCreate(Path root, SecretProtector protector,
                                     boolean existingDatabase) throws Exception {
        Objects.requireNonNull(protector, "protector");
        Path directory = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
        Files.createDirectories(directory);
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Invalid vault directory");
        Path keyFile = directory.resolve(KEY_FILE);
        Path lockFile = directory.resolve("messenger-db-key.lock");
        try (FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            ProtectedFile.restrictToOwner(lockFile);
            try (FileLock ignored = channel.lock()) {
                if (Files.exists(keyFile, LinkOption.NOFOLLOW_LINKS)) {
                    ProtectedFile.restrictToOwner(keyFile);
                    byte[] envelope = ProtectedFile.readBounded(keyFile, MAX_WRAPPED + 12);
                    byte[] wrapped = null;
                    byte[] key = null;
                    try {
                        ByteBuffer input = ByteBuffer.wrap(envelope);
                        if (input.remaining() < 12 || input.getInt() != MAGIC || input.getInt() != VERSION) {
                            throw new IOException("Unsupported messenger key envelope");
                        }
                        int size = input.getInt();
                        if (size < 1 || size > MAX_WRAPPED || size != input.remaining()) {
                            throw new IOException("Invalid messenger key envelope length");
                        }
                        wrapped = new byte[size];
                        input.get(wrapped);
                        key = protector.unprotect(wrapped);
                        if (key == null || key.length != 32) throw new IOException("Invalid messenger database key");
                        byte[] result = key;
                        key = null;
                        return result;
                    } finally {
                        Arrays.fill(envelope, (byte) 0);
                        if (wrapped != null) Arrays.fill(wrapped, (byte) 0);
                        if (key != null) Arrays.fill(key, (byte) 0);
                    }
                }
                if (existingDatabase) throw new IOException("Messenger database key is missing; recovery required");
                byte[] key = new byte[32];
                byte[] wrapped = null;
                byte[] envelope = null;
                boolean success = false;
                try {
                    new SecureRandom().nextBytes(key);
                    wrapped = protector.protect(key);
                    if (wrapped == null || wrapped.length < 1 || wrapped.length > MAX_WRAPPED) {
                        throw new IOException("Invalid protected key length");
                    }
                    envelope = ByteBuffer.allocate(12 + wrapped.length).putInt(MAGIC).putInt(VERSION)
                            .putInt(wrapped.length).put(wrapped).array();
                    ProtectedFile.atomicWrite(keyFile, envelope);
                    success = true;
                    return key;
                } finally {
                    if (!success) Arrays.fill(key, (byte) 0);
                    if (wrapped != null) Arrays.fill(wrapped, (byte) 0);
                    if (envelope != null) Arrays.fill(envelope, (byte) 0);
                }
            }
        }
    }
}
