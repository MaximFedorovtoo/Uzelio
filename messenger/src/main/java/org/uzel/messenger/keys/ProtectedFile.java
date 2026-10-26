package org.uzel.messenger.keys;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.EnumSet;
import java.util.List;

/** Writes already wrapped bytes only. Caller serializes concurrent writers. */
public final class ProtectedFile {
    private ProtectedFile() { }

    public static byte[] readBounded(Path file, int maximum) throws IOException {
        if (maximum < 1 || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Protected file is missing or is not a regular file");
        }
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            long size = channel.size();
            if (size < 1 || size > maximum) throw new IOException("Invalid protected file length");
            ByteBuffer result = ByteBuffer.allocate((int) size);
            while (result.hasRemaining()) {
                if (channel.read(result) < 0) throw new IOException("Truncated protected file");
            }
            if (channel.read(ByteBuffer.allocate(1)) != -1) throw new IOException("Protected file changed while reading");
            return result.array();
        }
    }

    public static void atomicWrite(Path file, byte[] wrapped) throws IOException {
        if (wrapped == null || wrapped.length == 0) throw new IOException("Empty protected file");
        Path parent = file.toAbsolutePath().normalize().getParent();
        if (parent == null || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Missing protected directory");
        }
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)
                && !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Invalid protected file target");
        }
        Path temp = Files.createTempFile(parent, ".messenger-key-", ".tmp");
        try {
            restrictToOwner(temp); // Before writing any contents.
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                ByteBuffer data = ByteBuffer.wrap(wrapped);
                while (data.hasRemaining()) channel.write(data);
                channel.force(true);
            }
            // Fail closed on filesystems without atomic replacement; no non-atomic fallback.
            Files.move(temp, file.toAbsolutePath().normalize(), StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temp); }
    }

    public static void restrictToOwner(Path file) throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Invalid protected file");
        AclFileAttributeView acl = Files.getFileAttributeView(file, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (acl != null) {
            acl.setAcl(List.of(AclEntry.newBuilder().setType(AclEntryType.ALLOW)
                    .setPrincipal(acl.getOwner()).setPermissions(EnumSet.allOf(AclEntryPermission.class)).build()));
            return;
        }
        PosixFileAttributeView posix = Files.getFileAttributeView(file, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (posix != null) {
            posix.setPermissions(PosixFilePermissions.fromString("rw-------"));
            return;
        }
        throw new IOException("Owner-only file permissions unavailable");
    }
}
