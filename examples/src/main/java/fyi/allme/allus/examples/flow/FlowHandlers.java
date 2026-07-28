package fyi.allme.allus.examples.flow;

import com.sun.net.httpserver.HttpExchange;

import fyi.allme.allus.companydata.ApiException;
import fyi.allme.allus.companydata.Client;
import fyi.allme.allus.companydata.ConfigException;
import fyi.allme.allus.companydata.Connection;
import fyi.allme.allus.companydata.FlowRun;
import fyi.allme.allus.companydata.Identity;
import fyi.allme.allus.companydata.ValidationException;

import fyi.allme.allus.examples.Http;
import fyi.allme.allus.examples.Json;
import fyi.allme.allus.examples.Runtime;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static fyi.allme.allus.examples.Util.action;
import static fyi.allme.allus.examples.Util.addCall;
import static fyi.allme.allus.examples.Util.asMapList;
import static fyi.allme.allus.examples.Util.asStringList;
import static fyi.allme.allus.examples.Util.envelope;
import static fyi.allme.allus.examples.Util.strOr;

/**
 * The ONE contract-flow scenario handler ({@code flow:run}, flow family). Each handler reaches only the
 * intended top-level SDK flow surface ({@link Client#identity()} / {@link Client#triggerFlowRun} /
 * {@link Client#flowRun} / {@link Client#processFlowRun} / {@link Client#flowRunAnswers} /
 * {@link Client#flowRunDocument}) and NEVER performs raw platform HTTP.
 *
 * <p>There is NO cross-card flow-run-id handoff: the platform flow run lives entirely INSIDE this one
 * demo run's file — the demo runId is the backend run and the platform flowRunId is stored inside it,
 * never exposed as a separate browser input.
 *
 * <p>Settings flow (config-file model): the browser POSTs the scenario's setup values to {@code /config},
 * written to a canonical SDK config FILE ({@code .runtime/config/flow_run.json}; the service PEM →
 * {@code .runtime/config/keys/} by path). {@link #start} builds the service {@link Client} from that file
 * via {@link Client#fromConfig} and runs OFF the config. A {@code /start} with no saved config → 409
 * not_configured.
 *
 * <p>The {@link #pollBody} poll is the drive loop AND the resume: each poll reads the platform run and, if
 * it is the company's turn, drives exactly ONE company step; otherwise it reports waiting/running and
 * touches nothing (the next poll after the person answers on their phone resumes automatically).
 */
public final class FlowHandlers {
    /** The single public scenario id (the flow family). */
    public static final String SCENARIO = "flow:run";

    private static final String DEFAULT_API_URL = "https://api.allme.fyi";

    /** The flow party keys the fixtures pin. */
    private static final String PARTY_COMPANY = "company";
    private static final String PARTY_CUSTOMER = "customer";

    /** The canned INVALID value the validation-demo submits once for an email field. */
    private static final String INVALID_EMAIL = "not-an-email";

    /**
     * The "what just happened" trace (#578). Every entry is {@code <SDK method> — <what that call did in
     * THIS scenario>}, appended AT the call site, in the order the calls were made. The annotations are
     * byte-identical in all six SDK examples — only the method reference is written in the language's own
     * idiom — so one scenario teaches one thing whichever example a reader starts. Keep them in step when
     * this handler changes.
     */
    private static final String CALL_SERVICE_BUILD = "Client.fromConfig — builds the SERVICE-role data client from the saved config file: client credentials plus the service private key, decrypted with its passphrase";
    private static final String CALL_IDENTITY = "Client.identity — GET /api/company-data/whoami: this service's own company_user_id, which the COMPANY party binds to";
    private static final String CALL_CONNECTION = "Client.connection — reads the configured connection; the connected person's id on it is what the CUSTOMER party binds to";
    private static final String CALL_TRIGGER = "Client.triggerFlowRun — starts a run of the published flow for that connection, pinning the flow's latest published version";
    private static final String CALL_FLOW_RUN = "Client.flowRun — re-read on every poll to see whose turn the run is on";
    private static final String CALL_PROCESS = "Client.processFlowRun — drives ONE company step: decrypts the answers so far, fills the node, type-checks the values, encrypts a copy per party, submits — and generates the document when the submit lands on a document-mode leaf";
    private static final String CALL_ANSWERS = "Client.flowRunAnswers — the completed run's answers, decrypted with the service key";
    private static final String CALL_DOCUMENT = "Client.flowRunDocument — downloads the company's own copy of the generated contract and decrypts it with the service key";

    private final Runtime rt;

    public FlowHandlers(Runtime rt) {
        this.rt = rt;
    }

    /** This family's contribution to GET /api/meta: the single runnable flow scenario. */
    public List<Map<String, Object>> scenarios() {
        List<Map<String, Object>> out = new ArrayList<>();
        out.add(Map.of("id", SCENARIO, "kind", "runnable"));
        return out;
    }

    // ── POST /api/scenarios/{id}/config ────────────────────────────────────────

