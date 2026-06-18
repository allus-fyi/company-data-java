package fyi.allme.allus.companydata.internal;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;

/** Small coercion helpers shared by the model factories (dates, bools). */
public final class Parse {
    private Parse() {
    }

    /** Parse an API ISO-8601 timestamp into an OffsetDateTime (tolerant of trailing 'Z'). */
    public static OffsetDateTime isoDateTime(Object value) {
        if (value == null) {
            return null;
        }
        String raw = String.valueOf(value).trim();
        if (raw.isEmpty()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(raw.replace("Z", "+00:00"));
        } catch (DateTimeParseException ignored) {
            // Some payloads carry a date or local datetime without an offset.
            try {
                return OffsetDateTime.parse(raw + "T00:00:00+00:00");
            } catch (DateTimeParseException ignored2) {
                return null;
            }
        }
    }

    /** Parse an ISO date (first 10 chars) → LocalDate, or null. */
    public static LocalDate isoDate(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.length() < 10) {
            return null;
        }
        try {
            return LocalDate.parse(trimmed.substring(0, 10));
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    /** Coerce a JSON bool or an XML "true"/"false" string into a boolean. */
    public static boolean bool(Object value) {
        Boolean b = boolOrNull(value);
        return b != null && b;
    }

    /** Coerce to Boolean, or null if the value is absent. */
    public static Boolean boolOrNull(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof Number n) {
            return n.intValue() != 0;
        }
        String low = String.valueOf(value).trim().toLowerCase();
        if (low.equals("true") || low.equals("1")) {
            return true;
        }
        if (low.equals("false") || low.equals("0") || low.isEmpty()) {
            return false;
        }
        return Boolean.parseBoolean(low);
    }

    /** Read a string-ish field, or null. */
    public static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
