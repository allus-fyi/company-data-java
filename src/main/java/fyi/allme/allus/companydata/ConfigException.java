package fyi.allme.allus.companydata;

/**
 * Missing or invalid configuration (or key file) at construction — fail fast.
 * Raised by {@link Config} and by {@link Client} when the service
 * PEM can't be read or its passphrase is wrong.
 *
 * <p>Named {@code ConfigException} (not {@code ConfigError}) to follow Java's
 * exception-naming convention; it is the {@code ConfigError} of the shared
 * taxonomy.
 */
public class ConfigException extends RuntimeException {
    public ConfigException(String message) {
        super(message);
    }

    public ConfigException(String message, Throwable cause) {
        super(message, cause);
    }
}
