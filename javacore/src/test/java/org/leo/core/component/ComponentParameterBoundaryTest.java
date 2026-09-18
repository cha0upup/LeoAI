package org.leo.core.component;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ComponentParameterBoundaryTest {
    @TempDir Path directory;

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void grepAcceptsEquivalentNumericTransportValues(boolean payload) throws Exception {
        Path file = Files.writeString(directory.resolve("sample.txt"), "match first\nmatch second\n");
        for (Object action : new Object[]{1, "1", utf8("1")}) {
            Object component = component("FileEnhanceComponent", payload);
            Map<String, Object> result = prepare(component, Map.of("action", action,
                    "path", file.toString(), "keyword", "match", "maxResults", utf8("1"),
                    "maxLineLen", utf8("5")));
            call(component, "invoke", new Class[0]);
            assertEquals(200, result.get("code"));
            assertEquals(1, result.get("matchCount"));
            assertEquals(true, result.get("truncated"));
            Map<?, ?> match = (Map<?, ?>) ((List<?>) result.get("matches")).get(0);
            List<?> hits = (List<?>) match.get("hits");
            assertEquals(1, hits.size());
            assertEquals("match...", ((Map<?, ?>) hits.get(0)).get("content"));
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void httpParsesEquivalentTimeoutsAndRedirectFlags(boolean payload) throws Exception {
        Object component = component("HttpRequestComponent", payload);
        for (Object value : new Object[]{1234, "1234", utf8("1234")}) {
            prepare(component, Map.of("timeout", value));
            assertEquals(1234, call(component, "getIntParam", new Class[]{String.class, int.class}, "timeout", 5000));
        }
        for (Object value : new Object[]{true, 1, "true", utf8("true")}) {
            prepare(component, Map.of("redirect", value));
            assertEquals(true, call(component, "getBooleanParam", new Class[]{String.class, boolean.class}, "redirect", false));
        }
        for (Object value : new Object[]{false, 0, "false", utf8("false")}) {
            prepare(component, Map.of("redirect", value));
            assertEquals(false, call(component, "getBooleanParam", new Class[]{String.class, boolean.class}, "redirect", true));
        }
        prepare(component, Map.of("timeout", utf8("invalid")));
        assertEquals(5000, call(component, "getIntParam", new Class[]{String.class, int.class}, "timeout", 5000));
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void commandTimeoutDecodingRetainsDefaultsAndUpperBound(boolean payload) throws Exception {
        Object component = component("ExecCommandSimpleComponent", payload);
        for (Object value : new Object[]{1, "1", utf8("1")}) {
            prepare(component, Map.of("timeout", value));
            assertEquals(1000L, call(component, "parseTimeoutMs", new Class[0]));
        }
        for (Object value : new Object[]{0, "bad", utf8("-1")}) {
            prepare(component, Map.of("timeout", value));
            assertEquals(30000L, call(component, "parseTimeoutMs", new Class[0]));
        }
        prepare(component, Map.of("timeout", utf8(String.valueOf(Long.MAX_VALUE))));
        Field max = component.getClass().getDeclaredField("MAX_TIMEOUT_MS");
        max.setAccessible(true);
        assertEquals(max.get(null), call(component, "parseTimeoutMs", new Class[0]));
    }

    static byte[] utf8(String value) { return value.getBytes(StandardCharsets.UTF_8); }

    static Object component(String name, boolean payload) throws Exception {
        if (!payload) return Class.forName("org.leo.core.component." + name).getDeclaredConstructor().newInstance();
        try (var input = ComponentParameterBoundaryTest.class.getResourceAsStream("/component/" + name + ".payload")) {
            assertNotNull(input);
            return new BytecodeLoader().load(input.readAllBytes()).getDeclaredConstructor().newInstance();
        }
    }

    static Map<String, Object> prepare(Object component, Map<String, Object> params) throws Exception {
        HashMap<String, Object> result = new HashMap<>();
        for (String name : List.of("params", "results")) {
            Field field = component.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(component, name.equals("params") ? new HashMap<>(params) : result);
        }
        return result;
    }

    static Object call(Object component, String name, Class<?>[] types, Object... arguments) throws Exception {
        Method method = component.getClass().getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method.invoke(component, arguments);
    }

    static class BytecodeLoader extends ClassLoader {
        Class<?> load(byte[] bytes) { return defineClass(null, bytes, 0, bytes.length); }
    }
}
