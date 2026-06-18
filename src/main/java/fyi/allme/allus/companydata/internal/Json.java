package fyi.allme.allus.companydata.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

/**
 * Tiny shared Jackson facade. The SDK works with plain {@code Map<String,Object>}
 * / {@code List<Object>} "API objects", so this only exposes parse-to-map /
 * parse-to-list / serialize helpers.
 */
public final class Json {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final TypeReference<List<Object>> LIST_TYPE = new TypeReference<>() {};

    private Json() {
    }

    /** Parse a JSON object string into a {@code Map<String,Object>}. */
    public static Map<String, Object> parseObject(String text) throws JsonProcessingException {
        return MAPPER.readValue(text, MAP_TYPE);
    }

    /** Parse arbitrary JSON (object/array/scalar) into Java objects. */
    public static Object parse(String text) throws JsonProcessingException {
        return MAPPER.readValue(text, Object.class);
    }

    /** Parse a JSON array string into a {@code List<Object>}. */
    public static List<Object> parseArray(String text) throws JsonProcessingException {
        return MAPPER.readValue(text, LIST_TYPE);
    }

    /** Serialize a value to a compact JSON string. */
    public static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException exc) {
            throw new IllegalStateException("could not serialize to JSON", exc);
        }
    }
}
