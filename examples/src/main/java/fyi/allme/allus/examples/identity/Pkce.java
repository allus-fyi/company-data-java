package fyi.allme.allus.examples.identity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * PKCE (RFC 7636) verifier + S256 challenge — pure local crypto, no network. The SDK takes the
 * {@code code_challenge} into {@link fyi.allme.allus.companydata.OAuthClient#authorizeUrl} and the
 * {@code code_verifier} into {@link fyi.allme.allus.companydata.OAuthClient#completeSignIn}; the demo
 * generates the pair. (Scenarios 5/6 use the Nimbus OIDC library's own {@code CodeVerifier} instead.)
 */
final class Pkce {
    private static final SecureRandom RANDOM = new SecureRandom();

    record Pair(String verifier, String challenge) {
    }

    static Pair generate() {
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String verifier = b64url(raw);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return new Pair(verifier, b64url(digest));
        } catch (Exception exc) {
            throw new RuntimeException("PKCE challenge failed: " + exc.getMessage(), exc);
        }
    }

    private static String b64url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
