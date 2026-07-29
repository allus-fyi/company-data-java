package fyi.allme.allus.examples.companydata;

import com.sun.net.httpserver.HttpExchange;

import fyi.allme.allus.companydata.BinaryHandle;
import fyi.allme.allus.companydata.Change;
import fyi.allme.allus.companydata.Client;
import fyi.allme.allus.companydata.Connection;
import fyi.allme.allus.companydata.Document;
import fyi.allme.allus.companydata.RequestField;
import fyi.allme.allus.companydata.Value;
import fyi.allme.allus.companydata.WebhookException;

import fyi.allme.allus.examples.Http;
import fyi.allme.allus.examples.Runtime;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.temporal.Temporal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;

import fyi.allme.allus.examples.Util;
import static fyi.allme.allus.examples.Util.action;
import static fyi.allme.allus.examples.Util.asInt;
import static fyi.allme.allus.examples.Util.calls;
import static fyi.allme.allus.examples.Util.envelope;
import static fyi.allme.allus.examples.Util.strOr;

/**
 * The company-data scenario handlers (config-file model). Each handler reaches the SDK's intended
 * top-level surface ONLY (no raw platform HTTP, no SDK internals). Five scenarios, all namespaced
 * {@code companydata:*}, all using the SERVICE-role data {@link Client} built from the persisted config
 * file:
 * <ul>
 *   <li>{@code read}        — {@link Client#connections()}     → connection-grouped decrypted values</li>
 *   <li>{@code definitions} — {@link Client#requestFields()}   → your request-field catalog</li>
 *   <li>{@code changes}     — {@link Client#processChanges}    → a crash-safe pump drain (dedup on Change.id)</li>
 *   <li>{@code webhook}     — {@link Client#verifyWebhook}/{@link Client#parseWebhook} → a public
 *       {@code POST /webhook} receiver + a {@link Client#drainBatch} feed fallback; ONE accumulating run
 *       keyed by the webhook id</li>
 *   <li>{@code documents}   — {@link Client#createDocument}×6  → the six document/contract types</li>
 * </ul>
 *
 * <p>Settings flow: the browser POSTs a scenario's setup to {@code /config}, written to a canonical SDK
 * config FILE ({@code .runtime/config/{sid}.json}). {@link #start} builds the Client from that file
 * ({@link Client#fromConfig}) and runs OFF it. A {@code /start} with no saved config → 409 not_configured.
 */
public final class CompanyDataHandlers {
    public static final String READ = "companydata:read";
    public static final String DEFINITIONS = "companydata:definitions";
    public static final String CHANGES = "companydata:changes";
    public static final String WEBHOOK = "companydata:webhook";

    /**
     * The "what just happened" trace. Every entry is {@code <SDK method> — <what that call did in
     * THIS scenario>}, appended AT the call site, in the order the calls were made; an entry wrapped in
     * parentheses is a step that is deliberately NOT an SDK call. Keep the wording stable when this
     * handler changes so the trace keeps reading as what the run DID.
     */
    private static final String CALL_SERVICE_BUILD = "Client.fromConfig — builds the SERVICE-role data client from the saved config file: client credentials plus the service private key, decrypted with its passphrase";
    private static final String CALL_CONNECTIONS = "Client.connections — pages GET /api/company-data/connections: loads your request-field catalog first for value typing, then decrypts each person's values with the service key";
    private static final String CALL_REQUEST_FIELDS = "Client.requestFields — GET /api/company-data/request-fields: your own request-field catalog, fetched once and cached for the life of the client";
    private static final String CALL_PROCESS_CHANGES = "Client.processChanges — drains the change feed through the crash-safe pump: handler before ack, at-least-once (dedup on Change.id), failures to the local dead-letter store";
    private static final String CALL_CREATE_DOCUMENT = "Client.createDocument — %s";
    private static final String CALL_WEBHOOK_STARTED = "(webhook run started) — POST /webhook receives each delivery; every poll also drains the change feed as a fallback";
    private static final String CALL_VERIFY_WEBHOOK = "Client.verifyWebhook — checks the delivery's X-Allus-Signature HMAC against the secret configured for its X-Allus-Webhook-Id; a failure answers 401";
    private static final String CALL_PARSE_WEBHOOK = "Client.parseWebhook — turns the verified body into a typed Change, decrypting its value with the service key";
    private static final String CALL_DRAIN_BATCH = "Client.drainBatch — the per-poll feed fallback: one unbuffered drain, so events still show up when no delivery can reach this machine";
    public static final String DOCUMENTS = "companydata:documents";

