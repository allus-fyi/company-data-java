package fyi.allme.allus.companydata.internal;

import fyi.allme.allus.companydata.BinaryFetchResult;
import fyi.allme.allus.companydata.BinaryHandle;
import fyi.allme.allus.companydata.DecryptException;
import fyi.allme.allus.companydata.FieldTypeRegistry;
import fyi.allme.allus.companydata.Wrapper;

import java.time.LocalDate;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The decrypt/type/binary-fetch closures the model factories need,
 * bundled so each factory takes one dependency object instead of three loose
 * callables — config-only key handling: NONE of these is a key/secret, they are
 * closures the {@code Client} builds over the once-loaded service key.
 *
 * @param decryptValue raw ciphertext wrapper (Map or JSON string or {@link Wrapper}) → plaintext string
 * @param typeForSlug  slug → the request field's type (e.g. "email", "photo"), or null
 * @param fieldTypes   the served field-type registry, which says what that type MEANS; supplied as
 *                     a {@link Supplier} because the client fetches it lazily, beside the catalog
 * @param binaryFetch  value_url → a {@link BinaryFetchResult} saying which of the route's two 200
 *                     shapes arrived (the client does the GET and classifies it); may be null
 */
public record ModelDeps(
    Function<Object, String> decryptValue,
    Function<String, String> typeForSlug,
    Supplier<FieldTypeRegistry> fieldTypes,
    Function<String, BinaryFetchResult> binaryFetch
) {
    /**
     * Decrypt + coerce one value entry to its typed Java form.
     *
     * <p>The shape comes from the type's RESOLVED definition in the served registry — its storage
     * lane and its primitive — so a type added to the registry types itself from the day it is a
     * row: a photo/document lane → a lazy {@link BinaryHandle} over the slot value_url (no eager
     * fetch/decrypt); a {@code composite} → a parsed Map; a {@code multilist} → a parsed List; a
     * {@code date} → a {@link LocalDate} (falling back to the raw string if unparseable);
     * everything else → the decrypted plaintext String.
     */
    public Object typedValue(Map<String, Object> entry, String fieldType) {
        String ftype = fieldType == null ? "" : fieldType.toLowerCase();
        FieldTypeRegistry registry = fieldTypes.get();
        FieldTypeRegistry.Resolved definition = registry.resolve(ftype);

        // Binary → a lazy handle over the slot value_url.
        if (registry.isBinary(ftype) || entry.containsKey("value_url")) {
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

        if ("composite".equals(definition.input())) {
            try {
                return Json.parseObject(plaintext);
            } catch (com.fasterxml.jackson.core.JsonProcessingException exc) {
                throw new DecryptException(
                    "structured value for type '" + ftype + "' is not valid JSON", exc);
            }
        }
        if ("multilist".equals(definition.input())) {
            try {
                return Json.parseArray(plaintext);
            } catch (com.fasterxml.jackson.core.JsonProcessingException exc) {
                throw new DecryptException(
                    "structured value for type '" + ftype + "' is not valid JSON", exc);
            }
        }
        if ("date".equals(definition.input())) {
            LocalDate d = Parse.isoDate(plaintext);
            return d != null ? d : plaintext;
        }
        // Every other primitive, and a type the registry does not carry, is the plaintext string.
        return plaintext;
    }
}
