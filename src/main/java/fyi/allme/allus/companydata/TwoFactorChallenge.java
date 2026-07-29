package fyi.allme.allus.companydata;

import fyi.allme.allus.companydata.internal.Parse;

import java.util.Map;

/**
 * 2FA-by-allme — a login-approval challenge returned by {@link TwoFactorClient#challenge} (spec §3).
 *
 * <p>{@link #matchingDigits()} is present only when the service has number matching on — the two digits to
 * DISPLAY on your login page. The person types them back into the allme app; the SERVER adjudicates them
 * (they never leave the app on any payload). Null when number matching is off.
 */
public record TwoFactorChallenge(
    String challengeId,
    String status,          // always "pending" on creation
    String expiresAt,
    String matchingDigits
) {
    static TwoFactorChallenge fromApi(Map<String, Object> obj) {
        return new TwoFactorChallenge(
            Parse.str(obj.get("challenge_id")),
            Parse.str(obj.get("status")),
            Parse.str(obj.get("expires_at")),
            Parse.str(obj.get("matching_digits"))
        );
    }
}
