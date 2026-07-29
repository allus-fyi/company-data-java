package fyi.allme.allus.companydata;

import fyi.allme.allus.companydata.internal.Http;

import java.util.HashMap;
import java.util.Map;
import java.util.function.LongConsumer;

/**
 * 2FA-by-allme — the relying-party challenge API (spec §3), on the SERVICE's data-client credentials
 * (the same auth {@link Client} uses). Reached via {@link Client#twoFactor()}.
 *
 * <p>A service asks a person (by share code) to approve a login inside the allme app, then polls for the
 * outcome. The poll is the record: the first read of a terminal state delivers it and burns it. A webhook
 * ({@code 2fa_challenge_completed}) is the best-effort push equivalent; the poll remains authoritative.
 */
public final class TwoFactorClient {

    private final Http http;
    // Injectable so waitForResult is unit-testable without real delays (matches OAuthClient).
    private final LongConsumer sleep;

    TwoFactorClient(Http http) {
        this(http, TwoFactorClient::sleepMillis);
    }

    /** Test/advanced seam: inject the sleeper (millis) used between waitForResult polls. */
    TwoFactorClient(Http http, LongConsumer sleep) {
        this.http = http;
        this.sleep = sleep;
    }

    /**
     * Initiate a login-approval challenge for the person behind {@code shareCode}.
     *
     * @param idempotencyKey required (&lt;=64); a repeat within the TTL returns the SAME challenge and sends
     *                       no second push
     * @param context        plain text shown to the person (&lt;=200 chars), or {@code null} for none
     */
    public TwoFactorChallenge challenge(String shareCode, String idempotencyKey, String context) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("share_code", shareCode);
        payload.put("idempotency_key", idempotencyKey);
        payload.put("context", context);
        Object body = http.post("/api/service-2fa/challenges", payload);
        return TwoFactorChallenge.fromApi(asMap(body));
    }

    /** Poll a challenge. While pending, {@code status} is {@code pending}; the first terminal read burns it. */
    public TwoFactorResult result(String challengeId) {
        Object body = http.get("/api/service-2fa/challenges/" + challengeId);
        return TwoFactorResult.fromApi(asMap(body));
    }

    /** Poll {@link #result} with the default budget (timeout 600s, interval 2s). */
    public TwoFactorResult waitForResult(String challengeId) {
        return waitForResult(challengeId, 600, 2);
    }

    /**
     * Poll {@link #result} until the status is terminal (no longer {@code pending}) and return that
     * first terminal {@link TwoFactorResult} (mirrors the §12c {@code pollResult} precedent).
     *
     * <p>Because the first terminal read burns the challenge, this returns as soon as the status
     * leaves {@code pending} — it never re-reads a consumed result. Throws {@link ApiException} if
     * {@code timeoutSeconds} elapse while still pending; {@code intervalSeconds} is the gap between polls.
     */
    public TwoFactorResult waitForResult(String challengeId, long timeoutSeconds, long intervalSeconds) {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000;
        while (true) {
            TwoFactorResult res = result(challengeId);
            if (!"pending".equals(res.status())) {
                return res;
            }
            if (System.currentTimeMillis() >= deadline) {
                throw new ApiException(0, null,
                    "2FA challenge " + challengeId + " not completed within " + timeoutSeconds + "s");
            }
            sleep.accept(intervalSeconds * 1000);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object body) {
        return body instanceof Map ? (Map<String, Object>) body : Map.of();
    }

    private static void sleepMillis(long ms) {
        try {
            Thread.sleep(Math.max(0, ms));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
