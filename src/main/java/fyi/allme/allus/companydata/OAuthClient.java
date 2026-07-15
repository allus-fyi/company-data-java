package fyi.allme.allus.companydata;

import fyi.allme.allus.companydata.internal.JdkTransport;
import fyi.allme.allus.companydata.internal.Json;
import fyi.allme.allus.companydata.internal.Transport;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.interfaces.RSAPrivateKey;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongConsumer;

/**
 * "Sign in with allme" — the RP-side OAuth client (#195).
 *
 * <p>A third-party site embeds a "Sign in with allme" button, sends the person to the hosted consent
 * screen, and — once they approve — receives an authorization code at its redirect URI. This wraps
 * the RP half: build the button URL, exchange the code, read the identity, and (for one_time) decrypt
 * the shared values. Config-only key handling still holds — the app private key + passphrase come
 * from {@link Config} (the idw role), never a method argument.
 */
public final class OAuthClient {

    /** The hosted consent surface. Native apps claim this https link; web is the fallback. */
    public static final String DEFAULT_AUTHORIZE_URL = "https://web.allme.fyi/auth";

    private static final Set<String> NON_CLAIMABLE = Set.of("photo", "document", "legal_document");
    private static final int MAX_CLAIMS = 15;
    private static final Set<String> MODES = Set.of("signin", "one_time", "connect");
    private static final Set<String> RESPONSE_MODES = Set.of("redirect", "detached");

    private final Config config;
    private final Transport transport;
    private final String authorizeBase;
    private final String apiUrl;
    private final LongConsumer sleep;

    public OAuthClient(Config config) {
        this(config, new JdkTransport(), DEFAULT_AUTHORIZE_URL, OAuthClient::sleepMillis);
    }

    /** Test/advanced seam: inject a transport (and optionally the authorize base + sleeper). */
    OAuthClient(Config config, Transport transport) {
        this(config, transport, DEFAULT_AUTHORIZE_URL, OAuthClient::sleepMillis);
    }

    OAuthClient(Config config, Transport transport, String authorizeBase, LongConsumer sleep) {
        if (config.oauthClientId() == null || config.oauthClientId().isEmpty()
                || config.oauthRedirectUri() == null || config.oauthRedirectUri().isEmpty()) {
            throw new ConfigException("OAuthClient requires oauth_client_id + oauth_redirect_uri (idw role)");
        }
        this.config = config;
        this.transport = transport;
        this.authorizeBase = authorizeBase;
        this.apiUrl = config.apiUrl().replaceAll("/+$", "");
        this.sleep = sleep;
    }

    /** Build from an idw-role JSON config file. */
    public static OAuthClient fromConfig(String path) {
        return new OAuthClient(Config.fromIdwFile(path));
    }

    /** Build from {@code ALLUS_OAUTH_*} env vars. */
    public static OAuthClient fromEnv() {
        return new OAuthClient(Config.fromIdwEnv());
    }

    /** A one_time claim the RP asks for: a field TYPE + an advisory suggestion. */
    public record Claim(String type, String suggest, boolean required, String label) {
        public Claim(String type) {
            this(type, null, false, null);
        }
    }

    /** The decrypted conclusion of {@link #completeSignIn}. */
    public record SignInResult(Map<String, String> user, String mode, boolean twoFactor,
                               Map<String, String> values) {
    }

    /** Optional parameters for {@link #authorizeUrl}. */
    public static final class AuthorizeOptions {
        List<Claim> claims;
        String state;
        String responseMode = "redirect";
        String codeChallenge;
        String redirectUri;

        public AuthorizeOptions claims(List<Claim> c) { this.claims = c; return this; }
        public AuthorizeOptions state(String s) { this.state = s; return this; }
        public AuthorizeOptions responseMode(String m) { this.responseMode = m; return this; }
        public AuthorizeOptions codeChallenge(String c) { this.codeChallenge = c; return this; }
        public AuthorizeOptions redirectUri(String u) { this.redirectUri = u; return this; }
    }

