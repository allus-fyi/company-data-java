package fyi.allme.allus.examples.identity;

import com.sun.net.httpserver.HttpExchange;

import fyi.allme.allus.companydata.ApiException;
import fyi.allme.allus.companydata.Client;
import fyi.allme.allus.companydata.Connection;
import fyi.allme.allus.companydata.OAuthClient;
import fyi.allme.allus.companydata.TwoFactorChallenge;
import fyi.allme.allus.companydata.TwoFactorResult;
import fyi.allme.allus.companydata.Value;

import fyi.allme.allus.examples.Http;
import fyi.allme.allus.examples.Json;
import fyi.allme.allus.examples.Runtime;
import fyi.allme.allus.examples.Util;

import com.nimbusds.jwt.JWT;
import com.nimbusds.oauth2.sdk.AuthorizationCode;
import com.nimbusds.oauth2.sdk.AuthorizationCodeGrant;
import com.nimbusds.oauth2.sdk.AuthorizationGrant;
import com.nimbusds.oauth2.sdk.ResponseType;
import com.nimbusds.oauth2.sdk.Scope;
import com.nimbusds.oauth2.sdk.TokenRequest;
import com.nimbusds.oauth2.sdk.TokenResponse;
import com.nimbusds.oauth2.sdk.auth.ClientAuthentication;
import com.nimbusds.oauth2.sdk.auth.ClientSecretPost;
import com.nimbusds.oauth2.sdk.auth.Secret;
import com.nimbusds.oauth2.sdk.http.HTTPResponse;
import com.nimbusds.oauth2.sdk.id.ClientID;
import com.nimbusds.oauth2.sdk.id.Issuer;
import com.nimbusds.oauth2.sdk.id.State;
import com.nimbusds.oauth2.sdk.pkce.CodeChallengeMethod;
import com.nimbusds.oauth2.sdk.pkce.CodeVerifier;
import com.nimbusds.openid.connect.sdk.AuthenticationRequest;
import com.nimbusds.openid.connect.sdk.Nonce;
import com.nimbusds.openid.connect.sdk.OIDCTokenResponse;
import com.nimbusds.openid.connect.sdk.OIDCTokenResponseParser;
import com.nimbusds.openid.connect.sdk.claims.IDTokenClaimsSet;
import com.nimbusds.openid.connect.sdk.op.OIDCProviderMetadata;
import com.nimbusds.openid.connect.sdk.validators.IDTokenValidator;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static fyi.allme.allus.examples.Util.action;
import static fyi.allme.allus.examples.Util.asInt;
import static fyi.allme.allus.examples.Util.asStringList;
import static fyi.allme.allus.examples.Util.calls;
import static fyi.allme.allus.examples.Util.envelope;
import static fyi.allme.allus.examples.Util.strOr;
import static fyi.allme.allus.examples.Util.strOrNull;

/**
 * The identity scenario handlers (config-file model): each handler reaches the SDK's intended top-level
 * surface ({@link OAuthClient} / {@link Client} / {@code TwoFactorClient}) — or, for the OIDC scenario
 * 5, the standard Nimbus OIDC library — and NEVER performs raw platform HTTP. Detached / challenge waits
 * are short-cycled ({@code timeout=2}) inside {@link #pollBody}.
 *
 * <p>Settings flow: the browser POSTs a scenario's setup to {@code /config}, written to a canonical SDK
 * config FILE ({@code .runtime/config/{id}.json}). {@link #start}/{@link #enroll} then build the SDK from
 * that file via the role-appropriate file constructor ({@link OAuthClient#fromConfig} for the idw role;
 * {@link Client#fromConfig} for the service reads) and run OFF the config — exactly as a real integrator
 * wires the SDK. A {@code /start} with no saved config → 409 not_configured.
 */
public final class IdentityHandlers {
    /** id → "runnable" | "guide". Scenario 7 is the guide card (no /start). */
    private static final Map<Integer, String> SCENARIOS = Map.ofEntries(
        Map.entry(1, "runnable"), Map.entry(2, "runnable"), Map.entry(3, "runnable"),
        Map.entry(4, "runnable"), Map.entry(5, "runnable"),
        Map.entry(7, "guide"), Map.entry(8, "runnable"));

