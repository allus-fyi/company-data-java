package fyi.allme.allus.companydata;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The latest published version of a flow — what {@link Client#triggerFlowRun} compiles a run's
 * text-tag values from. {@code requestFieldTypes} is the service's request fields: slug → field type.
 */
public record PublishedFlow(int version, Map<String, Object> definition, Map<String, String> requestFieldTypes) {

    @SuppressWarnings("unchecked")
    static PublishedFlow fromApi(Object body) {
        Map<String, Object> obj = body instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        Map<String, String> types = new LinkedHashMap<>();
        if (obj.get("request_field_types") instanceof Map<?, ?> tm) {
            for (Map.Entry<?, ?> e : tm.entrySet()) {
                if (e.getValue() instanceof String t) {
                    types.put(String.valueOf(e.getKey()), t);
                }
            }
        }
        Object v = obj.get("version");
        int version = v instanceof Number n ? n.intValue() : 0;
        Map<String, Object> def = obj.get("definition") instanceof Map<?, ?> d ? (Map<String, Object>) d : Map.of();
        return new PublishedFlow(version, def, types);
    }
}
