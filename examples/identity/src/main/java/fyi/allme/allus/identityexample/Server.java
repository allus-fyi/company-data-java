package fyi.allme.allus.identityexample;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import fyi.allme.allus.companydata.ApiException;
import fyi.allme.allus.companydata.Client;
import fyi.allme.allus.companydata.Connection;
import fyi.allme.allus.companydata.OAuthClient;
import fyi.allme.allus.companydata.TwoFactorChallenge;
import fyi.allme.allus.companydata.TwoFactorResult;
import fyi.allme.allus.companydata.Value;

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
import java.io.OutputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The demo-backend contract (config-file model). One class, one worker: HTTP dispatch → handler →
 * the SDK's intended top-level surface (or the Nimbus OIDC library for scenarios 5/6). Handlers NEVER
 * perform raw platform HTTP and NEVER block on the SDK's long defaults — detached / challenge waits are
 * short-cycled ({@code timeout=2}) inside {@code GET /api/runs}.
 *
 * <p>Settings flow: the browser POSTs a scenario's setup values to
 * {@code POST /api/scenarios/{id}/config}, which writes them to a canonical SDK config FILE
 * ({@code .runtime/config/{id}.json}). {@code /start} and {@code /enroll} then build the SDK from that
 * file via the role-appropriate file constructor ({@link OAuthClient#fromConfig} for the idw role;
 * {@link Client#fromConfig} for the service reads) and run OFF the config — exactly as a real
 * integrator wires the SDK. The request body of {@code /start} is ignored; a {@code /start} with no
 * saved config → 409 not_configured.
 */
final class Server {
    static final int CONTRACT_VERSION = 1;
    static final String SDK = "java";

    /** id → "runnable" | "guide". Scenario 7 is the guide card (no /start). */
    private static final Map<Integer, String> SCENARIOS = Map.ofEntries(
        Map.entry(1, "runnable"), Map.entry(2, "runnable"), Map.entry(3, "runnable"),
        Map.entry(4, "runnable"), Map.entry(5, "runnable"), Map.entry(6, "runnable"),
        Map.entry(7, "guide"), Map.entry(8, "runnable"));

    /** Scenarios that also read live values through the service data {@link Client}. */
    private static final Set<Integer> SERVICE_SCENARIOS = Set.of(4, 8);
    /** Scenarios that build an OAuth consent URL via {@link OAuthClient} (need the authorize base). */
    private static final Set<Integer> OAUTH_URL_SCENARIOS = Set.of(1, 2, 3, 4, 8);

    private static final String DEFAULT_API_URL = "https://api.allme.fyi";
    private static final String DEFAULT_AUTHORIZE_BASE = OAuthClient.DEFAULT_AUTHORIZE_URL; // web.allme.fyi/auth

    /** The short-cycled poll budget: ONE logical SDK wait of ~2s per poll (contract §"reads"). */
    private static final long POLL_TIMEOUT_S = 2;
    private static final long POLL_INTERVAL_S = 2;

    private static final Pattern P_CONFIG = Pattern.compile("^/api/scenarios/(\\d+)/config$");
    private static final Pattern P_START = Pattern.compile("^/api/scenarios/(\\d+)/start$");
    private static final Pattern P_ENROLL = Pattern.compile("^/api/scenarios/(\\d+)/enroll$");
    private static final Pattern P_CLEAR = Pattern.compile("^/api/scenarios/(\\d+)/clear$");
    private static final Pattern P_RUN = Pattern.compile("^/api/runs/([0-9a-f]{32})$");

    private final Runtime rt;
    private final Path frontendDir;
    private final String sdkVersion;
    private final int port;

    Server(Runtime rt, Path frontendDir, String sdkVersion, int port) {
        this.rt = rt;
        this.frontendDir = frontendDir;
        this.sdkVersion = sdkVersion;
        this.port = port;
    }

    void attach(HttpServer http) {
        http.createContext("/", this::dispatch);
    }

    // ── dispatch ───────────────────────────────────────────────────────────────

    private void dispatch(HttpExchange ex) throws IOException {
        try {
            rt.ensureDirs();
            rt.sweep(); // lazy TTL sweep on every request

            String method = ex.getRequestMethod();
            String path = URLDecoder.decode(ex.getRequestURI().getPath(), StandardCharsets.UTF_8);
            Matcher m;

            if (path.equals("/api/meta") && method.equals("GET")) {
                meta(ex);
            } else if (path.equals("/callback") && method.equals("GET")) {
                callback(ex);
            } else if (path.equals("/api/clear") && method.equals("POST")) {
                rt.clearAll();
                json(ex, 200, Map.of("ok", true));
            } else if ((m = P_CONFIG.matcher(path)).matches() && method.equals("POST")) {
                config(ex, Integer.parseInt(m.group(1)));
            } else if ((m = P_START.matcher(path)).matches() && method.equals("POST")) {
                start(ex, Integer.parseInt(m.group(1)));
            } else if ((m = P_ENROLL.matcher(path)).matches() && method.equals("POST")) {
                enroll(ex, Integer.parseInt(m.group(1)));
            } else if ((m = P_CLEAR.matcher(path)).matches() && method.equals("POST")) {
                rt.clearScenario(Integer.parseInt(m.group(1)));
                json(ex, 200, Map.of("ok", true));
            } else if ((m = P_RUN.matcher(path)).matches() && method.equals("GET")) {
                run(ex, m.group(1));
            } else if (path.startsWith("/api/")) {
                json(ex, 404, Map.of("error", "not_found"));
            } else {
                serveStatic(ex, path);
            }
        } catch (Throwable t) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("error", "server_error");
            err.put("message", String.valueOf(t.getMessage()));
            json(ex, 500, err);
        } finally {
            ex.close();
        }
    }

    // ── GET /api/meta ────────────────────────────────────────────────────────

    private void meta(HttpExchange ex) throws IOException {
        List<Map<String, Object>> scenarios = new ArrayList<>();
        for (int id = 1; id <= 8; id++) {
            scenarios.add(Map.of("id", id, "kind", SCENARIOS.get(id)));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sdk", SDK);
        out.put("sdkVersion", sdkVersion);
        out.put("contractVersion", CONTRACT_VERSION);
        out.put("scenarios", scenarios);
        json(ex, 200, out);
    }

    // ── POST /api/scenarios/{id}/config ────────────────────────────────────────

    private void config(HttpExchange ex, int id) throws IOException {
        if (!"runnable".equals(SCENARIOS.get(id))) {
            json(ex, 404, Map.of("error", "not_found"));
            return;
        }
        Map<String, Object> in = body(ex);

        // Canonical SDK config — the idw role for every OAuth scenario.
        Map<String, Object> cfg = new LinkedHashMap<>();
        String apiUrl = strOr(in.get("apiUrl"), "");
        cfg.put("api_url", (apiUrl.isEmpty() ? DEFAULT_API_URL : apiUrl).replaceAll("/+$", ""));
        cfg.put("oauth_client_id", strOr(in.get("oauthClientId"), ""));
        cfg.put("oauth_redirect_uri", redirectUri());
        String secret = strOr(in.get("oauthClientSecret"), "");
        if (!secret.isEmpty()) {
            cfg.put("oauth_client_secret", secret);
        }

        // Scenario 3 (one_time): the OAuth app private key decrypts the claim values.
        if (id == 3) {
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

        String configPath = rt.writeConfig(id, cfg);

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
        rt.writeConfigMeta(id, meta);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("configPath", configPath);
        json(ex, 200, out);
    }

    // ── POST /api/scenarios/{id}/start ─────────────────────────────────────────

    private void start(HttpExchange ex, int id) throws IOException {
        if (!"runnable".equals(SCENARIOS.get(id))) {
            json(ex, 404, Map.of("error", "not_found"));
            return;
        }
        if (!rt.hasConfig(id)) {
            json(ex, 409, Map.of("error", "not_configured"));
            return;
        }
        String runId = rt.newRunId();
        Map<String, Object> run = newRun(id, runId);

        switch (id) {
            case 1, 3, 4 -> { // redirect sign-in / one_time / connect
                Pkce.Pair pkce = Pkce.generate();
                run.put("verifier", pkce.verifier());
                String mode = id == 1 ? "signin" : (id == 3 ? "one_time" : "connect");
                OAuthClient oauth = oauthClientFor(id);
                OAuthClient.AuthorizeOptions opts = new OAuthClient.AuthorizeOptions()
                    .state(runId).responseMode("redirect").codeChallenge(pkce.challenge());
                if (id == 3) {
                    opts.claims(claimObjects(asStringList(rt.readConfigMeta(id).get("claims"))));
                }
                String url = oauth.authorizeUrl(mode, opts);
                run.put("calls", calls("OAuthClient.authorizeUrl"));
                rt.writeRun(runId, run);
                json(ex, 200, envelope(runId, action("redirect", "url", url)));
            }
            case 2 -> { // detached sign-in
                Pkce.Pair pkce = Pkce.generate();
                run.put("verifier", pkce.verifier());
                run.put("wait", "detached_signin");
                OAuthClient oauth = oauthClientFor(id);
                String url = oauth.authorizeUrl("signin", new OAuthClient.AuthorizeOptions()
                    .state(runId).responseMode("detached").codeChallenge(pkce.challenge()));
                run.put("calls", calls("OAuthClient.authorizeUrl"));
                rt.writeRun(runId, run);
                json(ex, 200, envelope(runId, action("detached", "url", url)));
            }
            case 5, 6 -> { // OIDC login / continue-on-phone (Nimbus OIDC library)
                CodeVerifier verifier = new CodeVerifier();
                Nonce nonce = new Nonce();
                run.put("verifier", verifier.getValue());
                run.put("nonce", nonce.getValue());
                OIDCProviderMetadata provider = oidcProvider(id);
                URI url = buildOidcAuthUri(provider, id, runId, verifier, nonce);
                run.put("calls", calls("(oidc) OIDCProviderMetadata.resolve", "(oidc) AuthenticationRequest.toURI"));
                rt.writeRun(runId, run);
                json(ex, 200, envelope(runId, action("redirect", "url", url.toString())));
            }
            case 8 -> { // standalone service-2FA — the challenge step
                Map<String, Object> meta = rt.readConfigMeta(id);
                String shareCode = strOr(meta.get("share_code"), "");
                String context = strOrNull(meta.get("context"));
                String idempotencyKey = ("demo-" + runId);
                idempotencyKey = idempotencyKey.substring(0, Math.min(64, idempotencyKey.length()));
                run.put("challengeIdemKey", idempotencyKey);
                run.put("wait", "challenge");
                Client client = serviceClientFor(id);
                TwoFactorChallenge challenge = client.twoFactor().challenge(shareCode, idempotencyKey, context);
                run.put("challengeId", challenge.challengeId());
                run.put("calls", calls("Client.twoFactor", "TwoFactorClient.challenge"));
                rt.writeRun(runId, run);
                Map<String, Object> act = new LinkedHashMap<>();
                act.put("type", "challenge");
                act.put("matchingDigits", challenge.matchingDigits());
                json(ex, 200, envelope(runId, act));
            }
            default -> json(ex, 404, Map.of("error", "not_found"));
        }
    }

    // ── POST /api/scenarios/{id}/enroll (scenario 8) ───────────────────────────

    private void enroll(HttpExchange ex, int id) throws IOException {
        if (id != 8) {
            json(ex, 404, Map.of("error", "not_found"));
            return;
        }
        if (!rt.hasConfig(id)) {
            json(ex, 409, Map.of("error", "not_configured"));
            return;
        }
        Map<String, Object> in = body(ex);
        String responseMode = "detached".equals(strOr(in.get("responseMode"), "redirect")) ? "detached" : "redirect";
        String runId = rt.newRunId();

        OAuthClient oauth = oauthClientFor(id);
        String url = oauth.authorizeUrl("2fa_enroll", new OAuthClient.AuthorizeOptions()
            .state(runId).responseMode(responseMode));

        Map<String, Object> run = new LinkedHashMap<>();
        run.put("scenario", 8);
        run.put("isEnroll", true);
        run.put("status", "pending");
        run.put("state", runId);
        run.put("calls", calls("OAuthClient.authorizeUrl"));
        run.put("wait", responseMode.equals("detached") ? "detached_enroll" : "enroll_redirect");
        rt.writeRun(runId, run);

        json(ex, 200, envelope(runId, action(responseMode, "url", url)));
    }

    // ── GET /callback ──────────────────────────────────────────────────────────

    private void callback(HttpExchange ex) throws IOException {
        Map<String, String> q = query(ex);
        String state = q.getOrDefault("state", "");
        Map<String, Object> run = rt.readRun(state);
        if (run == null) {
            redirect(ex, "/?error=unknown_run");
            return;
        }
        int id = asInt(run.get("scenario"));

        try {
            if ("true".equals(q.get("enrolled"))) {
                // Redirect-leg enrollment outcome (#436) — nothing to exchange; record it.
                run.put("status", "done");
                run.put("result", Map.of("enrolled", true));
                appendCall(run, "callback(enrolled=true)");
            } else if (q.get("code") != null && !q.get("code").isEmpty()) {
                String code = q.get("code");
                run = (id == 5 || id == 6) ? completeOidc(run, code) : completeSignin(run, code);
            } else {
                run.put("status", "failed");
                run.put("error", "callback missing code / enrolled");
            }
        } catch (Throwable t) {
            run.put("status", "failed");
            run.put("error", String.valueOf(t.getMessage()));
        }

        rt.writeRun(state, run);
        redirect(ex, "/?scenario=" + id + "&run=" + state); // state is 32-hex — URL-safe as-is
    }

    // ── GET /api/runs/{runId} ──────────────────────────────────────────────────

    private void run(HttpExchange ex, String runId) throws IOException {
        Map<String, Object> run = rt.readRun(runId);
        if (run == null) {
            json(ex, 404, Map.of("error", "not_found"));
            return;
        }
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
        json(ex, 200, out);
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
                OAuthClient oauth = oauthClientFor(id);
                Map<String, Object> b = oauth.pollResult(strOr(run.get("state"), ""), POLL_TIMEOUT_S, POLL_INTERVAL_S);
                appendCall(run, "OAuthClient.pollResult");
                String code = strOr(b.get("code"), "");
                if (!code.isEmpty()) {
                    run = completeSignin(run, code);
                }
            } else if ("detached_enroll".equals(wait)) {
                OAuthClient oauth = oauthClientFor(id);
                Map<String, Object> b = oauth.pollResult(strOr(run.get("state"), ""), POLL_TIMEOUT_S, POLL_INTERVAL_S);
                appendCall(run, "OAuthClient.pollResult");
                if (Boolean.TRUE.equals(b.get("enrolled"))) {
                    run.put("status", "done");
                    run.put("result", Map.of("enrolled", true));
                }
            } else if ("challenge".equals(wait)) {
                Client client = serviceClientFor(id);
                TwoFactorResult res = client.twoFactor()
                    .waitForResult(strOr(run.get("challengeId"), ""), POLL_TIMEOUT_S, POLL_INTERVAL_S);
                appendCall(run, "TwoFactorClient.waitForResult");
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
        OAuthClient oauth = oauthClientFor(id);
        OAuthClient.SignInResult out = oauth.completeSignIn(code, strOrNull(run.get("verifier")));
        appendCall(run, "OAuthClient.completeSignIn");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("user", out.user());
        result.put("mode", out.mode());
        result.put("two_factor", out.twoFactor());
        result.put("values", out.values());

        if (id == 4) {
            // Connect: read the person's LIVE values via the service data client.
            String shareCode = out.user() != null ? strOr(out.user().get("share_code"), "") : "";
            Client client = serviceClientFor(id);
            Map<String, Object> live = new LinkedHashMap<>();
            for (Connection conn : client.connections()) {
                if (!shareCode.isEmpty() && shareCode.equals(conn.shareCode())) {
                    live = serializeValues(conn.values());
                    break;
                }
            }
            appendCall(run, "Client.connections");
            result.put("live_values", live);
        }

        run.put("status", "done");
        run.put("result", result);
        return run;
    }

    /**
     * Complete an OIDC sign-in (scenarios 5/6) via the Nimbus OIDC library — the id_token is verified
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
            HTTPResponse httpResp = tokenReq.toHTTPRequest().send();
            appendCall(run, "(oidc) TokenRequest.send");
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
            IDTokenClaimsSet claims = validator.validate(idToken, new Nonce(strOr(run.get("nonce"), "")));
            appendCall(run, "(oidc) IDTokenValidator.validate");

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
        return OAuthClient.fromConfig(rt.configPathFor(id).toString());
    }

    /** Build the service data client OFF the scenario's config file (service role). */
    private Client serviceClientFor(int id) {
        return Client.fromConfig(rt.configPathFor(id).toString());
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
            return Json.parse(Files.readAllBytes(rt.configPathFor(id)));
        } catch (IOException e) {
            return new LinkedHashMap<>();
        }
    }

    private String configRedirectUri(int id) {
        String v = strOr(loadConfig(id).get("oauth_redirect_uri"), "");
        return v.isEmpty() ? redirectUri() : v;
    }

    /** The registered redirect URI: http://localhost:{port}/callback. */
    private String redirectUri() {
        return "http://localhost:" + port + "/callback";
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

    private static List<OAuthClient.Claim> claimObjects(List<String> types) {
        List<OAuthClient.Claim> out = new ArrayList<>();
        for (String t : types) {
            out.add(new OAuthClient.Claim(t));
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

    // ── run/envelope helpers ─────────────────────────────────────────────────────

    private static Map<String, Object> newRun(int id, String runId) {
        Map<String, Object> run = new LinkedHashMap<>();
        run.put("scenario", id);
        run.put("status", "pending");
        run.put("state", runId);
        run.put("calls", new ArrayList<String>());
        return run;
    }

    private static List<String> calls(String... names) {
        return new ArrayList<>(List.of(names));
    }

    @SuppressWarnings("unchecked")
    private static void appendCall(Map<String, Object> run, String name) {
        Object c = run.get("calls");
        List<Object> list = c instanceof List ? (List<Object>) c : new ArrayList<>();
        list.add(name);
        run.put("calls", list);
    }

    private static Map<String, Object> envelope(String runId, Map<String, Object> action) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("runId", runId);
        out.put("action", action);
        return out;
    }

    private static Map<String, Object> action(String type, String key, Object value) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("type", type);
        a.put(key, value);
        return a;
    }

    // ── HTTP plumbing ────────────────────────────────────────────────────────────

    private Map<String, Object> body(HttpExchange ex) throws IOException {
        return Json.parse(ex.getRequestBody().readAllBytes());
    }

    private static Map<String, String> query(HttpExchange ex) {
        Map<String, String> out = new LinkedHashMap<>();
        String raw = ex.getRequestURI().getRawQuery();
        if (raw == null || raw.isEmpty()) {
            return out;
        }
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            String k = eq >= 0 ? pair.substring(0, eq) : pair;
            String v = eq >= 0 ? pair.substring(eq + 1) : "";
            out.put(URLDecoder.decode(k, StandardCharsets.UTF_8), URLDecoder.decode(v, StandardCharsets.UTF_8));
        }
        return out;
    }

    private void json(HttpExchange ex, int status, Object data) throws IOException {
        byte[] b = Json.writeBytes(data);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, b.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(b);
        }
    }

    private void redirect(HttpExchange ex, String location) throws IOException {
        ex.getResponseHeaders().set("Location", location);
        ex.sendResponseHeaders(302, -1);
    }

    private void serveStatic(HttpExchange ex, String path) throws IOException {
        String rel = path.equals("/") ? "/index.html" : path;
        Path root = frontendDir.toAbsolutePath().normalize();
        Path full = root.resolve(rel.replaceFirst("^/+", "")).normalize();

        // Path-traversal guard + SPA fallback to index.html.
        if (!full.startsWith(root) || !Files.isRegularFile(full)) {
            Path index = root.resolve("index.html");
            if (Files.isRegularFile(index)) {
                byte[] b = Files.readAllBytes(index);
                ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
                ex.sendResponseHeaders(200, b.length);
                try (OutputStream os = ex.getResponseBody()) {
                    os.write(b);
                }
                return;
            }
            byte[] b = "bundle not found".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "text/plain");
            ex.sendResponseHeaders(404, b.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(b);
            }
            return;
        }

        byte[] b = Files.readAllBytes(full);
        ex.getResponseHeaders().set("Content-Type", mime(full));
        ex.sendResponseHeaders(200, b.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(b);
        }
    }

    private static String mime(Path path) {
        String name = path.getFileName().toString().toLowerCase();
        int dot = name.lastIndexOf('.');
        String ext = dot >= 0 ? name.substring(dot + 1) : "";
        return switch (ext) {
            case "html" -> "text/html; charset=utf-8";
            case "js", "mjs" -> "text/javascript; charset=utf-8";
            case "css" -> "text/css; charset=utf-8";
            case "json", "map" -> "application/json; charset=utf-8";
            case "svg" -> "image/svg+xml";
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "gif" -> "image/gif";
            case "ico" -> "image/x-icon";
            case "woff" -> "font/woff";
            case "woff2" -> "font/woff2";
            case "ttf" -> "font/ttf";
            case "webp" -> "image/webp";
            default -> "application/octet-stream";
        };
    }

    // ── small typed getters ──────────────────────────────────────────────────────

    private static int asInt(Object v) {
        if (v instanceof Number n) {
            return n.intValue();
        }
        try {
            return v == null ? 0 : Integer.parseInt(String.valueOf(v));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String strOr(Object v, String fallback) {
        return v == null ? fallback : String.valueOf(v);
    }

    private static String strOrNull(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    @SuppressWarnings("unchecked")
    private static List<String> asStringList(Object v) {
        List<String> out = new ArrayList<>();
        if (v instanceof List<?> l) {
            for (Object o : l) {
                out.add(String.valueOf(o));
            }
        }
        return out;
    }
}