    /** Scenarios that also read live values through the service data {@link Client}. */
    private static final Set<Integer> SERVICE_SCENARIOS = Set.of(4, 8);
    /** Scenarios that build an OAuth consent URL via {@link OAuthClient} (need the authorize base). */
    private static final Set<Integer> OAUTH_URL_SCENARIOS = Set.of(1, 2, 3, 4, 8);
    /**
     * Scenarios whose {@link OAuthClient#completeSignIn} response can carry claim values (userinfo
     * {@code values} non-empty) and therefore need the OAuth app private key configured to decrypt them:
     * mode one_time and mode connect, both delivered as app-key ciphertext through userinfo. Mode signin
     * (scenarios 1, 2) never carries values; scenario 8 never calls this leg at all; scenario 5 runs the
     * Nimbus OIDC library instead of this SDK's decrypt path.
     */
    private static final Set<Integer> CLAIM_VALUE_SCENARIOS = Set.of(3, 4);

    private static final String DEFAULT_API_URL = "https://api.allme.fyi";
    private static final String DEFAULT_AUTHORIZE_BASE = OAuthClient.DEFAULT_AUTHORIZE_URL; // web.allme.fyi/auth

    /**
     * Refusal when the request carries no Host header, so the browser's origin is unknown. There is
     * NO default host: substituting one (localhost) silently sends the round-trip to a DIFFERENT origin
     * than the browser is on — a different localStorage and a redirect URI the OAuth app never registered.
     */
    private static final String NO_ORIGIN =
        "no_origin — this request carried no Host header, so the OAuth redirect URI cannot be derived "
        + "from the origin your browser is using. Open the example by its address (http://<host>:<port>/) "
        + "and save the setup again.";

    /** Refusal when the scenario's saved config holds no redirect URI to complete the exchange with. */
    private static final String NO_STORED_ORIGIN =
        "no_origin — the saved config has no oauth_redirect_uri. Save the scenario setup again from the "
        + "browser you will complete the sign-in in.";

    /** The short-cycled poll budget: ONE logical SDK wait of ~2s per poll (contract §"reads"). */
    private static final long POLL_TIMEOUT_S = 2;
    private static final long POLL_INTERVAL_S = 2;

    /**
     * The "what just happened" trace. Every entry is {@code <SDK method> — <what that call did in
     * THIS scenario>}, appended AT the call site, in the order the calls were made; an entry wrapped in
     * parentheses is a step that is deliberately NOT an SDK call. Keep them in step when this handler
     * changes: the panel is headed "What just happened", and a list that no longer matches the code is
     * worse than a short one.
     */
    private static final String CALL_IDW_BUILD = "OAuthClient.fromConfig — builds the RP client from the saved config file: client id, secret and the registered redirect URI";
    private static final String CALL_AUTH_SIGNIN = "OAuthClient.authorizeUrl — the consent URL the person is sent to (mode signin, response_mode redirect, PKCE S256, state = this run id)";
    private static final String CALL_AUTH_SIGNIN_DETACHED = "OAuthClient.authorizeUrl — the sign-in URL behind the link + QR (mode signin, response_mode detached, PKCE S256, state = this run id)";
    private static final String CALL_AUTH_ONE_TIME = "OAuthClient.authorizeUrl — the consent URL the person is sent to (mode one_time, claims email + phone, PKCE S256, state = this run id)";
    private static final String CALL_AUTH_CONNECT = "OAuthClient.authorizeUrl — the consent URL the person is sent to (mode connect, PKCE S256, state = this run id)";
    private static final String CALL_AUTH_ENROLL = "OAuthClient.authorizeUrl — the enrollment URL the person is sent to (mode 2fa_enroll, response_mode redirect)";
    private static final String CALL_AUTH_ENROLL_DETACHED = "OAuthClient.authorizeUrl — the enrollment URL behind the link + QR (mode 2fa_enroll, response_mode detached)";
    private static final String CALL_POLL_SIGNIN = "OAuthClient.pollResult — polls POST /oauth2/result until the phone delivers the code (one 2s-bounded call per browser poll)";
    private static final String CALL_POLL_ENROLL = "OAuthClient.pollResult — polls POST /oauth2/result until the phone delivers {enrolled: true} (one 2s-bounded call per browser poll)";
    private static final String CALL_COMPLETE_SIGNIN = "OAuthClient.completeSignIn — exchanges the code + PKCE verifier at POST /oauth2/token, then reads GET /api/oauth/userinfo; mode signin returns the identity only, no claim values";
    private static final String CALL_COMPLETE_ONE_TIME = "OAuthClient.completeSignIn — exchanges the code + PKCE verifier at POST /oauth2/token, reads GET /api/oauth/userinfo, and decrypts every claim value with the OAuth app private key";
    private static final String CALL_COMPLETE_CONNECT = "OAuthClient.completeSignIn — exchanges the code + PKCE verifier at POST /oauth2/token, reads GET /api/oauth/userinfo, and decrypts the consented claim values with the OAuth app private key; the connection's live values still come separately from the data client below";
    private static final String CALL_ENROLLED_CALLBACK = "(callback ?enrolled=true) — the redirect-leg enrollment outcome; there is nothing to exchange, so no further SDK call";
    private static final String CALL_SERVICE_BUILD = "Client.fromConfig — builds the SERVICE-role data client from the saved config file: client credentials plus the service private key, decrypted with its passphrase";
    private static final String CALL_CONNECTIONS_LIVE = "Client.connections — pages GET /api/company-data/connections and decrypts each person's values with the service key; the run keeps the one whose share code just signed in";
    private static final String CALL_TWO_FACTOR = "Client.twoFactor — the service-2FA sub-client, on the same data-client credentials";
    private static final String CALL_CHALLENGE = "TwoFactorClient.challenge — POST /api/service-2fa/challenges for the person's share code with a per-run idempotency key; returns the challenge id, plus matching digits when the service has number matching on";
    private static final String CALL_WAIT_RESULT = "TwoFactorClient.waitForResult — polls GET /api/service-2fa/challenges/{id} until the status leaves pending: approved, denied, expired or revoked (one 2s-bounded call per browser poll; the first terminal read burns the result)";
    private static final String CALL_OIDC_DISCOVERY = "(oidc) OIDCProviderMetadata.resolve — discovery: fetches /.well-known/openid-configuration from the configured API base";
    private static final String CALL_OIDC_AUTH_URL = "(oidc) AuthenticationRequest.toURI — the authorization URL (scope openid profile email, PKCE S256, nonce, state = this run id)";
    private static final String CALL_OIDC_TOKEN = "(oidc) TokenRequest.send — exchanges the code at the discovered token endpoint (client_secret_post + PKCE verifier)";
    private static final String CALL_OIDC_VERIFY = "(oidc) IDTokenValidator.validate — verifies the id_token against the JWKS: signature, issuer, audience and nonce; the claims shown are that verified token's";

