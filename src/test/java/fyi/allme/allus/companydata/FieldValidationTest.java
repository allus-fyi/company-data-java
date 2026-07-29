package fyi.allme.allus.companydata;

import fyi.allme.allus.companydata.internal.Json;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Field-type validation parity — every case in the pinned shared vector must match.
 */
class FieldValidationTest {

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> cases() throws Exception {
        Path p = TestData.testdataDir().resolve("contract-field-validation-vector.json");
        Object root = Json.parse(Files.readString(p));
        List<Object> raw = (List<Object>) ((Map<String, Object>) root).get("cases");
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : raw) {
            out.add((Map<String, Object>) o);
        }
        return out;
    }

    @TestFactory
    Stream<DynamicTest> vectorCases() throws Exception {
        List<Map<String, Object>> cases = cases();
        assertTrue(cases.size() > 0, "expected vector cases");
        return cases.stream().map(c -> DynamicTest.dynamicTest(
            (String) c.get("name"),
            () -> {
                String type = (String) c.get("type");
                String value = (String) c.get("value");
                boolean valid = (Boolean) c.get("valid");
                assertEquals(valid, FieldValidation.isValid(type, value), (String) c.get("name"));
            }));
    }
}
