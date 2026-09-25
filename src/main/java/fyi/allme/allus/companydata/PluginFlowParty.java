package fyi.allme.allus.companydata;

import fyi.allme.allus.companydata.internal.JdkTransport;
import fyi.allme.allus.companydata.internal.Json;
import fyi.allme.allus.companydata.internal.Parse;
import fyi.allme.allus.companydata.internal.Transport;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The plugin call a company party of a flow makes, shared by the service {@link Client} and the
 * {@link CustomerClient}: a pass from the API, the element's inputs read from ONE live answer
 * map, the request sealed to the plugin's public key and posted to the forwarder over a plain
 * transport that carries no allme credential, and the reply opened with a key pair made for the
 * call.
 */
final class PluginFlowParty {

    private final Transport transport;
    private final Supplier<FlowRun> fetchRun;
    private final Supplier<PluginPass> fetchPass;
    private final Function<FlowRun, Map<String, Object>> storedAnswers;
    private final Function<FlowRun, String> ownUserId;

    PluginFlowParty(
            Transport transport,
            Supplier<FlowRun> fetchRun,
            Supplier<PluginPass> fetchPass,
            Function<FlowRun, Map<String, Object>> storedAnswers,
            Function<FlowRun, String> ownUserId) {
        this.transport = transport;
        this.fetchRun = fetchRun;
        this.fetchPass = fetchPass;
        this.storedAnswers = storedAnswers;
        this.ownUserId = ownUserId;
    }

    /**
     * The plain transport the forwarder is reached over: no bearer token or other allme
     * credential, no base-URL rewriting, and no redirect followed.
     */
    static Transport newTransport() {
        return new JdkTransport(HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build());
    }

    PluginOptionsResult options(String slug, String block, String query,
            Map<String, String> picks, Map<String, Object> values, Map<String, Object> draft) {
        Map<String, Object> reply = call(slug, draft, (fieldType, inputs) -> {
            Map<String, Object> req = new LinkedHashMap<>();
            req.put("field_type", fieldType);
            req.put("op", "options");
            req.put("block", block);
            req.put("query", query == null ? "" : query);
            req.put("picks", picks == null ? Map.of() : picks);
            req.put("values", values == null ? Map.of() : values);
            req.put("inputs", inputs);
            return req;
        });
        if (!(reply.get("options") instanceof List<?> list)) {
            throw new ApiException(0, "plugin.not_responding", "the plugin reply carries no options");
        }
        List<PluginOptionsResult.Option> options = new ArrayList<>();
        for (Object o : list) {
            if (o instanceof Map<?, ?> om) {
                options.add(new PluginOptionsResult.Option(
                    FlowCondition.str(om.get("id")), FlowCondition.str(om.get("label"))));
            }
        }
        return new PluginOptionsResult(options, Parse.bool(reply.get("more")));
    }

    @SuppressWarnings("unchecked")
    PluginOutputsResult outputs(String slug, Map<String, String> picks, Map<String, Object> values,
            Map<String, Object> draft) {
        Map<String, Object> reply = call(slug, draft, (fieldType, inputs) -> {
            Map<String, Object> req = new LinkedHashMap<>();
            req.put("field_type", fieldType);
            req.put("op", "outputs");
            req.put("picks", picks == null ? Map.of() : picks);
            req.put("values", values == null ? Map.of() : values);
            req.put("inputs", inputs);
            return req;
        });
        if (Parse.bool(reply.get("picks_invalid"))) {
            return new PluginPicksInvalid();
        }
        if (!(reply.get("outputs") instanceof Map<?, ?> outs)) {
            throw new ApiException(0, "plugin.not_responding", "the plugin reply carries no outputs");
        }
        return new PluginOutputs((Map<String, Object>) outs);
    }

    private interface RequestBuilder {
        Map<String, Object> build(String fieldType, Map<String, Object> inputs);
    }

