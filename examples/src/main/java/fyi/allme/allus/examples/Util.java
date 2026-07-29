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

    // ── the "what just happened" trace ──────────────────────────────────────────

    /**
     * Append a call name to a run's "what just happened" trace, preserving first-occurrence order and
     * skipping a repeat. ONE implementation for all three families (standards §1): several handlers can
     * run twice for one run — {@code /callback} carries no already-completed guard, and the flow /
     * company-data poll loops legitimately re-attempt the same call on every poll — so an unconditional
     * append writes the same line again. The trace must read as what the run DID.
     *
     * <p><strong>RECORD AT ATTEMPT TIME: call this IMMEDIATELY BEFORE the SDK call it names, never after.</strong>
     * A run that ends `failed` is still a run the panel reports, and the call the reader most needs to see is
     * the one that threw — a bad client secret, a 429, a decrypt failure. An append placed after the call is
     * skipped by the very exception the reader is trying to understand, so the panel would say only that the
     * client was constructed. Recording after the call would silently reintroduce that same under-reporting
     * one path further in; the rule is the invariant, not a per-scenario habit. A bulk call records one entry per attempt, so a
     * partial run shows exactly how far it got.
     */
    public static List<String> addCall(Object callsObj, String name) {
        List<String> calls = asStringList(callsObj);
        if (!calls.contains(name)) {
            calls.add(name);
        }
        return calls;
    }

    /**
     * {@link #addCall} against a run map in place. Returns true when the name was newly added, so the
     * caller can persist on that transition.
     */
    public static boolean recordCall(Map<String, Object> run, String name) {
        int before = asStringList(run.get("calls")).size();
        List<String> calls = addCall(run.get("calls"), name);
        run.put("calls", calls);
        return calls.size() != before;
    }
}
