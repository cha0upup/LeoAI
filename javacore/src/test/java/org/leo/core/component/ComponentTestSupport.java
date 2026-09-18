package org.leo.core.component;

import org.leo.core.util.javassist.CloneWithJavassist;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

final class ComponentTestSupport {

    private ComponentTestSupport() {
    }

    static Map<String, Object> invokeComponent(Object component, HashMap<String, Object> params) throws Exception {
        HashMap<String, Object> results = new HashMap<>();
        setField(component, "params", params);
        setField(component, "results", results);
        Method invoke = component.getClass().getDeclaredMethod("invoke");
        invoke.setAccessible(true);
        invoke.invoke(component);
        return results;
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

    static void assertTransformedRunnable(String componentId) throws Exception {
        String className = "org.leo.generated." + componentId + System.nanoTime();
        byte[] bytecode = CloneWithJavassist.cloneClass(componentId, className);
        Class<?> transformed = new ClassLoader() {
            Class<?> define() {
                return defineClass(className, bytecode, 0, bytecode.length);
            }
        }.define();
        assertInstanceOf(Runnable.class, transformed.getDeclaredConstructor().newInstance());
    }
}
