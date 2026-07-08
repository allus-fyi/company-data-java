package fyi.allme.allus.companydata;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * One company↔company connection from the customer's side (b2b, #168).
 *
 * <p>A thin wrapper over the raw API dict; the plaintext fields (company identity, shared values,
 * company profile) are exposed directly and {@link #raw()} is always kept.
 */
public record CustomerConnection(
    String id,
    String companyUserId,
    String companyName,
    String companyCode,
    String customerType,
    List<Object> companyProfile,
    List<CustomerServiceLink> services,
    Map<String, Object> raw
) {
    @SuppressWarnings("unchecked")
    static CustomerConnection fromApi(Map<String, Object> obj) {
        Map<String, Object> company = (obj.get("company") instanceof Map<?, ?> m) ? (Map<String, Object>) m : Map.of();
        List<CustomerServiceLink> services = new ArrayList<>();
        if (obj.get("services") instanceof List<?> list) {
            for (Object s : list) {
                if (s instanceof Map<?, ?> sm) {
                    services.add(CustomerServiceLink.fromApi((Map<String, Object>) sm));
                }
            }
        }
        List<Object> profile = new ArrayList<>();
        if (obj.get("company_profile") instanceof List<?> list) {
            for (Object p : list) {
                profile.add(p);
            }
        }
        return new CustomerConnection(
            firstStr(obj.get("id"), obj.get("company_connection_id")),
            firstStr(obj.get("company_user_id"), company.get("user_id")),
            firstStr(obj.get("company_name"), company.get("display_name")),
            firstStr(obj.get("company_code"), company.get("share_code")),
            asStr(obj.get("customer_type")),
            profile,
            services,
            obj);
    }

    @SuppressWarnings("unchecked")
    static List<CustomerConnection> listFromApi(Object body) {
        Object itemsObj = body;
        if (body instanceof Map<?, ?> m) {
            itemsObj = m.get("connections") != null ? m.get("connections") : m.get("items");
        }
        List<CustomerConnection> out = new ArrayList<>();
        if (itemsObj instanceof List<?> items) {
            for (Object o : items) {
                if (o instanceof Map<?, ?> map) {
                    out.add(fromApi((Map<String, Object>) map));
                }
            }
        }
        return out;
    }

    private static String firstStr(Object... vals) {
        for (Object v : vals) {
            if (v instanceof String s && !s.isEmpty()) {
                return s;
            }
        }
        return null;
    }

    private static String asStr(Object v) {
        return (v instanceof String s && !s.isEmpty()) ? s : null;
    }
}