    /** id → "runnable" (insertion order = /api/meta order). Every scenario runs synchronously or accumulates. */
    private static final Map<String, String> SCENARIOS = new LinkedHashMap<>();
    static {
        SCENARIOS.put(READ, "runnable");
        SCENARIOS.put(DEFINITIONS, "runnable");
        SCENARIOS.put(CHANGES, "runnable");
        SCENARIOS.put(WEBHOOK, "runnable");
        SCENARIOS.put(DOCUMENTS, "runnable");
    }

    /** Scenarios whose SDK Client uses the pump (needs a cache_dir for its buffer / dead-letters). */
    private static final Set<String> PUMP_SCENARIOS = Set.of(CHANGES, WEBHOOK);

    private static final String DEFAULT_API_URL = "https://api.allme.fyi";
    /** Raw feed drain size for the webhook fallback (pump drains ≤500 per batch). */
    private static final int DRAIN_MAX = 500;

    private final Runtime rt;

    public CompanyDataHandlers(Runtime rt) {
        this.rt = rt;
    }

    /** Whether an id is one of this family's five scenarios. */
    public boolean knows(String id) {
        return SCENARIOS.containsKey(id);
    }

    /** This family's contribution to GET /api/meta: the five companydata:* scenarios, in order. */
    public List<Map<String, Object>> scenarios() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<String, String> e : SCENARIOS.entrySet()) {
            out.add(Map.of("id", e.getKey(), "kind", e.getValue()));
        }
        return out;
    }

    // ── POST /api/scenarios/{id}/config ────────────────────────────────────────

    /**
     * Write the browser's setup values to a canonical SDK config FILE. Every company-data scenario uses
     * the SERVICE-role Client, so the config always carries client_id/secret + the service PEM (by path)
     * + passphrase. The pump scenarios (changes/webhook) also set cache_dir. The webhook scenario adds
     * the {@code webhooks:{id:secret}} map (the SDK selects the secret by the X-Allus-Webhook-Id header)
     * and records the webhook id in a meta sidecar (the routing key /start needs); the documents scenario
     * records the target share code in the sidecar.
     */
    public void config(HttpExchange ex, String id) throws IOException {
        if (!SCENARIOS.containsKey(id)) {
            Http.json(ex, 404, Map.of("error", "not_found"));
            return;
        }
        Map<String, Object> in = Http.body(ex);

        // Canonical SDK config — the service role for every company-data scenario.
        Map<String, Object> cfg = new LinkedHashMap<>();
        String apiUrl = strOr(in.get("apiUrl"), "");
        cfg.put("api_url", (apiUrl.isEmpty() ? DEFAULT_API_URL : apiUrl).replaceAll("/+$", ""));
        cfg.put("client_id", strOr(in.get("clientId"), ""));
        cfg.put("client_secret", strOr(in.get("clientSecret"), ""));
        cfg.put("key_passphrase", strOr(in.get("keyPassphrase"), ""));
        String pem = strOr(in.get("servicePrivateKeyPem"), "");
        if (!pem.isEmpty()) {
            cfg.put("service_private_key", rt.materializeConfigKey(pem));
        }

        // Pump scenarios persist their buffer / dead-letters under .runtime/cache (Config.cacheDir).
        if (PUMP_SCENARIOS.contains(id)) {
            cfg.put("cache_dir", rt.cacheDir.toString());
        }

        Map<String, Object> meta = new LinkedHashMap<>();
        if (id.equals(WEBHOOK)) {
            // The verifier selects the secret by the delivery's X-Allus-Webhook-Id header, so the
            // config's webhooks map must be keyed by the real webhook id.
            String webhookId = strOr(in.get("webhookId"), "");
            String secret = strOr(in.get("webhookSecret"), "");
            if (!webhookId.isEmpty() && !secret.isEmpty()) {
                Map<String, Object> webhooks = new LinkedHashMap<>();
                webhooks.put(webhookId, secret);
                cfg.put("webhooks", webhooks);
            }
            if (!webhookId.isEmpty()) {
                meta.put("webhook_id", webhookId); // the routing key /start writes into the route record
            }
        }
        if (id.equals(DOCUMENTS)) {
            meta.put("share_code", strOr(in.get("shareCode"), "")); // the per-person / contract target
        }

        String configPath = rt.writeConfig(id, cfg);
        rt.writeConfigMeta(id, meta);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("configPath", configPath);
        Http.json(ex, 200, out);
    }

    // ── POST /api/scenarios/{id}/start ─────────────────────────────────────────

    public void start(HttpExchange ex, String id) throws IOException {
        if (!SCENARIOS.containsKey(id)) {
            Http.json(ex, 404, Map.of("error", "not_found"));
            return;
        }
        if (!rt.hasConfig(id)) {
            // The run is built from the persisted config file, not the request body.
            Http.json(ex, 409, Map.of("error", "not_configured"));
            return;
        }

        switch (id) {
            case READ -> dataRun(ex, id, this::doRead);
            case DEFINITIONS -> dataRun(ex, id, this::doDefinitions);
            case CHANGES -> dataRun(ex, id, this::doChanges);
            case DOCUMENTS -> dataRun(ex, id, this::doDocuments);
            case WEBHOOK -> startWebhook(ex);
            default -> Http.json(ex, 404, Map.of("error", "not_found"));
        }
    }

    /**
     * Run a synchronous data scenario: build the Client from the config file, run the SDK call, and store
     * the terminal result. The immediate outcome is read once via GET /api/runs (action {type:"data"}).
     * A build/handler failure is written as a {@code failed} run (never a 200 without the success shape).
     */
    private void dataRun(HttpExchange ex, String id, BiFunction<Client, List<String>, Map<String, Object>> op)
            throws IOException {
        String runId = rt.newRunId();
        List<String> calls = new ArrayList<>();
        Map<String, Object> run = new LinkedHashMap<>();
        run.put("family", "companydata");
        run.put("scenario", id);
        try {
            calls.add(CALL_SERVICE_BUILD);
            Client client = Client.fromConfig(rt.configPathFor(id).toString());
            Map<String, Object> result = op.apply(client, calls);
            run.put("status", "done");
            run.put("result", result);
            run.put("calls", calls);
        } catch (Throwable t) {
            run.put("status", "failed");
            run.put("error", String.valueOf(t.getMessage()));
            run.put("calls", calls);
        }
        rt.writeRun(runId, run);
        Http.json(ex, 200, envelope(runId, action("data")));
    }

    /**
     * companydata:read — {@link Client#connections()} grouped BY connection (one card per connected
     * person), so two people who both filled the same slug stay distinguishable.
     */
    private Map<String, Object> doRead(Client client, List<String> calls) {
        calls.add(CALL_CONNECTIONS);
        List<Map<String, Object>> connections = new ArrayList<>();
        for (Connection conn : client.connections()) {
            List<Map<String, Object>> values = new ArrayList<>();
            for (Map.Entry<String, Value> e : conn.values().entrySet()) {
                Value v = e.getValue();
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("slug", e.getKey());
                row.put("value", stringifyValue(v.value()));
                row.put("live", v.live());
                row.put("at", iso(v.updatedAt()));
                values.add(row);
            }
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("connectionId", conn.id());
            c.put("personId", conn.personId());
            c.put("displayName", conn.displayName());
            c.put("customerType", conn.customerType());
            c.put("shareCode", conn.shareCode());
            c.put("values", values);
            connections.add(c);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("connections", connections);
        return out;
    }

    /**
     * companydata:definitions — {@link Client#requestFields()} → your request-field catalog (the folded
     * {@code mandatory} bool + {@code one_time}; the raw split flags are debug-only, off the intended
     * surface).
     */
    private Map<String, Object> doDefinitions(Client client, List<String> calls) {
        calls.add(CALL_REQUEST_FIELDS);
        List<Map<String, Object>> fields = new ArrayList<>();
        for (RequestField f : client.requestFields()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("slug", f.slug());
            row.put("label", f.label());
            row.put("type", f.type());
            row.put("mandatory", f.mandatory());
            row.put("one_time", f.oneTime());
            fields.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("fields", fields);
        return out;
    }

    /**
     * companydata:changes — {@link Client#processChanges} drains the feed on start through the crash-safe
     * pump (handler-before-ack, at-least-once), so the append handler is idempotent on the pull-feed
     * Change.id. Each event is the rendered-column projection PLUS a raw object with the full public
     * Change fields.
     */
    private Map<String, Object> doChanges(Client client, List<String> calls) {
        calls.add(CALL_PROCESS_CHANGES);
        List<Map<String, Object>> events = new ArrayList<>();
        Set<String> seen = new java.util.HashSet<>();
        client.processChanges(c -> {
            String cid = c.id();
            if (cid != null && !seen.add(cid)) {
                return; // idempotent: the pump may replay after a crash — dedup on Change.id
            }
            events.add(projectChange(c, null));
        });
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("events", events);
        out.put("drained", true);
        return out;
    }

    /**
     * companydata:documents — {@link Client#createDocument} for each of the six document/contract types
     * (payloads verbatim from apitests/php/documents.php). The per-person / private / contract types
     * target the connected person by share code (from the setup sidecar).
     */
    private Map<String, Object> doDocuments(Client client, List<String> calls) {
        String shareCode = strOr(rt.readConfigMeta(DOCUMENTS).get("share_code"), "");

        List<DocSpec> specs = new ArrayList<>();
        specs.add(new DocSpec("Broadcast plaintext JSON (no target)", false,
            Client.CreateDocumentRequest.builder()
                .kind("document").name("Service notice").payloadKind("json")
                .jsonValue(Map.of("msg", "Scheduled maintenance Sunday"))));
        specs.add(new DocSpec("Broadcast PDF file (no target)", false,
            Client.CreateDocumentRequest.builder()
                .kind("document").name("Price list").payloadKind("file")
                .fileBytes(minimalPdf("Price list")).fileMime("application/pdf")));
        specs.add(new DocSpec("Per-person NON-private file", true,
            Client.CreateDocumentRequest.builder()
                .kind("document").name("Your invoice").payloadKind("file")
                .fileBytes(minimalPdf("Your invoice")).fileMime("application/pdf")));
        specs.add(new DocSpec("Per-person PRIVATE file (lock → reveal)", true,
            Client.CreateDocumentRequest.builder()
                .kind("document").name("Confidential report").payloadKind("file").isPrivate(true)
                .fileBytes(minimalPdf("Confidential report")).fileMime("application/pdf")));
        specs.add(new DocSpec("CONTRACT requiring SIGNATURE", true,
            Client.CreateDocumentRequest.builder()
                .kind("agreement").name("Service agreement").payloadKind("file").requiresSignature(true)
                .fileBytes(minimalPdf("Service agreement")).fileMime("application/pdf")
                .metadata(mapOf("can_be_cancelled_in_app", true))));
        specs.add(new DocSpec("CONTRACT requiring ACCEPTANCE", true,
            Client.CreateDocumentRequest.builder()
                .kind("agreement").name("Terms update").payloadKind("json").requiresAcceptance(true)
                .jsonValue(Map.of("version", "2.0"))
                .metadata(acceptanceMetadata())));

        List<Map<String, Object>> docs = new ArrayList<>();
        int index = 1;
        for (DocSpec spec : specs) {
            if (spec.perPerson) {
                if (shareCode.isEmpty()) {
                    throw new RuntimeException("this document type targets a connected person — set a "
                        + "target person share code in the setup, then re-run");
                }
                spec.req.shareCode(shareCode);
            }
            calls.add(String.format(CALL_CREATE_DOCUMENT, spec.label));
            Document doc = client.createDocument(spec.req);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("index", index++);
            row.put("label", spec.label);
            row.put("document_id", doc.id());
            row.put("status", doc.status());
            docs.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("docs", docs);
        return out;
    }

    /** The metadata block for the acceptance-contract doc (verbatim from apitests/php/documents.php). */
    private static Map<String, Object> acceptanceMetadata() {
        Map<String, Object> md = new LinkedHashMap<>();
        md.put("plan_name", "Pro Plan");
        md.put("price", "9.99");
        md.put("currency", "EUR");
        md.put("renewal_term", "Monthly");
        md.put("renewal_date", "2026-07-30");
        md.put("valid_until", "2027-06-30");
        md.put("can_be_cancelled_in_app", true);
        md.put("management_url", "https://example.com/manage");
        return md;
    }

    private static Map<String, Object> mapOf(String k, Object v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(k, v);
        return m;
    }

    /** One document spec: its label, whether it targets the connected person, and the SDK request. */
    private static final class DocSpec {
        final String label;
        final boolean perPerson;
        final Client.CreateDocumentRequest req;

        DocSpec(String label, boolean perPerson, Client.CreateDocumentRequest req) {
            this.label = label;
            this.perPerson = perPerson;
            this.req = req;
        }
    }

    // ── companydata:webhook — the accumulating run + public receiver ────────────

    /**
     * Start the single accumulating webhook run. Persists the routing record webhookId → runId
     * (superseding any prior active webhook run) and returns {@code {action:{type:"none"}}} — there is NO
     * long-poll (it would wedge the single worker). Events arrive via POST /webhook and via a per-poll
     * {@link Client#drainBatch} feed fallback; the growing list is read back via GET /api/runs.
     */
    private void startWebhook(HttpExchange ex) throws IOException {
        String webhookId = strOr(rt.readConfigMeta(WEBHOOK).get("webhook_id"), "");
        if (webhookId.isEmpty()) {
            Http.json(ex, 409, Map.of("error", "not_configured"));
            return;
        }
        String runId = rt.newRunId();
        Map<String, Object> run = new LinkedHashMap<>();
        run.put("family", "companydata");
        run.put("scenario", WEBHOOK);
        run.put("status", "pending"); // accumulating — the shared enum is unchanged
        run.put("webhookId", webhookId);
        run.put("events", new ArrayList<>());
        run.put("seenFeedIds", new ArrayList<>()); // feed-only dedup set for the drainBatch() fallback
        run.put("unparseable", 0);
        run.put("calls", calls(CALL_WEBHOOK_STARTED));
        rt.writeRun(runId, run);
        rt.writeRoute(webhookId, runId);
        Http.json(ex, 200, envelope(runId, action("none")));
    }

    /**
     * POST /webhook — the PUBLIC inbound delivery. The exact call/status sequence (never the combined
     * handleWebhook(), which throws one WebhookException for BOTH a bad HMAC and a parse failure):
     * <ol>
     *   <li>read X-Allus-Webhook-Id; unknown/stale id or no active run → 200 acknowledge-and-discard.</li>
     *   <li>verifyWebhook(): false → 401 (a genuine signature failure; loud).</li>
     *   <li>parseWebhook(): success → append (source:"webhook") + 200; a WebhookException here is a
     *       VERIFIED-but-unparseable delivery → 200 acknowledge-and-note (increment unparseable) — NOT
     *       401, since the signature was valid.</li>
     * </ol>
     * All accepted-and-dropped cases return 200 because the platform worker counts EXACTLY 200 as success
     * (202/401/other = failure → retry + circuit-break).
     */
    public void webhook(HttpExchange ex) throws IOException {
        String rawBody = Http.rawBody(ex);
        Map<String, String> headers = Http.requestHeaders(ex);
        String webhookId = Http.header(headers, "X-Allus-Webhook-Id");

        Map<String, String> route = rt.readRoute();
        if (route == null || webhookId == null || !webhookId.equals(route.get("webhookId"))) {
            Http.text(ex, 200, "discarded: unknown or stale webhook id");
            return;
        }
        Map<String, Object> run = rt.readRun(route.get("runId"));
        if (run == null) {
            Http.text(ex, 200, "discarded: no active webhook run");
            return;
        }

        recordCall(run, CALL_SERVICE_BUILD);
        Client client = Client.fromConfig(rt.configPathFor(WEBHOOK).toString());
        recordCall(run, CALL_VERIFY_WEBHOOK);
        if (!client.verifyWebhook(rawBody, headers)) {
            // A genuine signature failure — persist the attempted verify so the calls trace stays truthful.
            rt.writeRun(route.get("runId"), run);
            Http.text(ex, 401, "signature verification failed");
            return;
        }
        try {
            recordCall(run, CALL_PARSE_WEBHOOK);
            Change change = client.parseWebhook(rawBody, headers);
            events(run).add(projectChange(change, "webhook"));
        } catch (WebhookException e) {
            // Verified but unparseable/undecryptable — acknowledge (200) and note it in the raw view.
            run.put("unparseable", asInt(run.get("unparseable")) + 1);
            Map<String, Object> note = new LinkedHashMap<>();
            note.put("source", "webhook");
            note.put("event", null);
            note.put("id", null);
            note.put("note", "received, could not parse");
            note.put("raw", mapOf("error", String.valueOf(e.getMessage())));
            events(run).add(note);
        }
        rt.writeRun(route.get("runId"), run);
        Http.text(ex, 200, "ok");
    }

    // ── GET /api/runs/{runId} (company-data) ────────────────────────────────────

    /**
     * The company-data run-poll body. The accumulating webhook run does ONE immediate {@code drainBatch()}
     * raw feed fetch per poll (NOT {@code processChanges()}, which loops the pump to empty and could stall
     * the single worker) so events generated AFTER start still appear in deployed-no-tunnel mode; every
     * other scenario returns its stored terminal result.
     */
    public Map<String, Object> pollBody(String runId, Map<String, Object> run) {
        if (WEBHOOK.equals(String.valueOf(run.get("scenario")))) {
            run = webhookFeedFallback(runId, run);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("webhookId", strOr(run.get("webhookId"), ""));
            result.put("events", run.getOrDefault("events", new ArrayList<>()));
            result.put("unparseable", asInt(run.get("unparseable")));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("status", strOr(run.get("status"), "pending"));
            out.put("calls", run.getOrDefault("calls", new ArrayList<>()));
            out.put("result", result);
            return out;
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
     * One immediate drainBatch() fetch per poll for the active webhook run, appending new source:"feed"
     * events deduped on the pull-feed Change.id (a feed-only seen-id set in run state). Only the CURRENT
     * active run pulls (a superseded run stops receiving). A transport/API error is swallowed so a
     * blackholed feed never fails the accumulating run — the webhook path still works.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> webhookFeedFallback(String runId, Map<String, Object> run) {
        Map<String, String> route = rt.readRoute();
        if (route == null || !runId.equals(route.get("runId"))) {
            return run; // superseded / cleared — this run no longer pulls
        }
        List<Object> seenList = run.get("seenFeedIds") instanceof List<?> l
            ? (List<Object>) l : new ArrayList<>();
        Set<String> seen = new java.util.HashSet<>();
        for (Object sid : seenList) {
            seen.add(String.valueOf(sid));
        }
        try {
            boolean buildNew = recordCall(run, CALL_SERVICE_BUILD);
            Client client = Client.fromConfig(rt.configPathFor(WEBHOOK).toString());
            // Every poll ATTEMPTS the feed pull — record the call now (deduped), so an empty poll still
            // reports the drainBatch it performed rather than claiming no call.
            boolean drainNew = recordCall(run, CALL_DRAIN_BATCH);
            boolean appended = false;
            for (Change change : client.drainBatch(DRAIN_MAX)) {
                String cid = change.id();
                if (cid != null) {
                    if (!seen.add(cid)) {
                        continue;
                    }
                    seenList.add(cid);
                }
                events(run).add(projectChange(change, "feed"));
                appended = true;
            }
            run.put("seenFeedIds", seenList);
            if (appended || drainNew || buildNew) {
                rt.writeRun(runId, run);
            }
        } catch (Throwable ignored) {
            // A blackholed / failed feed fetch must not fail the accumulating webhook run.
        }
        return run;
    }

    /**
     * Append an SDK-call name to a run's "what just happened" trace, deduped so the panel stays small no
     * matter how many deliveries / polls a call is attempted across. Returns true when the name was newly
     * added (so the caller can persist on that transition).
     */
    private static boolean recordCall(Map<String, Object> run, String name) {
        return Util.recordCall(run, name); // ONE implementation for all three families (standards §1)
    }

    @SuppressWarnings("unchecked")
    private static List<Object> events(Map<String, Object> run) {
        Object e = run.get("events");
        List<Object> list = e instanceof List ? (List<Object>) e : new ArrayList<>();
        run.put("events", list);
        return list;
    }

    // ── Change projection ──────────────────────────────────────────────────────

    /**
     * The rendered-column projection of a Change PLUS a raw object holding the full public Change fields,
     * so a raw/detailed view of the response can still show the event-specific extras — the compact
     * renderer uses only the leading columns and ignores raw. Nothing is dropped from result. {@code
     * source} labels a webhook delivery vs a pull-feed row (null for the changes scenario).
     */
    private Map<String, Object> projectChange(Change c, String source) {
        Map<String, Object> event = new LinkedHashMap<>();
        if (source != null) {
            event.put("source", source);
        }
        event.put("event", c.event());
        event.put("personId", c.personId());
        event.put("shareCode", c.shareCode());
        event.put("customerType", c.customerType());
        event.put("slug", c.slug());
        event.put("value", stringifyValue(c.value()));
        event.put("live", c.live());
        event.put("at", iso(c.at()));
        event.put("documentId", c.documentId());
        event.put("status", c.status());
        event.put("action", c.action());
        event.put("id", c.id());

        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("id", c.id());
        raw.put("event", c.event());
        raw.put("personId", c.personId());
        raw.put("shareCode", c.shareCode());
        raw.put("customerType", c.customerType());
        raw.put("slug", c.slug());
        raw.put("value", stringifyValue(c.value()));
        raw.put("live", c.live());
        raw.put("documentId", c.documentId());
        raw.put("status", c.status());
        raw.put("action", c.action());
        raw.put("note", c.note());
        raw.put("method", c.method());
        raw.put("contentSha256", c.contentSha256());
        raw.put("signedAt", c.signedAt());
        raw.put("cancelEffectiveDate", c.cancelEffectiveDate());
        raw.put("requestId", c.requestId());
        raw.put("publicKeySha256", c.publicKeySha256());
        raw.put("verified", c.verified());
        raw.put("at", iso(c.at()));
        event.put("raw", raw);
        return event;
    }

    /**
     * Render a decrypted value for JSON. A binary value is a lazy {@link BinaryHandle} — resolve its
     * bytes to a short descriptor rather than dumping raw bytes; a structured value stays a Map (the
     * frontend JSON-stringifies it); a date stays its ISO string.
     */
    private Object stringifyValue(Object v) {
        if (v == null || v instanceof String || v instanceof Number || v instanceof Boolean
            || v instanceof Map) {
            return v;
        }
        if (v instanceof Temporal) {
            return v.toString();
        }
        if (v instanceof BinaryHandle h) {
            try {
                return "[binary " + h.bytes().length + " bytes]";
            } catch (Throwable t) {
                return "[binary value]";
            }
        }
        return String.valueOf(v);
    }

    private static String iso(OffsetDateTime t) {
        return t == null ? null : t.toString();
    }

    /**
     * A tiny valid one-page PDF carrying {@code label} (verbatim shape from apitests/php/documents.php) —
     * so the broadcast / per-person / contract file docs upload real bytes without a fixture file.
     */
    private static byte[] minimalPdf(String label) {
        String stream = "BT /F1 18 Tf 40 90 Td (" + label.replace("(", "[").replace(")", "]") + ") Tj ET";
        Map<Integer, String> objs = new LinkedHashMap<>();
        objs.put(1, "<< /Type /Catalog /Pages 2 0 R >>");
        objs.put(2, "<< /Type /Pages /Kids [3 0 R] /Count 1 >>");
        objs.put(3, "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 420 160] "
            + "/Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>");
        objs.put(4, "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>");
        objs.put(5, "<< /Length " + stream.getBytes(StandardCharsets.UTF_8).length + " >>\nstream\n"
            + stream + "\nendstream");

        StringBuilder pdf = new StringBuilder("%PDF-1.4\n");
        Map<Integer, Integer> offsets = new LinkedHashMap<>();
        for (Map.Entry<Integer, String> e : objs.entrySet()) {
            offsets.put(e.getKey(), pdf.toString().getBytes(StandardCharsets.UTF_8).length);
            pdf.append(e.getKey()).append(" 0 obj\n").append(e.getValue()).append("\nendobj\n");
        }
        int xrefPos = pdf.toString().getBytes(StandardCharsets.UTF_8).length;
        pdf.append("xref\n0 ").append(objs.size() + 1).append("\n0000000000 65535 f \n");
        for (Integer n : objs.keySet()) {
            pdf.append(String.format("%010d 00000 n \n", offsets.get(n)));
        }
        pdf.append("trailer\n<< /Size ").append(objs.size() + 1).append(" /Root 1 0 R >>\nstartxref\n")
            .append(xrefPos).append("\n%%EOF");
        return pdf.toString().getBytes(StandardCharsets.UTF_8);
    }
}
