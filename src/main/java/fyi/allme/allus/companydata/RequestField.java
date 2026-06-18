package fyi.allme.allus.companydata;

import fyi.allme.allus.companydata.internal.Parse;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A request-field DEFINITION — YOUR config, never the person's.
 *
 * <p>{@code mandatory} folds the API's two flags: it is true when the field is
 * mandatory to provide OR mandatory to stay connected. {@code raw} is the
 * underlying hardened API object (it never contains a person source field).
 */
public record RequestField(
    String slug,
    String label,
    String type,
    boolean oneTime,
    boolean mandatory,
    Map<String, Object> raw
) {
    static RequestField fromApi(Map<String, Object> obj) {
        return new RequestField(
            Parse.str(obj.get("slug")),
            Parse.str(obj.get("label")),
            Parse.str(obj.get("type")),
            Parse.bool(obj.get("one_time")),
            Parse.bool(obj.get("mandatory_provide")) || Parse.bool(obj.get("mandatory_connected")),
            obj);
    }

    /** Parse the {@code /request-fields} response → a list of definitions. */
    @SuppressWarnings("unchecked")
    static List<RequestField> listFromApi(Object body) {
        Object itemsObj;
        if (body instanceof Map<?, ?> m) {
            itemsObj = m.get("request_fields");
        } else {
            itemsObj = body;
        }
        List<RequestField> out = new ArrayList<>();
        if (itemsObj instanceof List<?> items) {
            for (Object o : items) {
                if (o instanceof Map<?, ?> map) {
                    out.add(fromApi((Map<String, Object>) map));
                }
            }
        }
        return out;
    }
}
