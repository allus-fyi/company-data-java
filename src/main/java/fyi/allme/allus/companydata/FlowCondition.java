package fyi.allme.allus.companydata;

import java.util.List;
import java.util.Map;

/**
 * Pure port of the platform {@code FlowConditionEvaluator} (A-spec §4) — pinned to the shared
 * {@code contract-flow-condition-vector.json}.
 *
 * <p>A condition is one of:
 * <ul>
 *   <li>{@code null} / a non-object → always {@code true} (the "no condition" short-circuit).</li>
 *   <li>a boolean node {@code {op:"and"|"or"|"not", children:[...]}} ({@code not} = one child).</li>
 *   <li>a comparison leaf {@code {field, op, value}} with op in
 *       {@code eq ne lt le gt ge in nin answered empty}.</li>
 * </ul>
 *
 * <p>{@code answers} is the decrypted {@code {slug: value}} map.
 *
 * <p>Frozen semantics (see the vector):
 * <ul>
 *   <li>A blank/missing answer is "unanswered": never matches eq/ne/an ordered comparison (→ false);
 *       {@code empty} true, {@code answered} false; {@code nin} true on missing.</li>
 *   <li>eq/ne: booleans by truth, numbers (with numeric-string coercion) by value, else strings
 *       exactly. in/nin: membership in the array value.</li>
 *   <li>Ordered (lt/le/gt/ge): BOTH numeric → numeric compare; BOTH non-numeric → string compare
 *       (so {@code YYYY-MM-DD} dates sort chronologically); MIXED → false.</li>
 *   <li>and over [] → true; or over [] → false.</li>
 * </ul>
 */
public final class FlowCondition {

    private FlowCondition() {
    }

    /**
     * Evaluate a parsed condition (Map / null) against the decrypted {@code {slug: value}} map.
     *
     * @param condition the condition (a parsed JSON object, or {@code null})
     * @param answers   the decrypted answer map (scalar values)
     * @return whether the condition holds
     */
    @SuppressWarnings("unchecked")
    public static boolean evaluate(Object condition, Map<String, Object> answers) {
        if (!(condition instanceof Map<?, ?>)) {
            return true; // null / non-object = true
        }
        Map<String, Object> cond = (Map<String, Object>) condition;
        Object opObj = cond.get("op");
        String op = opObj instanceof String s ? s : "";
        if (op.equals("and") || op.equals("or") || op.equals("not")) {
            List<Object> kids = cond.get("children") instanceof List<?> l ? (List<Object>) l : List.of();
            switch (op) {
                case "and":
                    for (Object c : kids) {
                        if (!evaluate(c, answers)) {
                            return false;
                        }
                    }
                    return true;
                case "or":
                    for (Object c : kids) {
                        if (evaluate(c, answers)) {
                            return true;
                        }
                    }
                    return false;
                default: // not
                    return !evaluate(kids.isEmpty() ? null : kids.get(0), answers);
            }
        }

        String slug = cond.get("field") instanceof String f ? f : "";
        Object target = cond.get("value");
        Object val = answers.get(slug);

        switch (op) {
            case "answered":
                return answered(val);
            case "empty":
                return !answered(val);
            case "in":
                return inList(target, val);
            case "nin":
                return !inList(target, val);
            default:
                break;
        }

        if (!answered(val)) {
            return false;
        }
        switch (op) {
            case "eq":
                return looseEq(target, val);
            case "ne":
                return !looseEq(target, val);
            case "lt":
            case "gt":
            case "le":
            case "ge": {
                Double a = toNum(val);
                Double b = toNum(target);
                if (a != null && b != null) {
                    return cmpNum(op, a, b);
                }
                // Mixed (one numeric, one not) → false; both non-numeric → string compare.
                if (a != null || b != null) {
                    return false;
                }
                return cmpStr(op, str(val), str(target));
            }
            default:
                return false;
        }
    }

    private static boolean answered(Object v) {
        if (v == null) {
            return false;
        }
        if (v instanceof String s) {
            return !s.isEmpty();
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    private static boolean inList(Object target, Object val) {
        if (!(target instanceof List<?>)) {
            return false;
        }
        for (Object x : (List<Object>) target) {
            if (looseEq(x, val)) {
                return true;
            }
        }
        return false;
    }

    private static Double toNum(Object v) {
        if (v instanceof Boolean) {
            return null;
        }
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        if (v instanceof String s) {
            String t = s.trim();
            if (t.isEmpty()) {
                return null;
            }
            try {
                return Double.parseDouble(t);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static boolean looseEq(Object a, Object b) {
        if (a instanceof Boolean || b instanceof Boolean) {
            return truthy(a) == truthy(b);
        }
        Double na = toNum(a);
        Double nb = toNum(b);
        if (na != null && nb != null) {
            return na.doubleValue() == nb.doubleValue();
        }
        return str(a).equals(str(b));
    }

    private static boolean truthy(Object v) {
        if (v instanceof Boolean b) {
            return b;
        }
        if (v == null) {
            return false;
        }
        if (v instanceof String s) {
            return !s.isEmpty();
        }
        Double n = toNum(v);
        return n != null ? n != 0.0 : true;
    }

    private static String str(Object v) {
        if (v == null) {
            return "";
        }
        if (v instanceof Boolean b) {
            return b ? "true" : "false";
        }
        if (v instanceof String s) {
            return s;
        }
        if (v instanceof Number n) {
            double d = n.doubleValue();
            if (d == Math.floor(d) && !Double.isInfinite(d)) {
                return Long.toString((long) d);
            }
            return n.toString();
        }
        return v.toString();
    }

    private static boolean cmpNum(String op, double a, double b) {
        switch (op) {
            case "lt": return a < b;
            case "gt": return a > b;
            case "le": return a <= b;
            default: return a >= b; // ge
        }
    }

    private static boolean cmpStr(String op, String a, String b) {
        int c = a.compareTo(b);
        switch (op) {
            case "lt": return c < 0;
            case "gt": return c > 0;
            case "le": return c <= 0;
            default: return c >= 0; // ge
        }
    }
}
