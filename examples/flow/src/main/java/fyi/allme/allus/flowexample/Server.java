package fyi.allme.allus.flowexample;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import fyi.allme.allus.companydata.ApiException;
import fyi.allme.allus.companydata.Client;
import fyi.allme.allus.companydata.ConfigException;
import fyi.allme.allus.companydata.Connection;
import fyi.allme.allus.companydata.FlowRun;
import fyi.allme.allus.companydata.Identity;
import fyi.allme.allus.companydata.ValidationException;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The demo-backend contract for the ONE contract-flow scenario ({@code flow:run}, flow family — contract
 * v2). One class, one worker: HTTP dispatch → handler → the intended top-level SDK flow surface only
 * ({@link Client#identity()} / {@link Client#triggerFlowRun} / {@link Client#flowRun} /
 * {@link Client#processFlowRun} / {@link Client#flowRunAnswers} / {@link Client#flowRunDocument}).
 * Handlers NEVER perform raw platform HTTP.
 *
 * <p>Single scenario {@code "flow:run"}. There is NO cross-card flow-run-id handoff: the platform flow
 * run lives entirely INSIDE this one demo run's file — the demo runId is the backend run and the
 * platform flowRunId is stored inside it, never exposed as a separate browser input.
 *
 * <p>Settings flow (config-file model): the browser POSTs the scenario's setup values to
 * {@code POST /api/scenarios/{id}/config}, which writes them to a canonical SDK config FILE
 * ({@code .runtime/config/{store}.json}; the service PEM → {@code .runtime/config/keys/} by path).
 * {@code /start} builds the service {@link Client} from that file via {@link Client#fromConfig} and runs
 * OFF the config — exactly as a real integrator wires the SDK. The request body of {@code /start} is
 * ignored; a {@code /start} with no saved config → 409 not_configured.
 *
 * <p>The {@code GET /api/runs/{runId}} poll is the drive loop AND the resume: each poll reads the
 * platform run and, if it is the company's turn, drives exactly ONE company step; otherwise it reports
 * waiting/running and touches nothing (the next poll after the person answers on their phone resumes
 * automatically).
 */
final class Server {
    static final int CONTRACT_VERSION = 2; // flow family lands at the next-available version (identity=1)
    static final String SDK = "java";

    /** The single public scenario id (the flow family). */
    private static final String SCENARIO = "flow:run";

    /** Internal store key for the config/meta/run files (the public id is not filesystem-shaped). */
    private static final int STORE_ID = 1;

    private static final String DEFAULT_API_URL = "https://api.allme.fyi";

    /** The flow party keys the fixtures pin. */
    private static final String PARTY_COMPANY = "company";
    private static final String PARTY_CUSTOMER = "customer";

    /** The canned INVALID value the validation-demo submits once for an email field. */
    private static final String INVALID_EMAIL = "not-an-email";

    private static final Pattern P_CONFIG = Pattern.compile("^/api/scenarios/([\\w:.\\-]+)/config$");
    private static final Pattern P_START = Pattern.compile("^/api/scenarios/([\\w:.\\-]+)/start$");
    private static final Pattern P_CLEAR = Pattern.compile("^/api/scenarios/([\\w:.\\-]+)/clear$");
    private static final Pattern P_RUN = Pattern.compile("^/api/runs/([0-9a-f]{32})$");

    private final Runtime rt;
    private final Path frontendDir;
    private final String sdkVersion;

    Server(Runtime rt, Path frontendDir, String sdkVersion) {
        this.rt = rt;
        this.frontendDir = frontendDir;
        this.sdkVersion = sdkVersion;
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
            } else if (path.equals("/api/clear") && method.equals("POST")) {
                rt.clearAll();
                json(ex, 200, Map.of("ok", true));
            } else if ((m = P_CONFIG.matcher(path)).matches() && method.equals("POST")) {
                config(ex, m.group(1));
            } else if ((m = P_START.matcher(path)).matches() && method.equals("POST")) {
                start(ex, m.group(1));
            } else if ((m = P_CLEAR.matcher(path)).matches() && method.equals("POST")) {
                if (!isKnownScenario(m.group(1))) {
                    json(ex, 404, Map.of("error", "not_found"));
                } else {
                    rt.clearScenario(STORE_ID);
                    json(ex, 200, Map.of("ok", true));
                }
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

    private static boolean isKnownScenario(String id) {
        return SCENARIO.equals(id);
    }

    // ── GET /api/meta ────────────────────────────────────────────────────────

    private void meta(HttpExchange ex) throws IOException {
        List<Map<String, Object>> scenarios = new ArrayList<>();
        scenarios.add(Map.of("id", SCENARIO, "kind", "runnable"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sdk", SDK);
        out.put("sdkVersion", sdkVersion);
        out.put("contractVersion", CONTRACT_VERSION);
        out.put("scenarios", scenarios);
        json(ex, 200, out);
    }

    // ── POST /api/scenarios/{id}/config ────────────────────────────────────────

    /**
     * Write the browser's setup values to a canonical SDK config FILE (service role). The service PEM is
     * written to config/keys/ and referenced by path; the demo-only run parameters (published flow id,
     * connection id, fixture choice) go to the meta sidecar so the config file stays a pure SDK config
     * the run executes off.
     */
    private void config(HttpExchange ex, String id) throws IOException {
        if (!isKnownScenario(id)) {
            json(ex, 404, Map.of("error", "not_found"));
            return;
        }
        Map<String, Object> in = body(ex);

        // Canonical SDK config — the service role (client_credentials + service PEM).
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
        String configPath = rt.writeConfig(STORE_ID, cfg);

        // Demo-only run parameters (NOT SDK Config fields) → meta sidecar.
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("flow_id", strOr(in.get("flowId"), ""));
        meta.put("connection_id", strOr(in.get("connectionId"), ""));
        meta.put("fixture", strOr(in.get("fixture"), ""));
        rt.writeConfigMeta(STORE_ID, meta);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("configPath", configPath);
        json(ex, 200, out);
    }

    // ── POST /api/scenarios/{id}/start ─────────────────────────────────────────

    /**
     * Trigger the flow run. Build the service {@link Client} from the persisted config file, construct
     * the bindings via the intended SDK surface (company → {@link Identity#companyUserId()}; customer →
     * {@link Connection#personId()}), call {@link Client#triggerFlowRun}, and store the returned platform
     * flowRunId in the demo run file. Returns {@code {runId, action:{"type":"none"}}} — the drive happens
     * on the {@code GET /api/runs} poll.
     */
    private void start(HttpExchange ex, String id) throws IOException {
        if (!isKnownScenario(id)) {
            json(ex, 404, Map.of("error", "not_found"));
            return;
        }
        if (!rt.hasConfig(STORE_ID)) {
            // The run is built from the persisted config file, not the request body.
            json(ex, 409, Map.of("error", "not_configured"));
            return;
        }
        Map<String, Object> meta = rt.readConfigMeta(STORE_ID);
        String flowId = strOr(meta.get("flow_id"), "");
        String connectionId = strOr(meta.get("connection_id"), "");
        if (flowId.isEmpty() || connectionId.isEmpty()) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("error", "not_configured");
            err.put("message", "flow id and connection id are required");
            json(ex, 409, err);
            return;
        }

        List<String> calls = new ArrayList<>();
        String flowRunId;
        try {
            Client client = serviceClient();

            // The COMPANY party binds to this service's own company_user_id.
            Identity identity = client.identity();
            calls.add("Client.identity");
            String companyUserId = identity.companyUserId();
            if (companyUserId == null || companyUserId.isEmpty()) {
                json(ex, 502, errorMessage("identity_error", "identity() returned no company_user_id"));
                return;
            }

            // The CUSTOMER party binds to the connected person's public personId (no public user_id).
            Connection connection = client.connection(connectionId);
            calls.add("Client.connection");
            String personId = connection.personId();
            if (personId == null || personId.isEmpty()) {
                json(ex, 502, errorMessage("connection_error",
                    "connection " + connectionId + " has no personId (not found or not connected)"));
                return;
            }

            Map<String, String> bindings = new LinkedHashMap<>();
            bindings.put(PARTY_COMPANY, companyUserId);
            bindings.put(PARTY_CUSTOMER, personId);
            FlowRun flowRun = client.triggerFlowRun(flowId, connectionId, bindings);
            calls.add("Client.triggerFlowRun");

            flowRunId = flowRun.id();
            if (flowRunId == null || flowRunId.isEmpty()) {
                json(ex, 502, errorMessage("trigger_error", "triggerFlowRun returned no run id"));
                return;
            }
        } catch (ApiException | ConfigException e) {
            json(ex, 502, errorMessage("start_failed", e.getMessage()));
            return;
        }

        String runId = rt.newRunId();
        Map<String, Object> run = new LinkedHashMap<>();
        run.put("scenario", STORE_ID);
        run.put("flowRunId", flowRunId);
        run.put("steps", new ArrayList<>());
        run.put("rejectedNodes", new ArrayList<>());
        run.put("calls", calls);
        run.put("completed", false);
        rt.writeRun(runId, run);

        Map<String, Object> action = new LinkedHashMap<>();
        action.put("type", "none");
        json(ex, 200, envelope(runId, action));
    }

    // ── GET /api/runs/{runId} ──────────────────────────────────────────────────

    /**
     * The idempotent, short-cycled poll that IS the drive loop and the resume. Reads the platform run;
     * if it is the company's turn drives exactly ONE step; on completion fetches the answers and
     * (document-mode) downloads the generated contract. A terminal run returns its cached result on every
     * poll until TTL/Clear.
     */
    private void run(HttpExchange ex, String runId) throws IOException {
        Map<String, Object> run = rt.readRun(runId);
        if (run == null) {
            json(ex, 404, Map.of("error", "not_found"));
            return;
        }

        // Idempotent: once terminal (completed OR errored) the outcome is returned unchanged on every
        // subsequent poll — a failed run must stay failed (outer "failed"), not re-drive the platform.
        boolean terminal = Boolean.TRUE.equals(run.get("completed")) || run.containsKey("error");
        if (!terminal) {
            run = advance(run);
            rt.writeRun(runId, run);
        }

        json(ex, 200, result(run));
    }

    /** One poll's worth of work. Returns the (possibly mutated) run map. */
    private Map<String, Object> advance(Map<String, Object> run) {
        String flowRunId = strOr(run.get("flowRunId"), "");
        if (flowRunId.isEmpty()) {
            run.put("status", "error");
            run.put("error", "run has no platform flowRunId");
            return run;
        }

        try {
            Client client = serviceClient();
            FlowRun flowRun = client.flowRun(flowRunId);
            run.put("calls", addCall(run.get("calls"), "Client.flowRun"));

            String status = strOr(flowRun.status(), "");
            String companyParty = flowRun.companyPartyKey();
            boolean companyTurn = companyParty != null && status.equals("awaiting_" + companyParty);

            if (status.equals("completed")) {
                return complete(run, client, flowRun, flowRunId);
            }
            if (companyTurn) {
                return driveStep(run, client, flowRun, flowRunId);
            }
            if (status.startsWith("awaiting_")) {
                // The person's turn (or the phone signature) — wait; the next poll resumes automatically.
                run.put("status", "waiting_person");
                return run;
            }
            // Any transient in-between state (e.g. generating) — keep polling.
            run.put("status", "running");
            return run;
        } catch (ApiException | ConfigException e) {
            run.put("status", "error");
            run.put("error", e.getMessage());
            return run;
        }
    }

    /**
     * Drive ONE company step via {@link Client#processFlowRun}. The validation demo: for an email field
     * whose node has not yet been rejected once, the fill returns the canned INVALID value, which
     * {@code processFlowRun} rejects with a {@link ValidationException} BEFORE any submit — recorded as
     * {@code accepted:false} without advancing. The next poll (node marked rejected) fills the VALID
     * value → advances → {@code accepted:true}.
     */
    private Map<String, Object> driveStep(Map<String, Object> run, Client client, FlowRun flowRun,
                                          String flowRunId) {
        String nodeKey = strOr(flowRun.currentNode(), "");
        List<String> rejectedNodes = asStringList(run.get("rejectedNodes"));

        // Captured by the fill closure below; populated while processFlowRun invokes it for this node.
        List<Map<String, String>> filled = new ArrayList<>();
        Client.FillNode fillNode = (node, answers) -> {
            String nk = strOr(node.get("key"), "");
            Map<String, Object> fill = new LinkedHashMap<>();
            Object elements = node.get("elements");
            if (elements instanceof List<?> els) {
                for (Object elObj : els) {
                    if (!(elObj instanceof Map<?, ?> el) || !"field".equals(el.get("kind"))) {
                        continue;
                    }
                    String slug = strOr(el.get("slug"), "");
                    if (slug.isEmpty()) {
                        continue;
                    }
                    String ftype = fieldType(el);
                    boolean rejectDemo = "email".equals(ftype) && !rejectedNodes.contains(nk);
                    String value = rejectDemo ? INVALID_EMAIL : cannedValue(ftype);
                    fill.put(slug, value);
                    Map<String, String> f = new LinkedHashMap<>();
                    f.put("slug", slug);
                    f.put("type", ftype);
                    f.put("submitted", value);
                    filled.add(f);
                }
            }
            return fill;
        };

        List<Map<String, Object>> steps = asMapList(run.get("steps"));
        try {
            client.processFlowRun(flowRunId, fillNode);
            run.put("calls", addCall(run.get("calls"), "Client.processFlowRun"));
            // Advanced: every field filled for this node was accepted.
            for (Map<String, String> f : filled) {
                Map<String, Object> step = new LinkedHashMap<>();
                step.put("slug", f.get("slug"));
                step.put("type", f.get("type"));
                step.put("submitted", f.get("submitted"));
                step.put("accepted", true);
                steps.add(step);
            }
            run.put("steps", steps);
            run.put("status", "running");
            return run;
        } catch (ValidationException e) {
            // The canned invalid value was rejected BEFORE submit — record it and mark the node so the
            // next poll submits the valid value. The node did NOT advance.
            run.put("calls", addCall(run.get("calls"), "Client.processFlowRun"));
            String submitted = INVALID_EMAIL;
            for (Map<String, String> f : filled) {
                if (f.get("slug").equals(e.getSlug())) {
                    submitted = f.get("submitted");
                    break;
                }
            }
            Map<String, Object> step = new LinkedHashMap<>();
            step.put("slug", strOr(e.getSlug(), ""));
            step.put("type", strOr(e.getFieldType(), "email"));
            step.put("submitted", submitted);
            step.put("accepted", false);
            step.put("error", e.getMessage());
            steps.add(step);
            run.put("steps", steps);
            if (!nodeKey.isEmpty() && !rejectedNodes.contains(nodeKey)) {
                rejectedNodes.add(nodeKey);
            }
            run.put("rejectedNodes", rejectedNodes);
            run.put("status", "running");
            return run;
        }
    }

    /**
     * Terminal: fetch the decrypted answers and, for a document-mode run, download the generated
     * contract's company copy ({@link Client#flowRunDocument} — the run-scoped, service-key-decryptable
     * surface).
     */
    private Map<String, Object> complete(Map<String, Object> run, Client client, FlowRun flowRun,
                                         String flowRunId) {
        Map<String, Object> answers = client.flowRunAnswers(flowRun);
        run.put("calls", addCall(run.get("calls"), "Client.flowRunAnswers"));
        List<Map<String, Object>> answersOut = new ArrayList<>();
        for (Map.Entry<String, Object> e : answers.entrySet()) {
            Map<String, Object> a = new LinkedHashMap<>();
            a.put("slug", e.getKey());
            a.put("value", e.getValue());
            answersOut.add(a);
        }
        run.put("answers", answersOut);

        if ("document".equals(flowRun.outputMode())) {
            try {
                byte[] bytes = client.flowRunDocument(flowRunId);
                run.put("calls", addCall(run.get("calls"), "Client.flowRunDocument"));
                Map<String, Object> doc = new LinkedHashMap<>();
                doc.put("status", "downloaded");
                doc.put("downloaded", true);
                doc.put("bytes", bytes == null ? 0 : bytes.length);
                run.put("document", doc);
            } catch (ApiException e) {
                // The run completed but the document is not retrievable yet — report it, don't fail.
                Map<String, Object> doc = new LinkedHashMap<>();
                doc.put("status", "unavailable");
                doc.put("downloaded", false);
                doc.put("error", e.getMessage());
                run.put("document", doc);
            }
        }

        run.put("status", "completed");
        run.put("completed", true);
        return run;
    }

    /**
     * The {@code GET /api/runs/{runId}} response: the SHARED run envelope (outer
     * {@code {status:"pending"|"done"|"failed", result?, error?, calls}}) with the pinned FLOW shape
     * nested under {@code result} ({@code {status:"running"|"waiting_person"|"completed", steps,
     * answers?, document?}}). The shared frontend reads progress ONLY from {@code run.result} and keeps
     * polling ONLY while the outer status is {@code "pending"}, so the inner flow status must NOT sit at
     * the top level — it drives under {@code "pending"} until the platform run completes ({@code "done"})
     * or errors ({@code "failed"}).
     */
    private Map<String, Object> result(Map<String, Object> run) {
        String flowStatus = strOr(run.get("status"), "running");
        String outer = run.containsKey("error") ? "failed" : (flowStatus.equals("completed") ? "done" : "pending");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", flowStatus);
        result.put("steps", asMapList(run.get("steps")));
        if (run.containsKey("answers")) {
            result.put("answers", run.get("answers"));
        }
        if (run.containsKey("document")) {
            result.put("document", run.get("document"));
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", outer);
        out.put("result", result);
        out.put("calls", asStringList(run.get("calls")));
        if (run.containsKey("error")) {
            out.put("error", run.get("error"));
        }
        return out;
    }

    // ── SDK client builder — built from the persisted config FILE ──────────────

    /** Build the service data client OFF the scenario's config file (service role, Config.fromFile). */
    private Client serviceClient() {
        return Client.fromConfig(rt.configPathFor(STORE_ID).toString());
    }

    // ── value shaping ───────────────────────────────────────────────────────────

    /** A flow field element's type — {@code field_type}, falling back to {@code type}, default text. */
    private static String fieldType(Map<?, ?> el) {
        Object ft = el.get("field_type");
        if (ft instanceof String s && !s.isEmpty()) {
            return s;
        }
        Object t = el.get("type");
        return t instanceof String s2 && !s2.isEmpty() ? s2 : "text";
    }

    /**
     * A canned VALID plaintext for a field type (demo values over already-supported answerable types).
     * An unknown / text type accepts anything.
     */
    private static String cannedValue(String ftype) {
        return switch (ftype) {
            case "email" -> "billing@acme.example";
            case "number" -> "42";
            case "boolean" -> "true";
            case "date" -> "2024-01-15";
            case "date_of_birth" -> "1990-05-01";
            case "phone" -> "+31201234567";
            case "url" -> "https://acme.example";
            case "address" -> Json.write(Map.of(
                "street", "Herengracht 1",
                "city", "Amsterdam",
                "postal_code", "1011AB",
                "country", "NL"));
            default -> "Acme Corporation";
        };
    }

    // ── run/envelope helpers ─────────────────────────────────────────────────────

    private static Map<String, Object> errorMessage(String error, String message) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("error", error);
        out.put("message", message);
        return out;
    }

    private static Map<String, Object> envelope(String runId, Map<String, Object> action) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("runId", runId);
        out.put("action", action);
        return out;
    }

    /** Append a call name preserving first-occurrence order (a poll may repeat flowRun across polls). */
    private static List<String> addCall(Object callsObj, String name) {
        List<String> calls = asStringList(callsObj);
        if (!calls.contains(name)) {
            calls.add(name);
        }
        return calls;
    }

    // ── HTTP plumbing ────────────────────────────────────────────────────────────

    private Map<String, Object> body(HttpExchange ex) throws IOException {
        return Json.parse(ex.getRequestBody().readAllBytes());
    }

    private void json(HttpExchange ex, int status, Object data) throws IOException {
        byte[] b = Json.writeBytes(data);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, b.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(b);
        }
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

    private static String strOr(Object v, String fallback) {
        return v == null ? fallback : String.valueOf(v);
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

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> asMapList(Object v) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (v instanceof List<?> l) {
            for (Object o : l) {
                if (o instanceof Map<?, ?> m) {
                    out.add((Map<String, Object>) m);
                }
            }
        }
        return out;
    }
}
