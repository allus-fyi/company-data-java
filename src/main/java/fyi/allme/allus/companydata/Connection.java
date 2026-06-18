package fyi.allme.allus.companydata;

import fyi.allme.allus.companydata.internal.ModelDeps;
import fyi.allme.allus.companydata.internal.Parse;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A connected person — identity + the slug-keyed value map.
 *
 * <p>NO source field anywhere: {@link #values()} is keyed by YOUR request slug.
 * {@code raw} is the underlying hardened API object (no person source field).
 */
public record Connection(
    String id,
    String personId,
    String displayName,
    OffsetDateTime connectedAt,
    Map<String, Value> values,
    Map<String, Object> raw
) {
    /**
     * Build a Connection from a hardened connectionDetail (or list) object.
     *
     * <p>connectionDetail returns {@code {connection_id, user_id, values}} and no
     * display_name/connected_at, so those can be supplied via {@code identity}
     * (the matching list row, which carries them).
     */
    @SuppressWarnings("unchecked")
    static Connection fromApi(Map<String, Object> obj, ModelDeps deps, Map<String, Object> identity) {
        Map<String, Object> id = identity != null ? identity : Map.of();

        String connId = firstNonNull(
            Parse.str(obj.get("connection_id")), Parse.str(obj.get("id")),
            Parse.str(id.get("connection_id")));
        String personId = firstNonNull(
            Parse.str(obj.get("user_id")), Parse.str(obj.get("person_id")),
            Parse.str(obj.get("person_user_id")), Parse.str(id.get("user_id")));
        String displayName = firstNonNull(
            Parse.str(obj.get("display_name")), Parse.str(id.get("display_name")));
        OffsetDateTime connectedAt = Parse.isoDateTime(
            obj.get("connected_at") != null ? obj.get("connected_at") : id.get("connected_at"));

        Map<String, Value> values = new LinkedHashMap<>();
        Object valuesObj = obj.get("values");
        if (valuesObj instanceof Map<?, ?> vm) {
            for (Map.Entry<?, ?> e : vm.entrySet()) {
                String slug = String.valueOf(e.getKey());
                if (e.getValue() instanceof Map<?, ?> entry) {
                    String fieldType = deps.typeForSlug().apply(slug);
                    values.put(slug, Value.fromApi((Map<String, Object>) entry, fieldType, deps));
                }
            }
        }
        return new Connection(connId, personId, displayName, connectedAt, values, obj);
    }

    private static String firstNonNull(String... candidates) {
        for (String c : candidates) {
            if (c != null) {
                return c;
            }
        }
        return null;
    }
}
