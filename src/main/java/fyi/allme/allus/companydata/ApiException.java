package fyi.allme.allus.companydata;

/**
 * Any non-2xx from the API.
 *
 * <p>Carries the HTTP {@link #status()}, the platform {@link #errorKey()} (when
 * the body provided one), and a human-readable {@link #apiMessage()}.
 * {@link RateLimitException} is a 429 subclass.
 */
public class ApiException extends RuntimeException {
    private final int status;
    private final String errorKey;
    private final String apiMessage;

    public ApiException(int status, String errorKey, String message) {
        super(buildMessage(status, errorKey, message));
        this.status = status;
        this.errorKey = errorKey;
        this.apiMessage = message;
    }

    private static String buildMessage(int status, String errorKey, String message) {
        StringBuilder sb = new StringBuilder("HTTP ").append(status);
        if (errorKey != null && !errorKey.isEmpty()) {
            sb.append(" (").append(errorKey).append(')');
        }
        if (message != null && !message.isEmpty()) {
            sb.append(": ").append(message);
        }
        return sb.toString();
    }

    /** The HTTP status code (0 if the request never reached the server). */
    public int status() {
        return status;
    }

    /** The platform {@code error_key} from the body, or {@code null} if absent. */
    public String errorKey() {
        return errorKey;
    }

    /** The human-readable message from the body, or {@code null} if absent. */
    public String apiMessage() {
        return apiMessage;
    }
}
