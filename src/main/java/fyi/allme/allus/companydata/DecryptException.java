package fyi.allme.allus.companydata;

/**
 * A ciphertext wrapper is malformed, the key is wrong, or the GCM tag mismatched.
 * Also raised when loading the encrypted service PEM fails (wrong
 * passphrase / malformed PEM).
 */
public class DecryptException extends RuntimeException {
    public DecryptException(String message) {
        super(message);
    }

    public DecryptException(String message, Throwable cause) {
        super(message, cause);
    }
}
