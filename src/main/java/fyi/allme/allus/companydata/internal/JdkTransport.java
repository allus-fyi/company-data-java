package fyi.allme.allus.companydata.internal;

import fyi.allme.allus.companydata.ApiException;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.StringJoiner;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.SSLException;

/**
 * The default {@link Transport} over {@link java.net.http.HttpClient}.
 * No auth, rate-limit or region logic here — that lives in {@code Http}; this sends bytes, and
 * sends a request other than GET or HEAD once more when its connection was closed before the
 * response headers arrived (see {@code send}).
 */
public final class JdkTransport implements Transport {
    /** How long one request of the transport this class builds itself waits for the platform's answer. */
    private static final Duration OWN_CLIENT_REQUEST_TIMEOUT = Duration.ofSeconds(45);
    /** How long one request over an {@link HttpClient} the caller hands in waits. */
    private static final Duration SUPPLIED_CLIENT_REQUEST_TIMEOUT = Duration.ofSeconds(60);

    private final HttpClient client;
    private final Duration requestTimeout;
    private final boolean resendClosedConnection;

    public JdkTransport() {
        this(HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build(), OWN_CLIENT_REQUEST_TIMEOUT, true);
    }

    public JdkTransport(HttpClient client) {
        this(client, SUPPLIED_CLIENT_REQUEST_TIMEOUT, true);
    }

    private JdkTransport(HttpClient client, Duration requestTimeout, boolean resendClosedConnection) {
        this.client = client;
        this.requestTimeout = requestTimeout;
        this.resendClosedConnection = resendClosedConnection;
    }

    /**
     * A transport over {@code client} that reports a closed connection at once instead of sending
     * the request again — for a host other than the allme API.
     */
    public static JdkTransport withoutResend(HttpClient client) {
        return new JdkTransport(client, SUPPLIED_CLIENT_REQUEST_TIMEOUT, false);
    }

    @Override
    public Response postForm(String url, Map<String, String> form, Map<String, String> headers) {
        String body = urlEncode(form);
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
            .timeout(requestTimeout)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        applyHeaders(b, headers);
        return send(b.build(), url);
    }

    @Override
    public Response get(String url, Map<String, String> params, Map<String, String> headers) {
        String full = (params == null || params.isEmpty()) ? url : url + "?" + urlEncode(params);
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(full))
            .timeout(requestTimeout)
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
            .timeout(requestTimeout)
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

    /**
     * One exchange, sent once more when its connection was closed before the response headers
     * arrived; the second outcome, failure included, is the answer.
     *
     * <p>That is the server ending a kept-alive connection on its idle timeout at the moment a
     * request was written to it: the server never read the request, so sending it again is the
     * request's first delivery, and the closed connection is never picked again. The client reports
     * neither whether the connection had carried an earlier request nor whether part of the status
     * line or headers had arrived, so a first request on a new connection is sent again too, and so
     * is one whose response had begun but whose headers were cut off. GET and HEAD are never sent
     * again here: the client replays those itself when a pooled connection closed under them, so a
     * second send of ours would be a third delivery. Nothing else is sent again — not a timeout, a
     * connection that could not be opened, a TLS failure, nor an exchange whose response headers had
     * arrived (the body handler marks that). A request is immutable, so the same one is sent again.
     */
    private Response send(HttpRequest req, String url) {
        for (int attempt = 0; ; attempt++) {
            AtomicBoolean responded = new AtomicBoolean();
            try {
                // Receive raw bytes (NOT ofString) so a binary body — a broadcast document's
                // PDF/image — reaches Http/Client byte-identically; the text paths decode via
                // Response#body(). ofString corrupts non-UTF-8 downloads, which is why byte-array
                // reception is used here instead.
                HttpResponse<byte[]> resp = client.send(req, info -> {
                    responded.set(true);
                    return HttpResponse.BodySubscribers.ofByteArray();
                });
                return new Response(resp.statusCode(), resp.body(), resp.headers().map());
            } catch (IOException exc) {
                if (attempt == 0 && resendClosedConnection && !replayedByClient(req)
                    && closedBeforeResponse(exc, responded.get())) {
                    continue;
                }
                throw new ApiException(0, null, "request to " + url + " failed: " + exc.getMessage());
            } catch (InterruptedException exc) {
                Thread.currentThread().interrupt();
                throw new ApiException(0, null, "request to " + url + " interrupted");
            }
        }
    }

    /** Whether the client itself sends {@code req} again when its pooled connection closed under it. */
    private static boolean replayedByClient(HttpRequest req) {
        return "GET".equals(req.method()) || "HEAD".equals(req.method());
    }

    /** Whether {@code exc} ended an exchange whose connection closed before the response headers arrived. */
    private static boolean closedBeforeResponse(IOException exc, boolean responded) {
        return !responded
            && !(exc instanceof HttpTimeoutException)
            && !(exc instanceof ConnectException)
            && !(exc instanceof SSLException);
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
