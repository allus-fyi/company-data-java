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
 * computeConstants parity — every case in the pinned shared constants vector must pass.
 */
class FlowConstantsTest {

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> cases() throws Exception {
        Path p = TestData.testdataDir().resolve("contract-flow-constants-vector.json");
        Object root = Json.parse(Files.readString(p));
        List<Object> raw = (List<Object>) ((Map<String, Object>) root).get("cases");
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : raw) {
            out.add((Map<String, Object>) o);
        }
        return out;
    }

    /** Numeric-tolerant deep compare: Integer/Long/Double compare by value; null only equals null. */
    private static boolean deepEq(Object a, Object b) {
        if (a == null || b == null) {
            return a == b;
        }
        if (a instanceof Number na && b instanceof Number nb) {
            return na.doubleValue() == nb.doubleValue();
        }
        return a.equals(b);
    }

    @TestFactory
    @SuppressWarnings("unchecked")
    Stream<DynamicTest> vectorCases() throws Exception {
        List<Map<String, Object>> cases = cases();
        assertEquals(51, cases.size(), "expected 51 vector cases");
        return cases.stream().map(c -> DynamicTest.dynamicTest(
            (String) c.get("name"),
            () -> {
                List<Object> constants = c.get("constants") instanceof List<?> l
                    ? (List<Object>) l : List.of();
                Map<String, Object> answers = c.get("answers") instanceof Map<?, ?> m
                    ? (Map<String, Object>) m : Map.of();
                String refDate = c.get("reference_date") instanceof String s ? s : null;
                Map<String, Object> expect = (Map<String, Object>) c.get("expect");

                Map<String, Object> got = FlowCondition.computeConstants(constants, answers, refDate);

                for (Map.Entry<String, Object> e : expect.entrySet()) {
                    String key = e.getKey();
                    assertTrue(got.containsKey(key), c.get("name") + ": missing constant " + key);
                    assertTrue(deepEq(e.getValue(), got.get(key)),
                        c.get("name") + ": " + key + " expected " + e.getValue()
                            + " but was " + got.get(key));
                }
                // answers must survive untouched in the output map.
                for (Map.Entry<String, Object> e : answers.entrySet()) {
                    assertTrue(deepEq(e.getValue(), got.get(e.getKey())),
                        c.get("name") + ": answer " + e.getKey() + " was mutated");
                }
            }));
    }
}
