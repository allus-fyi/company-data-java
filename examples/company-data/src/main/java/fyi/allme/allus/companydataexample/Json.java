package fyi.allme.allus.companydataexample;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** Tiny JSON helper (Jackson) for the config files, run stash, and wire envelopes. */
final class Json {
    private static final ObjectMapper M = new ObjectMapper();
    private static final TypeReference<LinkedHashMap<String, Object>> OBJ = new TypeReference<>() { };

    private Json() {
    }

    static String write(Object value) {
        try {
            return M.writeValueAsString(value);
        } catch (Exception exc) {
            throw new RuntimeException("json encode failed: " + exc.getMessage(), exc);
        }
    }

    static byte[] writeBytes(Object value) {
        return write(value).getBytes(StandardCharsets.UTF_8);
    }

    /** Parse a JSON object; returns an empty map for empty/invalid/non-object input. */
    static Map<String, Object> parse(byte[] body) {
        if (body == null || body.length == 0) {
            return new LinkedHashMap<>();
        }
        try {
            Map<String, Object> m = M.readValue(body, OBJ);
            return m != null ? m : new LinkedHashMap<>();
        } catch (Exception exc) {
            return new LinkedHashMap<>();
        }
    }

    static Map<String, Object> parse(String body) {
        return body == null ? new LinkedHashMap<>() : parse(body.getBytes(StandardCharsets.UTF_8));
    }
}
