package fyi.allme.allus.companydata;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** One service the customer is connected to, inside a {@link CustomerConnection} (b2b). */
public record CustomerServiceLink(
    String serviceLinkId,
    String serviceId,
    String serviceName,
    String serviceCode,
    List<Object> shared,
    List<Object> mappings,
    Object pendingConsent,
    Map<String, Object> raw
) {
    static CustomerServiceLink fromApi(Map<String, Object> obj) {
        return new CustomerServiceLink(
            str(obj, "service_link_id", "id"),
            str(obj, "service_id"),
            str(obj, "service_name", "name"),
            str(obj, "service_code", "share_code"),
            list(obj.get("shared")),
            list(obj.get("mappings")),
            obj.get("pending_consent"),
            obj);
    }

    private static List<Object> list(Object v) {
        List<Object> out = new ArrayList<>();
        if (v instanceof List<?> l) {
            for (Object o : l) {
                out.add(o);
            }
        }
        return out;
    }

    private static String str(Map<String, Object> obj, String... keys) {
        for (String k : keys) {
            Object v = obj.get(k);
            if (v instanceof String s && !s.isEmpty()) {
                return s;
            }
        }
        return null;
    }
}
