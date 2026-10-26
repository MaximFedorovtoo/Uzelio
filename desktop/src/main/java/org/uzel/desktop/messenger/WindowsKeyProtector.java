package org.uzel.desktop.messenger;

import org.uzel.messenger.keys.SecretProtector;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;

public final class WindowsKeyProtector implements SecretProtector {
    private static final int MAGIC = 0x44504131; // DPA1
    private static final int MAX_PLAIN = 4096;
    private static final int MAX_WRAPPED = 65528;
    private static final int UI_FORBIDDEN = 1; // CurrentUser: deliberately no LOCAL_MACHINE flag.
    private static final byte[] ENTROPY = "org.uzel.messenger.db-key.v1/windows-dpapi".getBytes(StandardCharsets.UTF_8);

    public byte[] protect(byte[] plaintext) throws Exception {
        if (plaintext == null || plaintext.length < 1 || plaintext.length > MAX_PLAIN) {
            throw new GeneralSecurityException("Invalid secret length");
        }
        byte[] protectedBytes = transform(plaintext, true);
        try {
            return ByteBuffer.allocate(8 + protectedBytes.length).putInt(MAGIC)
                    .putInt(protectedBytes.length).put(protectedBytes).array();
        } finally { Arrays.fill(protectedBytes, (byte) 0); }
    }

    public byte[] unprotect(byte[] envelope) throws Exception {
        if (envelope == null || envelope.length < 9 || envelope.length > MAX_WRAPPED + 8) {
            throw new GeneralSecurityException("Invalid DPAPI envelope");
        }
        ByteBuffer input = ByteBuffer.wrap(envelope);
        if (input.getInt() != MAGIC || input.getInt() != input.remaining()) {
            throw new GeneralSecurityException("Unsupported DPAPI envelope");
        }
        byte[] wrapped = new byte[input.remaining()];
        input.get(wrapped);
        try { return transform(wrapped, false); }
        finally { Arrays.fill(wrapped, (byte) 0); }
    }

    private static byte[] transform(byte[] input, boolean encrypt) throws Exception {
        if (!System.getProperty("os.name", "").startsWith("Windows")) {
            throw new GeneralSecurityException("Windows DPAPI unavailable");
        }
        DataBlob output = new DataBlob();
        try (Memory inputMemory = new Memory(input.length); Memory entropyMemory = new Memory(ENTROPY.length)) {
            inputMemory.write(0, input, 0, input.length);
            entropyMemory.write(0, ENTROPY, 0, ENTROPY.length);
            DataBlob source = new DataBlob(inputMemory, input.length);
            DataBlob entropy = new DataBlob(entropyMemory, ENTROPY.length);
            try {
                boolean ok = encrypt
                        ? Crypt32.INSTANCE.CryptProtectData(source, null, entropy, null, null, UI_FORBIDDEN, output)
                        : Crypt32.INSTANCE.CryptUnprotectData(source, null, entropy, null, null, UI_FORBIDDEN, output);
                output.read();
                if (!ok) throw new GeneralSecurityException("DPAPI operation failed (" + Native.getLastError() + ")");
                int maximum = encrypt ? MAX_WRAPPED : MAX_PLAIN;
                if (output.cbData < 1 || output.cbData > maximum || output.pbData == null) {
                    throw new GeneralSecurityException("Invalid DPAPI output length");
                }
                return output.pbData.getByteArray(0, output.cbData);
            } finally {
                inputMemory.clear();
                entropyMemory.clear();
                if (output.pbData != null) {
                    // The OS owns this buffer. Clear before releasing with the matching allocator.
                    if (output.cbData > 0 && output.cbData <= MAX_WRAPPED) output.pbData.clear(output.cbData);
                    Kernel32.INSTANCE.LocalFree(output.pbData);
                }
            }
        }
    }

    @Structure.FieldOrder({"cbData", "pbData"})
    public static final class DataBlob extends Structure {
        public int cbData;
        public Pointer pbData;
        public DataBlob() { }
        DataBlob(Pointer data, int length) { cbData = length; pbData = data; write(); }
    }

    private interface Crypt32 extends StdCallLibrary {
        Crypt32 INSTANCE = Native.load("Crypt32", Crypt32.class, W32APIOptions.DEFAULT_OPTIONS);
        boolean CryptProtectData(DataBlob in, Pointer description, DataBlob entropy, Pointer reserved,
                                 Pointer prompt, int flags, DataBlob out);
        boolean CryptUnprotectData(DataBlob in, Pointer description, DataBlob entropy, Pointer reserved,
                                   Pointer prompt, int flags, DataBlob out);
    }

    private interface Kernel32 extends StdCallLibrary {
        Kernel32 INSTANCE = Native.load("Kernel32", Kernel32.class, W32APIOptions.DEFAULT_OPTIONS);
        Pointer LocalFree(Pointer memory);
    }
}
