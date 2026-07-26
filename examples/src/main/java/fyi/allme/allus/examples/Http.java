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

    public static void json(HttpExchange ex, int status, Object data) throws IOException {
        byte[] b = Json.writeBytes(data);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, b.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(b);
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
