package fyi.allme.allus.companydata.internal;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * The minimal HTTP transport seam the {@code Http} layer talks to. The default
 * implementation wraps {@link java.net.http.HttpClient}; tests inject a fake so the
 * auth / error / backoff logic is exercised without a live API.
 */
public interface Transport {

    /**
     * A minimal HTTP response (status + body + headers). The body is held as raw
     * {@code bodyBytes} so a binary response (a broadcast document's PDF/image
     * bytes — see {@code Client#documentFile}) survives the transport
     * byte-identically; {@link #body()} decodes those bytes as UTF-8 for the
     * JSON/XML/error paths that want text.
     */
    record Response(int status, byte[] bodyBytes, Map<String, List<String>> headers) {
        /**
         * Convenience constructor for the text paths (form/JSON/XML fakes) that
         * already hold a {@code String}; the bytes are its UTF-8 encoding. Binary
         * responses use the canonical {@code byte[]} constructor so no charset
         * round-trip ever touches them.
         */
        public Response(int status, String body, Map<String, List<String>> headers) {
            this(status, body == null ? null : body.getBytes(StandardCharsets.UTF_8), headers);
        }

        /** The body decoded as UTF-8 text — for JSON/XML/error parsing. */
        public String body() {
            return bodyBytes == null ? null : new String(bodyBytes, StandardCharsets.UTF_8);
        }

        /** First value of a header (case-insensitive), or null. */
        public String header(String name) {
            for (Map.Entry<String, List<String>> e : headers.entrySet()) {
                if (e.getKey() != null && e.getKey().equalsIgnoreCase(name)) {
                    List<String> v = e.getValue();
                    return (v != null && !v.isEmpty()) ? v.get(0) : null;
                }
            }
            return null;
        }
    }

    /** POST form-encoded ({@code application/x-www-form-urlencoded}). */
    Response postForm(String url, Map<String, String> form, Map<String, String> headers);

    /** GET with query params + headers. */
    Response get(String url, Map<String, String> params, Map<String, String> headers);

    /**
     * Send a body verb (POST/PUT/DELETE) with a raw byte body + headers.
     *
     * <p>The {@code Http} façade serializes JSON bodies and sets the
     * {@code Content-Type} header before calling this; this just sends bytes. A
     * {@code null} {@code body} means no request body (e.g. a bare DELETE).
     */
    Response send(String method, String url, byte[] body, Map<String, String> headers);
}
