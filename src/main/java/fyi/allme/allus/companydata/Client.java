package fyi.allme.allus.companydata;

import fyi.allme.allus.companydata.internal.Http;
import fyi.allme.allus.companydata.internal.Json;
import fyi.allme.allus.companydata.internal.ModelDeps;
import fyi.allme.allus.companydata.internal.Transport;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.function.Consumer;
import java.util.logging.Logger;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * The company-data SDK client facade — the one object an
 * integrating company touches. Build it from config (the keys live there and
 * nowhere else), then call:
 *
 * <pre>
 * client.requestFields()              -> cached List&lt;RequestField&gt;  (slug -> meta)
 * client.connections(limit, offset)   -> lazy Iterable&lt;Connection&gt; (auto-paged)
 * client.connection(id)               -> one Connection
 * client.logs(limit, offset)          -> List&lt;LogEntry&gt;
 * client.processChanges(handler, ...) -> the crash-safe pump
 * client.drainBatch(max)              -> raw unbuffered drain (advanced)
 * client.deadLetters() / client.retryDeadLetters(handler)
 * client.verifyWebhook / parseWebhook / handleWebhook
 * </pre>
 *
 * <p>How it is wired: an {@link Http} owns the {@code client_credentials} token +
 * JSON/XML + error mapping; the service private key is loaded ONCE at construction
 * (config-only key handling) and a decrypt closure over it is handed to every model
 * factory + the pump; {@code requestFields()} is fetched once + cached to type every
 * value; a binary value is a lazy {@link BinaryHandle} over the slot file endpoint;
 * {@code processChanges} delegates to a {@link Pump} over the drain-on-fetch feed.
 */
public final class Client {
    private static final String BASE = "/api/company-data";
    private static final String CONNECTIONS = BASE + "/connections";
    private static final String CHANGES = BASE + "/changes";
    private static final String REQUEST_FIELDS = BASE + "/request-fields";
    private static final String LOGS = BASE + "/logs";
    private static final String DOCUMENTS = BASE + "/documents";
    private static final String KEYS = "/api/keys";

    private static final int DEFAULT_CONN_PAGE = 100;
    private static final int CONN_MAX_429_BACKOFFS = 5;
    private static final double CONN_DEFAULT_BACKOFF_S = 5.0;
    private static final double CONN_MAX_BACKOFF_S = 120.0;

    private final Config config;
    private final Http http;
    private final Logger log;
    private final java.util.function.DoubleConsumer sleep;
    private final java.security.interfaces.RSAPrivateKey privateKey;
    private final java.security.interfaces.RSAPrivateKey accountKey;
    private final ModelDeps deps;

    private List<RequestField> requestFields;
    private Map<String, String> typeBySlug = new LinkedHashMap<>();
    private Pump pump;

    // Recipient RSA public keys (by share_code) — cached for per-person document
    // encryption. A public key is immutable + not a secret (fetched live, never configured).
    private final Map<String, java.security.interfaces.RSAPublicKey> pubkeyCache = new LinkedHashMap<>();

    public Client(Config config) {
        this(config, new Http(config), Logger.getLogger("fyi.allme.allus.companydata.client"), Client::defaultSleep);
    }

    /** Build with an injected transport (tests / custom HTTP client). */
    public Client(Config config, Transport transport) {
        this(config, new Http(config, transport), Logger.getLogger("fyi.allme.allus.companydata.client"), Client::defaultSleep);
    }

    public Client(Config config, Http http, Logger log, java.util.function.DoubleConsumer sleep) {
        this.config = config;
        this.http = http;
        this.log = log;
        this.sleep = sleep;

        // Load the service private key ONCE from the configured encrypted PEM +
        // passphrase (config-only key handling). Single read; a closure
        // over it does every decrypt.
        this.privateKey = loadServiceKey(config);
        // Load the ACCOUNT private key ONCE too (null unless configured) — reused for
        // every encrypt_payload webhook (no per-request PBKDF2).
        this.accountKey = Webhooks.loadAccountKey(config);

        this.deps = new ModelDeps(this::decryptValue, this::typeForSlug, this::binaryFetch);
    }

    private static void defaultSleep(double seconds) {
        try {
            Thread.sleep((long) (Math.max(0.0, seconds) * 1000));
        } catch (InterruptedException exc) {
            Thread.currentThread().interrupt();
        }
    }

    // ── constructors (config-only keys) ────────────────────────────────────────

    /** Build from a JSON config file (env vars override secrets). */
    public static Client fromConfig(String path) {
        return new Client(Config.fromFile(path));
    }

