package fyi.allme.allus.companydata;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The value tags a contract-flow TEXT element names, read by the platform's text grammar: HTML tags
 * are removed first, {@code \[} {@code \]} {@code \{} {@code \\} are escapes, and a {@code {{…}}} an
 * escape breaks is not a tag. A tag inside a link address ({@code [a href=X]}) is a tag too. A
 * starter compiles the values of the definition's non-owner PARTY tags before it starts a run
 * ({@link Client#triggerFlowRun}).
 */
public final class FlowText {
    private static final Pattern HTML_TAG = Pattern.compile("</?[a-zA-Z][^<>]*>");
    private static final Pattern TAG_AT = Pattern.compile(
        "\\{\\{\\s*([a-z][a-z0-9_]*(?:\\.[a-z][a-z0-9_]*){0,2})\\s*\\}\\}", Pattern.CASE_INSENSITIVE);
    private static final String ESCAPABLE = "[]{\\";

    private FlowText() {
    }

    /** One non-owner party tag of a definition: the tag, its party key and its field (request slug). */
    public record PartyTag(String tag, String party, String field) {
    }

    private static boolean addressCloses(String s, int from) {
        for (int i = from; i < s.length(); i++) {
            if (s.charAt(i) == '\\' && i + 1 < s.length() && ESCAPABLE.indexOf(s.charAt(i + 1)) >= 0) {
                i++;
                continue;
            }
            if (s.charAt(i) == ']') {
                return true;
            }
        }
        return false;
    }

    /** Every value-tag key a text body names — in its text and its link addresses — lower-cased, first use first. */
    public static List<String> tags(String body) {
        String s = HTML_TAG.matcher(body == null ? "" : body).replaceAll("");
        List<String> out = new ArrayList<>();
        boolean inAddress = false;
        Matcher m = TAG_AT.matcher(s);
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length() && ESCAPABLE.indexOf(s.charAt(i + 1)) >= 0) {
                i += 2;
                continue;
            }
            if (c == '{') {
                m.region(i, s.length());
                if (m.lookingAt()) {
                    String k = m.group(1).toLowerCase(Locale.ROOT);
                    if (!out.contains(k)) {
                        out.add(k);
                    }
                    i = m.end();
                    continue;
                }
            }
            if (inAddress && c == ']') {
                inAddress = false;
                i++;
                continue;
            }
            if (!inAddress && c == '[' && i + 8 <= s.length()
                && s.substring(i, i + 8).toLowerCase(Locale.ROOT).equals("[a href=") && addressCloses(s, i + 8)) {
                inAddress = true;
                i += 8;
                continue;
            }
            i++;
        }
        return out;
    }

    /** The definition's NON-OWNER party tags — the tags whose values a starter compiles and seals. */
    @SuppressWarnings("unchecked")
    public static List<PartyTag> nonOwnerPartyTags(Map<String, Object> definition) {
        Map<String, String> types = new LinkedHashMap<>();
        if (definition.get("parties") instanceof List<?> ps) {
            for (Object p : ps) {
                if (p instanceof Map<?, ?> pm && pm.get("key") instanceof String k) {
                    Object t = pm.get("type");
                    types.put(k.toLowerCase(Locale.ROOT), t instanceof String ts && !ts.isEmpty() ? ts : "");
                }
            }
        }
        List<PartyTag> out = new ArrayList<>();
        if (!(definition.get("nodes") instanceof List<?> nodes)) {
            return out;
        }
        for (Object n : nodes) {
            if (!(n instanceof Map<?, ?> nm) || !(nm.get("elements") instanceof List<?> els)) {
                continue;
            }
            for (Object e : els) {
                if (!(e instanceof Map<?, ?> el) || !"text".equals(el.get("kind"))) {
                    continue;
                }
                String body = "";
                for (String k : new String[] {"body", "text", "label"}) {
                    if (el.get(k) instanceof String v && !v.isEmpty()) {
                        body = v;
                        break;
                    }
                }
                for (String tag : tags(body)) {
                    int dot = tag.indexOf('.');
                    if (dot < 0) {
                        continue;
                    }
                    String party = tag.substring(0, dot);
                    if (!types.containsKey(party) || "owner".equals(types.get(party))
                        || out.stream().anyMatch(x -> x.tag().equals(tag))) {
                        continue;
                    }
                    out.add(new PartyTag(tag, party, tag.substring(dot + 1)));
                }
            }
        }
        return out;
    }
}
