package fyi.allme.allus.companydata;

import fyi.allme.allus.companydata.internal.Http;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** HTTP/auth layer tests. Mirrors the Python reference's test_http. */
class HttpTest {

    private static Config config(String format) {
        return Config.builder()
            .apiUrl("https://api.allme.fyi")
            .clientId("svc_abc")
            .clientSecret("topsecret")
            .servicePrivateKey("k.pem") // never loaded by Http
            .keyPassphrase("pp")
            .format(format)
            .build();
    }

    private static Http client(FakeTransport t, List<Double> sleeps) {
        return new Http(config("json"), t, sleeps::add, System::nanoTime, 3);
    }

    // ── token fetch + caching ───────────────────────────────────────────────

    @Test
    void tokenFetchedWithClientCredentialsAndAttached() {
        FakeTransport t = new FakeTransport();
        t.postResponses.add(FakeTransport.tokenOk());
        t.getResponses.add(FakeTransport.json(200, "{\"ok\":true}"));
        Http c = client(t, new ArrayList<>());

        Object body = c.get("/api/company-data/request-fields");
        assertEquals(Map.of("ok", true), body);

        Map<String, String> post = t.posts.get(0);
        assertEquals("https://api.allme.fyi/oauth2/token", post.get("__url__"));
        assertEquals("client_credentials", post.get("grant_type"));
        assertEquals("svc_abc", post.get("client_id"));
        assertEquals("topsecret", post.get("client_secret"));

        assertEquals("Bearer tok-123", t.gets.get(0).headers().get("Authorization"));
        assertEquals("application/json", t.gets.get(0).headers().get("Accept"));
    }

    @Test
    void tokenCachedAcrossCalls() {
        FakeTransport t = new FakeTransport();
        t.postResponses.add(FakeTransport.tokenOk());
        t.getResponses.add(FakeTransport.json(200, "{\"n\":1}"));
        t.getResponses.add(FakeTransport.json(200, "{\"n\":2}"));
        Http c = client(t, new ArrayList<>());

        c.get("/api/company-data/changes");
        c.get("/api/company-data/changes");
        assertEquals(1, t.posts.size()); // token fetched once and reused
    }

    @Test
    void tokenRefetchedWhenExpired() {
        FakeTransport t = new FakeTransport();
        t.postResponses.add(FakeTransport.json(200, "{\"access_token\":\"first\",\"expires_in\":0}"));
        t.postResponses.add(FakeTransport.json(200, "{\"access_token\":\"second\",\"expires_in\":3600}"));
        t.getResponses.add(FakeTransport.json(200, "{}"));
        t.getResponses.add(FakeTransport.json(200, "{}"));

        // Clock advances so the 0-expiry token is stale on the 2nd call (seconds → nanos).
        Deque<Double> ticksS = new ArrayDeque<>(List.of(0.0, 0.0, 100.0, 100.0, 100.0, 100.0));
        LongSupplier clock = () -> (long) (ticksS.pop() * 1_000_000_000.0);
        Http c = new Http(config("json"), t, x -> {}, clock, 3);

        c.get("/api/company-data/changes"); // "first" (expires_in=0 → already stale)
        c.get("/api/company-data/changes"); // must refetch → "second"
        assertEquals(2, t.posts.size());
        assertEquals("Bearer second", t.gets.get(1).headers().get("Authorization"));
    }

    @Test
    void tokenFetchFailureRaisesAuth() {
        FakeTransport t = new FakeTransport();
        t.postResponses.add(FakeTransport.json(401, "{\"error_key\":\"oauth.bad_client\"}"));
        Http c = client(t, new ArrayList<>());
        assertThrows(AuthException.class, () -> c.get("/api/company-data/changes"));
    }

    // ── 401 refresh-and-retry ───────────────────────────────────────────────

    @Test
    void unauthorizedTriggersOneRefreshThenSucceeds() {
        FakeTransport t = new FakeTransport();
        t.postResponses.add(FakeTransport.tokenOk());
        t.postResponses.add(FakeTransport.tokenOk());
        t.getResponses.add(FakeTransport.json(401, "{\"error_key\":\"auth.expired\"}"));
        t.getResponses.add(FakeTransport.json(200, "{\"recovered\":true}"));
        Http c = client(t, new ArrayList<>());

        Object body = c.get("/api/company-data/connections");
        assertEquals(Map.of("recovered", true), body);
        assertEquals(2, t.posts.size()); // refreshed once
        assertEquals(2, t.gets.size());  // original + retry
    }

