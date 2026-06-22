package fyi.allme.allus.companydata;

import fyi.allme.allus.companydata.internal.Transport;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

/**
 * Records calls and replays scripted responses (FIFO) per method — mirrors the
 * Python tests' {@code FakeSession}. Lets the {@code Http} auth/error/backoff logic
 * be exercised without a live API.
 */
final class FakeTransport implements Transport {
    final Deque<Response> postResponses = new ArrayDeque<>();
    final Deque<Response> getResponses = new ArrayDeque<>();
    final Deque<Response> sendResponses = new ArrayDeque<>();
    final List<Map<String, String>> posts = new ArrayList<>();
    final List<GetCall> gets = new ArrayList<>();
    final List<SendCall> sends = new ArrayList<>();

    record GetCall(String url, Map<String, String> params, Map<String, String> headers) {
    }

    /** A recorded POST/PUT/DELETE body call (body is the raw bytes; null = no body). */
    record SendCall(String method, String url, byte[] body, Map<String, String> headers) {
    }

    @Override
    public Response postForm(String url, Map<String, String> form, Map<String, String> headers) {
        Map<String, String> rec = new java.util.LinkedHashMap<>(form);
        rec.put("__url__", url);
        posts.add(rec);
        return postResponses.pop();
    }

    @Override
    public Response get(String url, Map<String, String> params, Map<String, String> headers) {
        gets.add(new GetCall(url, params, headers));
        return getResponses.pop();
    }

    @Override
    public Response send(String method, String url, byte[] body, Map<String, String> headers) {
        sends.add(new SendCall(method, url, body, headers));
        return sendResponses.pop();
    }

    // ── response builders ───────────────────────────────────────────────────

    static Response json(int status, String body) {
        return new Response(status, body, Map.of());
    }

    static Response json(int status, String body, Map<String, List<String>> headers) {
        return new Response(status, body, headers);
    }

    static Response tokenOk() {
        return json(200, "{\"access_token\":\"tok-123\",\"token_type\":\"Bearer\",\"expires_in\":3600}");
    }
}
