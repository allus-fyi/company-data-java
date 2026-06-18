package fyi.allme.allus.companydata;

import java.util.Map;

/**
 * The platform hybrid-encryption wrapper {@code {"_enc":1,"k","iv","d"}}
 * — base64 fields, decrypted by {@link Crypto#decrypt}.
 *
 * @param k  base64 RSA-OAEP-wrapped AES key
 * @param iv base64 12-byte AES-GCM IV
 * @param d  base64 AES-256-GCM ciphertext with the 16-byte tag appended
 */
public record Wrapper(String k, String iv, String d) {

    /**
     * Build a {@code Wrapper} from a parsed API object (a {@code Map}) or a JSON
     * string. Tolerates either form, the way the API serves a value
     * ({@code {"_enc":1,...}} inline) or a slot envelope unwraps to it.
     *
     * @throws DecryptException if the value is neither a map nor a JSON object
     *                          string, or is missing {@code k}/{@code iv}/{@code d}.
     */
    public static Wrapper of(Object value) {
        if (value instanceof Wrapper w) {
            return w;
        }
        Map<?, ?> map;
        if (value instanceof Map<?, ?> m) {
            map = m;
        } else if (value instanceof String s) {
            try {
                Object parsed = fyi.allme.allus.companydata.internal.Json.parse(s);
                if (!(parsed instanceof Map<?, ?> m)) {
                    throw new DecryptException("wrapper string is not a JSON object");
                }
                map = m;
            } catch (com.fasterxml.jackson.core.JsonProcessingException exc) {
                throw new DecryptException("wrapper string is not valid JSON", exc);
            }
        } else {
            throw new DecryptException("wrapper must be a Map or a JSON object string");
        }
        return new Wrapper(asString(map, "k"), asString(map, "iv"), asString(map, "d"));
    }

    private static String asString(Map<?, ?> map, String field) {
        if (!map.containsKey(field)) {
            throw new DecryptException("wrapper missing required field '" + field + "'");
        }
        Object v = map.get(field);
        if (!(v instanceof String s)) {
            throw new DecryptException("wrapper field '" + field + "' must be a base64 string");
        }
        return s;
    }
}
