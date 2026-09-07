package fyi.allme.allus.companydata;

import fyi.allme.allus.companydata.internal.Parse;

import java.util.Map;

/**
 * One participant's row on a run's {@code participants[]} (flows.html §5a/§9 item 12) — the
 * durable participant set, additively carrying its place in the leaf PDF rule's ordered signing
 * plan. One account may hold TWO of these (two owner parties, or one customer bound to two party
 * keys) — never collapse this to a single row by user id.
 */
public record FlowRunParticipant(
    String partyKey,
    String personUserId,
    String connectionId,
    String documentId,
    String documentStatus,
    boolean requiresSignature,
    boolean requiresAcceptance,
    /** 1-based place in the signing plan; null for a party the plan does not name. */
    Integer position,
    /** "signed" | "accepted" | null — null until this participant's document has acted. */
    String action,
    String actedAt
) {
    static FlowRunParticipant fromApi(Map<String, Object> obj) {
        Object pos = obj.get("position");
        Integer position = pos instanceof Number n ? n.intValue() : null;
        return new FlowRunParticipant(
            Parse.str(obj.get("party_key")),
            Parse.str(obj.get("person_user_id")),
            Parse.str(obj.get("connection_id")),
            Parse.str(obj.get("document_id")),
            Parse.str(obj.get("document_status")),
            Parse.bool(obj.get("requires_signature")),
            Parse.bool(obj.get("requires_acceptance")),
            position,
            Parse.str(obj.get("action")),
            Parse.str(obj.get("acted_at")));
    }
}