    /**
     * Resolve the element's inputs, seal the request, post it and open the reply. A 409
     * {@code plugin.key_changed} reseals once with the key it names; a 401 or 403 takes a new pass
     * once.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> call(String slug, Map<String, Object> draft, RequestBuilder build) {
        FlowRun run = fetchRun.get();
        Map<String, Object> live = liveAnswers(run, storedAnswers.apply(run), draft);
        PluginPass pass = fetchPass.get();
        Map<String, Object> spec = pass.specs().get(slug);
        if (spec == null) {
            throw new ConfigException("\"" + slug + "\" is not a plugin field on the run's current step");
        }
        Privacy privacy = new Privacy(run, draft, ownUserId.apply(run));
        Map<String, Object> inputs = pluginInputs(spec, live, privacy);
        String pluginId = Parse.str(spec.get("plugin_id"));
        Map<String, Object> request = build.build(Parse.str(spec.get("field_type")), inputs);

        String spki = pass.publicKeyFor(pluginId);
        boolean resealed = false;
        boolean renewed = false;
        while (true) {
            if (spki == null || spki.isEmpty()) {
                throw new ApiException(0, "plugin.not_responding", "the plugin has no usable description");
            }
            RSAPublicKey pluginKey = Crypto.loadPublicKey(spki);
            Crypto.ReplyKey replyKey = Crypto.generateReplyKey();
            request.put("reply_key", replyKey.spki());
            Map<String, Object> sealed = Crypto.encryptForPublicKey(Json.write(request), pluginKey);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("pass", pass.pass());
            payload.put("plugin_id", pluginId);
            payload.put("request", Json.write(sealed));
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("Content-Type", "application/json");
            headers.put("Accept", "application/json");
            Transport.Response resp;
            try {
                resp = transport.send("POST", pass.forwarderUrl() + "/call",
                    Json.write(payload).getBytes(StandardCharsets.UTF_8), headers);
            } catch (ApiException exc) {
                throw new ApiException(0, "plugin.not_responding",
                    "the forwarder could not be reached: " + exc.apiMessage());
            }
            Map<String, Object> body = parseBody(resp.body());
            String errorKey = Parse.str(body.get("error_key"));
            int status = resp.status();
            if (status == 200) {
                String plaintext = Crypto.decrypt(Wrapper.of(body.get("reply")), replyKey.privateKey());
                try {
                    if (Json.parse(plaintext) instanceof Map<?, ?> reply) {
                        return (Map<String, Object>) reply;
                    }
                } catch (com.fasterxml.jackson.core.JsonProcessingException exc) {
                    throw new ApiException(0, "plugin.not_responding", "the plugin reply is not valid JSON");
                }
                throw new ApiException(0, "plugin.not_responding", "the plugin reply is not a JSON object");
            }
            if (status == 409 && "plugin.key_changed".equals(errorKey) && !resealed) {
                resealed = true;
                spki = Parse.str(body.get("public_key"));
                continue;
            }
            if ((status == 401 || status == 403) && !renewed) {
                renewed = true;
                pass = fetchPass.get();
                spki = pass.publicKeyFor(pluginId);
                continue;
            }
            throw new ApiException(status, errorKey, Parse.str(body.get("error")));
        }
    }

    // The forwarder's JSON answer; an unreadable body reads as empty, so the caller surfaces the
    // status without an error_key.
    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseBody(String text) {
        if (text == null || text.isEmpty()) {
            return new LinkedHashMap<>();
        }
        try {
            Object parsed = Json.parse(text);
            return parsed instanceof Map<?, ?> m ? (Map<String, Object>) m : new LinkedHashMap<>();
        } catch (com.fasterxml.jackson.core.JsonProcessingException exc) {
            return new LinkedHashMap<>();
        }
    }

    // ── the live answer map, privacy and inputs ─────────────────────────────────

    /** The slugs of every plugin element in a flow definition. */
    static List<String> pluginSlugsOf(Map<String, Object> definition) {
        List<String> out = new ArrayList<>();
        for (Element el : elements(definition)) {
            if ("plugin".equals(el.map().get("kind")) && el.slug() != null && !el.slug().isEmpty()) {
                out.add(el.slug());
            }
        }
        return out;
    }

    private record Element(String nodeKey, String slug, Map<?, ?> map) {
    }

    private static List<Element> elements(Map<String, Object> definition) {
        List<Element> out = new ArrayList<>();
        if (definition == null || !(definition.get("nodes") instanceof List<?> nodes)) {
            return out;
        }
        for (Object n : nodes) {
            if (!(n instanceof Map<?, ?> nm) || !(nm.get("elements") instanceof List<?> els)) {
                continue;
            }
            String nodeKey = Parse.str(nm.get("key"));
            for (Object e : els) {
                if (e instanceof Map<?, ?> em) {
                    out.add(new Element(nodeKey, Parse.str(em.get("slug")), em));
                }
            }
        }
        return out;
    }

