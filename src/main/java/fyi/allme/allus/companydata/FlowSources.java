package fyi.allme.allus.companydata;

import fyi.allme.allus.companydata.internal.Json;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * A document leaf's participant PDF sources and the generation inputs they need.
 *
 * <p>A leaf output rule's PDF is a company template ({@code asset_key}), a flow field's answer
 * ({@code source_field: slug} → source key {@code field:<slug>}) or what a bound customer shared on
 * its connection ({@code source_connection: {party, request_slug}} →
 * {@code conn:<party>:<request_slug>}). The generating party uploads its own copy of every HELD
 * source of the run's current leaf, sealed under the call's one-time key, before it calls
 * {@code /generate}; the server refuses a generate whose inputs are not exactly the held set.
 */
final class FlowSources {

    private FlowSources() {
    }

    /**
     * One held participant source of the current leaf. {@code kind} is {@code "field"} ({@code slug}
     * the flow field, {@code file} the generating party's own answer file) or {@code "conn"}
     * ({@code file} the generating party's own copy made at run start).
     */
    record HeldSource(String sourceKey, String kind, String slug, String file) {
    }

    /**
     * The file a plaintext {@code {"_enc_file": file, …}} answer value names, else null. A
     * captured, uploaded or frozen-linked file answer is that plaintext reference, never a
     * ciphertext wrapper; every other answer value is a wrapper and answers null.
     */
    static String fileRef(Object value) {
        Object v = value;
        if (v instanceof String s) {
            try {
                v = Json.parse(s);
            } catch (com.fasterxml.jackson.core.JsonProcessingException | RuntimeException exc) {
                return null;
            }
        }
        if (v instanceof Map<?, ?> m && m.get("_enc_file") instanceof String f && !f.isEmpty()) {
            return f;
        }
        return null;
    }

    /** A file answer as it stands in a decrypted answer map: its reference, which reads as answered. */
    static String fileRefMarker(Object value) {
        return value instanceof String s ? s : Json.write(value);
    }

    /**
     * The held set of the leaf {@code nodeKey}, in rule order, each source key once. Reads every rule
     * of every output of the leaf (a leaf with the older {@code pdfs} list carries template rules
     * only). {@code field:<slug>} is held when the generating party's own answer copy for the slug
     * ({@code for_user_id == ownUserId}) is a file reference; {@code conn:<party>:<slug>} when
     * {@code sourceFiles} (the run read's own copies) names it.
     */
    static List<HeldSource> heldSources(
            Map<String, Object> definition, String nodeKey, List<Map<String, Object>> answers,
            String ownUserId, Map<String, String> sourceFiles) {
        Map<?, ?> node = null;
        if (definition != null && definition.get("nodes") instanceof List<?> nodes) {
            for (Object n : nodes) {
                if (n instanceof Map<?, ?> nm && nodeKey != null && nodeKey.equals(nm.get("key"))) {
                    node = nm;
                    break;
                }
            }
        }
        List<HeldSource> out = new ArrayList<>();
        if (node == null || !(node.get("outputs") instanceof List<?> outputs)) {
            return out;
        }
        Map<String, String> ownFiles = new HashMap<>();
        for (Map<String, Object> row : answers) {
            if (ownUserId != null && ownUserId.equals(row.get("for_user_id"))
                && row.get("slug") instanceof String slug) {
                String f = fileRef(row.get("value"));
                if (f != null) {
                    ownFiles.put(slug, f);
                }
            }
        }
        Set<String> seen = new HashSet<>();
        for (Object output : outputs) {
            if (!(output instanceof Map<?, ?> om) || !(om.get("rules") instanceof List<?> rules)) {
                continue;
            }
            for (Object r : rules) {
                if (!(r instanceof Map<?, ?> rule)) {
                    continue;
                }
                if (rule.get("source_field") instanceof String field && !field.isEmpty()) {
                    String key = "field:" + field;
                    if (!seen.contains(key) && ownFiles.containsKey(field)) {
                        seen.add(key);
                        out.add(new HeldSource(key, "field", field, ownFiles.get(field)));
                    }
                } else if (rule.get("source_connection") instanceof Map<?, ?> conn
                    && conn.get("party") instanceof String party
                    && conn.get("request_slug") instanceof String requestSlug) {
                    String key = "conn:" + party + ":" + requestSlug;
                    String f = sourceFiles == null ? null : sourceFiles.get(key);
                    if (!seen.contains(key) && f != null && !f.isEmpty()) {
                        seen.add(key);
                        out.add(new HeldSource(key, "conn", null, f));
                    }
                }
            }
        }
        return out;
    }

