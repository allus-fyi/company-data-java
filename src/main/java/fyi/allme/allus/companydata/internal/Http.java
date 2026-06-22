package fyi.allme.allus.companydata.internal;

import fyi.allme.allus.companydata.ApiException;
import fyi.allme.allus.companydata.AuthException;
import fyi.allme.allus.companydata.Config;
import fyi.allme.allus.companydata.RateLimitException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.DoubleConsumer;
import java.util.function.LongSupplier;

/**
 * OAuth token + HTTP layer. The thin transport every higher
 * layer goes through. It owns:
 *
 * <ul>
 *   <li><b>Auth</b> — {@code client_credentials} only. On the first call (or when
 *       the cached token is near expiry) it POSTs the credentials to
 *       {@code {api_url}/oauth2/token} and caches the bearer token + expiry.
 *       Refresh is automatic; a 401 mid-flight triggers exactly one
 *       refresh-and-retry, then {@link AuthException}.</li>
 *   <li><b>Format</b> — sets {@code Accept} per {@code config.format()}
 *       (json/xml) and parses the body accordingly (XML is XXE-safe via {@link Xml}).</li>
 *   <li><b>Errors</b> — maps non-2xx to the error taxonomy: 401 → refresh+retry then
 *       {@link AuthException}; 429 → Retry-After-driven bounded backoff then
 *       {@link RateLimitException}; other non-2xx → {@link ApiException}.</li>
 * </ul>
 *
 * <p>Config-only key handling: the client id/secret come from
 * {@link Config} — never a method argument. Injectable {@code clock}/{@code sleep}
 * keep the layer unit-testable without the live API.
 */
public final class Http {
    /** Refresh the token a little before it actually expires (seconds). */
    private static final double TOKEN_EXPIRY_SKEW_S = 30.0;

    private static final int DEFAULT_MAX_RETRIES_429 = 3;
    private static final double DEFAULT_BACKOFF_S = 1.0;
    private static final double MAX_BACKOFF_S = 60.0;

    private final Config config;
    private final Transport transport;
    private final DoubleConsumer sleep;     // seconds
    private final LongSupplier clockNanos;  // monotonic nanos
    private final int maxRetries429;

    private final String apiUrl;
    private String token;
    private double tokenExpiryS = 0.0; // monotonic-seconds deadline

    public Http(Config config) {
        this(config, new JdkTransport(), Http::defaultSleep, System::nanoTime, DEFAULT_MAX_RETRIES_429);
    }

    public Http(Config config, Transport transport) {
        this(config, transport, Http::defaultSleep, System::nanoTime, DEFAULT_MAX_RETRIES_429);
    }

    public Http(Config config, Transport transport, DoubleConsumer sleep,
                LongSupplier clockNanos, int maxRetries429) {
        this.config = config;
        this.transport = transport;
        this.sleep = sleep;
        this.clockNanos = clockNanos;
        this.maxRetries429 = maxRetries429;
        this.apiUrl = stripTrailingSlash(config.apiUrl());
    }

    private static void defaultSleep(double seconds) {
        try {
            Thread.sleep((long) (Math.max(0.0, seconds) * 1000));
        } catch (InterruptedException exc) {
            Thread.currentThread().interrupt();
        }
    }

    private static String stripTrailingSlash(String s) {
        return (s != null && s.endsWith("/")) ? s.substring(0, s.length() - 1) : s;
    }

    private double nowS() {
        return clockNanos.getAsLong() / 1_000_000_000.0;
    }

    // ── auth ──────────────────────────────────────────────────────────────

    private boolean tokenValid() {
        return token != null && nowS() < tokenExpiryS;
    }

    private String fetchToken() {
        String url = apiUrl + "/oauth2/token";
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "client_credentials");
        form.put("client_id", config.clientId());
        form.put("client_secret", config.clientSecret());

