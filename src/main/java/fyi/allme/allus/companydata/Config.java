package fyi.allme.allus.companydata;

import fyi.allme.allus.companydata.internal.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * The whole SDK configuration. Keys live here and nowhere else.
 *
 * <p><b>Config-only key handling (a hard rule).</b> No SDK method ever takes a
 * key, passphrase, or secret as an argument. Everything cryptographic — decrypting
 * the service PEM, decrypting field values, verifying the webhook HMAC, unwrapping
 * the account-key envelope — is driven entirely by this config. The developer's
 * only key responsibility is putting the right values here.
 *
 * <p>A single JSON file holds everything; any field may be overridden by an
 * {@code ALLUS_*} env var, so secrets needn't live in the file.
 */
public final class Config {
    /** Reserved webhook-map key under which a flat {@code "webhook_secret"} is stored. */
    public static final String SINGLE_WEBHOOK_KEY = "__single__";

    private static final List<String> VALID_FORMATS = List.of("json", "xml");

    private final String apiUrl;
    private final String clientId;
    private final String clientSecret;
    private final String servicePrivateKey;   // path to the OpenSSL-encrypted PKCS#8 PEM
    private final String keyPassphrase;        // decrypts the service PEM in memory

    private final String accountPrivateKey;    // optional — only for encrypt_payload webhooks
    private final String accountPassphrase;
    private final Map<String, String> webhooks; // webhook id -> HMAC secret

    // OPTIONAL — alternative webhook auth methods, mirroring the platform's
    // per-webhook delivery auth. Configure AT MOST ONE family among
    // hmac (webhooks/webhook_secret) | bearer | basic | header | none;
    // two or more → ConfigException. See webhookAuthMethod().
    private final String webhookBearerToken;            // "Authorization: Bearer <token>"
    private final Map<String, String> webhookBasic;     // {"username","password"} → Basic auth
    private final Map<String, String> webhookHeader;    // {"name","value"} → custom header
    private final boolean webhookAuthNone;              // explicit opt-out — verify always true

    private final String cacheDir;
    private final String format;               // json | xml

    private Config(String apiUrl, String clientId, String clientSecret,
                   String servicePrivateKey, String keyPassphrase,
                   String accountPrivateKey, String accountPassphrase,
                   Map<String, String> webhooks,
                   String webhookBearerToken, Map<String, String> webhookBasic,
                   Map<String, String> webhookHeader, boolean webhookAuthNone,
                   String cacheDir, String format) {
        this.apiUrl = apiUrl;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.servicePrivateKey = servicePrivateKey;
        this.keyPassphrase = keyPassphrase;
        this.accountPrivateKey = accountPrivateKey;
        this.accountPassphrase = accountPassphrase;
        this.webhooks = webhooks;
        this.webhookBearerToken = webhookBearerToken;
        this.webhookBasic = webhookBasic;
        this.webhookHeader = webhookHeader;
        this.webhookAuthNone = webhookAuthNone;
        this.cacheDir = cacheDir;
        this.format = format;
    }

    // ── constructors (config-only keys) ─────────────────────────────────────

    /** Load from a JSON file; env vars override file values. */
    public static Config fromFile(String path) {
        Map<String, Object> data;
        try {
            String text = Files.readString(Path.of(path), StandardCharsets.UTF_8);
            data = Json.parseObject(text);
        } catch (NoSuchFileException exc) {
            throw new ConfigException("config file not found: " + path, exc);
        } catch (com.fasterxml.jackson.core.JsonProcessingException exc) {
            throw new ConfigException("config file is not valid JSON: " + path + ": " + exc.getMessage(), exc);
        } catch (IOException exc) {
            throw new ConfigException("could not read config file: " + path + ": " + exc.getMessage(), exc);
        }
        return build(data);
    }

    /** Build entirely from {@code ALLUS_*} env vars. */
    public static Config fromEnv() {
        return build(Map.of());
    }

    /** Build a Config directly (used by tests + advanced embedding). */
    public static Builder builder() {
        return new Builder();
    }

    // The env resolver — System::getenv in production; tests swap it via the
    // package-private build(data, env) overload to exercise env override.
    private static String envOf(Function<String, String> env, String name) {
        String v = env.apply(name);
        return (v != null && !v.isEmpty()) ? v : null;
    }

    // Package-private: mirrors the Python reference's Config._build(data) (used by tests).
    static Config build(Map<String, Object> data) {
        return build(data, System::getenv);
    }

    // Package-private: env-resolver-injectable build (config-test only).
    static Config build(Map<String, Object> data, Function<String, String> envLookup) {
        return doBuild(data, envLookup);
    }

