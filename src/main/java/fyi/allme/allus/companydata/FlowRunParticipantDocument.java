package fyi.allme.allus.companydata;

import fyi.allme.allus.companydata.internal.Parse;

import java.util.Map;

/**
 * One of a participant's own documents on a run — one per output document the leaf produced for
 * that participant. {@code position} is the step's 1-based place in the run's ONE signing line;
 * null for a party the output's signer list does not name (its copy is {@code active} from the
 * start, owing nothing).
 */
public record FlowRunParticipantDocument(
    String outputKey,
    String name,
    String documentId,
    String documentStatus,
    boolean requiresSignature,
    boolean requiresAcceptance,
    Integer position,
    /** "signed" | "accepted" | null — null until this document has been acted on. */
    String action,
    String actedAt
) {
    static FlowRunParticipantDocument fromApi(Map<String, Object> obj) {
        Object pos = obj.get("position");
        Integer position = pos instanceof Number n ? n.intValue() : null;
        return new FlowRunParticipantDocument(
            Parse.str(obj.get("output_key")),
            Parse.str(obj.get("name")),
            Parse.str(obj.get("document_id")),
            Parse.str(obj.get("document_status")),
            Parse.bool(obj.get("requires_signature")),
            Parse.bool(obj.get("requires_acceptance")),
            position,
            Parse.str(obj.get("action")),
            Parse.str(obj.get("acted_at")));
    }
}
