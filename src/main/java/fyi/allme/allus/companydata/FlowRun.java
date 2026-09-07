package fyi.allme.allus.companydata;

import fyi.allme.allus.companydata.internal.Parse;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A contract-flow run (company-data side).
 *
 * <p>The company is one of the two bound parties. {@code bindings} maps each party key to the bound
 * user_id (the company's own is {@code companyUserId}); {@code answers} are the per-party encrypted
 * answer copies (the company reads the rows whose {@code for_user_id == companyUserId}, decryptable
 * with the service private key); {@code definition} is the pinned flow-version graph
 * ({@code nodes}, {@code edges}, {@code parties}, {@code output_mode}).
 *
 * <p>{@code answers} is the raw list of {@code {slug, for_user_id, value}} rows; the client
 * decrypts the company's copies on demand.
 *
 * <p>{@code referenceDate} is the run's pinned "today" ({@code YYYY-MM-DD}), used when computing
 * flow constants; {@code null} when the API does not carry one.
 */
public record FlowRun(
    String id,
    String flowId,
    Object flowVersion,
    String serviceId,
    String connectionId,
    String companyUserId,
    Map<String, String> bindings,
    String status,
    String currentNode,
    String documentId,
    String outputMode,
    String referenceDate,
    Map<String, Object> definition,
    List<Map<String, Object>> answers,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt,
    /**
     * Every party the run binds, the owning company included (flows.html §5a/§9 item 12).
     * {@code connectionId} above names only the PRIMARY counterparty, so a multi-actor run's
     * other counterparties are reachable only here.
     */
    List<FlowRunParticipant> participants,
    Map<String, Object> raw
) {

    /** The party key the company is bound to ({@code bindings[key] == companyUserId}). */
    public String companyPartyKey() {
        if (companyUserId == null) {
            return null;
        }
        for (Map.Entry<String, String> e : bindings.entrySet()) {
            if (companyUserId.equals(e.getValue())) {
                return e.getKey();
            }
        }
        return null;
    }

    /** The company's bound user_id — its answer copies use this {@code for_user_id}. */
    public String serviceUserId() {
        return companyUserId;
    }

    @SuppressWarnings("unchecked")
    static FlowRun fromApi(Object body) {
        Map<String, Object> obj = body instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();

        Map<String, Object> definition;
        if (obj.get("definition") instanceof Map<?, ?> d) {
            definition = (Map<String, Object>) d;
        } else {
            definition = new LinkedHashMap<>();
            definition.put("nodes", obj.get("nodes"));
            definition.put("edges", obj.get("edges"));
            definition.put("parties", obj.get("parties"));
            definition.put("output_mode", obj.get("output_mode"));
        }

        Map<String, String> bindings = new LinkedHashMap<>();
        if (obj.get("bindings") instanceof Map<?, ?> b) {
            for (Map.Entry<?, ?> e : b.entrySet()) {
                bindings.put(String.valueOf(e.getKey()), e.getValue() == null ? "" : String.valueOf(e.getValue()));
            }
        }

        List<Map<String, Object>> answers = new ArrayList<>();
        if (obj.get("answers") instanceof List<?> l) {
            for (Object a : l) {
                if (a instanceof Map<?, ?> am) {
                    answers.add((Map<String, Object>) am);
                }
            }
        }

        String outputMode = Parse.str(obj.get("output_mode"));
        if (outputMode == null || outputMode.isEmpty()) {
            outputMode = Parse.str(definition.get("output_mode"));
        }

        List<FlowRunParticipant> participants = new ArrayList<>();
        if (obj.get("participants") instanceof List<?> pl) {
            for (Object p : pl) {
                if (p instanceof Map<?, ?> pm) {
                    participants.add(FlowRunParticipant.fromApi((Map<String, Object>) pm));
                }
            }
        }

        return new FlowRun(
            Parse.str(obj.get("id")),
            Parse.str(obj.get("flow_id")),
            obj.get("flow_version"),
            Parse.str(obj.get("service_id")),
            Parse.str(obj.get("connection_id")),
            Parse.str(obj.get("company_user_id")),
            bindings,
            Parse.str(obj.get("status")),
            Parse.str(obj.get("current_node")),
            Parse.str(obj.get("document_id")),
            outputMode,
            Parse.str(obj.get("reference_date")),
            definition,
            answers,
            Parse.isoDateTime(obj.get("created_at")),
            Parse.isoDateTime(obj.get("updated_at")),
            participants,
            obj);
    }
}
