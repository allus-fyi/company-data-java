package fyi.allme.allus.companydata;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * The field-type registry — the whole of what a contact-field TYPE means.
 *
 * <p>A type is a ROW, not a literal: the row says what its parent is, which primitive draws it,
 * which named check verifies it, which additive regexes it must match, which sub-fields it carries
 * and on which storage lane its value lives. The rows are served by
 * {@code GET /api/contact-field-types}; this class interprets them, so adding a type that reuses
 * existing primitives and checks is a row and nothing else.
 *
 * <p>TWO FIXED VOCABULARIES, and only these two are code. {@link #INPUTS} names the editor a value
 * is drawn with and {@link #CHECKS} what is verified beyond a regex; a row may only name a member
 * of each, so a new member is code here rather than data.
 *
 * <p>INHERITANCE. A child inherits any column it leaves null from its nearest ancestor that sets it
 * — {@code input}, {@code lane}, {@code check}, {@code options}, {@code fields}.
 * {@code validation} is the exception and is ADDITIVE: a value must match the regex of every
 * ancestor that has one, root first, plus the type's own. {@link #resolve} answers the row with
 * every inherited column filled in and the validations in that order, and every consumer works on
 * that resolved definition rather than on a raw row.
 *
 * <p>Pinned case-for-case by {@code testdata/contract-field-validation-vector.json}.
 */
public final class FieldTypeRegistry {

    /** The storage lanes a value can live on. {@code inline} is the value itself; the other two are files. */
    public static final List<String> LANES = List.of("inline", "photo", "document");

    /** The drawing primitives a row may name. A new member is code here, not a row. */
    public static final List<String> INPUTS = List.of(
        "line", "date", "list", "multilist", "country", "nationality", "state", "phone",
        "composite", "file", "pages");

    /** The named checks a row may name — verification beyond a regex. A new member is code here, not a row. */
    public static final List<String> CHECKS = List.of("url", "card", "number", "integer", "decimal", "float");

    /**
     * The lanes each primitive can store on. {@code file} is the only primitive with a choice,
     * which is why a root with that input is the only row whose lane an operator picks.
     */
    public static final Map<String, List<String>> INPUT_LANES = Map.ofEntries(
        Map.entry("line", List.of("inline")),
        Map.entry("date", List.of("inline")),
        Map.entry("list", List.of("inline")),
        Map.entry("multilist", List.of("inline")),
        Map.entry("country", List.of("inline")),
        Map.entry("nationality", List.of("inline")),
        Map.entry("state", List.of("inline")),
        Map.entry("phone", List.of("inline")),
        Map.entry("composite", List.of("inline")),
        Map.entry("file", List.of("photo", "document")),
        Map.entry("pages", List.of("document")));

    /** The primitives a sub-field entry may name: no composite nesting and no binary. */
    public static final List<String> ENTRY_INPUTS =
        List.of("line", "date", "list", "country", "nationality", "state", "phone");

    /**
     * The members a {@code file}/{@code pages} envelope carries itself. They belong to the
     * primitive, so a {@code fields} entry may never claim one — the entries are the extra
     * metadata beside them.
     */
    public static final List<String> ENVELOPE_MEMBERS =
        List.of("file", "pages", "original_name", "mime_type", "size", "name", "full", "thumb");

    /** The members ONE page of a {@code pages} envelope may carry. */
    public static final List<String> PAGE_MEMBERS =
        List.of("label", "file", "original_name", "mime_type", "size");

    /** The page slots the multi-page upload draws: a front, an optional back, repeatable extras. */
    public static final List<String> PAGE_LABELS = List.of("front", "back", "additional");

    /** The longest a stored {@code validation} regex may be. */
    public static final int MAX_VALIDATION_LENGTH = 200;

    private static final Pattern URL_RE =
        Pattern.compile("^https?://[^\\s/$.?#][^\\s]*\\.[^\\s]{2,}$", Pattern.CASE_INSENSITIVE);
    private static final Pattern URL_SCHEME_RE = Pattern.compile("^https?://.*", Pattern.CASE_INSENSITIVE);
    private static final Pattern MIME_RE = Pattern.compile("^[\\w.+-]+/[\\w.+-]+$");
    private static final Pattern PHONE_RE = Pattern.compile("^\\+?\\d{4,15}$");
    private static final Pattern CARD_RE = Pattern.compile("^\\d{12,19}$");
    private static final Pattern DATE_RE = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");
    /** Numeric grammars accept ASCII digits only. */
    private static final Pattern INTEGER_RE = Pattern.compile("^-?[0-9]+$");
    /** decimal(10,2) is a FIXED shape: up to 8 integer digits + up to 2 decimal digits. */
    private static final Pattern DECIMAL_RE = Pattern.compile("^-?[0-9]{1,8}(\\.[0-9]{1,2})?$");
    /** Float accepts decimal or scientific notation. */
    private static final Pattern FLOAT_RE = Pattern.compile("^-?[0-9]+(\\.[0-9]+)?([eE][+-]?[0-9]+)?$");

    private static final int[] DAYS_IN_MONTH = {31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31};

    private static final Set<String> COUNTRY_SET = Set.copyOf(CountryData.COUNTRY_CODES);
    private static final Set<String> US_STATE_SET = Set.copyOf(CountryData.US_STATE_CODES);

    // Compiled stored regexes, keyed by the raw pattern. An absent value records a pattern this
    // engine cannot compile, so it is attempted once rather than per value.
    private static final Map<String, Pattern> COMPILED = new HashMap<>();
    private static final Set<String> UNCOMPILABLE = new HashSet<>();

    /** A row with every inherited column filled in, plus the validations root-first. */
    public record Resolved(
        String type,
        String parent,
        String label,
        boolean isSystem,
        boolean known,
        String input,
        String lane,
        String check,
        List<String> options,
        List<Map<String, Object>> fields,
        List<String> validations
    ) {
    }

    private final Map<String, Map<String, Object>> byType = new LinkedHashMap<>();
    private final Map<String, Resolved> resolved = new HashMap<>();

    /**
     * Build a registry from the raw {@code GET /api/contact-field-types} array.
     *
     * <p>A registry with no rows knows no type, which is the honest answer for a client that has
     * not loaded it: every type resolves as unknown and validates as "accept anything".
     */
    @SuppressWarnings("unchecked")
    public FieldTypeRegistry(List<?> rows) {
        if (rows == null) {
            return;
        }
        for (Object row : rows) {
            if (row instanceof Map<?, ?> m) {
                Object type = m.get("type");
                if (type instanceof String s && !s.isEmpty()) {
                    byType.put(s, (Map<String, Object>) m);
                }
            }
        }
    }

    // ── the tree ─────────────────────────────────────────────────────────────

    /** The raw rows keyed by type, in served order. */
    public Map<String, Map<String, Object>> rows() {
        return Collections.unmodifiableMap(byType);
    }

    /** Every type the registry carries, in served order. */
    public List<String> types() {
        return List.copyOf(byType.keySet());
    }

    /** Whether the registry carries this type at all. */
    public boolean knows(String type) {
        return type != null && byType.containsKey(type);
    }

    /**
     * The resolved definition: every inherited column filled in, validations root-first.
     *
     * <p>A type the registry does not carry resolves to the UNKNOWN definition — every column
     * null, no validations, {@code known} false. That is a distinct answer from a known type with
     * nothing set, and callers must read it as "this platform cannot draw or store this", never as
     * a default.
     */
    public Resolved resolve(String type) {
        String key = type == null ? "" : type;
        Resolved cached = resolved.get(key);
        if (cached == null) {
            cached = resolveIn(key);
            resolved.put(key, cached);
        }
        return cached;
    }

    @SuppressWarnings("unchecked")
    private Resolved resolveIn(String type) {
        Map<String, Object> row = byType.get(type);
        if (row == null) {
            return new Resolved(type, null, type, false, false, null, null, null, null, null, List.of());
        }

        // Walk to the root collecting the chain, then fill downward: the nearest ancestor that sets
        // an inherited column wins, and the validations come out root-first.
        List<Map<String, Object>> chain = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        String cursor = type;
        while (cursor != null && byType.containsKey(cursor) && seen.add(cursor)) {
            Map<String, Object> current = byType.get(cursor);
            chain.add(current);
            cursor = asString(current.get("parent"));
        }
        Collections.reverse(chain);

        String label = asString(row.get("label"));
        String input = null;
        String lane = null;
        String check = null;
        List<String> options = null;
        List<Map<String, Object>> fields = null;
        List<String> validations = new ArrayList<>();
        for (Map<String, Object> ancestor : chain) {
            if (ancestor.get("input") != null) {
                input = asString(ancestor.get("input"));
            }
            if (ancestor.get("lane") != null) {
                lane = asString(ancestor.get("lane"));
            }
            if (ancestor.get("check") != null) {
                check = asString(ancestor.get("check"));
            }
            if (ancestor.get("options") instanceof List<?> o) {
                options = new ArrayList<>();
                for (Object e : o) {
                    options.add(String.valueOf(e));
                }
            }
            if (ancestor.get("fields") instanceof List<?> f) {
                fields = (List<Map<String, Object>>) f;
            }
            String validation = asString(ancestor.get("validation"));
            if (validation != null && !validation.isEmpty()) {
                validations.add(validation);
            }
        }

        return new Resolved(
            type,
            asString(row.get("parent")),
            label == null || label.isEmpty() ? type : label,
            Boolean.TRUE.equals(row.get("is_system")),
            true,
            input,
            lane,
            check,
            options,
            fields,
            List.copyOf(validations));
    }

    /**
     * The type and every descendant of it. An unknown type answers itself alone, so a lookup keyed
     * on a type the registry does not carry still addresses that type rather than nothing.
     */
    public List<String> descendants(String type) {
        List<String> out = new ArrayList<>();
        out.add(type);
        Set<String> frontier = new LinkedHashSet<>();
        frontier.add(type);
        // Bounded by the number of rows: each pass adds only types not already collected.
        for (int guard = byType.size(); guard > 0 && !frontier.isEmpty(); guard--) {
            Set<String> next = new LinkedHashSet<>();
            for (Map.Entry<String, Map<String, Object>> entry : byType.entrySet()) {
                String parent = asString(entry.getValue().get("parent"));
                if (parent != null && frontier.contains(parent) && !out.contains(entry.getKey())) {
                    out.add(entry.getKey());
                    next.add(entry.getKey());
                }
            }
            frontier = next;
        }
        return out;
    }

    /** Whether a request for {@code requested} is answered by a field of {@code actual}. */
    public boolean accepts(String requested, String actual) {
        return actual.equals(requested) || descendants(requested).contains(actual);
    }

    // ── storage lane ─────────────────────────────────────────────────────────

    /** Whether this type's value is a file rather than an inline value. */
    public boolean isBinary(String type) {
        String lane = resolve(type).lane();
        return lane != null && !lane.equals("inline");
    }

    /** Whether this type uses the document upload/storage lane. */
    public boolean isDocumentLike(String type) {
        return "document".equals(resolve(type).lane());
    }

    /** Whether this type carries the multi-page ID-document envelope. */
    public boolean isIdDocument(String type) {
        return "pages".equals(resolve(type).input());
    }

    /** Every type on a lane other than {@code inline}. */
    public List<String> binaryTypes() {
        return filterTypes(this::isBinary);
    }

    /** Every type on the {@code document} lane. */
    public List<String> documentLikeTypes() {
        return filterTypes(this::isDocumentLike);
    }

    /** Every type drawn by the multi-page upload. */
    public List<String> idDocumentTypes() {
        return filterTypes(this::isIdDocument);
    }

    private List<String> filterTypes(java.util.function.Predicate<String> keep) {
        List<String> out = new ArrayList<>();
        for (String type : byType.keySet()) {
            if (keep.test(type)) {
                out.add(type);
            }
        }
        return out;
    }

    // ── derived sets ─────────────────────────────────────────────────────────

    /**
     * A choice type whose options are supplied elsewhere. It is usable only where something else
     * carries them — a flow element — so it is offered for no contact field, no request row and no
     * claim.
     */
    public boolean isOptionLessChoice(String type) {
        Resolved definition = resolve(type);
        boolean isChoice = "list".equals(definition.input()) || "multilist".equals(definition.input());
        return isChoice && (definition.options() == null || definition.options().isEmpty());
    }

    /**
     * The option domain a choice value is held to: the ROW's own resolved options when it carries
     * any, else the ones the caller supplies, and NEVER a merge of the two — a row that states its
     * domain owns it, and a row that states none borrows the caller's whole.
     *
     * <p>null means neither source has a domain: an option-less row asked about with nothing
     * supplied. A value cannot be measured against that, so {@link #validate} refuses rather than
     * testing membership of an empty list, which would refuse every value including a legitimate
     * one.
     *
     * <p>Public so a caller can RENDER exactly the domain the validator will enforce.
     */
    public List<String> optionsFor(String type, List<String> suppliedOptions) {
        List<String> rowOptions = resolve(type).options();
        if (rowOptions != null && !rowOptions.isEmpty()) {
            return rowOptions;
        }
        if (suppliedOptions != null && !suppliedOptions.isEmpty()) {
            return suppliedOptions;
        }
        return null;
    }

    /** The types a contact field, a service request row or an admin field may declare. */
    public List<String> requestableTypes() {
        return filterTypes(t -> !isOptionLessChoice(t));
    }

    /** The requestable set plus the option-less choice types a flow element supplies options for. */
    public List<String> flowTypes() {
        List<String> out = new ArrayList<>(requestableTypes());
        out.addAll(filterTypes(this::isOptionLessChoice));
        return out;
    }

    /**
     * The types an OAuth claim may declare: the requestable set on the {@code inline} lane. A file
     * can never be sealed to a relying party's app key, so no claim can name a binary type.
     */
    public List<String> claimableTypes() {
        List<String> out = new ArrayList<>();
        for (String type : requestableTypes()) {
            if ("inline".equals(resolve(type).lane())) {
                out.add(type);
            }
        }
        return out;
    }

    // ── display ──────────────────────────────────────────────────────────────

    /**
     * The label to render. A seeded row's {@code label} is the {@code fieldtype_*} translation key
     * and a data-added row's is the literal an operator typed; {@code is_system} is the
     * discriminator, and a literal is rendered verbatim rather than looked up.
     */
    public String labelFor(String type) {
        return resolve(type).label();
    }

    /**
     * The requested types in display order: roots A→Z, each followed by its own children A→Z,
     * recursively, by the stored label. A requested type the registry does not carry sorts after
     * the tree, so a picker built from a stale set still shows every entry it was given.
     */
    public List<String> ordered(List<String> types) {
        Set<String> wanted = new HashSet<>(types);
        List<String> out = new ArrayList<>();
        walk(null, wanted, out);
        List<String> unknown = new ArrayList<>();
        for (String type : types) {
            if (!out.contains(type)) {
                unknown.add(type);
            }
        }
        unknown.sort(FieldTypeRegistry::compareLabels);
        out.addAll(unknown);
        return out;
    }

    private void walk(String parent, Set<String> wanted, List<String> out) {
        List<String> children = new ArrayList<>();
        for (Map.Entry<String, Map<String, Object>> entry : byType.entrySet()) {
            String rowParent = asString(entry.getValue().get("parent"));
            if (java.util.Objects.equals(rowParent, parent)) {
                children.add(entry.getKey());
            }
        }
        children.sort((a, b) -> compareLabels(rowLabel(a), rowLabel(b)));
        for (String child : children) {
            if (wanted.contains(child)) {
                out.add(child);
            }
            walk(child, wanted, out);
        }
    }

    private String rowLabel(String type) {
        String label = asString(byType.get(type).get("label"));
        return label == null || label.isEmpty() ? type : label;
    }

    private static int compareLabels(String a, String b) {
        int byCase = a.toLowerCase(java.util.Locale.ROOT).compareTo(b.toLowerCase(java.util.Locale.ROOT));
        return byCase != 0 ? byCase : a.compareTo(b);
    }

    /**
     * The nearest ancestor, self included, that is {@code date} or {@code number}; otherwise the
     * type itself. The flow BUILDER'S question, and only the builder's.
     */
    public String effectiveType(String type) {
        Set<String> seen = new HashSet<>();
        String cursor = type;
        while (cursor != null && byType.containsKey(cursor) && seen.add(cursor)) {
            if (cursor.equals("date") || cursor.equals("number")) {
                return cursor;
            }
            cursor = asString(byType.get(cursor).get("parent"));
        }
        return type;
    }

    // ── validation ───────────────────────────────────────────────────────────

    /**
     * Validate a plaintext value against a type, in the one fixed order: the
     * primitive's own rule, then the resolved check, then every regex root-first, then the
     * sub-field entries. The CHECK's normalised value is what those regexes see; the primitive's is
     * not.
     *
     * <p>An EMPTY value is valid — required is the caller's job — and a type the registry does not
     * carry accepts anything, which is the pinned answer for a client older than a type.
     *
     * @return null when valid, else the name of the first failing rule
     */
    public String validate(String type, String value) {
        return validate(type, value, null);
    }

    /**
     * Validate a plaintext value against a type, holding a choice value to
     * {@link #optionsFor(String, List)} over the caller's own option list — for a choice type whose
     * row states none of its own.
     *
     * @return null when valid, else the name of the first failing rule
     */
    public String validate(String type, String value, List<String> suppliedOptions) {
        String text = value == null ? "" : value;
        if (text.isEmpty()) {
            return null;
        }
        Resolved definition = resolve(type);
        if (!definition.known()) {
            return null;
        }

        String failure = applyPrimitive(definition, text, optionsFor(type, suppliedOptions));
        if (failure != null) {
            return failure;
        }

        // A CHECK'S NORMALISATION CARRIES; A PRIMITIVE'S DOES NOT, and the asymmetry is the rule
        // rather than an oversight. A check states the canonical form of the value it verifies — a
        // URL with its scheme, a card number without its separators — so a regex a child adds below
        // it describes that form and is tested against it. A primitive draws a value it does not
        // rewrite, so nothing it does reaches the regex step.
        String matched = text;
        if (definition.check() != null && !definition.check().isEmpty()) {
            failure = applyCheck(definition.check(), text);
            if (failure != null) {
                return failure;
            }
            matched = normaliseForCheck(definition.check(), text);
        }
        for (String regex : definition.validations()) {
            if (!matchesRegex(regex, matched)) {
                return "validation";
            }
        }
        return null;
    }

    /** True when {@code value} is an acceptable plaintext for {@code type}. */
    public boolean isFieldValueValid(String type, String value) {
        return validate(type, value, null) == null;
    }

    /** True when {@code value} is acceptable, holding a choice to the caller's own option list. */
    public boolean isFieldValueValid(String type, String value, List<String> suppliedOptions) {
        return validate(type, value, suppliedOptions) == null;
    }

    /** Null when valid, else the name of the first failing rule. */
    public String fieldValueError(String type, String value) {
        return validate(type, value, null);
    }

    /** Null when valid, else the name of the first failing rule, over the caller's own options. */
    public String fieldValueError(String type, String value, List<String> suppliedOptions) {
        return validate(type, value, suppliedOptions);
    }

    /**
     * The primitive's own rule, plus the sub-field entries for the three that carry them.
     *
     * <p>{@code options} is the domain a choice value is held to, already resolved by
     * {@link #optionsFor}; null is "there is no domain", which is refused rather than tested.
     */
    private String applyPrimitive(Resolved definition, String value, List<String> options) {
        String primitive = definition.input();
        if (primitive == null || primitive.equals("line")) {
            return null;
        }
        return switch (primitive) {
            case "date" -> isCalendarDate(value) ? null : "date";
            case "list" -> options == null
                ? "options_unavailable"
                : (options.contains(value) ? null : "list");
            case "multilist" -> options == null
                ? "options_unavailable"
                : (isOptionArray(value, options) ? null : "multilist");
            case "country", "nationality" -> COUNTRY_SET.contains(value) ? null : primitive;
            case "state" -> US_STATE_SET.contains(value) ? null : "state";
            case "phone" -> PHONE_RE.matcher(stripPhone(value)).matches() ? null : "phone";
            case "composite" -> validateObject(value, definition.fields(), List.of());
            case "file", "pages" -> validateObject(value, definition.fields(), ENVELOPE_MEMBERS);
            default -> null;
        };
    }

    /**
     * A JSON object value: no unknown key, every required entry present, and each non-empty entry
     * valid for its own primitive, check and regex.
     *
     * <p>{@code envelopeMembers} are the primitive's own members, accepted beside the entries and
     * validated by {@link #validateEnvelopeMember} — the one home for what each of them looks like.
     */
    private static String validateObject(
        String value, List<Map<String, Object>> fields, List<String> envelopeMembers) {
        Map<String, Object> object;
        try {
            object = fyi.allme.allus.companydata.internal.Json.parseObject(value);
        } catch (Exception exc) {
            return "object";
        }
        if (object == null) {
            return "object";
        }

        Map<String, Map<String, Object>> entries = new LinkedHashMap<>();
        if (fields != null) {
            for (Map<String, Object> entry : fields) {
                Object key = entry.get("key");
                if (key != null) {
                    entries.put(String.valueOf(key), entry);
                }
            }
        }

        for (Map.Entry<String, Object> member : object.entrySet()) {
            String key = member.getKey();
            Object raw = member.getValue();
            Map<String, Object> entry = entries.get(key);
            if (entry != null) {
                if (!(raw instanceof String s)) {
                    return key;
                }
                if (!s.isEmpty() && validateEntry(entry, s) != null) {
                    return key;
                }
                continue;
            }
            if (!envelopeMembers.contains(key)) {
                return "unknown_key";
            }
            String memberFailure = validateEnvelopeMember(key, raw);
            if (memberFailure != null) {
                return memberFailure;
            }
        }

        for (Map.Entry<String, Map<String, Object>> entry : entries.entrySet()) {
            if (!Boolean.TRUE.equals(entry.getValue().get("required"))) {
                continue;
            }
            Object got = object.get(entry.getKey());
            if (!object.containsKey(entry.getKey()) || "".equals(got)) {
                return entry.getKey();
            }
        }
        return null;
    }

    /**
     * ONE member of a {@code file}/{@code pages} envelope, by its own shape — the single home for
     * what each member looks like, so a member added to the envelope is one branch here and
     * nothing else.
     *
     * <p>{@code size} is a JSON integer, {@code pages} the multi-page list below,
     * {@code mime_type} a MIME string when it carries anything, and every other member a string.
     */
    private static String validateEnvelopeMember(String key, Object raw) {
        if (key.equals("pages")) {
            return validatePages(raw);
        }
        if (key.equals("size")) {
            return (raw instanceof Integer || raw instanceof Long) ? null : "size";
        }
        if (!(raw instanceof String s)) {
            return key;
        }
        if (key.equals("mime_type") && !s.isEmpty() && !MIME_RE.matcher(s).matches()) {
            return "mime_type";
        }
        return null;
    }

    /**
     * The {@code pages} member of an ID-document envelope: a LIST of page objects, never a scalar.
     *
     * <p>Each page names one uploaded file plus that file's own metadata. {@code file} is the
     * reference and is required; {@code label} says which slot the page fills, and the slots are
     * exactly the ones the multi-page editor draws — a front, an optional back, and repeatable
     * extras. An empty list is a document whose pages have not been uploaded yet, which is a valid
     * envelope.
     */
    private static String validatePages(Object raw) {
        if (!(raw instanceof List<?> pages)) {
            return "pages";
        }
        for (Object page : pages) {
            if (!(page instanceof Map<?, ?> members)) {
                return "pages";
            }
            for (Map.Entry<?, ?> member : members.entrySet()) {
                String key = String.valueOf(member.getKey());
                Object value = member.getValue();
                if (!PAGE_MEMBERS.contains(key)) {
                    return "pages";
                }
                if (key.equals("label")) {
                    if (!(value instanceof String label) || !PAGE_LABELS.contains(label)) {
                        return "pages";
                    }
                    continue;
                }
                if (key.equals("file")) {
                    if (!(value instanceof String file) || file.isEmpty()) {
                        return "pages";
                    }
                    continue;
                }
                if (validateEnvelopeMember(key, value) != null) {
                    return "pages";
                }
            }
            if (!members.containsKey("file")) {
                return "pages";
            }
        }
        return null;
    }

    /** One sub-field entry: its primitive rule, then its check, then its regex. */
    @SuppressWarnings("unchecked")
    private static String validateEntry(Map<String, Object> entry, String value) {
        String primitive = asString(entry.get("input"));
        if (primitive == null || primitive.isEmpty()) {
            primitive = "line";
        }
        List<String> options = entry.get("options") instanceof List<?> o
            ? (List<String>) o
            : List.of();
        String failure = switch (primitive) {
            case "date" -> isCalendarDate(value) ? null : "date";
            case "list" -> options.contains(value) ? null : "list";
            case "country", "nationality" -> COUNTRY_SET.contains(value) ? null : primitive;
            case "state" -> US_STATE_SET.contains(value) ? null : "state";
            case "phone" -> PHONE_RE.matcher(stripPhone(value)).matches() ? null : "phone";
            default -> null;
        };
        if (failure != null) {
            return failure;
        }

        // The entry runs the same primitive → check → regex order a top-level value does, and the
        // check's normalisation carries into its regex for the same reason it does there — so a
        // composite's entry can never disagree with a value of the same shape.
        String matched = value;
        String check = asString(entry.get("check"));
        if (check != null && !check.isEmpty()) {
            if (applyCheck(check, value) != null) {
                return check;
            }
            matched = normaliseForCheck(check, value);
        }
        String regex = asString(entry.get("validation"));
        if (regex != null && !regex.isEmpty() && !matchesRegex(regex, matched)) {
            return "validation";
        }
        return null;
    }

    /**
     * The CANONICAL FORM a named check verifies — and the form a regex below that check is tested
     * against, since the check is what states it.
     */
    public static String normaliseForCheck(String check, String value) {
        return switch (check) {
            case "url" -> URL_SCHEME_RE.matcher(value).matches() ? value : "https://" + value;
            case "card" -> value.replaceAll("[ -]", "");
            case "number", "integer", "decimal", "float" -> value.trim();
            default -> value;
        };
    }

    /** One named check, applied to the whole value in its canonical form; null when it passes. */
    public static String applyCheck(String check, String value) {
        String normalised = normaliseForCheck(check, value);
        boolean ok = switch (check) {
            case "url" -> URL_RE.matcher(normalised).matches();
            case "card" -> CARD_RE.matcher(normalised).matches() && luhnOk(normalised);
            case "number" -> finiteNumber(normalised);
            case "integer" -> INTEGER_RE.matcher(normalised).matches();
            case "decimal" -> DECIMAL_RE.matcher(normalised).matches();
            case "float" -> FLOAT_RE.matcher(normalised).matches();
            default -> true;
        };
        return ok ? null : check;
    }

    /** A stored regex anchored to the WHOLE value, or null when this engine cannot compile it. */
    public static synchronized Pattern compileRegex(String regex) {
        if (UNCOMPILABLE.contains(regex)) {
            return null;
        }
        Pattern cached = COMPILED.get(regex);
        if (cached != null) {
            return cached;
        }
        try {
            Pattern compiled = Pattern.compile("^(?:" + regex + ")$");
            COMPILED.put(regex, compiled);
            return compiled;
        } catch (PatternSyntaxException exc) {
            UNCOMPILABLE.add(regex);
            return null;
        }
    }

    /**
     * Whether a value matches a stored regex, which is anchored to the whole value.
     *
     * <p>A pattern that cannot be compiled is refused at write, so reaching this with one means the
     * stored row predates the rule it is now held to: no verdict can be stated, and refusing the
     * value would refuse every value of that type.
     */
    public static boolean matchesRegex(String regex, String value) {
        Pattern compiled = compileRegex(regex);
        return compiled == null || compiled.matcher(value).matches();
    }

    /** A real calendar date in {@code YYYY-MM-DD}. */
    public static boolean isCalendarDate(String value) {
        if (!DATE_RE.matcher(value).matches()) {
            return false;
        }
        int year = Integer.parseInt(value.substring(0, 4));
        int month = Integer.parseInt(value.substring(5, 7));
        int day = Integer.parseInt(value.substring(8, 10));
        if (month < 1 || month > 12) {
            return false;
        }
        return day >= 1 && day <= daysInMonth(year, month);
    }

    private static int daysInMonth(int year, int month) {
        if (month == 2) {
            boolean leap = (year % 4 == 0 && year % 100 != 0) || year % 400 == 0;
            return leap ? 29 : 28;
        }
        return DAYS_IN_MONTH[month - 1];
    }

    private static boolean luhnOk(String digits) {
        int total = 0;
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
            total += d;
            dbl = !dbl;
        }
        return total % 10 == 0;
    }

    private static boolean finiteNumber(String value) {
        if (value.isEmpty()) {
            return false;
        }
        try {
            double n = Double.parseDouble(value);
            return !Double.isNaN(n) && !Double.isInfinite(n);
        } catch (NumberFormatException exc) {
            return false;
        }
    }

    /**
     * Whether a value is a JSON LIST whose every element is a declared option.
     *
     * <p>Parsed as arbitrary JSON rather than into a list, so the JSON CONTAINER KIND survives:
     * reading {@code null} as a list answers a null reference, which would otherwise be walked as
     * an empty — and so trivially valid — list.
     */
    private static boolean isOptionArray(String value, List<String> options) {
        Object parsed;
        try {
            parsed = fyi.allme.allus.companydata.internal.Json.parse(value);
        } catch (Exception exc) {
            return false;
        }
        if (!(parsed instanceof List<?> decoded)) {
            return false;
        }
        for (Object element : decoded) {
            if (!(element instanceof String s) || !options.contains(s)) {
                return false;
            }
        }
        return true;
    }

    private static String stripPhone(String value) {
        return value.replaceAll("[ \\-().]", "");
    }

    private static String asString(Object value) {
        return value instanceof String s ? s : null;
    }
}
