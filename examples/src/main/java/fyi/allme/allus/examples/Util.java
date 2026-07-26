package fyi.allme.allus.examples;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Small shared value getters + the wire-envelope shapes every family's handlers build. Kept out of the
 * handler files so they read as pure SDK-call code, not JSON plumbing.
 */
public final class Util {
    private Util() {
    }

    // ── typed getters ──────────────────────────────────────────────────────────

    public static int asInt(Object v) {
        if (v instanceof Number n) {
            return n.intValue();
        }
        try {
            return v == null ? 0 : Integer.parseInt(String.valueOf(v));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public static String strOr(Object v, String fallback) {
        return v == null ? fallback : String.valueOf(v);
    }

    public static String strOrNull(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    public static List<String> asStringList(Object v) {
        List<String> out = new ArrayList<>();
        if (v instanceof List<?> l) {
            for (Object o : l) {
                out.add(String.valueOf(o));
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> asMapList(Object v) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (v instanceof List<?> l) {
            for (Object o : l) {
                if (o instanceof Map<?, ?> m) {
                    out.add((Map<String, Object>) m);
                }
            }
        }
        return out;
    }

    // ── wire envelope helpers (the /start reply shape) ──────────────────────────

    public static List<String> calls(String... names) {
        return new ArrayList<>(List.of(names));
    }

    public static Map<String, Object> envelope(String runId, Map<String, Object> action) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("runId", runId);
        out.put("action", action);
        return out;
    }

    public static Map<String, Object> action(String type) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("type", type);
        return a;
    }

    public static Map<String, Object> action(String type, String key, Object value) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("type", type);
        a.put(key, value);
        return a;
    }
}