    /** Build entirely from {@code ALLUS_*} env vars. */
    public static Client fromEnv() {
        return new Client(Config.fromEnv());
    }

    // ── decryption wiring (closures over the loaded key — never a method arg) ──

    private String decryptValue(Object wrapper) {
        return Crypto.decrypt(Wrapper.of(wrapper), privateKey);
    }

    private Wrapper binaryFetch(String valueUrl) {
        Object body = http.get(valueUrl);
        if (body instanceof Map<?, ?> m && m.containsKey("value")) {
            return Wrapper.of(m.get("value"));
        }
        // Defensive: some shapes might return the wrapper directly.
        return Wrapper.of(body);
    }

    private String typeForSlug(String slug) {
        if (requestFields == null) {
            requestFields();
        }
        return typeBySlug.get(slug);
    }

    // ── definitions ────────────────────────────────────────────────────────────

    /**
     * The cached request-field DEFINITIONS — fetched once from
     * {@code GET /api/company-data/request-fields} and cached for the life of the
     * client (it types every value). Returns YOUR request config, never the person's.
     */
    public List<RequestField> requestFields() {
        if (requestFields == null) {
            Object body = http.get(REQUEST_FIELDS);
            List<RequestField> fields = RequestField.listFromApi(body);
            requestFields = fields;
            Map<String, String> byType = new LinkedHashMap<>();
            for (RequestField f : fields) {
                if (f.slug() != null) {
                    byType.put(f.slug(), f.type());
                }
            }
            typeBySlug = byType;
        }
        return requestFields;
    }

    // ── connections (heavily rate-limited — initial sync / reconciliation) ─────

    /**
     * A lazy {@link Iterable} that pages the list endpoint, yielding one
     * {@link Connection} at a time (use in a for-each — bounded memory for a large
     * book). The connections endpoints are <b>heavily rate-limited</b>: use this
     * for the initial full sync + occasional reconciliation, never
     * as a poll substitute for the changes feed. On a surfaced
     * {@link RateLimitException} the iterator backs off per {@code Retry-After} and
     * retries the page a bounded number of times before re-raising.
     */
    public Iterable<Connection> connections() {
        return connections(DEFAULT_CONN_PAGE, 0);
    }

    public Iterable<Connection> connections(int limit, int offset) {
        int page = Math.max(1, limit);
        int start = Math.max(0, offset);
        requestFields(); // ensure the slug catalog is loaded so values are typed
        return () -> new ConnectionIterator(page, start);
    }

    /** A {@link Stream} view of {@link #connections(int, int)} (idiomatic alternative). */
    public Stream<Connection> connectionStream(int limit, int offset) {
        Iterator<Connection> it = connections(limit, offset).iterator();
        return StreamSupport.stream(
            Spliterators.spliteratorUnknownSize(it, Spliterator.ORDERED), false);
    }

    public Stream<Connection> connectionStream() {
        return connectionStream(DEFAULT_CONN_PAGE, 0);
    }

    private final class ConnectionIterator implements Iterator<Connection> {
        private final int page;
        private int offset;
        private final List<Connection> buf = new ArrayList<>();
        private int idx = 0;
        private boolean exhausted = false;

        ConnectionIterator(int page, int offset) {
            this.page = page;
            this.offset = offset;
        }

        @Override
        public boolean hasNext() {
            while (idx >= buf.size() && !exhausted) {
                fillNextPage();
            }
            return idx < buf.size();
        }

        @Override
        public Connection next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            return buf.get(idx++);
        }

