package org.leo.core.component;

import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import static org.leo.core.component.ComponentTestSupport.setField;

class ComponentHardeningTest {

    @Test
    void httpRejectsUnsupportedMethod() throws Exception {
        HttpRequestComponent component = new HttpRequestComponent();
        HashMap params = new HashMap();
        params.put("method", "CONNECT");
        params.put("url", "http://127.0.0.1/");
        HashMap results = prepare(component, params);

        component.invoke();

        assertEquals(Integer.valueOf(400), results.get("code"));
    }

    @Test
    void httpRejectsNonHttpProtocol() throws Exception {
        HttpRequestComponent component = new HttpRequestComponent();
        HashMap params = new HashMap();
        params.put("method", "GET");
        params.put("url", "file:///tmp/example");
        HashMap results = prepare(component, params);

        component.invoke();

        assertEquals(Integer.valueOf(400), results.get("code"));
    }

    @Test
    void httpRejectsOversizedRequestBodyBeforeConnecting() throws Exception {
        HttpRequestComponent component = new HttpRequestComponent();
        HashMap params = new HashMap();
        params.put("method", "POST");
        params.put("url", "http://127.0.0.1:1/");
        params.put("body", new byte[10 * 1024 * 1024 + 1]);
        HashMap results = prepare(component, params);

        component.invoke();

        assertEquals(Integer.valueOf(413), results.get("code"));
    }

    @Test
    void decompressRejectsOversizedEntry() throws Exception {
        DecompressComponent component = new DecompressComponent();
        Method method = DecompressComponent.class.getDeclaredMethod(
                "ensureExtractionLimit", long.class, long.class, String.class);
        method.setAccessible(true);

        InvocationTargetException expected = assertThrows(InvocationTargetException.class,
                () -> method.invoke(component, Long.valueOf(268435457L),
                        Long.valueOf(268435457L), "large.bin"));
        assertEquals(java.io.IOException.class, expected.getCause().getClass());
    }

    private HashMap prepare(Object component, HashMap params) throws Exception {
        HashMap results = new HashMap();
        setField(component, "params", params);
        setField(component, "results", results);
        return results;
    }
}
