package fyi.allme.allus.companydata.internal;

import fyi.allme.allus.companydata.ApiException;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.StringJoiner;

/**
 * The default {@link Transport} over {@link java.net.http.HttpClient}.
 * No retry/auth logic here — that lives in {@code Http}; this just sends bytes.
 */
public final class JdkTransport implements Transport {
    private final HttpClient client;

    public JdkTransport() {
        this(HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build());
    }

    public JdkTransport(HttpClient client) {
        this.client = client;
    }

    @Override
    public Response postForm(String url, Map<String, String> form, Map<String, String> headers) {
        String body = urlEncode(form);
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(60))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        applyHeaders(b, headers);
        return send(b.build(), url);
    }

    @Override
    public Response get(String url, Map<String, String> params, Map<String, String> headers) {
        String full = (params == null || params.isEmpty()) ? url : url + "?" + urlEncode(params);
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(full))
            .timeout(Duration.ofSeconds(60))
            .GET();
        applyHeaders(b, headers);
        return send(b.build(), url);
    }

    @Override
    public Response send(String method, String url, byte[] body, Map<String, String> headers) {
        HttpRequest.BodyPublisher publisher = (body == null)
            ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofByteArray(body);
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(60))
            .method(method, publisher);
        applyHeaders(b, headers);
        return send(b.build(), url);
    }

    private static void applyHeaders(HttpRequest.Builder b, Map<String, String> headers) {
        if (headers != null) {
            for (Map.Entry<String, String> e : headers.entrySet()) {
                if (e.getValue() != null) {
                    b.header(e.getKey(), e.getValue());
                }
            }
        }
    }

    private Response send(HttpRequest req, String url) {
        try {
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new Response(resp.statusCode(), resp.body(), resp.headers().map());
        } catch (IOException exc) {
            throw new ApiException(0, null, "request to " + url + " failed: " + exc.getMessage());
        } catch (InterruptedException exc) {
            Thread.currentThread().interrupt();
            throw new ApiException(0, null, "request to " + url + " interrupted");
        }
    }

    private static String urlEncode(Map<String, String> params) {
        StringJoiner sj = new StringJoiner("&");
        for (Map.Entry<String, String> e : params.entrySet()) {
            sj.add(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8)
                + "=" + URLEncoder.encode(e.getValue() == null ? "" : e.getValue(), StandardCharsets.UTF_8));
        }
        return sj.toString();
    }
}