    @Test
    void unauthorizedAfterRefreshRaisesAuth() {
        FakeTransport t = new FakeTransport();
        t.postResponses.add(FakeTransport.tokenOk());
        t.postResponses.add(FakeTransport.tokenOk());
        t.getResponses.add(FakeTransport.json(401, "{\"error_key\":\"auth.expired\"}"));
        t.getResponses.add(FakeTransport.json(401, "{\"error_key\":\"auth.expired\"}"));
        Http c = client(t, new ArrayList<>());
        assertThrows(AuthException.class, () -> c.get("/api/company-data/connections"));
        assertEquals(2, t.posts.size()); // only ONE refresh, then gives up
    }

    // ── 429 backoff ─────────────────────────────────────────────────────────

    @Test
    void rateLimitedWithRetryAfterBacksOffThenSucceeds() {
        FakeTransport t = new FakeTransport();
        t.postResponses.add(FakeTransport.tokenOk());
        t.getResponses.add(FakeTransport.json(429, "{\"error_key\":\"rate.limited\"}",
            Map.of("Retry-After", List.of("2"))));
        t.getResponses.add(FakeTransport.json(200, "{\"done\":true}"));
        List<Double> sleeps = new ArrayList<>();
        Http c = client(t, sleeps);

        Object body = c.get("/api/company-data/changes");
        assertEquals(Map.of("done", true), body);
        assertEquals(List.of(2.0), sleeps); // honored Retry-After
    }

    @Test
    void rateLimitedExhaustsRetriesThenRaises() {
        FakeTransport t = new FakeTransport();
        t.postResponses.add(FakeTransport.tokenOk());
        for (int i = 0; i < 10; i++) {
            t.getResponses.add(FakeTransport.json(429, "{\"error_key\":\"rate.limited\"}",
                Map.of("Retry-After", List.of("1"))));
        }
        List<Double> sleeps = new ArrayList<>();
        Http c = new Http(config("json"), t, sleeps::add, System::nanoTime, 3);

        RateLimitException exc = assertThrows(RateLimitException.class,
            () -> c.get("/api/company-data/connections"));
        assertEquals(1.0, exc.retryAfter());
        assertEquals(429, exc.status());
        assertEquals("rate.limited", exc.errorKey());
        assertEquals(3, sleeps.size()); // 3 bounded retries → 3 sleeps
        assertEquals(4, t.gets.size()); // 4 GET attempts
    }

    @Test
    void rateLimitedDefaultBackoffWhenNoRetryAfter() {
        FakeTransport t = new FakeTransport();
        t.postResponses.add(FakeTransport.tokenOk());
        t.getResponses.add(FakeTransport.json(429, "{\"error_key\":\"rate.limited\"}"));
        t.getResponses.add(FakeTransport.json(200, "{\"ok\":1}"));
        List<Double> sleeps = new ArrayList<>();
        Http c = client(t, sleeps);
        assertEquals(Map.of("ok", 1), c.get("/api/company-data/changes"));
        assertEquals(1, sleeps.size());
        assertTrue(sleeps.get(0) > 0);
    }

    // ── ApiException mapping ──────────────────────────────────────────────────

    @Test
    void non2xxMapsToApiExceptionWithErrorKey() {
        FakeTransport t = new FakeTransport();
        t.postResponses.add(FakeTransport.tokenOk());
        t.getResponses.add(FakeTransport.json(403,
            "{\"error\":\"Not a registered service client\",\"error_key\":\"company_data.no_client\"}"));
        Http c = client(t, new ArrayList<>());
        ApiException exc = assertThrows(ApiException.class, () -> c.get("/api/company-data/connections"));
        assertEquals(403, exc.status());
        assertEquals("company_data.no_client", exc.errorKey());
        assertEquals("Not a registered service client", exc.apiMessage());
        assertFalse(exc instanceof RateLimitException);
    }