    private final Runtime rt;

    public IdentityHandlers(Runtime rt) {
        this.rt = rt;
    }

    /** This family's contribution to GET /api/meta: scenarios 1–5, 7–8 (7 is the guide card). */
    public List<Map<String, Object>> scenarios() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (int id : new TreeSet<>(SCENARIOS.keySet())) {
            out.add(Map.of("id", id, "kind", SCENARIOS.get(id)));
        }
        return out;
    }

    private String key(int id) {
        return String.valueOf(id);
    }

    // ── POST /api/scenarios/{id}/config ────────────────────────────────────────

    public void config(HttpExchange ex, int id) throws IOException {
        if (!"runnable".equals(SCENARIOS.get(id))) {
            Http.json(ex, 404, Map.of("error", "not_found"));
            return;
        }
        // The redirect URI is derived from THIS request's origin and from nothing else. Refuse
        // rather than invent a host: the suite renders this sentence on Save.
        if (requestHost(ex).isEmpty()) {
            Http.json(ex, 400, Map.of("error", NO_ORIGIN));
            return;
        }
        Map<String, Object> in = Http.body(ex);

        // Canonical SDK config — the idw role for every OAuth scenario.
        Map<String, Object> cfg = new LinkedHashMap<>();
        String apiUrl = strOr(in.get("apiUrl"), "");
        cfg.put("api_url", (apiUrl.isEmpty() ? DEFAULT_API_URL : apiUrl).replaceAll("/+$", ""));
        cfg.put("oauth_client_id", strOr(in.get("oauthClientId"), ""));
        cfg.put("oauth_redirect_uri", redirectUri(ex));
        String secret = strOr(in.get("oauthClientSecret"), "");
        if (!secret.isEmpty()) {
            cfg.put("oauth_client_secret", secret);
        }

        // Any scenario whose run can carry claim values (CLAIM_VALUE_SCENARIOS) needs the OAuth app
        // private key to decrypt them.
        if (CLAIM_VALUE_SCENARIOS.contains(id)) {
            String pem = strOr(in.get("oauthPrivateKeyPem"), "");
            if (!pem.isEmpty()) {
                cfg.put("oauth_private_key", rt.materializeConfigKey(pem));
            }
            String pass = strOr(in.get("oauthKeyPassphrase"), "");
            if (!pass.isEmpty()) {
                cfg.put("oauth_key_passphrase", pass);
            }
        }

        // Scenarios 4/8 also read live values via the service data Client — add the service-role keys.
        if (SERVICE_SCENARIOS.contains(id)) {
            cfg.put("client_id", strOr(in.get("clientId"), ""));
            cfg.put("client_secret", strOr(in.get("clientSecret"), ""));
            String sPem = strOr(in.get("servicePrivateKeyPem"), "");
            if (!sPem.isEmpty()) {
                cfg.put("service_private_key", rt.materializeConfigKey(sPem));
            }
            cfg.put("key_passphrase", strOr(in.get("keyPassphrase"), ""));
        }

        String configPath = rt.writeConfig(key(id), cfg);

        // Demo-only run parameters (NOT SDK Config fields) → meta sidecar.
        Map<String, Object> meta = new LinkedHashMap<>();
        if (OAUTH_URL_SCENARIOS.contains(id)) {
            String base = strOr(in.get("authorizeBase"), "");
            meta.put("authorize_base", base.isEmpty() ? DEFAULT_AUTHORIZE_BASE : base);
        }
        if (id == 3) {
            meta.put("claims", claims(in));
        }
        if (id == 8) {
            meta.put("share_code", strOr(in.get("shareCode"), ""));
            String ctx = strOr(in.get("context"), "");
            if (!ctx.isEmpty()) {
                meta.put("context", ctx);
            }
        }
        rt.writeConfigMeta(key(id), meta);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("configPath", configPath);
        Http.json(ex, 200, out);
    }

    // ── POST /api/scenarios/{id}/start ─────────────────────────────────────────

    public void start(HttpExchange ex, int id) throws IOException {
        if (!"runnable".equals(SCENARIOS.get(id))) {
            Http.json(ex, 404, Map.of("error", "not_found"));
            return;
        }
        if (!rt.hasConfig(key(id))) {
            Http.json(ex, 409, Map.of("error", "not_configured"));
            return;
        }
        String runId = rt.newRunId();
        Map<String, Object> run = newRun(id, runId);

        switch (id) {
            case 1, 3, 4 -> { // redirect sign-in / one_time / connect
                Pkce.Pair pkce = Pkce.generate();
                run.put("verifier", pkce.verifier());
                String mode = id == 1 ? "signin" : (id == 3 ? "one_time" : "connect");
                run.put("calls", calls(CALL_IDW_BUILD,
                    id == 3 ? CALL_AUTH_ONE_TIME : (id == 4 ? CALL_AUTH_CONNECT : CALL_AUTH_SIGNIN)));
                OAuthClient oauth = oauthClientFor(id);
                OAuthClient.AuthorizeOptions opts = new OAuthClient.AuthorizeOptions()
                    .state(runId).responseMode("redirect").codeChallenge(pkce.challenge());
                if (id == 3) {
                    opts.claims(claimObjects(asStringList(rt.readConfigMeta(key(id)).get("claims"))));
                }
                String url = oauth.authorizeUrl(mode, opts);
                rt.writeRun(runId, run);
                Http.json(ex, 200, envelope(runId, action("redirect", "url", url)));
            }
            case 2 -> { // detached sign-in
                Pkce.Pair pkce = Pkce.generate();
                run.put("verifier", pkce.verifier());
                run.put("wait", "detached_signin");
                run.put("calls", calls(CALL_IDW_BUILD, CALL_AUTH_SIGNIN_DETACHED));
                OAuthClient oauth = oauthClientFor(id);
                String url = oauth.authorizeUrl("signin", new OAuthClient.AuthorizeOptions()
                    .state(runId).responseMode("detached").codeChallenge(pkce.challenge()));
                rt.writeRun(runId, run);
                Http.json(ex, 200, envelope(runId, action("detached", "url", url)));
            }
            case 5 -> { // OIDC login (Nimbus OIDC library)
                CodeVerifier verifier = new CodeVerifier();
                Nonce nonce = new Nonce();
                run.put("verifier", verifier.getValue());
                run.put("nonce", nonce.getValue());
                OIDCProviderMetadata provider = oidcProvider(id);
                URI url = buildOidcAuthUri(provider, id, runId, verifier, nonce);
                run.put("calls", calls(CALL_OIDC_DISCOVERY, CALL_OIDC_AUTH_URL));
                rt.writeRun(runId, run);
                Http.json(ex, 200, envelope(runId, action("redirect", "url", url.toString())));
            }
            case 8 -> { // standalone service-2FA — the challenge step
                Map<String, Object> meta = rt.readConfigMeta(key(id));
                String shareCode = strOr(meta.get("share_code"), "");
                String context = strOrNull(meta.get("context"));
                String idempotencyKey = ("demo-" + runId);
                idempotencyKey = idempotencyKey.substring(0, Math.min(64, idempotencyKey.length()));
                run.put("challengeIdemKey", idempotencyKey);
                run.put("wait", "challenge");
                run.put("calls", calls(CALL_SERVICE_BUILD, CALL_TWO_FACTOR, CALL_CHALLENGE));
                Client client = serviceClientFor(id);
                TwoFactorChallenge challenge = client.twoFactor().challenge(shareCode, idempotencyKey, context);
                run.put("challengeId", challenge.challengeId());
                rt.writeRun(runId, run);
                Map<String, Object> act = new LinkedHashMap<>();
                act.put("type", "challenge");
                act.put("matchingDigits", challenge.matchingDigits());
                Http.json(ex, 200, envelope(runId, act));
            }
            default -> Http.json(ex, 404, Map.of("error", "not_found"));
        }
    }

    // ── POST /api/scenarios/{id}/enroll (scenario 8) ───────────────────────────

    public void enroll(HttpExchange ex, int id) throws IOException {
        if (id != 8) {
            Http.json(ex, 404, Map.of("error", "not_found"));
            return;
        }
        if (!rt.hasConfig(key(id))) {
            Http.json(ex, 409, Map.of("error", "not_configured"));
            return;
        }
        Map<String, Object> in = Http.body(ex);
        String responseMode = "detached".equals(strOr(in.get("responseMode"), "redirect")) ? "detached" : "redirect";
        String runId = rt.newRunId();

        OAuthClient oauth = oauthClientFor(id);
        String url = oauth.authorizeUrl("2fa_enroll", new OAuthClient.AuthorizeOptions()
            .state(runId).responseMode(responseMode));

        Map<String, Object> run = new LinkedHashMap<>();
        run.put("family", "identity");
        run.put("scenario", key(8));
        run.put("isEnroll", true);
        run.put("status", "pending");
        run.put("state", runId);
        run.put("calls", calls(CALL_IDW_BUILD,
            responseMode.equals("detached") ? CALL_AUTH_ENROLL_DETACHED : CALL_AUTH_ENROLL));
        run.put("wait", responseMode.equals("detached") ? "detached_enroll" : "enroll_redirect");
        rt.writeRun(runId, run);

        Http.json(ex, 200, envelope(runId, action(responseMode, "url", url)));
    }

    // ── GET /callback ──────────────────────────────────────────────────────────

    public void callback(HttpExchange ex) throws IOException {
        Map<String, String> q = Http.query(ex);
        String state = q.getOrDefault("state", "");
        Map<String, Object> run = rt.readRun(state);
        if (run == null) {
            Http.redirect(ex, "/?error=unknown_run");
            return;
        }
        int id = asInt(run.get("scenario"));

        try {
            if ("true".equals(q.get("enrolled"))) {
                // Redirect-leg enrollment outcome — nothing to exchange; record it.
                run.put("status", "done");
                run.put("result", Map.of("enrolled", true));
                appendCall(run, CALL_ENROLLED_CALLBACK);
            } else if (q.get("code") != null && !q.get("code").isEmpty()) {
                String code = q.get("code");
                run = (id == 5) ? completeOidc(run, code) : completeSignin(run, code);
            } else {
                run.put("status", "failed");
                run.put("error", "callback missing code / enrolled");
            }
        } catch (Throwable t) {
            run.put("status", "failed");
            run.put("error", String.valueOf(t.getMessage()));
        }

        rt.writeRun(state, run);
        Http.redirect(ex, "/?scenario=" + id + "&run=" + state); // state is 32-hex — URL-safe as-is
    }

    // ── GET /api/runs/{runId} (identity) ────────────────────────────────────────

    /**
     * The identity run-poll body: advance a still-pending detached / challenge run one short cycle, then
     * report {@code {status, calls, result?, error?}}. A completed run returns its cached outcome unchanged.
     */
    public Map<String, Object> pollBody(String runId, Map<String, Object> run) {
        if ("pending".equals(strOr(run.get("status"), "pending"))) {
            run = advance(run);
            rt.writeRun(runId, run);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", strOr(run.get("status"), "pending"));
        out.put("calls", run.getOrDefault("calls", new ArrayList<>()));
        if (run.containsKey("result")) {
            out.put("result", run.get("result"));
        }
        if (run.containsKey("error")) {
            out.put("error", run.get("error"));
        }
        return out;
    }

    /**
     * Short-cycled advance for a pending run awaiting a detached / challenge outcome. ONE SDK wait with
     * {@code timeout=2} per poll; the SDK's LOGICAL "not completed within Ns" timeout is treated as
     * still-pending, while a real transport failure is a failed run. Clients are rebuilt from the run's
     * scenario config file — the run stores no credentials.
     */
    private Map<String, Object> advance(Map<String, Object> run) {
        String wait = strOrNull(run.get("wait"));
        int id = asInt(run.get("scenario"));
        try {
            if ("detached_signin".equals(wait)) {
                appendCall(run, CALL_POLL_SIGNIN);
                OAuthClient oauth = oauthClientFor(id);
                Map<String, Object> b = oauth.pollResult(strOr(run.get("state"), ""), POLL_TIMEOUT_S, POLL_INTERVAL_S);
                String code = strOr(b.get("code"), "");
                if (!code.isEmpty()) {
                    run = completeSignin(run, code);
                }
            } else if ("detached_enroll".equals(wait)) {
                appendCall(run, CALL_POLL_ENROLL);
                OAuthClient oauth = oauthClientFor(id);
                Map<String, Object> b = oauth.pollResult(strOr(run.get("state"), ""), POLL_TIMEOUT_S, POLL_INTERVAL_S);
                if (Boolean.TRUE.equals(b.get("enrolled"))) {
                    run.put("status", "done");
                    run.put("result", Map.of("enrolled", true));
                }
            } else if ("challenge".equals(wait)) {
                appendCall(run, CALL_WAIT_RESULT);
                Client client = serviceClientFor(id);
                TwoFactorResult res = client.twoFactor()
                    .waitForResult(strOr(run.get("challengeId"), ""), POLL_TIMEOUT_S, POLL_INTERVAL_S);
                run.put("status", "done");
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("status", res.status());
                result.put("completed_at", res.completedAt());
                run.put("result", result);
            }
            // else (redirect / continue-on-phone): completion arrives via /callback — stay pending.
        } catch (ApiException e) {
            // The SDK poll helpers signal a LOGICAL "not completed within {n}s" timeout as
            // ApiException(status=0) with that sentinel message. A real transport failure ALSO surfaces
            // as ApiException(status=0), so match the sentinel: only the logical timeout is "still
            // pending"; a real network failure is a failed run.
            if (e.status() == 0 && String.valueOf(e.getMessage()).contains("not completed within")) {
                return run; // logical short-cycle timeout → still pending
            }
            run.put("status", "failed");
            run.put("error", String.valueOf(e.getMessage()));
        } catch (Throwable t) {
            run.put("status", "failed");
            run.put("error", String.valueOf(t.getMessage()));
        }
        return run;
    }

    // ── SDK / OIDC completion helpers ──────────────────────────────────────────

    /**
     * Complete a redirect / detached SIGN-IN (scenarios 1, 2, 3, 4): exchange + read identity via
     * {@link OAuthClient#completeSignIn}, and for connect read the person's LIVE values via the service
     * data {@link Client}.
     */
    private Map<String, Object> completeSignin(Map<String, Object> run, String code) {
        int id = asInt(run.get("scenario"));
        appendCall(run, id == 3 ? CALL_COMPLETE_ONE_TIME : (id == 4 ? CALL_COMPLETE_CONNECT : CALL_COMPLETE_SIGNIN));
        OAuthClient oauth = oauthClientFor(id);
        OAuthClient.SignInResult out = oauth.completeSignIn(code, strOrNull(run.get("verifier")));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("user", out.user());
        result.put("mode", out.mode());
        result.put("two_factor", out.twoFactor());
        result.put("values", out.values());
        // The raw app-key ciphertext each decrypted value above came from — pairs with "values"
        // by claim name so the panel can show a decrypt actually ran on real bytes.
        result.put("values_cipher", out.valuesCipher());

        if (id == 4) {
            // Connect: read the person's LIVE values via the service data client.
            String shareCode = out.user() != null ? strOr(out.user().get("share_code"), "") : "";
            appendCall(run, CALL_SERVICE_BUILD);
            Client client = serviceClientFor(id);
            appendCall(run, CALL_CONNECTIONS_LIVE);
            Map<String, Object> live = new LinkedHashMap<>();
            for (Connection conn : client.connections()) {
                if (!shareCode.isEmpty() && shareCode.equals(conn.shareCode())) {
                    live = serializeValues(conn.values());
                    break;
                }
            }
            result.put("live_values", live);
        }

        run.put("status", "done");
        run.put("result", result);
        return run;
    }

    /**
     * Complete an OIDC sign-in (scenario 5) via the Nimbus OIDC library — the id_token is verified
     * (signature + issuer + audience + nonce) by {@link IDTokenValidator}.
     */
    private Map<String, Object> completeOidc(Map<String, Object> run, String code) {
        int id = asInt(run.get("scenario"));
        try {
            OIDCProviderMetadata provider = oidcProvider(id);
            Map<String, Object> cfg = loadConfig(id);
            String clientId = strOr(cfg.get("oauth_client_id"), "");
            String clientSecret = strOr(cfg.get("oauth_client_secret"), "");
            URI redirect = new URI(configRedirectUri(id));

            CodeVerifier verifier = new CodeVerifier(strOr(run.get("verifier"), ""));
            AuthorizationGrant grant = new AuthorizationCodeGrant(new AuthorizationCode(code), redirect, verifier);
            ClientAuthentication clientAuth = new ClientSecretPost(new ClientID(clientId), new Secret(clientSecret));
            Scope scope = new Scope("openid", "profile", "email");

            TokenRequest tokenReq = new TokenRequest(provider.getTokenEndpointURI(), clientAuth, grant, scope);
            appendCall(run, CALL_OIDC_TOKEN);
            HTTPResponse httpResp = tokenReq.toHTTPRequest().send();
            TokenResponse tokenResp = OIDCTokenResponseParser.parse(httpResp);
            if (!tokenResp.indicatesSuccess()) {
                throw new RuntimeException("OIDC token endpoint rejected the code: "
                    + tokenResp.toErrorResponse().getErrorObject());
            }
            OIDCTokenResponse success = (OIDCTokenResponse) tokenResp.toSuccessResponse();
            JWT idToken = success.getOIDCTokens().getIDToken();
            if (idToken == null) {
                throw new RuntimeException("OIDC token response carried no id_token");
            }

            IDTokenValidator validator = new IDTokenValidator(
                new Issuer(strOr(cfg.get("api_url"), "")),
                new ClientID(clientId),
                com.nimbusds.jose.JWSAlgorithm.RS256,
                provider.getJWKSetURI().toURL());
            appendCall(run, CALL_OIDC_VERIFY);
            IDTokenClaimsSet claims = validator.validate(idToken, new Nonce(strOr(run.get("nonce"), "")));

            run.put("status", "done");
            run.put("result", Map.of("claims", claims.toJSONObject()));
            return run;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("OIDC completion failed: " + e.getMessage(), e);
        }
    }

    // ── SDK / OIDC client builders — built from the persisted config FILE ───────

    /**
     * Build the OAuth client OFF the scenario's config file via the idw file constructor
     * ({@link OAuthClient#fromConfig}). NOTE: the Java SDK's public OAuth surface uses the SDK's
     * default (deployed) authorize base — the acceptance path. A non-default authorize base is not
     * settable through the public constructor, so the local-stack authorize base is not applied here
     * (documented in the README); {@code api_url} (which drives OIDC discovery and the token exchange)
     * IS honoured from the config file.
     */
    private OAuthClient oauthClientFor(int id) {
        return OAuthClient.fromConfig(rt.configPathFor(key(id)).toString());
    }

    /** Build the service data client OFF the scenario's config file (service role). */
    private Client serviceClientFor(int id) {
        return Client.fromConfig(rt.configPathFor(key(id)).toString());
    }

    /** Discover the OIDC provider metadata from the configured {@code api_url} (issuer override). */
    private OIDCProviderMetadata oidcProvider(int id) {
        try {
            return OIDCProviderMetadata.resolve(new Issuer(strOr(loadConfig(id).get("api_url"), "")));
        } catch (Exception e) {
            throw new RuntimeException("OIDC discovery failed: " + e.getMessage(), e);
        }
    }

    private URI buildOidcAuthUri(OIDCProviderMetadata provider, int id, String runId,
                                 CodeVerifier verifier, Nonce nonce) {
        try {
            Map<String, Object> cfg = loadConfig(id);
            AuthenticationRequest req = new AuthenticationRequest.Builder(
                new ResponseType("code"),
                new Scope("openid", "profile", "email"),
                new ClientID(strOr(cfg.get("oauth_client_id"), "")),
                new URI(configRedirectUri(id)))
                .endpointURI(provider.getAuthorizationEndpointURI())
                .state(new State(runId))
                .nonce(nonce)
                .codeChallenge(verifier, CodeChallengeMethod.S256)
                .build();
            return req.toURI();
        } catch (Exception e) {
            throw new RuntimeException("OIDC authorization URL build failed: " + e.getMessage(), e);
        }
    }

    // ── config plumbing ────────────────────────────────────────────────────────

    private Map<String, Object> loadConfig(int id) {
        try {
            return Json.parse(Files.readAllBytes(rt.configPathFor(key(id))));
        } catch (IOException e) {
            return new LinkedHashMap<>();
        }
    }

    /**
     * The redirect URI recorded in the scenario's config file (used by the OIDC library) — the SAME value
     * the authorize URL carried, so the two legs of the exchange cannot diverge. An absent record is a
     * loud failure, never a substituted host.
     */
    private String configRedirectUri(int id) {
        String v = strOr(loadConfig(id).get("oauth_redirect_uri"), "");
        if (v.isEmpty()) {
            throw new IllegalStateException(NO_STORED_ORIGIN);
        }
        return v;
    }

    /** The origin THIS request arrived on — the only source the redirect URI is ever derived from. */
    private static String requestHost(HttpExchange ex) {
        String host = ex.getRequestHeaders().getFirst("Host");
        return host == null ? "" : host.trim();
    }

    /**
     * The registered redirect URI: http://{host}/callback, host = the origin the browser actually used.
     * The server binds all interfaces, so a phone on the LAN saves ITS origin into the config file
     * and the OAuth round-trip returns to the phone rather than to the phone's own localhost. Never falls
     * back to a hardcoded host — `127.0.0.1` and `localhost` are DIFFERENT origins for redirect
     * matching and for browser storage alike, so a substituted default drops the developer on an origin
     * whose localStorage never held the setup and whose URI the OAuth app never registered.
     */
    private String redirectUri(HttpExchange ex) {
        String host = requestHost(ex);
        if (host.isEmpty()) {
            throw new IllegalStateException(NO_ORIGIN);
        }
        return "http://" + host + "/callback";
    }

    // ── value / claim shaping ────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static List<String> claims(Map<String, Object> in) {
        Object raw = in.get("claims");
        if (raw instanceof List<?> l && !l.isEmpty()) {
            List<String> out = new ArrayList<>();
            for (Object o : l) {
                out.add(String.valueOf(o));
            }
            return out;
        }
        return List.of("email", "phone"); // a small default claim set
    }

    /**
     * A claim carries a mandatory, unique {@code name} — the key {@code values} and
     * {@code attestations} come back under. The demo's config lists claim TYPES, so the type doubles
     * as the name here; a real integration usually names them for its own domain ("billing_email").
     */
    private static List<OAuthClient.Claim> claimObjects(List<String> types) {
        List<OAuthClient.Claim> out = new ArrayList<>();
        for (String t : types) {
            out.add(new OAuthClient.Claim(t, t));
        }
        return out;
    }

    /** Shape a slug→{@link Value} map into a JSON-friendly display map for the "what just happened" panel. */
    private static Map<String, Object> serializeValues(Map<String, Value> values) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (values == null) {
            return out;
        }
        for (Map.Entry<String, Value> e : values.entrySet()) {
            Value v = e.getValue();
            Map<String, Object> m = new LinkedHashMap<>();
            Object val = v.value();
            m.put("value", (val instanceof String || val instanceof Map || val instanceof Number
                || val instanceof Boolean) ? val : (val == null ? null : String.valueOf(val)));
            m.put("live", v.live());
            m.put("verified", v.verified());
            m.put("updated_at", v.updatedAt() != null ? v.updatedAt().toString() : null);
            out.put(e.getKey(), m);
        }
        return out;
    }

    // ── run helpers ──────────────────────────────────────────────────────────────

    private static Map<String, Object> newRun(int id, String runId) {
        Map<String, Object> run = new LinkedHashMap<>();
        run.put("family", "identity");
        run.put("scenario", String.valueOf(id));
        run.put("status", "pending");
        run.put("state", runId);
        run.put("calls", new ArrayList<String>());
        return run;
    }

    /**
     * Record a call on a run's "what just happened" trace through the shared, deduping implementation
     * (standards §1): appending unconditionally would duplicate an entry whenever a handler runs twice
     * for the same run.
     */
    private static void appendCall(Map<String, Object> run, String name) {
        Util.recordCall(run, name);
    }
}
