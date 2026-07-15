package fyi.allme.allus.companydata;

import fyi.allme.allus.companydata.internal.Json;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Field-type value validation — issue #302. Pure + i18n-free. Data-driven: each type maps to a
 * "kind"; structured types map each sub-field to its own sub-rule (§2b), reusing the same kinds.
 * Validate the PLAINTEXT before encryption, at input surfaces only (never on share/propagate).
 * Kept byte-aligned across web / allus / iOS / Android / the 6 SDKs by
 * {@code docs/contract-field-validation-vector.json}. Reference: {@code frontend/src/fieldValidation.js}.
 *
 * <p>Contract: {@link #isValid(String, String)} — an empty value is valid (required is the caller's
 * job); only present, non-empty sub-fields of a structured type are checked.
 */
public final class FieldValidation {

    private FieldValidation() {
    }

    private static final Pattern EMAIL_RE = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    private static final Pattern URL_RE =
        Pattern.compile("^https?://[^\\s/$.?#][^\\s]*\\.[^\\s]{2,}$", Pattern.CASE_INSENSITIVE);
    private static final Pattern SCHEME_RE = Pattern.compile("^https?://", Pattern.CASE_INSENSITIVE);
    private static final Pattern MIME_RE = Pattern.compile("^[\\w.+-]+/[\\w.+-]+$");
    private static final Pattern PHONE_RE = Pattern.compile("^\\+?\\d{4,15}$");
    private static final Pattern CARD_RE = Pattern.compile("^\\d{12,19}$");
    private static final Pattern DATE_RE = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");

