package org.leo.core.component;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Verifies the response contract without adding runtime conversion code. */
class ComponentWireValueTest {

    @Test
    void componentResponsesUseWireTypes() throws Exception {
        assertDoesNotThrow(() -> assertWireValue(invoke(new BasicInfoComponent(), "disks")));
        assertDoesNotThrow(() -> assertWireValue(invoke(new FileComponent(), "profile")));

        HashMap<String, Object> database = new HashMap<>();
        database.put("driverClass", "missing.Driver");
        database.put("jdbcUrl", "jdbc:missing:test");
        database.put("sql", "SELECT 1");
        assertDoesNotThrow(() -> assertWireValue(invoke(new DatabaseComponent(), database)));
    }

    @Test
    void unsupportedRuntimeValuesAreRejected() {
        HashMap<String, Object> response = new HashMap<>();
        response.put("date", new Date());
        assertThrows(AssertionError.class, () -> assertWireValue(response));

        response.clear();
        response.put("array", new String[]{"/"});
        assertThrows(AssertionError.class, () -> assertWireValue(response));
    }

    private Map<String, Object> invoke(Object component, String action) throws Exception {
        HashMap<String, Object> params = new HashMap<>();
        params.put("action", action);
        return invoke(component, params);
    }

    private Map<String, Object> invoke(Object component, HashMap<String, Object> params) throws Exception {
        HashMap<String, Object> results = new HashMap<>();
        setField(component, "params", params);
        setField(component, "results", results);
        component.getClass().getDeclaredMethod("invoke").invoke(component);
        return results;
    }

    private void assertWireValue(Object value) {
        if (value == null || value instanceof String || value instanceof Boolean
                || value instanceof Integer || value instanceof Long
                || value instanceof Double || value instanceof byte[]) {
            return;
        }
        if (value instanceof Map) {
            for (Object entryObject : ((Map) value).entrySet()) {
                Map.Entry entry = (Map.Entry) entryObject;
                if (!(entry.getKey() instanceof String)) {
                    throw new AssertionError("wire map key is not String: " + entry.getKey());
                }
                assertWireValue(entry.getValue());
            }
            return;
        }
        if (value instanceof List) {
            for (Object item : (List) value) assertWireValue(item);
            return;
        }
        throw new AssertionError("unsupported wire value: " + value.getClass().getName());
    }

    private void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
