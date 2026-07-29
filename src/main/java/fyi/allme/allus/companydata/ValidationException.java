package fyi.allme.allus.companydata;

/**
 * A freshly-typed value failed its field type's shape/format check before encryption.
 * Names the offending {@link #getSlug()} (a flow slug or a request_field_id) and the resolved
 * {@link #getFieldType()}. Client validation is UX, never a security boundary.
 *
 * <p>Named {@code ValidationException} (not {@code ValidationError}) to follow Java's
 * exception-naming convention.
 */
public class ValidationException extends RuntimeException {
    private final String slug;
    private final String fieldType;

    public ValidationException(String slug, String fieldType) {
        super("validation error: value for \"" + slug + "\" is not a valid " + fieldType);
        this.slug = slug;
        this.fieldType = fieldType;
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
