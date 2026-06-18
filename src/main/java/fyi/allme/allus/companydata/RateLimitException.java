package fyi.allme.allus.companydata;

/**
 * A 429 from a rate-limited endpoint.
 *
 * <p>Subclass of {@link ApiException} with a fixed status of 429; carries the
 * {@link #retryAfter()} value parsed from the {@code Retry-After} response header
 * (seconds, or {@code null} when absent). On the changes feed the SDK auto-backs
 * off and retries within reason; for the heavily-limited connections endpoints it
 * surfaces after backoff so you don't accidentally hammer them.
 */
public class RateLimitException extends ApiException {
    private final Double retryAfter;

    public RateLimitException(Double retryAfter, String errorKey, String message) {
        super(429, errorKey, message);
        this.retryAfter = retryAfter;
    }

    /** Seconds to wait before retrying, parsed from {@code Retry-After}; may be {@code null}. */
    public Double retryAfter() {
        return retryAfter;
    }
}