    // Every answerable slug (field and plugin elements) → its element.
    private static Map<String, Element> answerElements(Map<String, Object> definition) {
        Map<String, Element> out = new LinkedHashMap<>();
        for (Element el : elements(definition)) {
            Object kind = el.map().get("kind");
            if (el.slug() != null && !el.slug().isEmpty() && ("field".equals(kind) || "plugin".equals(kind))) {
                out.put(el.slug(), el);
            }
        }
        return out;
    }

    /**
     * The ONE live answer map inputs and bounds are read from: the stored answers the caller can
     * read, overlaid with {@code draft} for the current step's slugs, plugin answers expanded,
     * constants computed.
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> liveAnswers(FlowRun run, Map<String, Object> stored, Map<String, Object> draft) {
        Map<String, Object> merged = new LinkedHashMap<>(stored == null ? Map.of() : stored);
        Map<String, Element> byslug = answerElements(run.definition());
        if (draft != null && run.currentNode() != null) {
            for (Map.Entry<String, Object> e : draft.entrySet()) {
                Element el = byslug.get(e.getKey());
                if (el != null && run.currentNode().equals(el.nodeKey())) {
                    merged.put(e.getKey(), e.getValue());
                }
            }
        }
        List<Object> constants = run.definition().get("constants") instanceof List<?> cl
            ? (List<Object>) cl : List.of();
        return FlowCondition.computeConstants(constants,
            FlowCondition.expandPluginAnswers(merged, pluginSlugsOf(run.definition())), run.referenceDate());
    }

    /** Decides whether a key of the live map reaches another party's private value, failing closed. */
    private static final class Privacy {
        private final FlowRun run;
        private final Map<String, Object> draft;
        private final String ownUser;
        private final Set<String> privateSlugs;
        private final Map<String, Element> elements;
        private final Map<String, Map<?, ?>> constants = new LinkedHashMap<>();

        Privacy(FlowRun run, Map<String, Object> draft, String ownUser) {
            this.run = run;
            this.draft = draft == null ? Map.of() : draft;
            this.ownUser = ownUser;
            this.privateSlugs = run.privateSlugs() == null ? null : new HashSet<>(run.privateSlugs());
            this.elements = answerElements(run.definition());
            if (run.definition().get("constants") instanceof List<?> cl) {
                for (Object c : cl) {
                    if (c instanceof Map<?, ?> cm && cm.get("key") instanceof String k) {
                        constants.put(k, cm);
                    }
                }
            }
        }

        /**
         * Whether {@code ref} — a slug, a dotted plugin key or a constant — reaches a private
         * source: a slug in {@code private_slugs} (or, when the run carried no {@code private_slugs},
         * any slug another party answers), a constant whose refs reach one, or a current-step draft
         * whose field's default reaches one. A plugin answer's outputs are never private, whatever
         * inputs produced them.
         */
        boolean isPrivate(String ref, Set<String> seen) {
            int dot = ref.indexOf('.');
            String base = dot >= 0 ? ref.substring(0, dot) : ref;
            if (!seen.add(base)) {
                return false;
            }
            Map<?, ?> constant = constants.get(base);
            if (constant != null) {
                return exprReachesPrivate(constant.get("expr"), seen);
            }
            Element el = elements.get(base);
            if (privateSlugs == null) {
                if (el != null) {
                    String bound = run.bindings().get(Client.partyOf(run.definition(), el.nodeKey()));
                    if (bound == null || !bound.equals(ownUser)) {
                        return true;
                    }
                }
            } else if (privateSlugs.contains(base)) {
                return true;
            }
            if (draft.containsKey(base) && el != null && el.nodeKey() != null
                    && el.nodeKey().equals(run.currentNode()) && el.map().get("default") != null) {
                return exprReachesPrivate(el.map().get("default"), seen);
            }
            return false;
        }

