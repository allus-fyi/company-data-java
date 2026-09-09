package fyi.allme.allus.companydata;

import fyi.allme.allus.companydata.internal.Json;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
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
 * Field-type registry parity — every case in the pinned shared vector must match; that vector is
 * the contract {@link FieldTypeRegistry} is held to. Its {@code registry} member is the row set
 * every case is resolved against.
 */
class FieldValidationTest {

    @SuppressWarnings("unchecked")
    static Map<String, Object> vector() throws Exception {
        Path p = TestData.testdataDir().resolve("contract-field-validation-vector.json");
        return (Map<String, Object>) Json.parse(Files.readString(p));
    }

    @SuppressWarnings("unchecked")
    static FieldTypeRegistry registry() throws Exception {
        return new FieldTypeRegistry((List<Object>) vector().get("registry"));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> section(String key) throws Exception {
        List<Object> raw = (List<Object>) vector().get(key);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : raw) {
            out.add((Map<String, Object>) o);
        }
        return out;
    }

    private static List<Map<String, Object>> cases() throws Exception {
        return section("cases");
    }

    @TestFactory
    Stream<DynamicTest> vectorCases() throws Exception {
        List<Map<String, Object>> cases = cases();
        assertTrue(cases.size() > 0, "expected vector cases");
        FieldTypeRegistry registry = registry();
        return cases.stream().map(c -> DynamicTest.dynamicTest(
            (String) c.get("name"),
            () -> {
                String type = (String) c.get("type");
                String value = (String) c.get("value");
                // `options` is the caller's own option list, present only on a choice case.
                @SuppressWarnings("unchecked")
                List<String> options = (List<String>) c.get("options");
                boolean valid = (Boolean) c.get("valid");
                assertEquals(
                    valid, registry.isFieldValueValid(type, value, options), (String) c.get("name"));
            }));
    }

    @SuppressWarnings("unchecked")
    @Test
    void resolveCases() throws Exception {
        FieldTypeRegistry registry = registry();
        for (Map<String, Object> c : section("resolve_cases")) {
            Map<String, Object> want = (Map<String, Object>) c.get("resolved");
            FieldTypeRegistry.Resolved got = registry.resolve((String) c.get("type"));
            String name = (String) c.get("name");
            assertEquals(want.get("type"), got.type(), name);
            assertEquals(want.get("parent"), got.parent(), name);
            assertEquals(want.get("label"), got.label(), name);
            assertEquals(want.get("is_system"), got.isSystem(), name);
            assertEquals(want.get("known"), got.known(), name);
            assertEquals(want.get("input"), got.input(), name);
            assertEquals(want.get("lane"), got.lane(), name);
            assertEquals(want.get("check"), got.check(), name);
            assertEquals(want.get("options"), got.options(), name);
            assertEquals(want.get("fields"), got.fields(), name);
            assertEquals(want.get("validations"), got.validations(), name);
        }
    }

    @Test
    void acceptsCases() throws Exception {
        FieldTypeRegistry registry = registry();
        for (Map<String, Object> c : section("accepts_cases")) {
            assertEquals(
                c.get("accepts"),
                registry.accepts((String) c.get("requested"), (String) c.get("actual")),
                (String) c.get("name"));
        }
    }

    @SuppressWarnings("unchecked")
    @Test
    void orderedCases() throws Exception {
        FieldTypeRegistry registry = registry();
        for (Map<String, Object> c : section("ordered_cases")) {
            assertEquals(
                c.get("ordered"),
                registry.ordered((List<String>) c.get("types")),
                (String) c.get("name"));
        }
    }

    @Test
    void effectiveTypeCases() throws Exception {
        FieldTypeRegistry registry = registry();
        for (Map<String, Object> c : section("effective_type_cases")) {
            assertEquals(
                c.get("effective_type"),
                registry.effectiveType((String) c.get("type")),
                (String) c.get("name"));
        }
    }

    @SuppressWarnings("unchecked")
    @Test
    void derivedSets() throws Exception {
        FieldTypeRegistry registry = registry();
        Map<String, Object> sets = (Map<String, Object>) vector().get("derived_sets");
        assertEquals(sets.get("requestable_types"), registry.requestableTypes());
        assertEquals(sets.get("flow_types"), registry.flowTypes());
        assertEquals(sets.get("claimable_types"), registry.claimableTypes());
    }
}
