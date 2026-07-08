package fyi.allme.allus.companydata;

import fyi.allme.allus.companydata.internal.Http;
import fyi.allme.allus.companydata.internal.ModelDeps;
import fyi.allme.allus.companydata.internal.Json;

import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The CUSTOMER-role client (b2b, #168).
 *
 * <p>{@code CustomerClient} is what a connecting company uses to consume and answer another
 * company's service over its {@code acct_*} credentials: list company↔company connections,
 * provide/edit typed consent answers, read (and decrypt) issued documents, run contract flows,
 * drain the account change feed, and verify account-level webhooks. It reuses the same crash-safe
 * {@link Pump}, webhook helpers, and hybrid-crypto core as the service {@link Client}.
 *
 * <p><b>NO sign/accept methods (spec D6):</b> signing/accepting a contract is a deliberate human
 * step-up that stays portal-only; a machine {@code acct_*} token is rejected by the API for those
 * routes.
 */
public final class CustomerClient {

    private static final String CONN = "/api/company-connections";
    private static final String CONSENTS = "/api/company-connections/consents";
    private static final String CUSTOMER_CHANGES = "/api/customer/changes";
    private static final String KEYS = "/api/keys";

    private final Config config;
    private final Http http;
    private final RSAPrivateKey accountKey;
    private final ModelDeps deps;
    private final Map<String, RSAPublicKey> pubKeyCache = new LinkedHashMap<>();
    private final Map<String, RSAPublicKey> serviceKeyCache = new LinkedHashMap<>();
    private Pump pump;

    public CustomerClient(Config config) {
        this(config, null);
    }

    public CustomerClient(Config config, Http http) {
        if (config.customerClientId() == null || config.customerClientSecret() == null) {
            throw new ConfigException(
                "CustomerClient requires customer_client_id + customer_client_secret "
                + "(load with Config.fromCustomerFile / fromCustomerEnv)");
        }
        this.config = config;
        // The transport authenticates as the acct_* client — hand Http a config whose
        // clientId/secret are the customer pair.
        this.http = http != null ? http : new Http(config.toCustomerHttpConfig());
        this.accountKey = Webhooks.loadAccountKey(config);
        // No slug catalog for customer events (they never carry a person's secret field).
        this.deps = new ModelDeps(this::decryptAccount, slug -> null, valueUrl -> Wrapper.of(fetchBinary(valueUrl)));
    }

    /** Build from a customer-role JSON config file. */
    public static CustomerClient fromConfig(String path) {
        return new CustomerClient(Config.fromCustomerFile(path));
    }

    /** Build entirely from {@code ALLUS_*} env vars (customer role). */
    public static CustomerClient fromEnv() {
        return new CustomerClient(Config.fromCustomerEnv());
    }

    // ── connections ─────────────────────────────────────────────────────────

    public List<CustomerConnection> connections() {
        return CustomerConnection.listFromApi(http.get(CONN));
    }

    public CustomerConnection connection(String id) {
        return CustomerConnection.fromApi(asMap(http.get(CONN + "/" + id)));
    }

    // ── consents (typed answers) ──────────────────────────────────────────────

    public Object provideConsent(String consentId, List<TypedAnswer> answers, String companyCode, String serviceCode) {
        List<Object> decisions = encryptTyped(answers, companyCode, serviceCode);
        return http.post(CONSENTS + "/" + consentId + "/provide", Map.of("decisions", decisions));
    }

    public Object declineConsent(String consentId) {
        return http.post(CONSENTS + "/" + consentId + "/decline", null);
    }

    public Object editAnswers(String connectionId, String serviceLinkId, List<TypedAnswer> answers,
                              String companyCode, String serviceCode) {
        List<Object> decisions = encryptTyped(answers, companyCode, serviceCode);
        return http.put(CONN + "/" + connectionId + "/services/" + serviceLinkId + "/mappings",
            Map.of("decisions", decisions));
    }

    // ── documents (account-key decrypt; NO sign/accept — D6) ───────────────────

    @SuppressWarnings("unchecked")
    public List<Document> documents(CustomerConnection connection) {
        List<Document> out = new ArrayList<>();
        for (CustomerServiceLink svc : connection.services()) {
            Object docs = svc.raw().get("documents");
            if (docs instanceof List<?> list) {
                for (Object d : list) {
                    if (d instanceof Map<?, ?> m) {
                        out.add(Document.fromApi((Map<String, Object>) m, this::decryptAccount));
                    }
                }
            }
        }
        Object docs = connection.raw().get("documents");
        if (docs instanceof List<?> list) {
            for (Object d : list) {
                if (d instanceof Map<?, ?> m) {
                    out.add(Document.fromApi((Map<String, Object>) m, this::decryptAccount));
                }
            }
        }
        return out;
    }

    public Object documentFile(String connectionId, String documentId) {
        Object body = http.get(CONN + "/" + connectionId + "/documents/" + documentId + "/file");
        if (body instanceof Map<?, ?> m) {
            Object enc = m.get("encrypted");
            if (Boolean.TRUE.equals(enc) && m.containsKey("value")) {
                return parseJson(decryptAccount(m.get("value")));
            }
            if (m.containsKey("_enc")) {
                return parseJson(decryptAccount(m));
            }
        }
        return body;
    }

    public Object cancelDocument(String connectionId, String documentId, String note) {
        Object payload = note != null ? Map.of("note", note) : null;
        return http.post(CONN + "/" + connectionId + "/documents/" + documentId + "/cancel", payload);
    }

    // ── contract flows ─────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    public List<FlowRun> flowRuns(String connectionId) {
        Object body = http.get(CONN + "/" + connectionId + "/flow-runs");
        Object items = (body instanceof Map<?, ?> m) ? m.get("runs") : body;
        List<FlowRun> out = new ArrayList<>();
        if (items instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> map) {
                    out.add(FlowRun.fromApi((Map<String, Object>) map));
                }
            }
        }
        return out;
    }

    public FlowRun flowRun(String connectionId, String runId) {
        return FlowRun.fromApi(asMap(http.get(CONN + "/" + connectionId + "/flow-runs/" + runId)));
    }

    public Object submitFlowAnswers(String connectionId, String runId, Map<String, Object> body) {
        return http.post(CONN + "/" + connectionId + "/flow-runs/" + runId + "/answers", body);
    }

    public Object declineFlowRun(String connectionId, String runId) {
        return http.post(CONN + "/" + connectionId + "/flow-runs/" + runId + "/decline", null);
    }

    /** Encrypt one answer value for one flow party per the P4 key rule. */
    public Map<String, Object> encryptFlowAnswer(String plaintext, FlowParty party,
                                                 String companyCode, String serviceCode) {
        RSAPublicKey pub = party.isOwner()
            ? serviceKey(companyCode, serviceCode)
            : batchKey(party.userId());
        if (pub == null) {
            throw new ConfigException("no public key available for party " + party.userId());
        }
        return Crypto.encryptForPublicKey(plaintext, pub);
    }

    // ── change feed (P2 account feed) ───────────────────────────────────────────

    public Pump pump() {
        if (pump == null) {
            pump = new Pump(config, this::fetchChanges, this::decryptChange);
        }
        return pump;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> fetchChanges(int limit) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("limit", String.valueOf(limit));
        Object body = http.get(CUSTOMER_CHANGES, params);
        Object itemsObj = (body instanceof Map<?, ?> m) ? m.get("changes") : body;
        List<Map<String, Object>> out = new ArrayList<>();
        if (itemsObj instanceof List<?> items) {
            for (Object o : items) {
                if (o instanceof Map<?, ?> map) {
                    out.add((Map<String, Object>) map);
                }
            }
        }
        return out;
    }

    private Change decryptChange(Map<String, Object> event) {
        return Change.fromApi(event, deps);
    }

    public void processChanges(Consumer<Change> handler) {
        pump().processChanges(handler);
    }

    public void processChanges(Consumer<Change> handler, Pump.Options options) {
        pump().processChanges(handler, options);
    }

    public List<Change> drainBatch(int max) {
        return pump().drainBatch(max);
    }

    public List<Map<String, Object>> deadLetters() {
        return pump().deadLetters();
    }

    public int retryDeadLetters(Consumer<Change> handler) {
        return pump().retryDeadLetters(handler);
    }

    // ── account-level webhook receiver helpers (config-driven) ──────────────────

    public boolean verifyWebhook(Object rawBody, Map<String, ?> headers) {
        return Webhooks.verifyWebhook(rawBody, headers, config);
    }

    public Change parseWebhook(Object rawBody, Map<String, ?> headers) {
        return Webhooks.parseWebhook(rawBody, headers, config, deps, accountKey);
    }

    public Change handleWebhook(Object rawBody, Map<String, ?> headers) {
        return Webhooks.handleWebhook(rawBody, headers, config, deps, accountKey);
    }

    // ── internals ────────────────────────────────────────────────────────────────

    private String decryptAccount(Object wrapper) {
        if (accountKey == null) {
            throw new ConfigException("account_private_key is required to decrypt this value");
        }
        return Crypto.decrypt(Wrapper.of(wrapper), accountKey);
    }

    private Object fetchBinary(String valueUrl) {
        Object body = http.get(valueUrl);
        if (body instanceof Map<?, ?> m && m.containsKey("value")) {
            return m.get("value");
        }
        return body;
    }

    private List<Object> encryptTyped(List<TypedAnswer> answers, String companyCode, String serviceCode) {
        RSAPublicKey pub = serviceKey(companyCode, serviceCode);
        if (pub == null) {
            throw new ConfigException("no service key for " + companyCode + "/" + serviceCode);
        }
        List<Object> out = new ArrayList<>();
        for (TypedAnswer a : answers) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("request_field_id", a.requestFieldId());
            entry.put("kind", a.kind() != null ? a.kind() : "typed");
            entry.put("value", Crypto.encryptForPublicKey(a.value(), pub));
            out.add(entry);
        }
        return out;
    }

    private RSAPublicKey serviceKey(String companyCode, String serviceCode) {
        String key = companyCode + "/" + serviceCode;
        if (!serviceKeyCache.containsKey(key)) {
            Object body = http.get(KEYS + "/" + companyCode + "/" + serviceCode);
            String spki = (body instanceof Map<?, ?> m && m.get("public_key") != null) ? String.valueOf(m.get("public_key")) : null;
            serviceKeyCache.put(key, (spki != null && !spki.isEmpty()) ? Crypto.loadPublicKey(spki) : null);
        }
        return serviceKeyCache.get(key);
    }

    private RSAPublicKey batchKey(String userId) {
        if (!pubKeyCache.containsKey(userId)) {
            Object body = http.post(KEYS + "/batch", Map.of("user_ids", List.of(userId)));
            String spki = null;
            if (body instanceof Map<?, ?> m && m.get("keys") instanceof Map<?, ?> keys && keys.get(userId) != null) {
                spki = String.valueOf(keys.get(userId));
            }
            pubKeyCache.put(userId, (spki != null && !spki.isEmpty()) ? Crypto.loadPublicKey(spki) : null);
        }
        return pubKeyCache.get(userId);
    }

    private static Object parseJson(String text) {
        try {
            return Json.parse(text);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new DecryptException("decrypted document is not valid JSON: " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return (o instanceof Map<?, ?> m) ? (Map<String, Object>) m : Map.of();
    }

    // ── nested value types ─────────────────────────────────────────────────────

    /** A typed answer to a consent/edit request row (before encryption). */
    public record TypedAnswer(String requestFieldId, String value, String kind) {
        public TypedAnswer(String requestFieldId, String value) {
            this(requestFieldId, value, "typed");
        }
    }

    /** A flow party for {@link #encryptFlowAnswer}. */
    public record FlowParty(String userId, String type, boolean isOwner) {
        public FlowParty(String userId, boolean isOwner) {
            this(userId, null, isOwner);
        }
    }
}