        private boolean exprReachesPrivate(Object expr, Set<String> seen) {
            Set<String> refs = new LinkedHashSet<>();
            FlowCondition.collectExprConstRefs(expr, null, refs);
            for (String r : refs) {
                if (isPrivate(r, seen)) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * The submitted slugs whose answer is private by the same rule the plugin helpers apply to
     * inputs: a field whose default reaches a private source. The submit carries
     * {@code source_private: true} on exactly these.
     */
    static Set<String> sourcePrivate(FlowRun run, Set<String> submitted, String ownUser) {
        Map<String, Object> draft = new LinkedHashMap<>();
        for (String slug : submitted) {
            draft.put(slug, Boolean.TRUE);
        }
        Privacy privacy = new Privacy(run, draft, ownUser);
        Set<String> out = new HashSet<>();
        for (String slug : submitted) {
            if (privacy.isPrivate(slug, new HashSet<>())) {
                out.add(slug);
            }
        }
        return out;
    }

    /**
     * The element's declared inputs from the live map, converted to their declared types. A
     * required input that cannot be sent throws {@link PluginInputUnavailableException}; an
     * optional one is left out of the call.
     */
    private static Map<String, Object> pluginInputs(Map<String, Object> spec, Map<String, Object> live,
            Privacy privacy) {
        Map<?, ?> snapshot = spec.get("snapshot") instanceof Map<?, ?> sm ? sm : Map.of();
        Map<?, ?> wiring = spec.get("inputs") instanceof Map<?, ?> wm ? wm : Map.of();
        Map<String, Object> out = new LinkedHashMap<>();
        if (!(snapshot.get("inputs") instanceof List<?> declared)) {
            return out;
        }
        for (Object in : declared) {
            if (!(in instanceof Map<?, ?> im)) {
                continue;
            }
            String key = Parse.str(im.get("key"));
            boolean required = Parse.bool(im.get("required"));
            Object refObj = wiring.get(key);
            String ref = refObj == null ? "" : String.valueOf(refObj);
            String reason = null;
            Object converted = null;
            if (ref.isEmpty()) {
                reason = PluginInputUnavailableException.UNWIRED;
            } else if (!live.containsKey(ref) || !FlowCondition.answered(live.get(ref))) {
                reason = PluginInputUnavailableException.UNANSWERED;
            } else if (privacy.isPrivate(ref, new HashSet<>())) {
                reason = PluginInputUnavailableException.OTHER_PARTY_PRIVATE;
            } else {
                converted = convertInput(Parse.str(im.get("type")), live.get(ref));
                if (converted == null) {
                    reason = PluginInputUnavailableException.NOT_CONVERTIBLE;
                }
            }
            if (reason == null) {
                out.put(key, converted);
            } else if (required) {
                throw new PluginInputUnavailableException(key, ref.isEmpty() ? null : ref, reason);
            }
        }
        return out;
    }

    /**
     * Convert a live value to an input's declared type: number a finite JSON number (through the
     * evaluator's number coercion), date a {@code YYYY-MM-DD} string, boolean a JSON boolean, text
     * a string. {@code null} when it does not convert.
     */
    private static Object convertInput(String type, Object value) {
        switch (type == null ? "" : type) {
            case "number": {
                Double n = FlowCondition.toNum(value);
                return n == null || !Double.isFinite(n) ? null : n;
            }
            case "date": {
                LocalDate d = FlowCondition.parseFlowDate(value);
                return d == null ? null : d.toString();
            }
            case "boolean": {
                if (value instanceof Boolean b) {
                    return b;
                }
                if (value instanceof String s) {
                    String t = s.trim();
                    if (t.equals("true")) {
                        return Boolean.TRUE;
                    }
                    if (t.equals("false")) {
                        return Boolean.FALSE;
                    }
                }
                return null;
            }
            default:
                return FlowCondition.str(value);
        }
    }

    /**
     * Check {@code value} against the min and max of {@code slug}'s field element, each evaluated
     * over the live answer map; a bound that evaluates to {@code null} is no bound. Numbers compare
     * as numbers and dates as dates.
     *
     * @throws ValidationException naming the broken bound
     */
    static void checkBounds(FlowRun run, String slug, Object value, Map<String, Object> live) {
        Map<?, ?> element = Client.fieldElementForSlug(run.definition(), slug);
        if (element == null || !FlowCondition.answered(value)) {
            return;
        }
        for (String bound : List.of("min", "max")) {
            Object expr = element.get(bound);
            if (expr == null) {
                continue;
            }
            Object limit = FlowCondition.evalExpr(expr, live, run.referenceDate());
            if (limit == null) {
                continue;
            }
            boolean broken = false;
            Double vn = FlowCondition.toNum(value);
            Double ln = FlowCondition.toNum(limit);
            if (vn != null && ln != null) {
                broken = bound.equals("min") ? vn < ln : vn > ln;
            } else {
                LocalDate vd = FlowCondition.parseFlowDate(value);
                LocalDate ld = FlowCondition.parseFlowDate(limit);
                if (vd != null && ld != null) {
                    broken = bound.equals("min") ? vd.isBefore(ld) : vd.isAfter(ld);
                }
            }
            if (broken) {
                throw new ValidationException(slug, Client.fieldTypeOfElement(element), bound, limit);
            }
        }
    }
}
