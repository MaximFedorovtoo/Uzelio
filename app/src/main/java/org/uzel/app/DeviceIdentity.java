package org.uzel.app;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import java.security.*;
import java.security.spec.ECGenParameterSpec;
import java.math.BigInteger;
import java.util.Date;
import javax.security.auth.x500.X500Principal;
import org.uzel.core.TlsIdentity;

public final class DeviceIdentity {
    // v1 authorized only SHA-256, but Conscrypt signs already-hashed TLS messages.
    // Key authorizations are immutable: retain v1 and create a compatible identity.
    private static final String ALIAS="uzel-node-v2";
    public static synchronized TlsIdentity load() throws Exception {
        KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);
        if(!store.containsAlias(ALIAS)) {
            KeyPairGenerator generator=KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC,"AndroidKeyStore");
            generator.initialize(new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_SIGN|KeyProperties.PURPOSE_VERIFY)
                    .setAlgorithmParameterSpec(new ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_NONE,KeyProperties.DIGEST_SHA256)
                    .setCertificateSubject(new X500Principal("CN=Uzel node"))
                    .setCertificateSerialNumber(new BigInteger(120,new SecureRandom()).add(BigInteger.ONE))
                    .setCertificateNotBefore(new Date(System.currentTimeMillis()-86400000L))
                    .setCertificateNotAfter(new Date(System.currentTimeMillis()+10L*365*86400000L)).build());
            generator.generateKeyPair();
        }
        return TlsIdentity.fromKeyStore(store,null,ALIAS);
    }
}
