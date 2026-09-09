package fyi.allme.allus.companydata;

import fyi.allme.allus.companydata.internal.Json;

import java.nio.file.Files;
import java.util.List;
import java.util.Map;

/**
 * The vector's own registry, which every model test types its values against, and the same rows as
 * a served {@code GET /api/contact-field-types} body so a fake transport answers that route the way
 * a deployment does.
 */
final class TestFieldTypes {

    private static FieldTypeRegistry cached;
    private static String body;

    private TestFieldTypes() {
    }

    @SuppressWarnings("unchecked")
    static synchronized FieldTypeRegistry registry() {
        if (cached == null) {
            try {
                Map<String, Object> vector = (Map<String, Object>) Json.parse(
                    Files.readString(TestData.testdataDir().resolve("contract-field-validation-vector.json")));
                List<Object> rows = (List<Object>) vector.get("registry");
                body = Json.write(rows);
                cached = new FieldTypeRegistry(rows);
            } catch (Exception exc) {
                throw new IllegalStateException("could not load the field-type vector", exc);
            }
        }
        return cached;
    }

    static synchronized String body() {
        registry();
        return body;
    }
}
