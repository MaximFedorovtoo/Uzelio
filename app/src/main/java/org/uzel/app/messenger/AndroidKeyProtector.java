package org.uzel.app.messenger;

import org.uzel.messenger.keys.SecretProtector;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.security.keystore.KeyInfo;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.Arrays;

public final class AndroidKeyProtector implements SecretProtector {
    private static final String DEFAULT_ALIAS = "org.uzel.messenger.database-wrap.v1";
    private static final byte[] AAD = "org.uzel.messenger.db-key.v1/android-keystore".getBytes(StandardCharsets.UTF_8);
    private static final int MAGIC = 0x415a4b31; // AZK1
    private static final int IV_LENGTH = 12;
    private static final int TAG_BYTES = 16;
    private static final int MAX_PLAIN = 4096;
    private final String alias;

    public AndroidKeyProtector() { this(DEFAULT_ALIAS); }

    // Package-private for isolated instrumentation aliases; never reuses TLS/node identity.
    AndroidKeyProtector(String alias) {
        if (alias == null || !alias.startsWith("org.uzel.messenger.") || alias.length() > 200) {
            throw new IllegalArgumentException("Invalid messenger wrapping alias");
        }
        this.alias = alias;
    }

    @Override public byte[] protect(byte[] plaintext) throws Exception {
        if (plaintext == null || plaintext.length < 1 || plaintext.length > MAX_PLAIN) {
            throw new GeneralSecurityException("Invalid secret length");
        }
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        // AndroidKeyStore chooses a fresh random IV; caller-provided IVs are forbidden by key policy.
        cipher.init(Cipher.ENCRYPT_MODE, key(true));
        cipher.updateAAD(AAD);
        byte[] iv = cipher.getIV();
        if (iv == null || iv.length != IV_LENGTH) throw new GeneralSecurityException("Unexpected GCM nonce length");
        byte[] ciphertext = cipher.doFinal(plaintext);
        try {
            return ByteBuffer.allocate(12 + iv.length + ciphertext.length).putInt(MAGIC)
                    .putInt(iv.length).putInt(ciphertext.length).put(iv).put(ciphertext).array();
        } finally {
            Arrays.fill(iv, (byte) 0);
            Arrays.fill(ciphertext, (byte) 0);
        }
    }

    @Override public byte[] unprotect(byte[] envelope) throws Exception {
        if (envelope == null || envelope.length < 12 + IV_LENGTH + TAG_BYTES + 1
                || envelope.length > 12 + IV_LENGTH + TAG_BYTES + MAX_PLAIN) {
            throw new GeneralSecurityException("Invalid Android key envelope length");
        }
        ByteBuffer input = ByteBuffer.wrap(envelope);
        if (input.getInt() != MAGIC || input.getInt() != IV_LENGTH) {
            throw new GeneralSecurityException("Unsupported Android key envelope");
        }
        int size = input.getInt();
        if (size < TAG_BYTES + 1 || size > TAG_BYTES + MAX_PLAIN || size != input.remaining() - IV_LENGTH) {
            throw new GeneralSecurityException("Invalid Android key ciphertext length");
        }
        byte[] iv = new byte[IV_LENGTH];
        byte[] ciphertext = new byte[size];
        input.get(iv).get(ciphertext);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(false), new GCMParameterSpec(TAG_BYTES * 8, iv));
            cipher.updateAAD(AAD);
            return cipher.doFinal(ciphertext);
        } finally {
            Arrays.fill(iv, (byte) 0);
            Arrays.fill(ciphertext, (byte) 0);
        }
    }

    private SecretKey key(boolean allowCreate) throws Exception {
        synchronized (AndroidKeyProtector.class) {
            KeyStore store = KeyStore.getInstance("AndroidKeyStore");
            store.load(null);
            if (store.containsAlias(alias)) {
                java.security.Key existing = store.getKey(alias, null);
                if (!(existing instanceof SecretKey) || !"AES".equals(existing.getAlgorithm())) {
                    throw new GeneralSecurityException("Invalid messenger wrapping key");
                }
                KeyInfo info = (KeyInfo) SecretKeyFactory.getInstance("AES", "AndroidKeyStore")
                        .getKeySpec((SecretKey) existing, KeyInfo.class);
                int requiredPurposes = KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT;
                if (info.getKeySize() != 256 || info.getPurposes() != requiredPurposes
                        || !Arrays.equals(info.getBlockModes(), new String[] {KeyProperties.BLOCK_MODE_GCM})
                        || !Arrays.equals(info.getEncryptionPaddings(), new String[] {KeyProperties.ENCRYPTION_PADDING_NONE})) {
                    throw new GeneralSecurityException("Unsupported messenger wrapping key policy");
                }
                return (SecretKey) existing;
            }
            // In particular, restoring wrapped files without their device key never replaces it.
            if (!allowCreate) throw new GeneralSecurityException("Messenger wrapping key unavailable; recovery required");
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(alias,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true).build());
            return generator.generateKey();
        }
    }
}
