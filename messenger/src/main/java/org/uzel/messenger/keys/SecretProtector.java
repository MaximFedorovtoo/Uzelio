package org.uzel.messenger.keys;

/** Platform-bound wrapping of secrets; implementations must fail closed. */
public interface SecretProtector {
    byte[] protect(byte[] plaintext) throws Exception;
    byte[] unprotect(byte[] envelope) throws Exception;
}
