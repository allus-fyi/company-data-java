package fyi.allme.allus.companydata;

import fyi.allme.allus.companydata.internal.Parse;

import java.util.Map;

/**
 * #436 2FA-by-allme — the outcome of {@link TwoFactorClient#result} (spec §3). The poll is the record: the
 * first read of a terminal state delivers it and burns it (a later read is {@code gone}).
 */
public record TwoFactorResult(
    String status,       // pending | approved | denied | expired | revoked | gone
    String expiresAt,    // set while pending
    String completedAt   // set on a terminal outcome
) {
    static TwoFactorResult fromApi(Map<String, Object> obj) {
        return new TwoFactorResult(
            Parse.str(obj.get("status")),
            Parse.str(obj.get("expires_at")),
            Parse.str(obj.get("completed_at"))
        );
    }
}