    /** Build the consent-screen URL — the "Sign in with allme" button target. */
    public String authorizeUrl(String mode, AuthorizeOptions opts) {
        if (!MODES.contains(mode)) {
            throw new ConfigException("invalid mode '" + mode + "' (expected signin | one_time | connect)");
        }
        AuthorizeOptions o = opts != null ? opts : new AuthorizeOptions();
        String responseMode = o.responseMode != null ? o.responseMode : "redirect";
        if (!RESPONSE_MODES.contains(responseMode)) {
            throw new ConfigException("invalid responseMode '" + responseMode + "' (expected redirect | detached)");
        }
        List<String> parts = new ArrayList<>();
        parts.add("client_id=" + enc(config.oauthClientId()));
        parts.add("redirect_uri=" + enc(o.redirectUri != null ? o.redirectUri : config.oauthRedirectUri()));
        parts.add("mode=" + enc(mode));
        parts.add("response_mode=" + enc(responseMode));
        if (o.state != null) {
            parts.add("state=" + enc(o.state));
        }
        if (o.codeChallenge != null && !o.codeChallenge.isEmpty()) {
            parts.add("code_challenge=" + enc(o.codeChallenge));
            parts.add("code_challenge_method=S256");
        }
        List<Map<String, Object>> cleaned = cleanClaims(o.claims);
        if (!cleaned.isEmpty()) {
            parts.add("claims=" + enc(Json.write(cleaned)));
        }
        return authorizeBase + "?" + String.join("&", parts);
    }

    private static List<Map<String, Object>> cleanClaims(List<Claim> claims) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (claims == null) {
            return out;
        }
        for (Claim c : claims) {
            if (c.type() == null || c.type().isEmpty() || NON_CLAIMABLE.contains(c.type())) {
                continue;
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("type", c.type());
            if (c.suggest() != null && !c.suggest().isEmpty()) {
                entry.put("suggest", c.suggest());
            }
            if (c.required()) {
                entry.put("required", true);
            }
            if (c.label() != null && !c.label().isEmpty()) {
                entry.put("label", c.label());
            }
            out.add(entry);
            if (out.size() >= MAX_CLAIMS) {
                break;
            }
        }
        return out;
    }

