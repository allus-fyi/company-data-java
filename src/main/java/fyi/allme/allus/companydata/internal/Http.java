package fyi.allme.allus.companydata.internal;

import fyi.allme.allus.companydata.ApiException;
import fyi.allme.allus.companydata.AuthException;
import fyi.allme.allus.companydata.Config;
import fyi.allme.allus.companydata.RateLimitException;

import java.net.URI;
import java.net.URISyntaxException;
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
 *   <li><b>Region</b> — the configured {@code api_url} is the starting point AND the
 *       fallback: every response that can name a home base (the token response, a 421
 *       refusal) rebases it, and the token request itself follows the rebase like every
 *       other call — pinning it to the configured value would keep minting at a region a
 *       client's company has left. See {@link #rebaseTo}.</li>
 *   <li><b>Format</b> — sets {@code Accept} per {@code config.format()}
 *       (json/xml) and parses the body accordingly (XML is XXE-safe via {@link Xml}).</li>
 *   <li><b>Errors</b> — maps non-2xx to the error taxonomy: 401 → refresh+retry then
 *       {@link AuthException}; 421 → rebase+retry once then {@link ApiException}; 429 →
 *       Retry-After-driven bounded backoff then {@link RateLimitException}; other non-2xx →
 *       {@link ApiException}.</li>
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

    /** The response member (token success body and 421 refusal body alike) naming the home-region base. */
    private static final String REGION_BASE_MEMBER = "api_url";
    /** The front door's refusal of a data route: rebase to the named base and replay. */
    private static final String REBASE_ERROR_KEY = "region.rebase_required";

    private final Config config;
    private final Transport transport;
    private final DoubleConsumer sleep;     // seconds
    private final LongSupplier clockNanos;  // monotonic nanos
    private final int maxRetries429;

    /**
     * The base every request goes to, including the token request. Starts at the configured
     * value; every rebase moves it. Clients do not validate a server-returned base against
     * anything — they store it and use it.
     */
    private String apiUrl;
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

    /**
     * POST the client credentials to {@code /oauth2/token} and cache the result.
     *
     * <p>Goes to the CURRENT base, exactly like every other call — once a token response has
     * named a home base, subsequent token requests go there too, the same as the data calls
     * they sit beside. The configured value is only the starting point, for the first call of
     * a process and the fallback when nothing has been stored yet.
     */
    private String fetchToken() {
        String url = apiUrl + "/oauth2/token";
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "client_credentials");
        form.put("client_id", config.clientId());
        form.put("client_secret", config.clientSecret());

        Transport.Response resp = transport.postForm(url, form, Map.of("Accept", "application/json"));
        int status = resp.status();
        if (status < 200 || status >= 300) {
            ErrorBody err = extractError(resp);
            throw new AuthException("token request rejected (HTTP " + status + ")"
                + (err.errorKey() != null ? " [" + err.errorKey() + "]" : "")
                + (err.message() != null ? ": " + err.message() : ""));
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
        // The token is minted at the client's home region and only validates there, so the
        // base the response names is where every company-data call must go from here on.
        rebaseTo(body.get(REGION_BASE_MEMBER));
        return this.token;
    }

    // ── region ────────────────────────────────────────────────────────────

    /**
     * Point subsequent requests — including the next token request — at {@code candidate}.
     *
     * <p>Returns {@code true} only when the base actually MOVED. A candidate that is absent,
     * not a string, empty, or equal to the current base is not stored and returns
     * {@code false}. Nothing here validates the candidate against a fetched region list: the
     * SDK stores the base the server names and uses it.
     */
    private boolean rebaseTo(Object candidate) {
        if (!(candidate instanceof String s)) {
            return false;
        }
        String base = stripTrailingSlash(s.strip());
        if (base.isEmpty() || base.equals(apiUrl)) {
            return false;
        }
        this.apiUrl = base;
        return true;
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
        return request("GET", path, params, null, null, null, false, false);
    }

    /**
     * GET {@code path} → the RAW 2xx response BYTES — NO JSON/XML parse and NO charset
     * decode. For downloading file bytes whose body may be non-UTF-8 binary (a broadcast
     * document's PDF/image) — see {@code Client#documentFile}, which must return them
     * byte-identically. Auth/refresh/retry handling is identical to {@link #get}.
     */
    public byte[] getRaw(String path) {
        return (byte[]) request("GET", path, null, null, null, null, true, false);
    }

    /**
     * GET {@code path} → the whole 2xx {@link Transport.Response} — status, headers AND raw body,
     * with no parse.
     *
     * <p>The company-facing binary file endpoints have two 200 shapes (a JSON wrapper for an
     * encrypted answer, the raw file bytes for a plaintext one) that are told apart by
     * {@code Content-Type}, and both carry an {@code X-Allus-Content-Sha256} digest header. Neither
     * {@link #get} (which parses) nor {@link #getRaw} (which drops the headers) can express that, so
     * this hands the caller the response itself — {@link #parseBody(Transport.Response)} does the
     * format-aware parse afterwards when the caller decides it wants one. Auth/refresh/retry and
     * error mapping are identical.
     */
    public Transport.Response getResponse(String path) {
        return (Transport.Response) request("GET", path, null, null, null, null, false, true);
    }

    /** POST {@code path} with a JSON body → parsed body. */
    public Object post(String path, Object jsonBody) {
        return request("POST", path, null, jsonBody, null, null, false, false);
    }

    /** POST {@code path} with a raw byte body + content type → parsed body. */
    public Object post(String path, byte[] rawBody, String contentType) {
        return request("POST", path, null, null, rawBody, contentType, false, false);
    }

    /** PUT {@code path} with a JSON body → parsed body. */
    public Object put(String path, Object jsonBody) {
        return request("PUT", path, null, jsonBody, null, null, false, false);
    }

    /** DELETE {@code path} → parsed body. */
    public Object delete(String path) {
        return request("DELETE", path, null, null, null, null, false, false);
    }

    /**
     * The shared request loop for every verb. Adds the bearer token + an
     * {@code Accept} header matching {@code config.format()}, carries an optional JSON
     * or raw-bytes body, parses JSON or XML (unless {@code raw} is set, in which case the
     * 2xx body BYTES are returned untouched — no charset decode, or {@code wantResponse}, in which
     * case the whole 2xx {@link Transport.Response} is), and maps non-2xx responses to the SDK
     * errors: 401 → one refresh-and-retry then {@link AuthException}; 421 → one rebase-and-retry
     * then {@link ApiException}; 429 → bounded Retry-After backoff then
     * {@link RateLimitException}; other non-2xx → {@link ApiException}.
     */
    private Object request(String method, String path, Map<String, String> params,
                           Object jsonBody, byte[] rawBody, String contentType,
                           boolean raw, boolean wantResponse) {
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
        boolean rebased421 = false;
        while (true) {
            // Resolved per attempt, AFTER the bearer call: the first bearer() of a process
            // mints the token and rebases from its response, so the base a fresh token was
            // just fetched under is the base this request must go to as well.
            String tok = bearer(false);
            String reqUrl = url(path);
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("Authorization", "Bearer " + tok);
            headers.put("Accept", accept);
            if (body != null && ctype != null) {
                headers.put("Content-Type", ctype);
            }

            Transport.Response resp;
            if ("GET".equals(method) && body == null) {
                resp = transport.get(reqUrl, params, headers);
            } else {
                resp = transport.send(method, reqUrl, body, headers);
            }
            int status = resp.status();

            if (status >= 200 && status < 300) {
                if (wantResponse) {
                    return resp;
                }
                return raw ? resp.bodyBytes() : parseBody(resp, wantsXml);
            }

            if (status == 401) {
                if (!refreshed401) {
                    refreshed401 = true;
                    bearer(true); // one refresh-and-retry
                    continue;
                }
                ErrorBody err = extractError(resp);
                throw new AuthException("unauthorized after token refresh"
                    + (err.errorKey() != null ? " [" + err.errorKey() + "]" : "")
                    + (err.message() != null ? ": " + err.message() : ""));
            }

            if (status == 421) {
                // The front door serves no data route: it names the caller's home base and
                // expects the call there. Rebase once and replay; a second 421 surfaces.
                ErrorBody err = extractError(resp);
                if (!rebased421 && REBASE_ERROR_KEY.equals(err.errorKey())
                    && rebaseTo(err.details().get(REGION_BASE_MEMBER))) {
                    rebased421 = true;
                    continue;
                }
                throw new ApiException(status, err.errorKey(), err.message(), err.details());
            }

            if (status == 429) {
                ErrorBody err = extractError(resp);
                // A pending-cap 429 means the caller already holds the maximum concurrent
                // 2FA challenges — a retry can never clear that, so surface it immediately as an
                // ApiException instead of the blind Retry-After backoff every other 429 gets.
                if ("twofa.pending_cap".equals(err.errorKey())) {
                    throw new ApiException(status, err.errorKey(), err.message());
                }
                Double retryAfter = parseRetryAfter(resp);
                if (retries429 < maxRetries429) {
                    retries429++;
                    sleep.accept(backoffDelay(retryAfter, retries429));
                    continue;
                }
                throw new RateLimitException(retryAfter, err.errorKey(), err.message());
            }

            ErrorBody err = extractError(resp);
            throw new ApiException(status, err.errorKey(), err.message(), err.details());
        }
    }

    /**
     * Resolve {@code path} against the CURRENT base. An already-absolute {@code path} (the
     * lazy binary handle's server-supplied {@code value_url}) is reduced to its path+query and
     * rebuilt against the current base too — so a value_url minted before a rebase, or replayed
     * on a 421 retry after one, still lands at the base every other request now uses.
     */
    private String url(String path) {
        if (path.startsWith("http://") || path.startsWith("https://")) {
            path = pathAndQuery(path);
        }
        return apiUrl + (path.startsWith("/") ? "" : "/") + path;
    }

    /**
     * The path + query + fragment portion of an absolute URL, dropping its scheme and host.
     */
    private static String pathAndQuery(String absoluteUrl) {
        try {
            URI uri = new URI(absoluteUrl);
            StringBuilder result = new StringBuilder(uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath());
            if (uri.getRawQuery() != null) {
                result.append('?').append(uri.getRawQuery());
            }
            if (uri.getRawFragment() != null) {
                result.append('#').append(uri.getRawFragment());
            }
            return result.toString();
        } catch (URISyntaxException exc) {
            return absoluteUrl;
        }
    }

    /**
     * Parse a response body with the configured {@code format} (json/xml) — the parse
     * {@link #getResponse} deliberately skips, so a caller that had to inspect the headers first can
     * still get the normal format-aware parse instead of hard-wiring JSON.
     */
    public Object parseBody(Transport.Response resp) {
        return parseBody(resp, "xml".equals(config.format()));
    }

    /**
     * Parse a response body as JSON whatever {@code format} this client speaks, for a route that
     * answers JSON to every caller rather than honouring the configured format.
     */
    public Object parseBodyAsJson(Transport.Response resp) {
        return parseBody(resp, false);
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

    /**
     * What a non-2xx body yields: the platform key, the human message, and everything else.
     *
     * @param details the error body's remaining fields, verbatim
     */
    private record ErrorBody(String errorKey, String message, Map<String, Object> details) {
    }

    /** Pull {@code error_key} + a message + the remaining fields out of a non-2xx body (JSON or XML). */
    private static ErrorBody extractError(Transport.Response resp) {
        Object body = null;
        String text = resp.body();
        if (text != null && !text.strip().isEmpty()) {
            try {
                body = Json.parse(text);
            } catch (Exception jsonExc) {
                try {
                    body = Xml.parse(text);
                } catch (Exception xmlExc) {
                    return new ErrorBody(null, text, Map.of());
                }
            }
        }
        if (body instanceof Map<?, ?> m) {
            Object errorKey = m.get("error_key");
            Object message = m.get("error") != null ? m.get("error") : m.get("message");
            // Everything BESIDE the key and the message travels on as `details`, so a body
            // that carries actionable data (a 410 file_expired's content_sha256 + expired_at) is
            // readable without a bespoke exception type per response.
            Map<String, Object> details = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                String key = String.valueOf(e.getKey());
                if (!"error_key".equals(key) && !"error".equals(key) && !"message".equals(key)) {
                    details.put(key, e.getValue());
                }
            }
            return new ErrorBody(
                errorKey != null ? String.valueOf(errorKey) : null,
                message != null ? String.valueOf(message) : null,
                details);
        }
        return new ErrorBody(null, null, Map.of());
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