    /**
     * Upload each held source, then POST {@code generatePath} with {@code {otk, values, inputs}}.
     * {@code envelopeOf} fetches and decrypts the generating party's own copy of one source to its
     * envelope JSON string. Each envelope is sealed under the SAME one-time key as {@code values} and
     * POSTed to {@code {generatePath}/inputs} as {@code {source_key, value}} → {@code {input}};
     * {@code inputs} is empty when nothing is held.
     */
    static Object generateWithInputs(
            BiFunction<String, Map<String, Object>, Object> post, String generatePath,
            Map<String, Object> answers, List<HeldSource> held, Function<HeldSource, String> envelopeOf) {
        byte[] otk = Crypto.newOneTimeKey();
        List<Map<String, Object>> inputs = new ArrayList<>();
        for (HeldSource src : held) {
            Map<String, Object> upload = new LinkedHashMap<>();
            upload.put("source_key", src.sourceKey());
            upload.put("value", Crypto.oneTimeKeySeal(otk, envelopeOf.apply(src)));
            Object res = post.apply(generatePath + "/inputs", upload);
            if (!(res instanceof Map<?, ?> rm) || !(rm.get("input") instanceof String ident) || ident.isEmpty()) {
                throw new ApiException(0, null, "generate/inputs answered no input for " + src.sourceKey(), null);
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("source_key", src.sourceKey());
            entry.put("input", ident);
            inputs.add(entry);
        }
        Map<String, Object> body = Crypto.oneTimeKeyBundle(answers, otk);
        body.put("inputs", inputs);
        return post.apply(generatePath, body);
    }

    /** A sealed wrapper as the JSON string a flow-answer or upload body carries. */
    static String sealedString(Object sealedValue) {
        return sealedValue instanceof String s ? s : Json.write(sealedValue);
    }

    /**
     * {@code body} with every {@code answers[].values[].value} sent as the sealed wrapper's JSON
     * string; a value that already is a string, and everything else in the body, stays as it is.
     * The caller's own structure is not modified.
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> sealAnswerValues(Map<String, Object> body) {
        if (body == null || !(body.get("answers") instanceof List<?> answers)) {
            return body;
        }
        List<Object> sealedAnswers = new ArrayList<>();
        for (Object a : answers) {
            if (a instanceof Map<?, ?> am && am.get("values") instanceof List<?> values) {
                List<Object> sealedValues = new ArrayList<>();
                for (Object v : values) {
                    if (v instanceof Map<?, ?> vm && vm.get("value") != null) {
                        Map<String, Object> copy = new LinkedHashMap<>((Map<String, Object>) vm);
                        copy.put("value", sealedString(vm.get("value")));
                        sealedValues.add(copy);
                    } else {
                        sealedValues.add(v);
                    }
                }
                Map<String, Object> answerCopy = new LinkedHashMap<>((Map<String, Object>) am);
                answerCopy.put("values", sealedValues);
                sealedAnswers.add(answerCopy);
            } else {
                sealedAnswers.add(a);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>(body);
        out.put("answers", sealedAnswers);
        return out;
    }

    /** The {@code file} of an upload's {@code 201 {file}} response. */
    static String responseFile(Object body) {
        if (body instanceof Map<?, ?> m && m.get("file") instanceof String f && !f.isEmpty()) {
            return f;
        }
        throw new ApiException(0, null, "the upload response carried no file", null);
    }
}
