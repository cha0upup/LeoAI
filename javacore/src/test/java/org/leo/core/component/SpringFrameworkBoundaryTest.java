package org.leo.core.component;

import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtField;
import javassist.CtNewMethod;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.leo.core.component.ComponentParameterBoundaryTest.*;

class SpringFrameworkBoundaryTest {
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void interceptorPatternsSupportStringsAndPatternObjects(boolean payload) throws Exception {
        for (Object[] excluded : new Object[][]{new String[]{"/private/**"},
                new Object[]{new PatternValue("/private/**")}, null}) {
            Object mapped = mappedInterceptor(excluded);
            Object component = withInterceptors(payload, mapped, new Object());
            List<?> result = (List<?>) call(component, "getAllMappedInterceptor", new Class[0]);
            assertEquals(2, result.size());
            Map<?, ?> first = (Map<?, ?>) result.get(0);
            assertEquals(List.of("/**"), first.get("pathPatterns"));
            assertEquals(excluded == null ? List.of() : List.of("/private/**"), first.get("excludePatterns"));
            assertEquals(List.of("/*"), ((Map<?, ?>) result.get(1)).get("pathPatterns"));
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void oneBrokenInterceptorDoesNotDiscardOtherEntries(boolean payload) throws Exception {
        Object broken = mappedInterceptor(new String[0]);
        broken.getClass().getField("interceptor").set(broken, null);
        Object component = withInterceptors(payload, new Object(), broken,
                mappedInterceptor(new String[]{"/skip"}));
        Map<?, ?> info = (Map<?, ?>) call(component, "getFrameworkInfo", new Class[0]);
        List<?> result = (List<?>) info.get("allMappedInterceptor");
        assertEquals(2, result.size());
        assertEquals(List.of("/skip"), ((Map<?, ?>) result.get(1)).get("excludePatterns"));
    }

    private Object withInterceptors(boolean payload, Object... interceptors) throws Exception {
        Object component = component("SpringFrameworkManageComponent", payload);
        Field context = component.getClass().getDeclaredField("context");
        context.setAccessible(true);
        context.set(component, new Context(new HandlerMapping(interceptors)));
        return component;
    }

    private Object mappedInterceptor(Object[] patterns) throws Exception {
        ClassPool pool = new ClassPool(true);
        CtClass type = pool.makeClass("org.springframework.web.servlet.handler.MappedInterceptor");
        try {
            type.addField(CtField.make("public Object[] excludePatterns;", type));
            type.addField(CtField.make("public Object interceptor = new Object();", type));
            type.addMethod(CtNewMethod.make("public String[] getPathPatterns() { return new String[] {\"/**\"}; }", type));
            type.addMethod(CtNewMethod.make("public Object getInterceptor() { return interceptor; }", type));
            Object result = new BytecodeLoader().load(type.toBytecode()).getDeclaredConstructor().newInstance();
            result.getClass().getField("excludePatterns").set(result, patterns);
            return result;
        } finally {
            type.detach();
        }
    }

    public static class PatternValue {
        private final String value;
        PatternValue(String value) { this.value = value; }
        public String getPatternString() { return value; }
    }

    public static class Context {
        private final Object mapping;
        Context(Object mapping) { this.mapping = mapping; }
        public Object getBean(String name) { return mapping; }
    }

    public static class HandlerMapping {
        private final Object[] interceptors;
        HandlerMapping(Object[] interceptors) { this.interceptors = interceptors; }
        public Object[] getAdaptedInterceptors() { return interceptors; }
    }
}