    /**
     * Write the browser's setup values to a canonical SDK config FILE (service role). The service PEM is
     * written to config/keys/ and referenced by path; the demo-only run parameters (published flow id,
     * connection id, fixture choice) go to the meta sidecar so the config file stays a pure SDK config
     * the run executes off.
     */
    public void config(HttpExchange ex, String id) throws IOException {
        Map<String, Object> in = Http.body(ex);

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
        String configPath = rt.writeConfig(SCENARIO, cfg);

        // Demo-only run parameters (NOT SDK Config fields) → meta sidecar.
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("flow_id", strOr(in.get("flowId"), ""));
        meta.put("connection_id", strOr(in.get("connectionId"), ""));
        meta.put("fixture", strOr(in.get("fixture"), ""));
        rt.writeConfigMeta(SCENARIO, meta);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("configPath", configPath);
        Http.json(ex, 200, out);
    }

    // ── POST /api/scenarios/{id}/start ─────────────────────────────────────────

    /**
     * Trigger the flow run. Build the service {@link Client} from the persisted config file, construct
     * the bindings via the intended SDK surface (company → {@link Identity#companyUserId()}; customer →
     * {@link Connection#personId()}), call {@link Client#triggerFlowRun}, and store the returned platform
     * flowRunId in the demo run file. Returns {@code {runId, action:{"type":"none"}}} — the drive happens
     * on the {@code GET /api/runs} poll.
     */
    public void start(HttpExchange ex, String id) throws IOException {
        if (!rt.hasConfig(SCENARIO)) {
            // The run is built from the persisted config file, not the request body.
            Http.json(ex, 409, Map.of("error", "not_configured"));
            return;
        }
        Map<String, Object> meta = rt.readConfigMeta(SCENARIO);
        String flowId = strOr(meta.get("flow_id"), "");
        String connectionId = strOr(meta.get("connection_id"), "");
        if (flowId.isEmpty() || connectionId.isEmpty()) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("error", "not_configured");
            err.put("message", "flow id and connection id are required");
            Http.json(ex, 409, err);
            return;
        }

        List<String> calls = new ArrayList<>();
        String flowRunId;
        try {
            calls.add(CALL_SERVICE_BUILD);
            Client client = serviceClient();

            // The COMPANY party binds to this service's own company_user_id.
            calls.add(CALL_IDENTITY);
            Identity identity = client.identity();
            String companyUserId = identity.companyUserId();
            if (companyUserId == null || companyUserId.isEmpty()) {
                Http.failure(ex, 502, "identity_error", "identity() returned no company_user_id");
                return;
            }

            // The CUSTOMER party binds to the connected person's public personId (no public user_id).
            calls.add(CALL_CONNECTION);
            Connection connection = client.connection(connectionId);
            String personId = connection.personId();
            if (personId == null || personId.isEmpty()) {
                Http.failure(ex, 502, "connection_error",
                    "connection " + connectionId + " has no personId (not found or not connected)");
                return;
            }

            Map<String, String> bindings = new LinkedHashMap<>();
            bindings.put(PARTY_COMPANY, companyUserId);
            bindings.put(PARTY_CUSTOMER, personId);
            calls.add(CALL_TRIGGER);
            FlowRun flowRun = client.triggerFlowRun(flowId, connectionId, bindings);

            flowRunId = flowRun.id();
            if (flowRunId == null || flowRunId.isEmpty()) {
                Http.failure(ex, 502, "trigger_error", "triggerFlowRun returned no run id");
                return;
            }
        } catch (ApiException | ConfigException e) {
            Http.failure(ex, 502, "start_failed", Http.reasonOf(e));
            return;
        }

        String runId = rt.newRunId();
        Map<String, Object> run = new LinkedHashMap<>();
        run.put("family", "flow");
        run.put("scenario", SCENARIO);
        run.put("flowRunId", flowRunId);
        run.put("steps", new ArrayList<>());
        run.put("rejectedNodes", new ArrayList<>());
        run.put("calls", calls);
        run.put("completed", false);
        rt.writeRun(runId, run);

        Http.json(ex, 200, envelope(runId, action("none")));
    }

    // ── GET /api/runs/{runId} (flow) ────────────────────────────────────────────

    /**
     * The idempotent, short-cycled poll that IS the drive loop and the resume. Reads the platform run;
     * if it is the company's turn drives exactly ONE step; on completion fetches the answers and
     * (document-mode) downloads the generated contract. A terminal run returns its cached result on every
     * poll until TTL/Clear.
     */
    public Map<String, Object> pollBody(String runId, Map<String, Object> run) {
        // Idempotent: once terminal (completed OR errored) the outcome is returned unchanged on every
        // subsequent poll — a failed run must stay failed (outer "failed"), not re-drive the platform.
        boolean terminal = Boolean.TRUE.equals(run.get("completed")) || run.containsKey("error");
        if (!terminal) {
            run = advance(run);
            rt.writeRun(runId, run);
        }
        return result(run);
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
            run.put("calls", addCall(run.get("calls"), CALL_SERVICE_BUILD));
            Client client = serviceClient();
            run.put("calls", addCall(run.get("calls"), CALL_FLOW_RUN));
            FlowRun flowRun = client.flowRun(flowRunId);

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
        run.put("calls", addCall(run.get("calls"), CALL_PROCESS));
        try {
            client.processFlowRun(flowRunId, fillNode);
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
        run.put("calls", addCall(run.get("calls"), CALL_ANSWERS));
        Map<String, Object> answers = client.flowRunAnswers(flowRun);
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
                run.put("calls", addCall(run.get("calls"), CALL_DOCUMENT));
                byte[] bytes = client.flowRunDocument(flowRunId);
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
        return Client.fromConfig(rt.configPathFor(SCENARIO).toString());
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

}
