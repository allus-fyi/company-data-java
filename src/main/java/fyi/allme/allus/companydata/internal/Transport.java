package fyi.allme.allus.companydata.internal;

import java.util.List;
import java.util.Map;

/**
 * The minimal HTTP transport seam the {@code Http} layer talks to. The default
 * implementation wraps {@link java.net.http.HttpClient}; tests inject a fake so the
 * auth / error / backoff logic is exercised without a live API. (Mirrors the
 * Python reference's injectable {@code requests.Session}.)
 */
public interface Transport {

    /** A minimal HTTP response (status + body text + headers). */
    record Response(int status, String body, Map<String, List<String>> headers) {
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
