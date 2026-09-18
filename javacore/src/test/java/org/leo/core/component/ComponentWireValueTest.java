package org.leo.core.component;

import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import static org.leo.core.component.ComponentTestSupport.invokeComponent;
import static org.leo.core.component.ComponentTestSupport.params;

/** Verifies the response contract without adding runtime conversion code. */
class ComponentWireValueTest {

    @Test
    void componentResponsesUseWireTypes() throws Exception {
        assertDoesNotThrow(() -> assertWireValue(invokeComponent(new BasicInfoComponent(), params("action", "disks"))));
        assertDoesNotThrow(() -> assertWireValue(invokeComponent(new FileComponent(), params("action", "profile"))));

        HashMap<String, Object> database = new HashMap<>();
        database.put("driverClass", "missing.Driver");
        database.put("jdbcUrl", "jdbc:missing:test");
        database.put("sql", "SELECT 1");
        assertDoesNotThrow(() -> assertWireValue(invokeComponent(new DatabaseComponent(), database)));
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
}
