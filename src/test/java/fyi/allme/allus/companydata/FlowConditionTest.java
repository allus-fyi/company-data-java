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

/**
 * FlowConditionEvaluator parity — every case in the shared vector must pass. The same vector pins
 * the PHP reference + the python/ts/go/csharp/iOS/Android ports.
 */
class FlowConditionTest {

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> cases() throws Exception {
        Path p = TestData.testdataDir().resolve("contract-flow-condition-vector.json");
        Object root = Json.parse(Files.readString(p));
        List<Object> raw = (List<Object>) ((Map<String, Object>) root).get("cases");
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : raw) {
            out.add((Map<String, Object>) o);
        }
        return out;
    }

    @TestFactory
    @SuppressWarnings("unchecked")
    Stream<DynamicTest> vectorCases() throws Exception {
        List<Map<String, Object>> cases = cases();
        assertEquals(27, cases.size(), "expected 27 vector cases");
        return cases.stream().map(c -> DynamicTest.dynamicTest(
            (String) c.get("name"),
            () -> {
                Object condition = c.get("condition");
                Map<String, Object> answers = c.get("answers") instanceof Map<?, ?> m
                    ? (Map<String, Object>) m : Map.of();
                boolean expect = (Boolean) c.get("expect");
                assertEquals(expect, FlowCondition.evaluate(condition, answers), (String) c.get("name"));
            }));
    }
}
