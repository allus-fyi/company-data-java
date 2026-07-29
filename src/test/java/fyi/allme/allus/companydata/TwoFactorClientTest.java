package fyi.allme.allus.companydata;

import fyi.allme.allus.companydata.internal.Http;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The 2FA client's waitForResult, layered on the base challenge/result client. */
class TwoFactorClientTest {

    private static Config config() {
        return Config.builder()
            .apiUrl("https://api.allme.fyi")
            .clientId("svc_abc")
            .clientSecret("topsecret")
            .servicePrivateKey("k.pem")
            .keyPassphrase("pp")
            .format("json")
            .build();
    }

    /** Build a 2FA client backed by a FakeTransport, with a no-op sleeper (no real delays). */
    private static TwoFactorClient client(FakeTransport t) {
        Http http = new Http(config(), t, x -> {}, System::nanoTime, 3);
        return new TwoFactorClient(http, ms -> {});
    }

    @Test
    void waitForResultReturnsFirstTerminal() {
        FakeTransport t = new FakeTransport();
        t.postResponses.add(FakeTransport.tokenOk());
        t.getResponses.add(FakeTransport.json(200, "{\"status\":\"pending\"}"));
        t.getResponses.add(FakeTransport.json(200, "{\"status\":\"pending\"}"));
        t.getResponses.add(FakeTransport.json(200,
            "{\"status\":\"approved\",\"completed_at\":\"2026-07-24T10:00:00Z\"}"));

        TwoFactorResult res = client(t).waitForResult("chal_1", 600, 0);
        assertEquals("approved", res.status());
        assertEquals("2026-07-24T10:00:00Z", res.completedAt());
        // Stopped at the first terminal read — never re-read a burned challenge.
        assertEquals(3, t.gets.size());
    }

    @Test
    void waitForResultEachTerminalStatus() {
        for (String terminal : List.of("approved", "denied", "expired", "revoked", "gone")) {
            FakeTransport t = new FakeTransport();
            t.postResponses.add(FakeTransport.tokenOk());
            t.getResponses.add(FakeTransport.json(200, "{\"status\":\"pending\"}"));
            t.getResponses.add(FakeTransport.json(200, "{\"status\":\"" + terminal + "\"}"));
            assertEquals(terminal, client(t).waitForResult("chal_1", 600, 0).status());
        }
    }

    @Test
    void waitForResultTimeoutRaisesApiException() {
        FakeTransport t = new FakeTransport();
        t.postResponses.add(FakeTransport.tokenOk());
        // timeout=0 → after the first pending poll the deadline has already passed.
        t.getResponses.add(FakeTransport.json(200, "{\"status\":\"pending\"}"));
        t.getResponses.add(FakeTransport.json(200, "{\"status\":\"pending\"}"));
        ApiException ex = assertThrows(ApiException.class,
            () -> client(t).waitForResult("chal_late", 0, 0));
        assertTrue(ex.getMessage().contains("not completed within"));
    }

    /** Defaults overload (timeout 600s, interval 2s) returns the terminal result. */
    @Test
    void waitForResultDefaultsOverload() {
        FakeTransport t = new FakeTransport();
        t.postResponses.add(FakeTransport.tokenOk());
        t.getResponses.add(FakeTransport.json(200, "{\"status\":\"approved\"}"));
        assertEquals("approved", client(t).waitForResult("chal_1").status());
    }
}
