package fyi.allme.allus.companydata;

import fyi.allme.allus.companydata.internal.ModelDeps;
import fyi.allme.allus.companydata.internal.Parse;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * A single answer for one of YOUR request slots.
 *
 * <p>{@link #value()} is the typed plaintext: {@code String} (email/phone/url/text),
 * {@code Map<String,Object>} (address/bank/creditcard), {@link java.time.LocalDate}
 * (date), or a lazy {@link BinaryHandle} (photo/document). {@link #live()} = the
 * person chose "keep connected" (auto-updates) vs a one-time snapshot;
 * {@link #updatedAt()} = when this answer last changed. Both ride on the Value
 * (per-answer), not the definition. {@link #raw()} is the underlying hardened entry.
 */
public record Value(
    Object value,
    boolean live,
    OffsetDateTime updatedAt,
    Map<String, Object> raw
) {
    static Value fromApi(Map<String, Object> entry, String fieldType, ModelDeps deps) {
        boolean live = Parse.bool(entry.get("live"));
        Object updatedRaw = entry.containsKey("updatedAt") ? entry.get("updatedAt") : entry.get("updated_at");
        OffsetDateTime updatedAt = Parse.isoDateTime(updatedRaw);
        Object typed = deps.typedValue(entry, fieldType);
        return new Value(typed, live, updatedAt, entry);
    }

    /** The value as a String (for text/email/phone/url); throws if it isn't one. */
    public String asString() {
        if (value instanceof String s) {
            return s;
        }
        throw new IllegalStateException("value is not a String (it is "
            + (value == null ? "null" : value.getClass().getSimpleName()) + ")");
    }

    /** The value as a parsed object Map (for address/bank/creditcard). */
    @SuppressWarnings("unchecked")
    public Map<String, Object> asObject() {
        if (value instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        throw new IllegalStateException("value is not a structured object");
    }

    /** The value as a lazy {@link BinaryHandle} (for photo/document). */
    public BinaryHandle asBinary() {
        if (value instanceof BinaryHandle h) {
            return h;
        }
        throw new IllegalStateException("value is not a binary handle");
    }
}
