package fyi.allme.allus.companydata;

/**
 * The {@code client_credentials} token fetch or refresh failed.
 *
 * <p>Raised when {@code /oauth2/token} rejects the credentials, or when a 401
 * mid-flight survives the one automatic refresh-and-retry.
 */
public class AuthException extends RuntimeException {
    public AuthException(String message) {
        super(message);
    }

    public AuthException(String message, Throwable cause) {
        super(message, cause);
    }
}