    private static Config doBuild(Map<String, Object> data, Function<String, String> env) {
        // Scalar fields: env var (if set) overrides the file value.
        String apiUrl = pick(envOf(env, "ALLUS_API_URL"), data.get("api_url"));
        String clientId = pick(envOf(env, "ALLUS_CLIENT_ID"), data.get("client_id"));
        String clientSecret = pick(envOf(env, "ALLUS_CLIENT_SECRET"), data.get("client_secret"));
        String servicePrivateKey = pick(envOf(env, "ALLUS_SERVICE_PRIVATE_KEY"), data.get("service_private_key"));
        String keyPassphrase = pick(envOf(env, "ALLUS_KEY_PASSPHRASE"), data.get("key_passphrase"));
        String accountPrivateKey = pick(envOf(env, "ALLUS_ACCOUNT_PRIVATE_KEY"), data.get("account_private_key"));
        String accountPassphrase = pick(envOf(env, "ALLUS_ACCOUNT_PASSPHRASE"), data.get("account_passphrase"));
        String cacheDir = pick(envOf(env, "ALLUS_CACHE_DIR"), data.get("cache_dir"));
        String format = pick(envOf(env, "ALLUS_FORMAT"), data.get("format"));

        // Webhook secrets: the "webhooks" map plus the flat "webhook_secret"
        // shortcut (and its env override), normalized into a single dict.
        Map<String, String> webhooks = new LinkedHashMap<>();
        Object fileWebhooks = data.get("webhooks");
        if (fileWebhooks != null) {
            if (!(fileWebhooks instanceof Map<?, ?> m)) {
                throw new ConfigException("\"webhooks\" must be an object mapping webhook id -> secret");
            }
            for (Map.Entry<?, ?> e : m.entrySet()) {
                webhooks.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
            }
        }
        String flatSecret = envOf(env, "ALLUS_WEBHOOK_SECRET");
        if (flatSecret == null && data.get("webhook_secret") != null) {
            flatSecret = String.valueOf(data.get("webhook_secret"));
        }
        if (flatSecret != null) {
            webhooks.put(SINGLE_WEBHOOK_KEY, flatSecret);
        }

        // Alternative webhook auth methods (file-config; no env overrides). Validate object shapes.
        String webhookBearerToken = null;
        Object bearer = data.get("webhook_bearer_token");
        if (bearer != null && !String.valueOf(bearer).isEmpty()) {
            webhookBearerToken = String.valueOf(bearer);
        }

        Map<String, String> webhookBasic = null;
        Object basic = data.get("webhook_basic");
        if (basic != null) {
            String username = mapStr(basic, "username");
            String password = mapStr(basic, "password");
            if (!(basic instanceof Map<?, ?>) || username == null || username.isEmpty()
                || password == null || password.isEmpty()) {
                throw new ConfigException(
                    "\"webhook_basic\" must be an object with non-empty \"username\" and \"password\"");
            }
            webhookBasic = new LinkedHashMap<>();
            webhookBasic.put("username", username);
            webhookBasic.put("password", password);
        }

        Map<String, String> webhookHeader = null;
        Object hdr = data.get("webhook_header");
        if (hdr != null) {
            String name = mapStr(hdr, "name");
            String value = mapStr(hdr, "value");
            if (!(hdr instanceof Map<?, ?>) || name == null || name.isEmpty()
                || value == null || value.isEmpty()) {
                throw new ConfigException(
                    "\"webhook_header\" must be an object with non-empty \"name\" and \"value\"");
            }
            webhookHeader = new LinkedHashMap<>();
            webhookHeader.put("name", name);
            webhookHeader.put("value", value);
        }

        boolean webhookAuthNone = Boolean.TRUE.equals(data.get("webhook_auth_none"));

        // At most one webhook auth method may be configured.
        List<String> present = new ArrayList<>();
        if (!webhooks.isEmpty()) {
            present.add("hmac");
        }
        if (webhookBearerToken != null) {
            present.add("bearer");
        }
        if (webhookBasic != null) {
            present.add("basic");
        }
        if (webhookHeader != null) {
            present.add("header");
        }
        if (webhookAuthNone) {
            present.add("none");
        }
        if (present.size() > 1) {
            throw new ConfigException(
                "configure at most one webhook auth method (found: " + String.join(", ", present) + ")");
        }

        // Required fields (fail fast).
        Map<String, String> required = new LinkedHashMap<>();
        required.put("api_url", apiUrl);
        required.put("client_id", clientId);
        required.put("client_secret", clientSecret);
        required.put("service_private_key", servicePrivateKey);
        required.put("key_passphrase", keyPassphrase);
        StringBuilder missing = new StringBuilder();
        for (Map.Entry<String, String> e : required.entrySet()) {
            if (e.getValue() == null || e.getValue().isEmpty()) {
                if (missing.length() > 0) {
                    missing.append(", ");
                }
                missing.append(e.getKey());
            }
        }
        if (missing.length() > 0) {
            throw new ConfigException("missing required config field(s): " + missing);
        }

        // Validate the wire format if supplied; default json.
        if (format == null) {
            format = "json";
        } else {
            format = format.toLowerCase();
            if (!VALID_FORMATS.contains(format)) {
                throw new ConfigException("invalid \"format\": '" + format + "' (expected one of " + VALID_FORMATS + ")");
            }
        }
        if (cacheDir == null) {
            cacheDir = "./allus-cache";
        }

        return new Config(apiUrl, clientId, clientSecret, servicePrivateKey, keyPassphrase,
            accountPrivateKey, accountPassphrase, webhooks,
            webhookBearerToken, webhookBasic, webhookHeader, webhookAuthNone,
            cacheDir, format);
    }

