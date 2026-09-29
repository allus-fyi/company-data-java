package fyi.allme.allus.companydata;

import fyi.allme.allus.companydata.internal.Parse;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * One participant's row on a run's {@code participants[]} — the durable participant set.
 * {@code documents} holds the participant's own copy of every output document the run produced,
 * ordered by signing-line position (unlisted last); empty before generation. One account may hold
 * TWO of these (two owner parties, or one customer bound to two party keys) — never collapse this
 * to a single row by user id.
 */
public record FlowRunParticipant(
    String partyKey,
    String personUserId,
    String connectionId,
    List<FlowRunParticipantDocument> documents
) {
    @SuppressWarnings("unchecked")
    static FlowRunParticipant fromApi(Map<String, Object> obj) {
        List<FlowRunParticipantDocument> documents = new ArrayList<>();
        if (obj.get("documents") instanceof List<?> dl) {
            for (Object d : dl) {
                if (d instanceof Map<?, ?> dm) {
                    documents.add(FlowRunParticipantDocument.fromApi((Map<String, Object>) dm));
                }
            }
        }
        return new FlowRunParticipant(
            Parse.str(obj.get("party_key")),
            Parse.str(obj.get("person_user_id")),
            Parse.str(obj.get("connection_id")),
            List.copyOf(documents));
    }
}
