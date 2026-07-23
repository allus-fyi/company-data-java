package fyi.allme.allus.companydata;

import fyi.allme.allus.companydata.internal.Http;

import java.util.HashMap;
import java.util.Map;

/**
 * #436 2FA-by-allme — the relying-party challenge API (spec §3), on the SERVICE's data-client credentials
 * (the same auth {@link Client} uses). Reached via {@link Client#twoFactor()}.
 *
 * <p>A service asks a person (by share code) to approve a login inside the allme app, then polls for the
 * outcome. The poll is the record: the first read of a terminal state delivers it and burns it. A webhook
 * ({@code 2fa_challenge_completed}) is the best-effort push equivalent; the poll remains authoritative.
 */
public final class TwoFactorClient {

    private final Http http;

    TwoFactorClient(Http http) {
        this.http = http;
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

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object body) {
        return body instanceof Map ? (Map<String, Object>) body : Map.of();
    }
}
