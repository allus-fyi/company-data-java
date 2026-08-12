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
    /** Which customer TYPE this row applies to: "person"|"company"|"both" (B2B); null on older API. */
    String audience,
    /**
     * This row DEMANDS a verified answer: only a value the person verified satisfies it, and an
     * unverified candidate is refused at the accepting act rather than downgraded.
     */
    boolean verified,
    /**
     * The oldest verification the demand accepts, in days; null = no age limit. Enforced at the
     * accepting act only — a standing live link is not re-enforced afterwards, so apply your own
     * policy from each {@link Value#verifiedAt()}.
     */
    Integer verifiedMaxAgeDays,
    Map<String, Object> raw
) {
    static RequestField fromApi(Map<String, Object> obj) {
        return new RequestField(
            Parse.str(obj.get("slug")),
            Parse.str(obj.get("label")),
            Parse.str(obj.get("type")),
            Parse.bool(obj.get("one_time")),
            Parse.bool(obj.get("mandatory_provide")) || Parse.bool(obj.get("mandatory_connected")),
            Parse.str(obj.get("audience")),
            Parse.bool(obj.get("verified")),
            Parse.intOrNull(obj.get("verified_max_age_days")),
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
