package fyi.allme.allus.companydata;

/**
 * A freshly-typed value failed its field type's shape/format check before encryption.
 * Names the offending {@link #getSlug()} (a flow slug or a request_field_id) and the resolved
 * {@link #getFieldType()}. Client validation is UX, never a security boundary.
 *
 * <p>A flow value outside its field's minimum or maximum is refused with the same type:
 * {@link #getBound()} is then {@code "min"} or {@code "max"} and {@link #getBoundValue()} the bound
 * it broke.
 *
 * <p>Named {@code ValidationException} (not {@code ValidationError}) to follow Java's
 * exception-naming convention.
 */
public class ValidationException extends RuntimeException {
    private final String slug;
    private final String fieldType;
    private final String bound;
    private final Object boundValue;

    public ValidationException(String slug, String fieldType) {
        super("validation error: value for \"" + slug + "\" is not a valid " + fieldType);
        this.slug = slug;
        this.fieldType = fieldType;
        this.bound = null;
        this.boundValue = null;
    }

    /** A value outside a flow field's bound: {@code bound} is {@code "min"} or {@code "max"}. */
    public ValidationException(String slug, String fieldType, String bound, Object boundValue) {
        super("validation error: value for \"" + slug + "\" is "
            + ("min".equals(bound) ? "below its minimum " : "above its maximum ")
            + FlowCondition.str(boundValue));
        this.slug = slug;
        this.fieldType = fieldType;
        this.bound = bound;
        this.boundValue = boundValue;
    }

    /** {@code "min"} or {@code "max"} for a bound refusal; {@code null} otherwise. */
    public String getBound() {
        return bound;
    }

    /** The bound the value broke; {@code null} when this is not a bound refusal. */
    public Object getBoundValue() {
        return boundValue;
    }

    /** The slug (flow) or request_field_id (typed answer) of the offending value. */
    public String getSlug() {
        return slug;
    }

    /** The resolved field type the value failed. */
    public String getFieldType() {
        return fieldType;
    }
}
