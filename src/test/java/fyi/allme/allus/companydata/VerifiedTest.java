package fyi.allme.allus.companydata;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class VerifiedTest {
    static String h(String salt, String pt) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-256").digest((salt + pt).getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : d) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    @Test void hashMatchesRoundTrip() throws Exception {
        String salt = "0011223344556677", pt = "alice@example.com";
        assertTrue(Crypto.hashMatches(salt, h(salt, pt), pt));
        assertFalse(Crypto.hashMatches(salt, "deadbeef", pt));
        assertFalse(Crypto.hashMatches("", "", pt));
    }

    @Test void verifiedFromMatchAndMismatch() throws Exception {
        String salt = "0011223344556677", pt = "alice@example.com";
        assertTrue(Value.verifiedFrom(Map.of("verified_hash", h(salt, pt), "verified_salt", salt), pt));
        assertFalse(Value.verifiedFrom(Map.of("verified_hash", "deadbeef", "verified_salt", salt), pt));
        assertFalse(Value.verifiedFrom(Map.of(), pt));
        assertFalse(Value.verifiedFrom(Map.of("verified_hash", h(salt, pt), "verified_salt", salt), (Object) 42));
    }
}
