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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongConsumer;

/**
 * "Sign in with allme" — the RP-side OAuth client.
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
    private static final Set<String> MODES = Set.of("signin", "one_time", "connect", "2fa_enroll");
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

    /**
     * A claim the relying party asks for — a REQUEST FIELD.
     *
     * <p>You describe what you need: a {@code name} (the claim's identity on the wire), a field
     * {@code type}, an advisory {@code suggest}ion, whether it is {@code required}, and whether only
     * a cryptographically {@code verified} answer will do. You never name one of the person's fields — THEY decide
     * which of theirs answers it.
     *
     * <p>{@code name} is MANDATORY and must be unique within one request: everything downstream is
     * keyed by it (the stored mapping, the consent outcome, and the {@code values}/
     * {@code attestations} maps {@link #completeSignIn} returns). Two claims sharing a name are
     * rejected rather than silently coalesced.
     *
     * <p>{@code verified} is accepted only where it can be honoured: on the OIDC flow,
     * and only for a type this SDK can cryptographically attest (v1: {@code email}). Sending it on a {@code one_time}
     * request is refused with {@code invalid_request} — that leg carries no source row id, so the
     * server could neither enforce the requirement nor attest it.
     */
    public record Claim(String name, String type, String suggest, boolean required,
                        boolean verified, String label) {
        public Claim(String name, String type) {
            this(name, type, null, false, false, null);
        }
    }

    /**
     * Proof that a delivered value is the cryptographically verified one.
     *
     * <p>Present only for a {@code verified} claim under ENCRYPTED delivery. The server builds and
     * seals it against your app key — a client-supplied attestation is never accepted — so it attests
     * the server's own record of the row the person chose, which is what makes it evidence.
     *
     * <p>{@code verified} is computed BY THIS SDK, in constant time, over the plaintext it just
     * decrypted; it is never passed through from the server. <b>A {@code verified == false} entry
     * means MISMATCH and you MUST reject the value.</b> A claim ABSENT from {@code attestations()}
     * means "not attested" — never "wrong" — and must be treated as unverified.
     *
     * <p>{@code verifiedAt} carries the snapshot caveat: it attests the value as verified AT THAT
     * MOMENT, not verified today. A field loses its verification whenever the person re-saves it.
     *
     * @param hash lowercase hex
     * @param salt lowercase hex
     */
    public record Attestation(boolean verified, String hash, String salt, String verifiedAt) {
    }

    /**
     * The decrypted conclusion of {@link #completeSignIn}.
     *
     * <p>{@code user.get("sub")} IS the person's SHARE CODE and is byte-identical to the
     * id_token's {@code sub}; {@code share_code} is retained beside it and now simply equals it.
     * {@code display_name} is GONE — it is a consented {@code name} claim now, or nothing: ask for
     * {@code new Claim("name", "text")} and read {@code values().get("name")}.
     *
     * <p>{@code attestations} is an ADDITIVE sibling map keyed by the SAME claim name as
     * {@code values}, present only for a {@code verified} claim under ENCRYPTED delivery. An
     * integration that never reads it behaves exactly as before.
     */
    public record SignInResult(Map<String, String> user, String mode, boolean twoFactor,
                               Map<String, String> values, Map<String, Attestation> attestations) {
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

    /**
     * Build the consent-screen URL — the "Sign in with allme" button target.
     *
     * <p>{@code mode} is one of {@code signin} | {@code one_time} | {@code connect} | {@code 2fa_enroll}.
     */
    public String authorizeUrl(String mode, AuthorizeOptions opts) {
        if (!MODES.contains(mode)) {
            throw new ConfigException(
                "invalid mode '" + mode + "' (expected signin | one_time | connect | 2fa_enroll)");
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
        Set<String> seen = new LinkedHashSet<>();
        for (Claim c : claims) {
            if (c.type() == null || c.type().isEmpty() || NON_CLAIMABLE.contains(c.type())) {
                continue;
            }
            // `name` is the claim's identity and it is mandatory. Refused HERE rather than
            // left to the API, so the integration error surfaces at the call that made it.
            String name = c.name() == null ? "" : c.name().trim();
            if (name.isEmpty()) {
                throw new ConfigException("every claim must carry a `name` (#498)");
            }
            if (!seen.add(name)) {
                throw new ConfigException("duplicate claim name '" + name + "' (#498)");
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", name);
            entry.put("type", c.type());
            if (c.suggest() != null && !c.suggest().isEmpty()) {
                entry.put("suggest", c.suggest());
            }
            if (c.required()) {
                entry.put("required", true);
            }
            if (c.verified()) {
                entry.put("verified", true);
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
        String mode = info.get("mode") instanceof String m ? m : str(token.get("mode"));
        boolean twoFactor = Boolean.TRUE.equals(info.get("two_factor"));
        Map<String, String> values = new LinkedHashMap<>();
        Map<String, Attestation> attestations = new LinkedHashMap<>();
        if (info.get("values") instanceof Map<?, ?> raw && !raw.isEmpty()) {
            values = decryptValues(raw);
            if (info.get("values_attestation") instanceof Map<?, ?> rawAttest && !rawAttest.isEmpty()) {
                attestations = decryptAttestations(rawAttest, values);
            }
        }
        return new SignInResult(user, mode, twoFactor, values, attestations);
    }

    /**
     * Open the app-key-sealed attestations and attest each value ourselves.
     *
     * <p>A SECOND decrypt per verified claim: {@code values} is byte-identical to before, but each
     * attestation is its own {@code {"_enc":1,...}} object. A passthrough accessor handing back an
     * undecrypted blob would not be an implementation of this.
     *
     * <p>An attestation that cannot be opened or parsed is DROPPED, not surfaced as
     * {@code verified == false} — absence means "not attested" and a mismatch means "reject the
     * value", and conflating the two would turn a key or transport problem into an accusation that
     * the data was tampered with.
     */
    private Map<String, Attestation> decryptAttestations(Map<?, ?> raw, Map<String, String> values) {
        byte[] pem;
        try {
            pem = Files.readAllBytes(Path.of(config.oauthPrivateKey()));
        } catch (IOException exc) {
            return Map.of();
        }
        RSAPrivateKey key = Crypto.loadPrivateKey(pem, config.oauthKeyPassphrase());
        Map<String, Attestation> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : raw.entrySet()) {
            String slug = String.valueOf(e.getKey());
            String plaintext = values.get(slug);
            if (plaintext == null) {
                continue;
            }
            Map<String, Object> obj;
            try {
                obj = Json.parseObject(Crypto.decrypt(Wrapper.of(e.getValue()), key));
            } catch (Exception exc) {
                continue;
            }
            String hash = str(obj.get("hash"));
            String salt = str(obj.get("salt"));
            if (hash == null || hash.isEmpty() || salt == null || salt.isEmpty()) {
                continue;
            }
            String verifiedAt = str(obj.get("verified_at"));
            out.put(slug, new Attestation(
                // Recomputed here, constant-time, over the plaintext just decrypted — never trusted
                // from the server. false = the delivered value is NOT the verified one; reject it.
                Crypto.hashMatches(salt, hash, plaintext),
                hash,
                salt,
                verifiedAt == null ? "" : verifiedAt));
        }
        return out;
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

    /**
     * Poll /oauth2/result for a detached sign-in or enrollment (single-delivery).
     *
     * <p>A detached sign-in returns {@code {code, state}}; a detached {@code 2fa_enroll} returns
     * {@code {enrolled: true, state}}. Returns on the first delivered shape ({@code code} OR
     * {@code enrolled}) and never polls past it, so a one-shot enrollment result is not consumed and lost.
     */
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
                // Return on the first delivered terminal shape — a sign-in `code` OR a
                // `2fa_enroll` `enrolled` sentinel ({enrolled: true, state}). Both are one-shot;
                // returning here (rather than looping) keeps an enrollment result from being lost.
                if (body.containsKey("code") || Boolean.TRUE.equals(body.get("enrolled"))) {
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
