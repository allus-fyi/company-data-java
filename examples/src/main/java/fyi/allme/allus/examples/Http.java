package fyi.allme.allus.examples;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.net.URLDecoder;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Shared HTTP plumbing — the request/response glue every family's handlers use (no SDK logic here). */
public final class Http {
    private Http() {
    }

    /** Parse a JSON request body into a map. */
    public static Map<String, Object> body(HttpExchange ex) throws IOException {
        return Json.parse(ex.getRequestBody().readAllBytes());
    }

    /** Read the whole request body as a UTF-8 string (the raw webhook payload). */
    public static String rawBody(HttpExchange ex) throws IOException {
        return new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    /** The whole request body as the exact bytes sent — for content that must not be re-encoded. */
    public static byte[] rawBodyBytes(HttpExchange ex) throws IOException {
        return ex.getRequestBody().readAllBytes();
    }

    /** The request query string as a name → value map. */
    public static Map<String, String> query(HttpExchange ex) {
        Map<String, String> out = new LinkedHashMap<>();
        String raw = ex.getRequestURI().getRawQuery();
        if (raw == null || raw.isEmpty()) {
            return out;
        }
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            String k = eq >= 0 ? pair.substring(0, eq) : pair;
            String v = eq >= 0 ? pair.substring(eq + 1) : "";
            out.put(URLDecoder.decode(k, StandardCharsets.UTF_8), URLDecoder.decode(v, StandardCharsets.UTF_8));
        }
        return out;
    }

    /** Request headers as a name → value map (SDK verify/parse look them up case-insensitively). */
    public static Map<String, String> requestHeaders(HttpExchange ex) {
        Map<String, String> out = new LinkedHashMap<>();
        Headers h = ex.getRequestHeaders();
        for (Map.Entry<String, List<String>> e : h.entrySet()) {
            List<String> vals = e.getValue();
            if (vals != null && !vals.isEmpty()) {
                out.put(e.getKey(), vals.get(0));
            }
        }
        return out;
    }

    /** Case-insensitive header lookup. */
    public static String header(Map<String, String> headers, String name) {
        String target = name.toLowerCase();
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (e.getKey() != null && e.getKey().toLowerCase().equals(target)) {
                return e.getValue();
            }
        }
        return null;
    }

    /**
     * The contract's FAILURE envelope:
     * {@code {"error": "<token> — <reason>", "message": "<reason>"}}.
     *
     * <p>The suite's shared client raises {@code body.error} VERBATIM and ignores every other key, so a
     * bare token in {@code error} reaches the developer as one uninformative word and the REASON — which
     * the backend has right there — is dropped. That is the swallowed failure of standards.html §9: a
     * failure converted into something indistinguishable from any other failure. The token is kept and the
     * reason appended in the shape this contract already uses for exactly this ({@code no_origin — …});
     * {@code message} keeps the bare reason for a programmatic reader.
     *
     * <p>NOT used for the token-only refusals the suite handles by STATUS rather than body —
     * {@code 409 not_configured} (mapped before the body is read) and {@code 404 not_found}.
     */
    public static void failure(HttpExchange ex, int status, String token, String reason) throws IOException {
        String text = reason == null ? "" : reason.trim();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", token + " — " + (text.isEmpty() ? "no reason was reported" : text));
        body.put("message", text);
        json(ex, status, body);
    }

    /**
     * A throwable's reason. {@code getMessage()} is null for a whole family of faults
     * (NullPointerException and kin) and {@code String.valueOf(null)} would report the literal reason
     * "null"; the class name stands in.
     */
    public static String reasonOf(Throwable t) {
        String raw = t.getMessage();
        return (raw == null || raw.isBlank()) ? t.getClass().getName() : raw.trim();
    }

    public static void json(HttpExchange ex, int status, Object data) throws IOException {
        byte[] b = Json.writeBytes(data);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, b.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(b);
        }
    }

    /**
     * Serve a JSON document that is already encoded, byte for byte — the stored setup snapshot. The
     * bytes are passed through as they are because parsing and re-serialising them here, or decoding
     * them to a {@code String} and back, would rewrite content this server is not allowed to interpret.
     */
    public static void rawJson(HttpExchange ex, int status, byte[] blob) throws IOException {
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, blob.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(blob);
        }
    }

    public static void text(HttpExchange ex, int status, String bodyText) throws IOException {
        byte[] b = bodyText.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        ex.sendResponseHeaders(status, b.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(b);
        }
    }

    public static void redirect(HttpExchange ex, String location) throws IOException {
        ex.getResponseHeaders().set("Location", location);
        ex.sendResponseHeaders(302, -1);
    }
}
