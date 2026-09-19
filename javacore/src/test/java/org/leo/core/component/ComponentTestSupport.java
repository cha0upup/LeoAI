package org.leo.core.component;

import org.leo.core.util.javassist.CloneWithJavassist;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;

final class ComponentTestSupport {

    private ComponentTestSupport() {
    }

    static Map<String, Object> invokeComponent(Object component, Map<String, Object> params) throws Exception {
        Map<String, Object> results = prepare(component, params);
        call(component, "invoke", new Class[0]);
        return results;
    }

    // 源码与最终变换产物走同一 run() 协议；变换过程已经包含 payload 的加载。
    static Map<String, Object> runComponent(String name, boolean transformed, Map<String, Object> params)
            throws Exception {
        Object instance = transformed
                ? new BytecodeLoader().load(CloneWithJavassist.cloneClass(
                        name, "org.leo.generated." + name + System.nanoTime()))
                        .getDeclaredConstructor().newInstance()
                : component(name, false);
        return runComponent((Runnable) instance, params);
    }

    static Map<String, Object> runComponent(Runnable component, Map<String, Object> params) {
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        ComponentBridge bridge = new ComponentBridge(previous, params);
        try {
            thread.setContextClassLoader(bridge);
            component.run();
            assertNotNull(bridge.result);
            return bridge.result;
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    static void assertWireValue(Object value) {
        if (value == null || value instanceof String || value instanceof Boolean
                || value instanceof Integer || value instanceof Long
                || value instanceof Double || value instanceof byte[]) return;
        if (value instanceof Map<?, ?> map) {
            for (var entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String)) {
                    throw new AssertionError("wire map key is not String: " + entry.getKey());
                }
                assertWireValue(entry.getValue());
            }
        } else if (value instanceof List<?> list) {
            list.forEach(ComponentTestSupport::assertWireValue);
        } else {
            throw new AssertionError("unsupported wire value: " + value.getClass().getName());
        }
    }

    static HashMap<String, Object> params(Object... values) {
        HashMap<String, Object> params = new HashMap<>();
        for (int index = 0; index < values.length; index += 2) {
            params.put((String) values[index], values[index + 1]);
        }
        return params;
    }

    static int code(Map<String, Object> response) {
        return ((Number) response.get("code")).intValue();
    }

    static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    static byte[] utf8(String value) { return value.getBytes(StandardCharsets.UTF_8); }

    static Object component(String name, boolean payload) throws Exception {
        if (!payload) return Class.forName("org.leo.core.component." + name).getDeclaredConstructor().newInstance();
        try (var input = ComponentTestSupport.class.getResourceAsStream("/component/" + name + ".payload")) {
            assertNotNull(input);
            return new BytecodeLoader().load(input.readAllBytes()).getDeclaredConstructor().newInstance();
        }
    }

    static Map<String, Object> prepare(Object component, Map<String, Object> params) throws Exception {
        HashMap<String, Object> result = new HashMap<>();
        setField(component, "params", new HashMap<>(params));
        setField(component, "results", result);
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

    private static final class ComponentBridge extends ClassLoader implements InvocationHandler {
        private final HashMap<String, Object> params;
        private Map<String, Object> result;

        private ComponentBridge(ClassLoader parent, Map<String, Object> params) {
            super(parent);
            this.params = new HashMap<>(params);
        }

        @Override
        @SuppressWarnings("unchecked")
        public Object invoke(Object proxy, Method method, Object[] args) {
            if (args == null) return params;
            result = (Map<String, Object>) args[0];
            return null;
        }
    }
}
