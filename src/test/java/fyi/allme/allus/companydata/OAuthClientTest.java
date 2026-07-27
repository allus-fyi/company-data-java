package fyi.allme.allus.companydata;

import fyi.allme.allus.companydata.internal.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** "Sign in with allme" RP OAuth client tests (#195). Ports test_oauth.py. */
class OAuthClientTest {

    private static Config idwCfg() {
        return idwCfg(null, null);
    }

    private static Config idwCfg(String pem, String pass) {
        try {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("api_url", "https://api.allme.fyi");
            m.put("oauth_client_id", "idw_abc123");
            m.put("oauth_redirect_uri", "https://shop.example/cb");
            if (pem != null) {
                m.put("oauth_private_key", pem);
            }
            if (pass != null) {
                m.put("oauth_key_passphrase", pass);
            }
            Path f = Files.createTempFile("idw", ".json");
            f.toFile().deleteOnExit();
            Files.writeString(f, Json.write(m));
            return Config.fromIdwFile(f.toString());
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static Map<String, String> parseQuery(String url) {
        URI u = URI.create(url);
        Map<String, String> q = new LinkedHashMap<>();
        String query = u.getQuery();
        if (query != null) {
            for (String pair : query.split("&")) {
                int i = pair.indexOf('=');
                q.put(pair.substring(0, i), URLDecoder.decode(pair.substring(i + 1), StandardCharsets.UTF_8));
            }
        }
        return q;
    }

    private static String base(String url) {
        URI u = URI.create(url);
        return u.getScheme() + "://" + u.getHost() + u.getPath();
    }

    @Test
    void authorizeUrlSigninGolden() {
        OAuthClient c = new OAuthClient(idwCfg(), new FakeTransport());
        String url = c.authorizeUrl("signin", new OAuthClient.AuthorizeOptions().state("st1"));
        assertEquals("https://web.allme.fyi/auth", base(url));
        Map<String, String> q = parseQuery(url);
        assertEquals("idw_abc123", q.get("client_id"));
        assertEquals("https://shop.example/cb", q.get("redirect_uri"));
        assertEquals("signin", q.get("mode"));
        assertEquals("redirect", q.get("response_mode"));
        assertEquals("st1", q.get("state"));
        assertFalse(q.containsKey("claims"));
    }

    @Test
    void authorizeUrlPkceAndDetached() {
        OAuthClient c = new OAuthClient(idwCfg(), new FakeTransport());
        Map<String, String> q = parseQuery(c.authorizeUrl("signin",
            new OAuthClient.AuthorizeOptions().responseMode("detached").codeChallenge("CH")));
        assertEquals("detached", q.get("response_mode"));
        assertEquals("CH", q.get("code_challenge"));
        assertEquals("S256", q.get("code_challenge_method"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void authorizeUrlClaimValidation() throws Exception {
        OAuthClient c = new OAuthClient(idwCfg(), new FakeTransport());
        // #498: every claim carries a mandatory `name` — the identity everything downstream is keyed by.
        List<OAuthClient.Claim> claims = List.of(
            new OAuthClient.Claim("email", "email", "email_personal", false, false, null),
            new OAuthClient.Claim("avatar", "photo"),
            new OAuthClient.Claim("phone", "phone", null, true, false, null),
            new OAuthClient.Claim("nothing", ""));
        Map<String, String> q = parseQuery(c.authorizeUrl("one_time",
            new OAuthClient.AuthorizeOptions().claims(claims)));
        List<Object> parsed = Json.parseArray(q.get("claims"));
        assertEquals(2, parsed.size());
        assertEquals("email", ((Map<String, Object>) parsed.get(0)).get("name"));
        assertEquals("email", ((Map<String, Object>) parsed.get(0)).get("type"));
        assertEquals("email_personal", ((Map<String, Object>) parsed.get(0)).get("suggest"));
        assertEquals("phone", ((Map<String, Object>) parsed.get(1)).get("name"));
        assertEquals("phone", ((Map<String, Object>) parsed.get(1)).get("type"));
        assertEquals(Boolean.TRUE, ((Map<String, Object>) parsed.get(1)).get("required"));
    }

    /** #498 §2: a nameless claim, and two sharing a name, are refused at the call that made them. */
    @Test
    void authorizeUrlClaimNameRequired() {
        OAuthClient c = new OAuthClient(idwCfg(), new FakeTransport());
        assertThrows(ConfigException.class, () -> c.authorizeUrl("one_time",
            new OAuthClient.AuthorizeOptions().claims(List.of(new OAuthClient.Claim(null, "email")))));
        assertThrows(ConfigException.class, () -> c.authorizeUrl("one_time",
            new OAuthClient.AuthorizeOptions().claims(List.of(
                new OAuthClient.Claim("email", "email"),
                new OAuthClient.Claim("email", "text")))));
    }

    /** #498 §3: `verified` travels on the wire, so an RP can demand a #311-attested answer. */
    @Test
    @SuppressWarnings("unchecked")
    void authorizeUrlClaimVerified() throws Exception {
        OAuthClient c = new OAuthClient(idwCfg(), new FakeTransport());
        Map<String, String> q = parseQuery(c.authorizeUrl("signin",
            new OAuthClient.AuthorizeOptions().claims(List.of(
                new OAuthClient.Claim("email", "email", null, false, true, null)))));
        List<Object> parsed = Json.parseArray(q.get("claims"));
        assertEquals(1, parsed.size());
        assertEquals(Boolean.TRUE, ((Map<String, Object>) parsed.get(0)).get("verified"));
    }

    @Test
    void authorizeUrlCaps15() throws Exception {
        OAuthClient c = new OAuthClient(idwCfg(), new FakeTransport());
        List<OAuthClient.Claim> claims = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            claims.add(new OAuthClient.Claim("c" + i, "text"));
        }
        Map<String, String> q = parseQuery(c.authorizeUrl("one_time",
            new OAuthClient.AuthorizeOptions().claims(claims)));
        assertEquals(15, Json.parseArray(q.get("claims")).size());
    }

    @Test
    void authorizeUrlInvalidMode() {
        OAuthClient c = new OAuthClient(idwCfg(), new FakeTransport());
        assertThrows(ConfigException.class, () -> c.authorizeUrl("bogus", null));
    }

    @Test
    void exchangeAndUserinfo() {
        FakeTransport t = new FakeTransport();
        t.postResponses.add(FakeTransport.json(200, "{\"access_token\":\"AT\",\"mode\":\"signin\"}"));
        t.getResponses.add(FakeTransport.json(200,
            "{\"sub\":\"AB12CD\",\"share_code\":\"AB12CD\",\"mode\":\"signin\",\"two_factor\":false}"));
        OAuthClient c = new OAuthClient(idwCfg(), t);
        Map<String, Object> tok = c.exchangeCode("CODE", "V");
        assertEquals("AT", tok.get("access_token"));
        assertEquals("authorization_code", t.posts.get(0).get("grant_type"));
        assertEquals("V", t.posts.get(0).get("code_verifier"));
        Map<String, Object> info = c.userinfo("AT");
        // #498 §5: `sub` IS the share code (byte-identical to the id_token's); display_name is gone.
        assertEquals("AB12CD", info.get("sub"));
        assertEquals(info.get("share_code"), info.get("sub"));
        assertFalse(info.containsKey("display_name"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void completeSignInDecrypts(@TempDir Path dir) throws Exception {
        Map<String, Object> vec = TestData.vector();
        Path pem = dir.resolve("app.pem");
        Files.writeString(pem, (String) vec.get("encrypted_private_key_pem"));
        Map<String, Object> text = (Map<String, Object>) vec.get("text");

        Map<String, Object> uinfo = new LinkedHashMap<>();
        uinfo.put("sub", "AB12CD");
        uinfo.put("share_code", "AB12CD");
        uinfo.put("mode", "one_time");
        uinfo.put("two_factor", true);
        uinfo.put("values", Map.of("email_personal", text.get("wrapper")));

        FakeTransport t = new FakeTransport();
        t.postResponses.add(FakeTransport.json(200, "{\"access_token\":\"AT\",\"mode\":\"one_time\"}"));
        t.getResponses.add(FakeTransport.json(200, Json.write(uinfo)));

        OAuthClient c = new OAuthClient(idwCfg(pem.toString(), (String) vec.get("passphrase")), t);
        OAuthClient.SignInResult res = c.completeSignIn("CODE", "V");
        assertEquals("one_time", res.mode());
        assertTrue(res.twoFactor());
        assertEquals("AB12CD", res.user().get("sub"));
        assertEquals(text.get("plaintext"), res.values().get("email_personal"));
        // #498 §3.1a: no `values_attestation` on the wire → "not attested", never "wrong".
        assertTrue(res.attestations().isEmpty());
    }

    @Test
    void pollResultPendingThenCode() {
        FakeTransport t = new FakeTransport();
        t.postResponses.add(FakeTransport.json(202, ""));
        t.postResponses.add(FakeTransport.json(202, ""));
        t.postResponses.add(FakeTransport.json(200, "{\"code\":\"AUTHCODE\",\"state\":\"DET1\"}"));
        OAuthClient c = new OAuthClient(idwCfg(), t);
        Map<String, Object> res = c.pollResult("DET1", 5, 0);
        assertEquals("AUTHCODE", res.get("code"));
        assertEquals(3, t.posts.size());
    }

    @Test
    void pollResultExpired() {
        FakeTransport t = new FakeTransport();
        t.postResponses.add(FakeTransport.json(410, "{\"error_key\":\"oauth.result_expired\"}"));
        OAuthClient c = new OAuthClient(idwCfg(), t);
        ApiException ex = assertThrows(ApiException.class, () -> c.pollResult("DET1", 5, 0));
        assertEquals(410, ex.status());
    }

    // ── #481: 2fa_enroll mode + detached enrollment poll delivery ──────────────

    @Test
    void authorizeUrlAcceptsEnrollMode() {
        OAuthClient c = new OAuthClient(idwCfg(), new FakeTransport());
        Map<String, String> q = parseQuery(c.authorizeUrl("2fa_enroll",
            new OAuthClient.AuthorizeOptions().responseMode("detached").state("EN1")));
        assertEquals("2fa_enroll", q.get("mode"));
        assertEquals("detached", q.get("response_mode"));
    }

    @Test
    void pollResultPendingThenEnrolled() {
        // #481: a detached 2fa_enroll delivers {enrolled: true, state}, NOT a code. pollResult must
        // return on the `enrolled` sentinel — otherwise it consumes the one-shot result and times out.
        FakeTransport t = new FakeTransport();
        t.postResponses.add(FakeTransport.json(202, ""));
        t.postResponses.add(FakeTransport.json(200, "{\"enrolled\":true,\"state\":\"EN1\"}"));
        OAuthClient c = new OAuthClient(idwCfg(), t);
        Map<String, Object> res = c.pollResult("EN1", 5, 0);
        assertEquals(true, res.get("enrolled"));
        assertEquals("EN1", res.get("state"));
        assertEquals(2, t.posts.size()); // returned on first delivery, never polled past it
    }

    @Test
    void pollResultStillReturnsOnCodeAfterEnrollChange() {
        // Regression: the enroll addition must not break the sign-in `code` delivery.
        FakeTransport t = new FakeTransport();
        t.postResponses.add(FakeTransport.json(200, "{\"code\":\"AUTHCODE\",\"state\":\"DET1\"}"));
        OAuthClient c = new OAuthClient(idwCfg(), t);
        assertEquals("AUTHCODE", c.pollResult("DET1", 5, 0).get("code"));
    }
}