    private static String pick(String envValue, Object fileValue) {
        if (envValue != null) {
            return envValue;
        }
        return fileValue != null ? String.valueOf(fileValue) : null;
    }

    /** Read a sub-key from a possibly-Map value as a string (null when absent/not a map). */
    private static String mapStr(Object obj, String key) {
        if (obj instanceof Map<?, ?> m) {
            Object v = m.get(key);
            return v != null ? String.valueOf(v) : null;
        }
        return null;
    }

    // ── accessors ───────────────────────────────────────────────────────────

    public String apiUrl() {
        return apiUrl;
    }

    public String clientId() {
        return clientId;
    }

    public String clientSecret() {
        return clientSecret;
    }

    public String servicePrivateKey() {
        return servicePrivateKey;
    }

    public String keyPassphrase() {
        return keyPassphrase;
    }

    public String accountPrivateKey() {
        return accountPrivateKey;
    }

    public String accountPassphrase() {
        return accountPassphrase;
    }

    public Map<String, String> webhooks() {
        return webhooks;
    }

    public String webhookBearerToken() {
        return webhookBearerToken;
    }

    public Map<String, String> webhookBasic() {
        return webhookBasic;
    }

    public Map<String, String> webhookHeader() {
        return webhookHeader;
    }

    public boolean webhookAuthNone() {
        return webhookAuthNone;
    }

    public String cacheDir() {
        return cacheDir;
    }

    public String format() {
        return format;
    }

    /**
     * Resolve the HMAC secret for a webhook id.
     *
     * <p>Falls back to the single-webhook shortcut secret when there is no id or
     * no id-specific match. The webhook helpers read this — application code never
     * passes a secret in. The only argument is a webhook <em>id</em>, never a secret.
     */
    public String webhookSecret(String webhookId) {
        if (webhookId != null && webhooks.containsKey(webhookId)) {
            return webhooks.get(webhookId);
        }
        return webhooks.get(SINGLE_WEBHOOK_KEY);
    }

    /** Convenience: resolve the single-webhook shortcut secret. */
    public String webhookSecret() {
        return webhookSecret(null);
    }

    /**
     * The single configured webhook auth method, or {@code null} if none is set.
     *
     * <p>Returns one of {@code "hmac"} | {@code "bearer"} | {@code "basic"} |
     * {@code "header"} | {@code "none"}. Config loading guarantees at most one is
     * configured, so the order here is only a tie-break that never triggers.
     */
    public String webhookAuthMethod() {
        if (webhookAuthNone) {
            return "none";
        }
        if (webhookBearerToken != null) {
            return "bearer";
        }
        if (webhookBasic != null) {
            return "basic";
        }
        if (webhookHeader != null) {
            return "header";
        }
        if (!webhooks.isEmpty()) {
            return "hmac";
        }
        return null;
    }

    /** A small builder for embedding/tests (the public constructors are file/env). */
    public static final class Builder {
        private final Map<String, Object> data = new HashMap<>();

        public Builder apiUrl(String v) {
            data.put("api_url", v);
            return this;
        }

        public Builder clientId(String v) {
            data.put("client_id", v);
            return this;
        }

        public Builder clientSecret(String v) {
            data.put("client_secret", v);
            return this;
        }

        public Builder servicePrivateKey(String v) {
            data.put("service_private_key", v);
            return this;
        }

        public Builder keyPassphrase(String v) {
            data.put("key_passphrase", v);
            return this;
        }

        public Builder accountPrivateKey(String v) {
            data.put("account_private_key", v);
            return this;
        }

        public Builder accountPassphrase(String v) {
            data.put("account_passphrase", v);
            return this;
        }

        public Builder cacheDir(String v) {
            data.put("cache_dir", v);
            return this;
        }

        public Builder format(String v) {
            data.put("format", v);
            return this;
        }

        public Builder webhooks(Map<String, String> v) {
            data.put("webhooks", v);
            return this;
        }

        public Builder webhookSecret(String v) {
            data.put("webhook_secret", v);
            return this;
        }

        public Builder webhookBearerToken(String v) {
            data.put("webhook_bearer_token", v);
            return this;
        }

        public Builder webhookBasic(Map<String, String> v) {
            data.put("webhook_basic", v);
            return this;
        }

        public Builder webhookHeader(Map<String, String> v) {
            data.put("webhook_header", v);
            return this;
        }

        public Builder webhookAuthNone(boolean v) {
            data.put("webhook_auth_none", v);
            return this;
        }

        public Config build() {
            return Config.build(data);
        }
    }
}