        Transport.Response resp = transport.postForm(url, form, Map.of("Accept", "application/json"));
        int status = resp.status();
        if (status < 200 || status >= 300) {
            String[] err = extractError(resp);
            throw new AuthException("token request rejected (HTTP " + status + ")"
                + (err[0] != null ? " [" + err[0] + "]" : "")
                + (err[1] != null ? ": " + err[1] : ""));
        }
        Map<String, Object> body;
        try {
            body = Json.parseObject(resp.body());
        } catch (Exception exc) {
            throw new AuthException("token response was not valid JSON");
        }
        Object accessToken = body.get("access_token");
        if (accessToken == null || String.valueOf(accessToken).isEmpty()) {
            throw new AuthException("token response missing access_token");
        }
        double expiresIn;
        try {
            Object ei = body.getOrDefault("expires_in", 3600);
            expiresIn = ei instanceof Number n ? n.doubleValue() : Double.parseDouble(String.valueOf(ei));
        } catch (NumberFormatException exc) {
            expiresIn = 3600.0;
        }
        this.token = String.valueOf(accessToken);
        this.tokenExpiryS = nowS() + Math.max(0.0, expiresIn - TOKEN_EXPIRY_SKEW_S);
        return this.token;
    }

    private String bearer(boolean forceRefresh) {
        if (forceRefresh || !tokenValid()) {
            return fetchToken();
        }
        return token;
    }

    // ── requests ────────────────────────────────────────────────────────────

    /** GET {@code path} (joined to api_url) → parsed body (Map/List/String). */
    public Object get(String path) {
        return get(path, null);
    }

    public Object get(String path, Map<String, String> params) {
        return request("GET", path, params, null, null, null);
    }

    /** POST {@code path} with a JSON body → parsed body. */
    public Object post(String path, Object jsonBody) {
        return request("POST", path, null, jsonBody, null, null);
    }

    /** POST {@code path} with a raw byte body + content type → parsed body. */
    public Object post(String path, byte[] rawBody, String contentType) {
        return request("POST", path, null, null, rawBody, contentType);
    }

    /** PUT {@code path} with a JSON body → parsed body. */
    public Object put(String path, Object jsonBody) {
        return request("PUT", path, null, jsonBody, null, null);
    }

    /** DELETE {@code path} → parsed body. */
    public Object delete(String path) {
        return request("DELETE", path, null, null, null, null);
    }

    /**
     * The shared request loop for every verb. Adds the bearer token + an
     * {@code Accept} header matching {@code config.format()}, carries an optional JSON
     * or raw-bytes body, parses JSON or XML, and maps non-2xx responses to the SDK
     * errors: 401 → one refresh-and-retry then {@link AuthException}; 429 → bounded
     * Retry-After backoff then {@link RateLimitException}; other non-2xx →
     * {@link ApiException}.
     */
    private Object request(String method, String path, Map<String, String> params,
                           Object jsonBody, byte[] rawBody, String contentType) {
        String url = url(path);
        boolean wantsXml = "xml".equals(config.format());
        String accept = wantsXml ? "application/xml" : "application/json";

        // Resolve the body bytes + content type once (the same body is re-sent on retry).
        byte[] body = rawBody;
        String ctype = contentType;
        if (rawBody == null && jsonBody != null) {
            body = Json.write(jsonBody).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            ctype = "application/json";
        }

        int retries429 = 0;
        boolean refreshed401 = false;
        while (true) {
            String tok = bearer(false);
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("Authorization", "Bearer " + tok);
            headers.put("Accept", accept);
            if (body != null && ctype != null) {
                headers.put("Content-Type", ctype);
            }

            Transport.Response resp;
            if ("GET".equals(method) && body == null) {
                resp = transport.get(url, params, headers);
            } else {
                resp = transport.send(method, url, body, headers);
            }
            int status = resp.status();

            if (status >= 200 && status < 300) {
                return parseBody(resp, wantsXml);
            }

            if (status == 401) {
                if (!refreshed401) {
                    refreshed401 = true;
                    bearer(true); // one refresh-and-retry
                    continue;
                }
                String[] err = extractError(resp);
                throw new AuthException("unauthorized after token refresh"
                    + (err[0] != null ? " [" + err[0] + "]" : "")
                    + (err[1] != null ? ": " + err[1] : ""));
            }

            if (status == 429) {
                Double retryAfter = parseRetryAfter(resp);
                if (retries429 < maxRetries429) {
                    retries429++;
                    sleep.accept(backoffDelay(retryAfter, retries429));
                    continue;
                }
                String[] err = extractError(resp);
                throw new RateLimitException(retryAfter, err[0], err[1]);
            }

            String[] err = extractError(resp);
            throw new ApiException(status, err[0], err[1]);
        }
    }

    private String url(String path) {
        if (path.startsWith("http://") || path.startsWith("https://")) {
            return path;
        }
        return apiUrl + (path.startsWith("/") ? "" : "/") + path;
    }

    private Object parseBody(Transport.Response resp, boolean wantsXml) {
        String text = resp.body();
        if (text == null || text.strip().isEmpty()) {
            return Map.of();
        }
        if (wantsXml) {
            try {
                return Xml.parse(text);
            } catch (Exception exc) {
                throw new ApiException(resp.status(), null, "response was not valid XML: " + exc.getMessage());
            }
        }
        try {
            return Json.parse(text);
        } catch (Exception exc) {
            throw new ApiException(resp.status(), null, "response was not valid JSON: " + exc.getMessage());
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────

    /** Pull {@code error_key} + a message out of a non-2xx body (JSON or XML). */
    private static String[] extractError(Transport.Response resp) {
        Object body = null;
        String text = resp.body();
        if (text != null && !text.strip().isEmpty()) {
            try {
                body = Json.parse(text);
            } catch (Exception jsonExc) {
                try {
                    body = Xml.parse(text);
                } catch (Exception xmlExc) {
                    return new String[]{null, text};
                }
            }
        }
        if (body instanceof Map<?, ?> m) {
            Object errorKey = m.get("error_key");
            Object message = m.get("error") != null ? m.get("error") : m.get("message");
            return new String[]{
                errorKey != null ? String.valueOf(errorKey) : null,
                message != null ? String.valueOf(message) : null
            };
        }
        return new String[]{null, null};
    }

    /** Parse the {@code Retry-After} header (delta-seconds form) → seconds, or null. */
    private static Double parseRetryAfter(Transport.Response resp) {
        String raw = resp.header("Retry-After");
        if (raw == null) {
            return null;
        }
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException exc) {
            // HTTP-date Retry-After is spec-legal but the platform sends delta-seconds.
            return null;
        }
    }

    private static double backoffDelay(Double retryAfter, int attempt) {
        if (retryAfter != null && retryAfter >= 0) {
            return Math.min(retryAfter, MAX_BACKOFF_S);
        }
        return Math.min(DEFAULT_BACKOFF_S * Math.pow(2, attempt - 1), MAX_BACKOFF_S);
    }
}