    /** Swap the authorization {@code code} for a token (POST /oauth2/token). */
    public Map<String, Object> exchangeCode(String code, String codeVerifier) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "authorization_code");
        form.put("client_id", config.oauthClientId());
        form.put("code", code);
        form.put("redirect_uri", config.oauthRedirectUri());
        if (codeVerifier != null && !codeVerifier.isEmpty()) {
            form.put("code_verifier", codeVerifier);
        }
        if (config.oauthClientSecret() != null && !config.oauthClientSecret().isEmpty()) {
            form.put("client_secret", config.oauthClientSecret());
        }
        Transport.Response res = transport.postForm(apiUrl + "/oauth2/token", form, accept());
        return parse(res, "token exchange");
    }

    /** Read the signed-in identity (GET /api/oauth/userinfo) with the RP token. */
    public Map<String, Object> userinfo(String accessToken) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer " + accessToken);
        headers.put("Accept", "application/json");
        Transport.Response res = transport.get(apiUrl + "/api/oauth/userinfo", Map.of(), headers);
        return parse(res, "userinfo");
    }

    /** Exchange + userinfo in one call, decrypting one_time values via the configured app key. */
    public SignInResult completeSignIn(String code, String codeVerifier) {
        Map<String, Object> token = exchangeCode(code, codeVerifier);
        Object accessToken = token.get("access_token");
        if (!(accessToken instanceof String at) || at.isEmpty()) {
            throw new AuthException("token exchange returned no access_token");
        }
        Map<String, Object> info = userinfo(at);
        Map<String, String> user = new LinkedHashMap<>();
        user.put("sub", str(info.get("sub")));
        user.put("share_code", str(info.get("share_code")));
        user.put("display_name", str(info.get("display_name")));
        String mode = info.get("mode") instanceof String m ? m : str(token.get("mode"));
        boolean twoFactor = Boolean.TRUE.equals(info.get("two_factor"));
        Map<String, String> values = new LinkedHashMap<>();
        if (info.get("values") instanceof Map<?, ?> raw && !raw.isEmpty()) {
            values = decryptValues(raw);
        }
        return new SignInResult(user, mode, twoFactor, values);
    }

    private Map<String, String> decryptValues(Map<?, ?> raw) {
        if (config.oauthPrivateKey() == null || config.oauthPrivateKey().isEmpty()
                || config.oauthKeyPassphrase() == null || config.oauthKeyPassphrase().isEmpty()) {
            throw new ConfigException(
                "one_time values present but oauth_private_key / oauth_key_passphrase not configured");
        }
        byte[] pem;
        try {
            pem = Files.readAllBytes(Path.of(config.oauthPrivateKey()));
        } catch (IOException exc) {
            throw new ConfigException("could not read oauth_private_key: " + exc.getMessage(), exc);
        }
        RSAPrivateKey key = Crypto.loadPrivateKey(pem, config.oauthKeyPassphrase());
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : raw.entrySet()) {
            out.put(String.valueOf(e.getKey()), Crypto.decrypt(Wrapper.of(e.getValue()), key));
        }
        return out;
    }

    /** Poll /oauth2/result for a detached sign-in (single-delivery). */
    public Map<String, Object> pollResult(String state, long timeoutSeconds, long intervalSeconds) {
        if (timeoutSeconds <= 0) {
            timeoutSeconds = 600;
        }
        if (intervalSeconds < 0) {
            intervalSeconds = 2;
        }
        Map<String, String> form = new LinkedHashMap<>();
        form.put("client_id", config.oauthClientId());
        form.put("state", state);
        if (config.oauthClientSecret() != null && !config.oauthClientSecret().isEmpty()) {
            form.put("client_secret", config.oauthClientSecret());
        }
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000;
        while (true) {
            Transport.Response res = transport.postForm(apiUrl + "/oauth2/result", form, accept());
            int status = res.status();
            if (status == 200) {
                Map<String, Object> body = parseObject(res.body());
                if (body.containsKey("code")) {
                    return body;
                }
            } else if (status == 410) {
                throw new ApiException(410, "oauth.result_expired", "detached sign-in expired before completion");
            } else if (status != 202) {
                String[] err = err(res.body());
                throw new ApiException(status, err[0],
                    err[1] != null ? err[1] : "result poll rejected (HTTP " + status + ")");
            }
            if (System.currentTimeMillis() >= deadline) {
                throw new ApiException(0, null, "detached sign-in not completed within " + timeoutSeconds + "s");
            }
            sleep.accept(intervalSeconds * 1000);
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private static Map<String, String> accept() {
        return Map.of("Accept", "application/json");
    }

    private Map<String, Object> parse(Transport.Response res, String what) {
        int status = res.status();
        if (status >= 200 && status < 300) {
            return parseObject(res.body());
        }
        String[] err = err(res.body());
        if (status == 401 || status == 403) {
            throw new AuthException(what + " rejected (HTTP " + status + ")"
                + (err[0] != null ? " [" + err[0] + "]" : "")
                + (err[1] != null ? ": " + err[1] : ""));
        }
        throw new ApiException(status, err[0], err[1] != null ? err[1] : what + " rejected (HTTP " + status + ")");
    }

    private static Map<String, Object> parseObject(String body) {
        if (body == null || body.isEmpty()) {
            return new LinkedHashMap<>();
        }
        try {
            return Json.parseObject(body);
        } catch (com.fasterxml.jackson.core.JsonProcessingException exc) {
            return new LinkedHashMap<>();
        }
    }

    private static String[] err(String body) {
        Map<String, Object> m = parseObject(body);
        return new String[] { str(m.get("error_key")), str(m.get("error")) };
    }

    private static String str(Object v) {
        return v instanceof String s ? s : null;
    }

    private static String enc(String v) {
        return URLEncoder.encode(v, StandardCharsets.UTF_8);
    }

    private static void sleepMillis(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