    @Test
    void notFoundMapsToApiException() {
        FakeTransport t = new FakeTransport();
        t.postResponses.add(FakeTransport.tokenOk());
        t.getResponses.add(FakeTransport.json(404, "{\"error_key\":\"company_data.connection_not_found\"}"));
        Http c = client(t, new ArrayList<>());
        ApiException exc = assertThrows(ApiException.class, () -> c.get("/api/company-data/connections/zzz"));
        assertEquals(404, exc.status());
        assertEquals("company_data.connection_not_found", exc.errorKey());
    }

    // ── XML format ──────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void xmlAcceptHeaderAndParsing() {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            + "<response><request_fields>"
            + "<item><slug>work_email</slug><label>Work email</label><type>email</type>"
            + "<one_time>false</one_time><mandatory_provide>true</mandatory_provide>"
            + "<mandatory_connected>false</mandatory_connected></item>"
            + "<item><slug>logo</slug><label>Logo</label><type>photo</type>"
            + "<one_time>false</one_time><mandatory_provide>false</mandatory_provide>"
            + "<mandatory_connected>false</mandatory_connected></item>"
            + "</request_fields></response>";
        FakeTransport t = new FakeTransport();
        t.postResponses.add(FakeTransport.tokenOk());
        t.getResponses.add(FakeTransport.json(200, xml));
        Http c = new Http(config("xml"), t, x -> {}, System::nanoTime, 3);

        Object body = c.get("/api/company-data/request-fields");
        assertEquals("application/xml", t.gets.get(0).headers().get("Accept"));
        assertInstanceOf(Map.class, body);
        Map<String, Object> m = (Map<String, Object>) body;
        List<Object> fields = (List<Object>) m.get("request_fields");
        assertEquals(2, fields.size());
        Map<String, Object> f0 = (Map<String, Object>) fields.get(0);
        assertEquals("work_email", f0.get("slug"));
        assertEquals("email", f0.get("type"));
        // Booleans come back as "true"/"false" strings; the model layer coerces them.
        assertEquals("false", f0.get("one_time"));
        assertEquals("true", f0.get("mandatory_provide"));
    }

    @Test
    void xmlErrorBodyCarriesErrorKey() {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            + "<response><error>nope</error><error_key>company_data.no_client</error_key></response>";
        FakeTransport t = new FakeTransport();
        t.postResponses.add(FakeTransport.tokenOk());
        t.getResponses.add(FakeTransport.json(403, xml));
        Http c = new Http(config("xml"), t, x -> {}, System::nanoTime, 3);
        ApiException exc = assertThrows(ApiException.class, () -> c.get("/api/company-data/connections"));
        assertEquals("company_data.no_client", exc.errorKey());
    }

    @Test
    @SuppressWarnings("unchecked")
    void xmlSingleItemListIsStillAList() {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            + "<response><changes><item><id>c1</id><event>connection_created</event>"
            + "<person_user_id>u1</person_user_id></item></changes></response>";
        FakeTransport t = new FakeTransport();
        t.postResponses.add(FakeTransport.tokenOk());
        t.getResponses.add(FakeTransport.json(200, xml));
        Http c = new Http(config("xml"), t, x -> {}, System::nanoTime, 3);
        Map<String, Object> body = (Map<String, Object>) c.get("/api/company-data/changes");
        assertInstanceOf(List.class, body.get("changes"));
        List<Object> changes = (List<Object>) body.get("changes");
        Map<String, Object> c0 = (Map<String, Object>) changes.get(0);
        assertEquals("connection_created", c0.get("event"));
    }

    @Test
    void xxeIsDisabledNoExternalEntityExpansion() {
        // An XXE attempt: a DOCTYPE with an external/internal entity. The hardened
        // parser DISALLOWS doctype declarations, so the response is rejected as
        // invalid XML rather than expanding the entity.
        String xxe = "<?xml version=\"1.0\"?>"
            + "<!DOCTYPE r [ <!ENTITY x \"PWNED\"> ]>"
            + "<response><val>&x;</val></response>";
        FakeTransport t = new FakeTransport();
        t.postResponses.add(FakeTransport.tokenOk());
        t.getResponses.add(FakeTransport.json(200, xxe));
        Http c = new Http(config("xml"), t, x -> {}, System::nanoTime, 3);
        // disallow-doctype-decl makes the parse fail → ApiException, never "PWNED".
        assertThrows(ApiException.class, () -> c.get("/api/company-data/changes"));
    }
}