        @SuppressWarnings("unchecked")
        private void fillNextPage() {
            Object body = getConnectionsPage(page, offset);
            List<Object> items = listItems(body);
            if (items.isEmpty()) {
                exhausted = true;
                return;
            }
            buf.clear();
            idx = 0;
            for (Object obj : items) {
                if (obj instanceof Map<?, ?> map) {
                    // The list row carries identity AND the values map, so the same
                    // object is both detail + identity.
                    Map<String, Object> m = (Map<String, Object>) map;
                    buf.add(Connection.fromApi(m, deps, m));
                }
            }
            // A short page means we reached the end (fewer rows than asked for).
            if (items.size() < page) {
                exhausted = true;
            } else {
                offset += page;
            }
        }
    }

    private Object getConnectionsPage(int page, int offset) {
        int attempts = 0;
        while (true) {
            try {
                Map<String, String> params = new LinkedHashMap<>();
                params.put("limit", String.valueOf(page));
                params.put("offset", String.valueOf(offset));
                return http.get(CONNECTIONS, params);
            } catch (RateLimitException exc) {
                attempts++;
                if (attempts > CONN_MAX_429_BACKOFFS) {
                    throw exc;
                }
                double delay = connBackoff(exc.retryAfter(), attempts);
                log.warning("connections rate-limited (offset=" + offset + "); backoff " + delay
                    + "s (attempt " + attempts + ")");
                if (delay > 0) {
                    sleep.accept(delay);
                }
            }
        }
    }

    /**
     * Fetch a single connection by id → one {@link Connection}.
     * This endpoint returns {@code {connection_id, user_id, values}} and no
     * display_name/connected_at, so those identity fields are null here (the list
     * endpoint carries them).
     */
    @SuppressWarnings("unchecked")
    public Connection connection(String id) {
        requestFields();
        Object body = http.get(CONNECTIONS + "/" + id);
        if (body instanceof Map<?, ?> m && m.containsKey("items") && !m.containsKey("values")) {
            List<Object> items = listItems(body);
            body = items.isEmpty() ? Map.of() : items.get(0);
        }
        if (!(body instanceof Map<?, ?> map)) {
            throw new ApiException(0, null, "connection response was not an object");
        }
        return Connection.fromApi((Map<String, Object>) map, deps, null);
    }

    // ── logs (moderate rate-limit) ──────────────────────────────────────────────

    /** The service's activity log → {@code List<LogEntry>}. */
    public List<LogEntry> logs() {
        return logs(50, 0);
    }

    public List<LogEntry> logs(int limit, int offset) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("limit", String.valueOf(Math.max(1, limit)));
        params.put("offset", String.valueOf(Math.max(0, offset)));
        Object body = http.get(LOGS, params);
        return LogEntry.listFromApi(body);
    }

    // ── changes feed — the crash-safe pump ────────────────────────

    /** The crash-safe changes {@link Pump} (built lazily). */
    public Pump pump() {
        if (pump == null) {
            pump = new Pump(config, this::fetchChanges, this::decryptChange, sleep, log);
        }
        return pump;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> fetchChanges(int limit) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("limit", String.valueOf(limit));
        Object body = http.get(CHANGES, params);
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

    /**
     * Drain the changes feed through {@code handler} one at a time, crash-safely.
     * Delegates to the {@link Pump}: replay buffer, drain ≤500,
     * persist-before-deliver, per-item ack, retry→dead-letter→continue, until empty
     * then return (no daemon mode — schedule re-runs yourself). {@code handler} must
     * be idempotent (at-least-once; dedup on {@code Change.id}).
     */
    public void processChanges(Consumer<Change> handler) {
        requestFields(); // ensure the catalog is loaded for value typing
        pump().processChanges(handler);
    }

    public void processChanges(Consumer<Change> handler, Pump.Options options) {
        requestFields();
        pump().processChanges(handler, options);
    }

    /** Raw, UNBUFFERED drain → {@code List<Change>} (advanced — you own durability). */
    public List<Change> drainBatch(int max) {
        requestFields();
        return pump().drainBatch(max);
    }

    /** The local dead-letter store. */
    public List<Map<String, Object>> deadLetters() {
        return pump().deadLetters();
    }

    /** Re-drive dead-lettered events through {@code handler}. */
    public int retryDeadLetters(Consumer<Change> handler) {
        requestFields();
        return pump().retryDeadLetters(handler);
    }

    public int retryDeadLetters(Consumer<Change> handler, Pump.Options options) {
        requestFields();
        return pump().retryDeadLetters(handler, options);
    }

    // ── webhook receiver helpers (config-driven, no key args) ───────────────────

    /** Verify a webhook's {@code X-Allus-Signature} HMAC. */
    public boolean verifyWebhook(Object rawBody, Map<String, ?> headers) {
        return Webhooks.verifyWebhook(rawBody, headers, config);
    }

    /** Parse a webhook body → a typed {@link Change}. */
    public Change parseWebhook(Object rawBody, Map<String, ?> headers) {
        requestFields(); // type the value via the cached catalog
        return Webhooks.parseWebhook(rawBody, headers, config, deps, accountKey);
    }

    /** Verify + parse a webhook in one call → {@link Change}. */
    public Change handleWebhook(Object rawBody, Map<String, ?> headers) {
        requestFields();
        return Webhooks.handleWebhook(rawBody, headers, config, deps, accountKey);
    }

    // ── company documents (write) ───────────────────────────────────────────────

    /** Fetch + cache the recipient RSA public key by share_code ({@code GET /api/keys/{shareCode}}). */
    @SuppressWarnings("unchecked")
    private java.security.interfaces.RSAPublicKey recipientPublicKey(String shareCode) {
        java.security.interfaces.RSAPublicKey cached = pubkeyCache.get(shareCode);
        if (cached != null) {
            return cached;
        }
        Object body = http.get(KEYS + "/" + shareCode);
        Object spki = (body instanceof Map<?, ?> m) ? ((Map<String, Object>) m).get("public_key") : null;
        if (spki == null || String.valueOf(spki).isEmpty()) {
            throw new ApiException(0, "keys.not_found", "no public_key for share_code " + shareCode);
        }
        java.security.interfaces.RSAPublicKey key = Crypto.loadPublicKey(String.valueOf(spki));
        pubkeyCache.put(shareCode, key);
        return key;
    }

    /**
     * Resolve a target's share_code (the recipient public-key handle). Prefers a
     * single-connection fetch (carries {@code share_code}); falls back to a connections
     * scan by {@code user_id}. Pass {@code shareCode} in the request to skip this.
     */
    @SuppressWarnings("unchecked")
    private String resolveShareCode(String connectionId, String personUserId) {
        if (connectionId != null) {
            Object body = http.get(CONNECTIONS + "/" + connectionId);
            Object sc = (body instanceof Map<?, ?> m) ? ((Map<String, Object>) m).get("share_code") : null;
            if (sc != null && !String.valueOf(sc).isEmpty()) {
                return String.valueOf(sc);
            }
        }
        if (personUserId != null) {
            for (Connection conn : connections()) {
                Map<String, Object> raw = conn.raw() != null ? conn.raw() : Map.of();
                if (personUserId.equals(raw.get("user_id")) || personUserId.equals(conn.personId())) {
                    Object sc = raw.get("share_code");
                    if (sc != null && !String.valueOf(sc).isEmpty()) {
                        return String.valueOf(sc);
                    }
                }
            }
        }
        throw new ConfigException(
            "could not resolve a share_code for the target — set shareCode() explicitly");
    }

    /**
     * Create a company document for a connection / person (PER-PERSON), or BROADCAST
     * (no target). Build the request with {@link CreateDocumentRequest#builder()}.
     *
     * <p>{@code payloadKind='json'} → {@code jsonValue} (object). {@code payloadKind='file'}
     * → {@code fileBytes} (+ {@code fileMime}).
     *
     * <p>Encryption is decided by the TARGET, not by is_private:
     * <ul>
     *   <li>PER-PERSON (connectionId/personUserId set) → the value is ALWAYS encrypted
     *       FOR THE RECIPIENT (shareCode resolved from connectionId/personUserId when not
     *       given) before it leaves the process — EVERY per-person doc, private or not.
     *       The server stores ciphertext. NO key argument.</li>
     *   <li>BROADCAST (no target) → the value is sent PLAINTEXT (you cannot single-key
     *       encrypt to all of a service's connections). A broadcast MUST be non-private;
     *       {@code isPrivate=true} therefore requires a per-person target.</li>
     * </ul>
     *
     * <p>is_private is a DISPLAY-ONLY flag passed through to the API — it governs the
     * recipient device's lock vs decrypt-on-load behaviour, NOT whether the value is
     * encrypted.
     */
    public Document createDocument(CreateDocumentRequest req) {
        if (!"json".equals(req.payloadKind) && !"file".equals(req.payloadKind)) {
            throw new ConfigException("payloadKind must be 'json' or 'file'");
        }
        if (!"document".equals(req.kind) && !"agreement".equals(req.kind) && !"subscription".equals(req.kind)) {
            throw new ConfigException("kind must be 'document', 'agreement' or 'subscription'");
        }
        Map<String, Object> target = null;
        if (req.connectionId != null) {
            target = Map.of("connection_id", req.connectionId);
        } else if (req.personUserId != null) {
            target = Map.of("person_user_id", req.personUserId);
        } // else: broadcast — target stays null

        boolean perPerson = target != null;
        // A contract (agreement/subscription, or either flag) is ALWAYS per-person → it must target one person.
        boolean isContract = "agreement".equals(req.kind) || "subscription".equals(req.kind)
            || req.requiresSignature || req.requiresAcceptance;
        if (isContract && !perPerson) {
            throw new ConfigException("a contract must target one connected person");
        }
        if (req.isPrivate && !perPerson) {
            // A plaintext broadcast cannot be locked — is_private needs a per-person target.
            throw new ConfigException(
                "isPrivate=true requires a per-person target (broadcast is plaintext)");
        }

        java.security.interfaces.RSAPublicKey pubkey = null;
        if (perPerson) {
            // EVERY per-person doc is encrypted, private or not — fetch the recipient key.
            String sc = req.shareCode != null
                ? req.shareCode : resolveShareCode(req.connectionId, req.personUserId);
            pubkey = recipientPublicKey(sc);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("kind", req.kind);
        body.put("name", req.name);
        body.put("payload_kind", req.payloadKind);
        body.put("is_private", req.isPrivate);
        body.put("requires_signature", req.requiresSignature);
        body.put("requires_acceptance", req.requiresAcceptance);
        body.put("target", target);
        if (req.description != null) {
            body.put("description", req.description);
        }
        if (req.metadata != null) {
            body.put("metadata", req.metadata);
        }
        if (req.status != null) {
            body.put("status", req.status);
        }

        if ("json".equals(req.payloadKind)) {
            if (req.jsonValue == null) {
                throw new ConfigException("jsonValue is required for payloadKind='json'");
            }
            body.put("value", perPerson
                ? Crypto.encryptForPublicKey(Json.write(req.jsonValue), pubkey)
                : req.jsonValue);
            Object created = http.post(DOCUMENTS, body);
            return Document.fromApi(docObj(created), this::decryptValue);
        }

        // file: create the metadata row first, then upload bytes to /{id}/file.
        if (req.fileBytes == null) {
            throw new ConfigException("fileBytes is required for payloadKind='file'");
        }
        Object created = http.post(DOCUMENTS, body);
        Document doc = Document.fromApi(docObj(created), this::decryptValue);
        if (perPerson) {
            // Encrypt the file bytes (EVERY per-person doc): wrap the file envelope string,
            // then send the wrapper JSON as bytes.
            String envelope = Json.write(Map.of("file", dataUri(req.fileBytes, req.fileMime)));
            Map<String, Object> wrapper = Crypto.encryptForPublicKey(envelope, pubkey);
            http.post(DOCUMENTS + "/" + doc.id() + "/file",
                Json.write(wrapper).getBytes(java.nio.charset.StandardCharsets.UTF_8),
                "application/json");
        } else {
            // Broadcast — raw plaintext bytes.
            http.post(DOCUMENTS + "/" + doc.id() + "/file",
                req.fileBytes,
                req.fileMime != null ? req.fileMime : "application/octet-stream");
        }
        return doc;
    }

    /**
     * List this service's documents → {@code List<Document>} (paged; optional
     * person/status filter).
     */
    public List<Document> listDocuments() {
        return listDocuments(null, null, 100, 0);
    }

    public List<Document> listDocuments(String personUserId, String status, int limit, int offset) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("limit", String.valueOf(Math.max(1, limit)));
        params.put("offset", String.valueOf(Math.max(0, offset)));
        if (personUserId != null) {
            params.put("person_user_id", personUserId);
        }
        if (status != null) {
            params.put("status", status);
        }
        Object body = http.get(DOCUMENTS, params);
        return Document.listFromApi(body, this::decryptValue);
    }

    /** Fetch one document by id → {@link Document}. */
    public Document document(String documentId) {
        Object body = http.get(DOCUMENTS + "/" + documentId);
        return Document.fromApi(docObj(body), this::decryptValue);
    }

    /**
     * Set a document's lifecycle status
     * ({@code offering|ready_to_sign|active|active_but_ending|ended}).
     */
    public Document updateDocumentStatus(String documentId, String status) {
        Object body = http.put(DOCUMENTS + "/" + documentId, Map.of("status", status));
        return Document.fromApi(docObj(body), this::decryptValue);
    }

    /** Update a document's metadata / name / description (any subset; at least one). */
    public Document updateDocumentMetadata(String documentId, Map<String, Object> metadata,
                                           String name, String description) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (metadata != null) {
            payload.put("metadata", metadata);
        }
        if (name != null) {
            payload.put("name", name);
        }
        if (description != null) {
            payload.put("description", description);
        }
        if (payload.isEmpty()) {
            throw new ConfigException("updateDocumentMetadata needs metadata, name, or description");
        }
        Object body = http.put(DOCUMENTS + "/" + documentId, payload);
        return Document.fromApi(docObj(body), this::decryptValue);
    }

    /** Delete a document (and its on-disk file). */
    public void deleteDocument(String documentId) {
        http.delete(DOCUMENTS + "/" + documentId);
    }

    /**
     * The arguments for {@link Client#createDocument(CreateDocumentRequest)} — a small
     * builder for the many optionals (broadcast vs per-person, json vs file).
     */
    public static final class CreateDocumentRequest {
        private String kind = "document";
        private String name;
        private String payloadKind;
        private boolean isPrivate = false;
        private String description;
        private String connectionId;
        private String personUserId;
        private String shareCode;            // recipient handle for per-person encryption
        private Object jsonValue;
        private byte[] fileBytes;
        private String fileMime;
        private boolean requiresSignature = false;   // contract: the person must sign (step-up)
        private boolean requiresAcceptance = false;  // contract: the person must accept
        private Map<String, Object> metadata;
        private String status;

        public static CreateDocumentRequest builder() {
            return new CreateDocumentRequest();
        }

        public CreateDocumentRequest kind(String v) { this.kind = v; return this; }
        public CreateDocumentRequest name(String v) { this.name = v; return this; }
        public CreateDocumentRequest payloadKind(String v) { this.payloadKind = v; return this; }
        public CreateDocumentRequest isPrivate(boolean v) { this.isPrivate = v; return this; }
        public CreateDocumentRequest description(String v) { this.description = v; return this; }
        public CreateDocumentRequest connectionId(String v) { this.connectionId = v; return this; }
        public CreateDocumentRequest personUserId(String v) { this.personUserId = v; return this; }
        public CreateDocumentRequest shareCode(String v) { this.shareCode = v; return this; }
        public CreateDocumentRequest jsonValue(Object v) { this.jsonValue = v; return this; }
        public CreateDocumentRequest fileBytes(byte[] v) { this.fileBytes = v; return this; }
        public CreateDocumentRequest fileMime(String v) { this.fileMime = v; return this; }
        public CreateDocumentRequest requiresSignature(boolean v) { this.requiresSignature = v; return this; }
        public CreateDocumentRequest requiresAcceptance(boolean v) { this.requiresAcceptance = v; return this; }
        public CreateDocumentRequest metadata(Map<String, Object> v) { this.metadata = v; return this; }
        public CreateDocumentRequest status(String v) { this.status = v; return this; }
    }

    // ── module-level helpers ──────────────────────────────────────────────────

    /**
     * Pull the document object out of a create/get/update response. The API returns the
     * bare document object; tolerate a {@code {"document": {...}}} wrapper too.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> docObj(Object body) {
        if (body instanceof Map<?, ?> m) {
            Object inner = ((Map<String, Object>) m).get("document");
            if (inner instanceof Map<?, ?> im) {
                return (Map<String, Object>) im;
            }
            return (Map<String, Object>) m;
        }
        return Map.of();
    }

    /** Build a {@code data:<mime>;base64,<…>} URI for the per-person file envelope. */
    private static String dataUri(byte[] fileBytes, String mime) {
        String b64 = java.util.Base64.getEncoder().encodeToString(fileBytes);
        return "data:" + (mime != null ? mime : "application/octet-stream") + ";base64," + b64;
    }

    private static java.security.interfaces.RSAPrivateKey loadServiceKey(Config config) {
        byte[] pem;
        try {
            pem = Files.readAllBytes(Path.of(config.servicePrivateKey()));
        } catch (IOException exc) {
            throw new ConfigException("could not read service_private_key PEM: "
                + config.servicePrivateKey() + ": " + exc.getMessage(), exc);
        }
        try {
            return Crypto.loadPrivateKey(pem, config.keyPassphrase());
        } catch (DecryptException exc) {
            throw new ConfigException("could not load service private key: " + exc.getMessage(), exc);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Object> listItems(Object body) {
        if (body instanceof Map<?, ?> m) {
            Object items = m.get("items");
            if (items == null) {
                return List.of();
            }
            if (items instanceof List<?> l) {
                return (List<Object>) l;
            }
            return List.of();
        }
        if (body instanceof List<?> l) {
            return (List<Object>) l;
        }
        return List.of();
    }

    private static double connBackoff(Double retryAfter, int attempt) {
        if (retryAfter != null && retryAfter >= 0) {
            return Math.min(retryAfter, CONN_MAX_BACKOFF_S);
        }
        return Math.min(CONN_DEFAULT_BACKOFF_S * Math.pow(2, attempt - 1), CONN_MAX_BACKOFF_S);
    }
}
