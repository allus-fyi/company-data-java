package fyi.allme.allus.companydata;

import fyi.allme.allus.companydata.internal.ModelDeps;
import fyi.allme.allus.companydata.internal.Parse;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A change-feed / webhook event.
 *
 * <p>{@link #id()} is the stable server change-row id (the pump dedupes on it
 * after a crash/replay); {@link #at()} is the change time (there is
 * NO separate updatedAt on a change). {@link #shareCode()} is the person's
 * profile share code, present on every event (may be {@code null}).
 * {@link #slug()}/{@link #value()}/{@link #live()} are present only on
 * {@code field_updated} (connection/consent events carry no slot/value).
 * {@link #live()} is {@code null} when absent.
 */
public record Change(
    String id,
    String event,
    String personId,
    String shareCode,
    String slug,
    Object value,
    Boolean live,
    OffsetDateTime at,
    Map<String, Object> raw
) {
    static Change fromApi(Map<String, Object> obj, ModelDeps deps) {
        String slug = Parse.str(obj.get("slug"));
        String event = Parse.str(obj.get("event"));
        Boolean live = obj.containsKey("live") ? Parse.boolOrNull(obj.get("live")) : null;

        Object value = null;
        if ("field_updated".equals(event) && slug != null
            && (obj.containsKey("value") || obj.containsKey("value_url"))) {
            String fieldType = deps.typeForSlug().apply(slug);
            value = deps.typedValue(obj, fieldType);
        }

        String personId = firstNonNull(
            Parse.str(obj.get("person_user_id")), Parse.str(obj.get("person_id")));

        return new Change(
            Parse.str(obj.get("id")), event, personId,
            Parse.str(obj.get("share_code")), slug, value, live,
            Parse.isoDateTime(obj.get("at")), obj);
    }

    /** Parse the {@code /changes} response → a list of typed Change events. */
    @SuppressWarnings("unchecked")
    static List<Change> listFromApi(Object body, ModelDeps deps) {
        Object itemsObj;
        if (body instanceof Map<?, ?> m) {
            itemsObj = m.get("changes");
        } else {
            itemsObj = body;
        }
        List<Change> out = new ArrayList<>();
        if (itemsObj instanceof List<?> items) {
            for (Object o : items) {
                if (o instanceof Map<?, ?> map) {
                    out.add(fromApi((Map<String, Object>) map, deps));
                }
            }
        }
        return out;
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
