package fyi.allme.allus.companydata;

import fyi.allme.allus.companydata.internal.Json;
import fyi.allme.allus.companydata.internal.Parse;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * A company document the SDK created/queried (company-data side).
 *
 * <p>value semantics mirror the connection-payload contract — keyed on
 * BROADCAST(plaintext) vs PER-PERSON(always encrypted), NOT on is_private:
 * <pre>
 *   broadcast file   -> {file, original_name, mime_type, size}   (plaintext)
 *   per-person file  -> {"_enc_file": "enc_…json"}   (ciphertext blob, ANY is_private)
 *   broadcast json   -> the JSON object   (plaintext)
 *   per-person json  -> {"_enc":1,k,iv,d}   (ciphertext wrapper, ANY is_private;
 *                                            decrypt on demand via .json())
 * </pre>
 * is_private is device-display-only (lock vs decrypt-on-load), not the value shape.
 *
 * <p>{@code raw} is the underlying hardened API object.
 */
public record Document(
    String id,
    String kind,
    String name,
    String description,
    String status,
    String payloadKind,        // 'file' | 'json'
    boolean isPrivate,
    Object value,
    Map<String, Object> metadata,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt,
    boolean requiresSignature,   // contract: the person must sign
    boolean requiresAcceptance,  // contract: the person must accept
    List<Map<String, Object>> signatures,  // contract sign/accept audit trail (company-side reads only)
    Function<Object, String> decryptValue,  // closure over the service key; never a key arg
    Map<String, Object> raw
) {
    /**
     * For a json document, return the plaintext object.
     *
     * <p>Decryption is keyed on the value shape (per-person → encrypted wrapper), NOT
     * on is_private: a per-person json doc (ANY is_private) is an {@code {"_enc":1,…}}
     * wrapper and is decrypted with the SDK's own private key; a broadcast json doc is
     * already plaintext and returned as-is.
     *
     * @throws DecryptException if this is not a json document, or no decrypt wiring
     *                          exists for an encrypted (per-person) value.
     */
    public Object json() {
        if (!"json".equals(payloadKind)) {
            throw new DecryptException("json() is only valid for payload_kind='json' documents");
        }
        if (value instanceof Map<?, ?> m && isEncryptedWrapper(m)) {
            if (decryptValue == null) {
                throw new DecryptException("no decrypt wiring for an encrypted (per-person) document");
            }
            String plaintext = decryptValue.apply(value);
            try {
                return Json.parse(plaintext);
            } catch (com.fasterxml.jackson.core.JsonProcessingException exc) {
                throw new DecryptException("decrypted document is not valid JSON", exc);
            }
        }
        return value;
    }

    /** True when the value is a {@code {"_enc":1,…}} per-person ciphertext wrapper. */
    private static boolean isEncryptedWrapper(Map<?, ?> m) {
        Object enc = m.get("_enc");
        if (enc instanceof Number n) {
            return n.intValue() == 1;
        }
        return enc != null && "1".equals(String.valueOf(enc));
    }

    @SuppressWarnings("unchecked")
    static Document fromApi(Map<String, Object> obj, Function<Object, String> decryptValue) {
        Object meta = obj.get("metadata");
        Map<String, Object> metadata = (meta instanceof Map<?, ?> m) ? (Map<String, Object>) m : null;
        List<Map<String, Object>> signatures = new java.util.ArrayList<>();
        if (obj.get("signatures") instanceof List<?> sigs) {
            for (Object s : sigs) {
                if (s instanceof Map<?, ?> sm) {
                    signatures.add((Map<String, Object>) sm);
                }
            }
        }
        return new Document(
            Parse.str(obj.get("id")),
            Parse.str(obj.get("kind")),
            Parse.str(obj.get("name")),
            Parse.str(obj.get("description")),
            Parse.str(obj.get("status")),
            Parse.str(obj.get("payload_kind")),
            Parse.bool(obj.get("is_private")),
            obj.get("value"),
            metadata,
            Parse.isoDateTime(obj.get("created_at")),
            Parse.isoDateTime(obj.get("updated_at")),
            Parse.bool(obj.get("requires_signature")),
            Parse.bool(obj.get("requires_acceptance")),
            signatures,
            decryptValue,
            obj);
    }

    /** Parse a {@code {total, items}} list response → a list of documents. */
    @SuppressWarnings("unchecked")
    static List<Document> listFromApi(Object body, Function<Object, String> decryptValue) {
        Object itemsObj;
        if (body instanceof Map<?, ?> m) {
            itemsObj = m.get("items");
        } else {
            itemsObj = body;
        }
        List<Document> out = new ArrayList<>();
        if (itemsObj instanceof List<?> items) {
            for (Object o : items) {
                if (o instanceof Map<?, ?> map) {
                    out.add(fromApi((Map<String, Object>) map, decryptValue));
                }
            }
        }
        return out;
    }
}
