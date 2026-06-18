package fyi.allme.allus.companydata;

import fyi.allme.allus.companydata.internal.Http;
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

    // ── module-level helpers ──────────────────────────────────────────────────

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
