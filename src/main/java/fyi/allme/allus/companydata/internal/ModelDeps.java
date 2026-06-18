package fyi.allme.allus.companydata.internal;

import fyi.allme.allus.companydata.BinaryHandle;
import fyi.allme.allus.companydata.DecryptException;
import fyi.allme.allus.companydata.Wrapper;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * The decrypt/type/binary-fetch closures the model factories need,
 * bundled so each factory takes one dependency object instead of three loose
 * callables — config-only key handling: NONE of these is a key/secret, they are
 * closures the {@code Client} builds over the once-loaded service key.
 *
 * @param decryptValue raw ciphertext wrapper (Map or JSON string or {@link Wrapper}) → plaintext string
 * @param typeForSlug  slug → the request field's type (e.g. "email", "photo"), or null
 * @param binaryFetch  value_url → the inner {@link Wrapper} (the client does the GET + envelope unwrap); may be null
 */
public record ModelDeps(
    Function<Object, String> decryptValue,
    Function<String, String> typeForSlug,
    Function<String, Wrapper> binaryFetch
) {
    /** Field types whose decrypted plaintext is a JSON object → a parsed Map. */
    public static final List<String> STRUCTURED_TYPES = List.of("address", "bank", "creditcard");
    /** Field types whose value is a lazy binary handle (served as a value_url). */
    public static final List<String> BINARY_TYPES = List.of("photo", "document", "legal_document");
    /** Field types whose decrypted plaintext is an ISO date. */
    public static final List<String> DATE_TYPES = List.of("date", "date_of_birth");

    /**
     * Decrypt + coerce one value entry to its typed Java form.
     *
     * <p>Binary → a lazy {@link BinaryHandle} over the slot value_url (no eager
     * fetch/decrypt). Structured → a parsed Map. Date → a {@link LocalDate}
     * (falling back to the raw string if unparseable). Everything else → the
     * decrypted plaintext String.
     */
    public Object typedValue(Map<String, Object> entry, String fieldType) {
        String ftype = fieldType == null ? "" : fieldType.toLowerCase();

        // Binary → a lazy handle over the slot value_url.
        if (BINARY_TYPES.contains(ftype) || entry.containsKey("value_url")) {
            Object valueUrl = entry.get("value_url");
            if (valueUrl == null) {
                return BinaryHandle.empty();
            }
            return BinaryHandle.lazy(
                String.valueOf(valueUrl),
                binaryFetch,
                w -> decryptValue.apply(w));
        }

        Object ciphertext = entry.get("value");
        if (ciphertext == null) {
            return null;
        }
        String plaintext = decryptValue.apply(ciphertext);

        if (STRUCTURED_TYPES.contains(ftype)) {
            try {
                return Json.parseObject(plaintext);
            } catch (com.fasterxml.jackson.core.JsonProcessingException exc) {
                throw new DecryptException(
                    "structured value for type '" + ftype + "' is not valid JSON", exc);
            }
        }
        if (DATE_TYPES.contains(ftype)) {
            LocalDate d = Parse.isoDate(plaintext);
            return d != null ? d : plaintext;
        }
        // text/email/phone/url and anything unknown → the plaintext string.
        return plaintext;
    }
}
