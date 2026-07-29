package fyi.allme.allus.companydata;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
            // Substring ops (text): contains needs an answer (like in); not_contains is
            // true when unanswered (like nin). Case-sensitive; empty needle counts as contained.
            case "contains":
                return answered(val) && str(val).contains(str(target));
            case "not_contains":
                return !(answered(val) && str(val).contains(str(target)));
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

    // ── Flow constants (computed variables). Pure; extends the evaluator above. ──
    // Reuses the class-local helpers toNum / str (=stringOf) / answered (=isAnswered) / evaluate
    // (=evaluateCondition) WITHOUT modifying them, so the 27-case condition vector stays byte-identical.
    // A "constant" is a parsed {key, label, result_type, expr} map. computeConstants materialises each
    // constant's value into a NEW slug->value map (answers + {key:value}) in dependency order, so the
    // evaluator's leaf path {field:<key>} references a constant with zero change. null propagates:
    // an unresolved operand yields null; a null constant behaves like an unanswered field in conditions.
    // Pinned by testdata/contract-flow-constants-vector.json (51 cases).

    /**
     * computeConstants(constants, answers, referenceDate) -> a NEW map = answers + {key:value} for
     * every constant, evaluated in topological (dependency) order. A ref to an operand not yet in the
     * map resolves to null; null propagates. Cycles (rejected by the validator) are broken defensively
     * via 3-colour DFS -> the back-edge operand reads null. Declared array order is irrelevant.
     *
     * @param constants     the flow's top-level {@code constants} list (parsed maps), or {@code null}
     * @param answers       the decrypted {@code {slug: value}} map, or {@code null}
     * @param referenceDate the run's reference date {@code YYYY-MM-DD} ("today"), or {@code null}
     * @return answers plus one entry per constant
     */
    public static Map<String, Object> computeConstants(
            List<Object> constants, Map<String, Object> answers, String referenceDate) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (answers != null) {
            out.putAll(answers);
        }

        Map<String, Map<String, Object>> byKey = new LinkedHashMap<>();
        if (constants != null) {
            for (Object c : constants) {
                Map<String, Object> cm = asMap(c);
                if (cm != null && cm.get("key") instanceof String k) {
                    byKey.put(k, cm);
                }
            }
        }
        Set<String> constKeys = byKey.keySet();

        List<String> order = new ArrayList<>();
        Map<String, Integer> state = new HashMap<>(); // 0 = visiting (grey), 1 = done (black)
        for (String key : byKey.keySet()) {
            visitConstant(key, byKey, constKeys, state, order);
        }

        for (String key : order) {
            out.put(key, evalExpr(byKey.get(key).get("expr"), out, referenceDate));
        }
        return out;
    }

    // 3-colour DFS post-order: a GREY re-entry is a cycle back-edge and is simply skipped, so the
    // back-edge operand reads null when its constant is later evaluated. Dependencies precede
    // dependents; dependency iteration is insertion-ordered (LinkedHashSet) so every port breaks
    // the same back-edge.
    private static void visitConstant(
            String key,
            Map<String, Map<String, Object>> byKey,
            Set<String> constKeys,
            Map<String, Integer> state,
            List<String> order) {
        Integer st = state.get(key);
        if (st != null) {
            return; // done, or grey => cycle back-edge: break it
        }
        state.put(key, 0);
        Set<String> deps = new LinkedHashSet<>();
        collectExprConstRefs(byKey.get(key).get("expr"), constKeys, deps);
        for (String dep : deps) {
            if (byKey.containsKey(dep)) {
                visitConstant(dep, byKey, constKeys, state, order);
            }
        }
        state.put(key, 1);
        order.add(key); // post-order => dependencies precede dependents
    }

    /** Per-call-site wrapper: materialise constants, then evaluate the condition unchanged. */
    public static boolean evaluateFlowCondition(
            Object condition, Map<String, Object> answers, List<Object> constants, String referenceDate) {
        return evaluate(condition, computeConstants(constants, answers, referenceDate));
    }

    /**
     * Convenience: just the resolved constant values ({@code {key: value}}, one entry per constant),
     * WITHOUT the original answers folded in.
     *
     * @param constants     the flow's top-level {@code constants} list (parsed maps), or {@code null}
     * @param answers       the decrypted {@code {slug: value}} map, or {@code null}
     * @param referenceDate the run's reference date {@code YYYY-MM-DD} ("today"), or {@code null}
     * @return a constants-only map of computed values
     */
    public static Map<String, Object> resolvedConstants(
            List<Object> constants, Map<String, Object> answers, String referenceDate) {
        Map<String, Object> full = computeConstants(constants, answers, referenceDate);
        Map<String, Object> out = new LinkedHashMap<>();
        if (constants != null) {
            for (Object c : constants) {
                Map<String, Object> cm = asMap(c);
                if (cm != null && cm.get("key") instanceof String k) {
                    out.put(k, full.get(k));
                }
            }
        }
        return out;
    }

    // evalExpr(expr, map, refDate) -> value | null. Covers every AST node type.
    static Object evalExpr(Object exprObj, Map<String, Object> map, String refDate) {
        Map<String, Object> expr = asMap(exprObj);
        if (expr == null) {
            return null;
        }
        String type = expr.get("type") instanceof String s ? s : "";
        switch (type) {
            case "lit":
                return expr.get("value"); // absent -> null; keeps native type
            case "ref": {
                String key = expr.get("key") instanceof String k ? k : "";
                return map != null && map.containsKey(key) ? map.get(key) : null; // operand not in map -> null
            }
            case "today":
                return (refDate != null && !refDate.isEmpty()) ? refDate : null; // never the device clock
            case "if": {
                List<Object> cases = asList(expr.get("cases"));
                for (Object cObj : cases) {
                    Map<String, Object> cs = asMap(cObj);
                    if (cs != null && evaluate(cs.get("when"), map)) {
                        return evalExpr(cs.get("then"), map, refDate);
                    }
                }
                return evalExpr(expr.get("else"), map, refDate); // else is required (total function)
            }
            case "concat": {
                String sep = expr.get("sep") instanceof String s ? s : "";
                List<Object> parts = asList(expr.get("parts"));
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < parts.size(); i++) {
                    if (i > 0) {
                        sb.append(sep);
                    }
                    Object v = evalExpr(parts.get(i), map, refDate);
                    sb.append(v == null ? "" : str(v)); // null part -> ""
                }
                return sb.toString(); // always text
            }
            case "datediff": {
                LocalDate from = parseFlowDate(evalExpr(expr.get("from"), map, refDate));
                LocalDate to = parseFlowDate(evalExpr(expr.get("to"), map, refDate));
                if (from == null || to == null) {
                    return null; // non-date operand -> null
                }
                String unit = expr.get("unit") instanceof String s ? s : "";
                switch (unit) {
                    case "days":   return diffDays(from, to);
                    case "weeks":  return diffDays(from, to) / 7L; // long/long truncates toward zero
                    case "months": return diffMonths(from, to);
                    case "years":  return diffYears(from, to);
                    default:       return null;
                }
            }
            case "math": {
                List<Object> args = asList(expr.get("args"));
                double[] nums = new double[args.size()];
                for (int i = 0; i < args.size(); i++) {
                    Double n = toNum(evalExpr(args.get(i), map, refDate));
                    // any null / non-numeric (incl. boolean) arg -> null; a non-finite arg (a string
                    // like "1e309" coercing to Infinity) -> null. Pinned non-finite policy (C2):
                    // math never yields Infinity/NaN.
                    if (n == null || !Double.isFinite(n)) {
                        return null;
                    }
                    nums[i] = n;
                }
                String op = expr.get("op") instanceof String s ? s : "";
                Double r;
                switch (op) {
                    case "add": {
                        double a = 0.0;
                        for (double n : nums) { a += n; }
                        r = a;
                        break;
                    }
                    case "mul": {
                        double a = 1.0;
                        for (double n : nums) { a *= n; }
                        r = a;
                        break;
                    }
                    case "sub":   r = nums.length >= 2 ? nums[0] - nums[1] : null; break;
                    case "div":   r = (nums.length >= 2 && nums[1] != 0.0) ? nums[0] / nums[1] : null; break; // /0 -> null
                    case "mod":   r = (nums.length >= 2 && nums[1] != 0.0) ? nums[0] % nums[1] : null; break; // %0 -> null; Java % is truncated remainder
                    case "neg":   r = nums.length >= 1 ? -nums[0] : null; break;
                    case "abs":   r = nums.length >= 1 ? Math.abs(nums[0]) : null; break;
                    case "round": r = nums.length >= 1 ? roundHalfAway(nums[0]) : null; break; // half away from zero
                    case "floor": r = nums.length >= 1 ? Math.floor(nums[0]) : null; break;
                    case "ceil":  r = nums.length >= 1 ? Math.ceil(nums[0]) : null; break;
                    default:      return null;
                }
                // overflow (e.g. 1e308 * 1e308) -> null (math_nonfinite_is_null).
                if (r == null || !Double.isFinite(r)) {
                    return null;
                }
                return normalizeNum(r);
            }
            default:
                return null;
        }
    }

    // Collect the constant KEYS an expression directly references (topological-ordering only).
    private static void collectExprConstRefs(Object exprObj, Set<String> constKeys, Set<String> acc) {
        Map<String, Object> expr = asMap(exprObj);
        if (expr == null) {
            return;
        }
        String type = expr.get("type") instanceof String s ? s : "";
        switch (type) {
            case "ref": {
                if (expr.get("key") instanceof String k && constKeys.contains(k)) {
                    acc.add(k);
                }
                return;
            }
            case "lit":
            case "today":
                return;
            case "if":
                for (Object cObj : asList(expr.get("cases"))) {
                    Map<String, Object> cs = asMap(cObj);
                    if (cs != null) {
                        collectCondConstRefs(cs.get("when"), constKeys, acc); // a when-leaf may name a constant
                        collectExprConstRefs(cs.get("then"), constKeys, acc);
                    }
                }
                collectExprConstRefs(expr.get("else"), constKeys, acc);
                return;
            case "concat":
                for (Object p : asList(expr.get("parts"))) {
                    collectExprConstRefs(p, constKeys, acc);
                }
                return;
            case "datediff":
                collectExprConstRefs(expr.get("from"), constKeys, acc);
                collectExprConstRefs(expr.get("to"), constKeys, acc);
                return;
            case "math":
                for (Object a : asList(expr.get("args"))) {
                    collectExprConstRefs(a, constKeys, acc);
                }
                return;
            default:
                return;
        }
    }

    private static void collectCondConstRefs(Object condObj, Set<String> constKeys, Set<String> acc) {
        Map<String, Object> cond = asMap(condObj);
        if (cond == null) {
            return;
        }
        String op = cond.get("op") instanceof String s ? s : "";
        if (op.equals("and") || op.equals("or") || op.equals("not")) {
            for (Object ch : asList(cond.get("children"))) {
                collectCondConstRefs(ch, constKeys, acc);
            }
            return;
        }
        if (cond.get("field") instanceof String f && constKeys.contains(f)) {
            acc.add(f);
        }
    }

    // Parse a value as a UTC-midnight calendar date. LocalDate is timezone-free, so day counts are
    // exact; LocalDate.of rejects impossible dates (e.g. 2026-02-30) via DateTimeException. Trim
    // BEFORE the strict anchored regex, matching JS String.trim() + /^\d{4}-\d{2}-\d{2}$/.
    private static LocalDate parseFlowDate(Object v) {
        if (!(v instanceof String s)) {
            return null;
        }
        String t = s.trim();
        if (!t.matches("\\d{4}-\\d{2}-\\d{2}")) {
            return null;
        }
        try {
            return LocalDate.of(
                Integer.parseInt(t.substring(0, 4)),
                Integer.parseInt(t.substring(5, 7)),
                Integer.parseInt(t.substring(8, 10)));
        } catch (DateTimeException | NumberFormatException e) {
            return null;
        }
    }

    private static long diffDays(LocalDate from, LocalDate to) {
        return to.toEpochDay() - from.toEpochDay(); // exact whole calendar days, sign = to - from
    }

    private static long diffMonths(LocalDate from, LocalDate to) {
        long n = (long) (to.getYear() - from.getYear()) * 12L + (to.getMonthValue() - from.getMonthValue());
        if (to.getDayOfMonth() < from.getDayOfMonth()) {
            n -= 1;
        }
        return n;
    }

    private static long diffYears(LocalDate from, LocalDate to) {
        long n = to.getYear() - from.getYear();
        if (to.getMonthValue() < from.getMonthValue()
                || (to.getMonthValue() == from.getMonthValue() && to.getDayOfMonth() < from.getDayOfMonth())) {
            n -= 1; // standard age
        }
        return n;
    }

    // Round half AWAY from zero: BigDecimal HALF_UP rounds ties away from zero (2.5->3, -2.5->-3).
    // Math.round would round toward +inf (Math.round(-2.5) == -2), which is wrong here.
    private static double roundHalfAway(double n) {
        return new BigDecimal(Double.toString(n)).setScale(0, RoundingMode.HALF_UP).doubleValue();
    }

    // Normalise a numeric result: integral finite values -> Long, otherwise Double ("long vs double").
    private static Object normalizeNum(double d) {
        if (Double.isFinite(d) && d == Math.floor(d)
                && d >= (double) Long.MIN_VALUE && d <= (double) Long.MAX_VALUE) {
            return (long) d;
        }
        return d;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asList(Object o) {
        return o instanceof List<?> l ? (List<Object>) l : List.of();
    }
}
