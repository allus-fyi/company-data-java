package fyi.allme.allus.companydata;

/**
 * Signature verification failed, or a webhook envelope couldn't be unwrapped.
 * Raised by {@code handleWebhook} on a bad/unknown signature and
 * by the parse/unwrap path on a malformed body or envelope.
 */
public class WebhookException extends RuntimeException {
    public WebhookException(String message) {
        super(message);
    }

    public WebhookException(String message, Throwable cause) {
        super(message, cause);
    }
}