    private static final Pattern POSTAL_RE = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9 -]{1,9}$");
    private static final Pattern EXPIRY_RE = Pattern.compile("^(0[1-9]|1[0-2])/\\d{2}(\\d{2})?$");
    private static final Pattern CVC_RE = Pattern.compile("^\\d{3,4}$");
    private static final Pattern SWIFT_RE = Pattern.compile("^[A-Za-z]{6}[A-Za-z0-9]{2}([A-Za-z0-9]{3})?$");
    private static final Pattern ROUTING_RE = Pattern.compile("^\\d{9}$");
    private static final Pattern ACCOUNT_RE = Pattern.compile("^[A-Za-z0-9 ]{4,34}$");

    private static final Pattern PHONE_STRIP = Pattern.compile("[ \\-().]");
    private static final Pattern CARD_STRIP = Pattern.compile("[ -]");

    private static final List<String> GENDER = List.of("Male", "Female", "Non-binary", "Prefer not to say");

    /** A structured sub-field rule. {@code any()} = any non-empty string. */
    private record Sub(boolean isInt, Pattern re, String kind) {
    }

    private static Sub any() {
        return new Sub(false, null, null);
    }

    private static Sub re(Pattern re) {
        return new Sub(false, re, null);
    }

    private static Sub kind(String kind) {
        return new Sub(false, null, kind);
    }

    private static Sub intSub() {
        return new Sub(true, null, null);
    }

    // Structured types → each allowed key → its sub-rule (§2b).
    private static final Map<String, Map<String, Sub>> OBJ = Map.of(
        "address", Map.of(
            "postal_code", re(POSTAL_RE),
            "street", any(), "building_number", any(), "affix", any(),
            "city", any(), "state", any(), "country", any()),
        "creditcard", Map.of(
            "number", kind("card"),
            "expiry", re(EXPIRY_RE),
            "cvc", re(CVC_RE),
            "name", any()),
        "bank", Map.of(
            "swift", re(SWIFT_RE),
            "routing_number", re(ROUTING_RE),
            "account_number", re(ACCOUNT_RE),
            "account_holder", any(), "bank_name", any()),
        "document", Map.of(
            "size", intSub(), "mime_type", re(MIME_RE),
            "name", any(), "file", any(), "original_name", any()),
        "legal_document", Map.of(
            "size", intSub(), "expiry_date", kind("date"), "mime_type", re(MIME_RE),
            "document_number", any(), "file", any(), "original_name", any()));

    /** A top-level type rule. */
    private record Rule(String kind, Pattern re, List<String> values) {
    }

    private static final Map<String, Rule> RULES = Map.ofEntries(
        Map.entry("email", new Rule("regex", EMAIL_RE, null)),
        Map.entry("phone", new Rule("phone", null, null)),
        Map.entry("url", new Rule("url", null, null)),
        Map.entry("date", new Rule("date", null, null)),
        Map.entry("date_of_birth", new Rule("date", null, null)),
        Map.entry("gender", new Rule("enum", null, GENDER)),
        Map.entry("address", new Rule("object", null, null)),
        Map.entry("creditcard", new Rule("object", null, null)),
        Map.entry("bank", new Rule("object", null, null)),
        Map.entry("document", new Rule("object", null, null)),
        Map.entry("legal_document", new Rule("object", null, null)),
        Map.entry("number", new Rule("number", null, null)),
        Map.entry("boolean", new Rule("boolean", null, null)));
    // text + unknown => no rule => accept anything

    private static boolean luhnOk(String digits) {
        int sum = 0;
        boolean dbl = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int d = digits.charAt(i) - '0';
            if (d < 0 || d > 9) {
                return false;
            }
            if (dbl) {
                d *= 2;
                if (d > 9) {
                    d -= 9;
                }
            }
            sum += d;
            dbl = !dbl;
        }
        return sum % 10 == 0;
    }

    private static int daysInMonth(int y, int m) {
        boolean leap = (y % 4 == 0 && y % 100 != 0) || y % 400 == 0;
        if (m == 2 && leap) {
            return 29;
        }
        return new int[] {31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31}[m - 1];
    }

    private static boolean validDate(String s) {
        if (!DATE_RE.matcher(s).matches()) {
            return false;
        }
        int y = Integer.parseInt(s.substring(0, 4));
        int m = Integer.parseInt(s.substring(5, 7));
        int d = Integer.parseInt(s.substring(8, 10));
        if (m < 1 || m > 12) {
            return false;
        }
        return d >= 1 && d <= daysInMonth(y, m);
    }

    // The "content" check shared by top-level rules AND structured sub-rules.
    private static boolean applyKind(String kind, String value) {
        switch (kind) {
            case "phone":
                return PHONE_RE.matcher(PHONE_STRIP.matcher(value).replaceAll("")).matches();
            case "url": {
                String u = SCHEME_RE.matcher(value).find() ? value : "https://" + value;
                return URL_RE.matcher(u).matches();
            }
            case "date":
                return validDate(value);
            case "card": {
                String s = CARD_STRIP.matcher(value).replaceAll("");
                return CARD_RE.matcher(s).matches() && luhnOk(s);
            }
            case "number": {
                String t = value.strip();
                if (t.isEmpty()) {
                    return false;
                }
                // Reject Java's float/double suffixes (d/D/f/F), which parseDouble accepts but
                // JS Number() / the other ports do not — keep the ports byte-identical.
                char last = t.charAt(t.length() - 1);
                if (last == 'd' || last == 'D' || last == 'f' || last == 'F') {
                    return false;
                }
                try {
                    double d = Double.parseDouble(t);
                    return !Double.isNaN(d) && !Double.isInfinite(d);
                } catch (NumberFormatException exc) {
                    return false;
                }
            }
            case "boolean":
                return "true".equals(value) || "false".equals(value);
            default:
                return true;
        }
    }

    @SuppressWarnings("unchecked")
    private static boolean validObject(String fieldType, String raw) {
        Object parsed;
        try {
            parsed = Json.parse(raw);
        } catch (Exception exc) {
            return false;
        }
        if (!(parsed instanceof Map<?, ?> obj)) {
            return false;
        }
        Map<String, Sub> spec = OBJ.get(fieldType);
        for (Map.Entry<?, ?> e : obj.entrySet()) {
            Sub sub = spec.get(String.valueOf(e.getKey()));
            if (sub == null) {
                return false; // unknown key
            }
            Object val = e.getValue();
            if (sub.isInt()) {
                if (!(val instanceof Number num)) {
                    return false;
                }
                double d = num.doubleValue();
                if (Double.isNaN(d) || Double.isInfinite(d) || d != Math.floor(d)) {
                    return false;
                }
                continue;
            }
            if (!(val instanceof String s)) {
                return false;
            }
            if (s.isEmpty()) {
                continue; // empty sub-field ok (partial fill)
            }
            if (sub.re() != null && !sub.re().matcher(s).matches()) {
                return false;
            }
            if (sub.kind() != null && !applyKind(sub.kind(), s)) {
                return false;
            }
        }
        return true;
    }

    /**
     * True when {@code value} is an acceptable plaintext for {@code fieldType}. An empty value is
     * valid (emptiness/required is the caller's concern).
     */
    public static boolean isValid(String fieldType, String value) {
        String s = value == null ? "" : value;
        if (s.isEmpty()) {
            return true;
        }
        Rule rule = RULES.get(fieldType);
        if (rule == null) {
            return true;
        }
        return switch (rule.kind()) {
            case "regex" -> rule.re().matcher(s).matches();
            case "enum" -> rule.values().contains(s);
            case "object" -> validObject(fieldType, s);
            default -> applyKind(rule.kind(), s);
        };
    }

    /** Returns null when valid, else the {@code fieldType} tag (for i18n mapping). */
    public static String error(String fieldType, String value) {
        return isValid(fieldType, value) ? null : fieldType;
    }
}
