package fyi.allme.allus.companydata;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Any non-2xx from the API.
 *
 * <p>Carries the HTTP {@link #status()}, the platform {@link #errorKey()} (when
 * the body provided one), a human-readable {@link #apiMessage()}, and the error
 * body's remaining fields as {@link #details()}. {@link RateLimitException} is a
 * 429 subclass.
 */
public class ApiException extends RuntimeException {
    private final int status;
    private final String errorKey;
    private final String apiMessage;
    private final Map<String, Object> details;

    public ApiException(int status, String errorKey, String message) {
        this(status, errorKey, message, Map.of());
    }

    /**
     * A 410 {@code company_data.file_expired} response carries actionable data BESIDE the key: it
     * returns the expired answer's {@code content_sha256} and {@code expired_at}, so a consumer can
     * record that its archived copy is now the only one and still prove what it holds. Generic rather
     * than a bespoke subclass — every error body's extra fields become reachable, and no future one
     * needs a new exception type to be readable.
     *
     * @param details the error body's remaining fields, verbatim.
     */
    public ApiException(int status, String errorKey, String message, Map<String, Object> details) {
        super(buildMessage(status, errorKey, message));
        this.status = status;
        this.errorKey = errorKey;
        this.apiMessage = message;
        this.details = details == null || details.isEmpty()
            ? Map.of()
            : Collections.unmodifiableMap(new LinkedHashMap<>(details));
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

    /**
     * The error body's fields other than {@code error_key} / {@code error} / {@code message},
     * verbatim — empty when the body carried none. A 410 {@code company_data.file_expired} puts
     * {@code content_sha256} and {@code expired_at} here.
     */
    public Map<String, Object> details() {
        return details;
    }
}
