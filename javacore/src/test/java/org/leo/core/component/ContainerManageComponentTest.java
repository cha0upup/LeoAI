package org.leo.core.component;

import org.junit.jupiter.api.Test;
import org.leo.core.util.javassist.CloneWithJavassist;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContainerManageComponentTest {

    @Test
    void transformedContainerPayloadsInitializeAfterMethodRandomization() throws Exception {
        assertTransformedRunnable("SpringFrameworkManageComponent");
        assertTransformedRunnable("TomcatContainerManageComponent");
        assertTransformedRunnable("WeblogicContainerManageComponent");
        assertTransformedRunnable("GenericServletContainerManageComponent");
        assertTransformedRunnable("JavaWebFrameworkManageComponent");
    }

    @Test
    void unknownOperationsReturnBadRequest() throws Exception {
        assertEquals(400, code(invoke(new SpringFrameworkManageComponent(), "unknown")));
        assertEquals(400, code(invoke(new TomcatContainerManageComponent(), "unknown")));
        assertEquals(400, code(invoke(new WeblogicContainerManageComponent(), "unknown")));
        assertEquals(400, code(invoke(new GenericServletContainerManageComponent(), "unknown")));
        assertEquals(400, code(invoke(new JavaWebFrameworkManageComponent(), "unknown")));
    }

    @Test
    void tomcatFieldWriterFindsInheritedFields() throws Exception {
        ChildHolder holder = new ChildHolder();
        TomcatContainerManageComponent.setFieldValue(holder, "value", "updated");
        assertEquals("updated", TomcatContainerManageComponent.getFV(holder, "value"));
    }

    @Test
    void weblogicFilterInfoUsesDeclaredServletName() {
        FakeFilterManager manager = new FakeFilterManager();
        manager.filters.put("sample", new FakeFilter("example.Filter"));
        manager.filterPatternList.add(new FakeFilterPattern("sample", "targetServlet", "/sample"));

        ArrayList filters = new WeblogicContainerManageComponent().getAllFilter(new FakeWeblogicContext(manager));

        assertEquals(1, filters.size());
        Map info = (Map) filters.get(0);
        assertEquals("targetServlet", info.get("servletName"));
        assertEquals("example.Filter", info.get("filterClassName"));
    }

    @Test
    void weblogicFilterRemovalHandlesMultipleMappings() throws Exception {
        FakeFilterManager manager = new FakeFilterManager();
        manager.filters.put("remove", new FakeFilter("example.Remove"));
        manager.filters.put("keep", new FakeFilter("example.Keep"));
        manager.filterPatternList.add(new FakeFilterPattern("remove", "a", "/a"));
        manager.filterPatternList.add(new FakeFilterPattern("remove", "b", "/b"));
        manager.filterPatternList.add(new FakeFilterPattern("keep", "c", "/c"));

        Method remove = WeblogicContainerManageComponent.class.getDeclaredMethod(
                "removeFilter", Object.class, String.class);
        remove.setAccessible(true);
        remove.invoke(new WeblogicContainerManageComponent(), manager, "remove");

        assertFalse(manager.filters.containsKey("remove"));
        assertEquals(1, manager.filterPatternList.size());
        assertEquals("keep", manager.filterPatternList.get(0).getFilterName());
    }

    @Test
    void genericServletAdapterOnlyExposesInspection() throws Exception {
        Map<String, Object> response = invoke(new GenericServletContainerManageComponent(), "removeFilter");
        assertEquals(400, code(response));
    }

    @Test
    void genericServletAdapterUsesStandardRegistrationApi() {
        FakeServletContext context = new FakeServletContext();
        context.filters.put("auth", new FakeFilterRegistration(
                "example.AuthFilter", Arrays.asList("/api/*"), Collections.singletonList("api")));
        context.servlets.put("api", new FakeServletRegistration(
                "example.ApiServlet", Arrays.asList("/api/*", "/health")));

        GenericServletContainerManageComponent component =
                new GenericServletContainerManageComponent();
        ArrayList filters = component.getAllFilter(context);
        ArrayList servlets = component.getAllServlet(context);

        assertEquals(1, filters.size());
        assertEquals("auth", ((Map) filters.get(0)).get("filterName"));
        assertEquals("example.AuthFilter", ((Map) filters.get(0)).get("filterClassName"));
        assertEquals(2, servlets.size());
        assertTrue(servlets.stream().map(item -> ((Map) item).get("url"))
                .anyMatch("/health"::equals));
    }

    @Test
    void containerCollectionAdaptersNormalizeEnumerationValues() throws Exception {
        Enumeration values = Collections.enumeration(Arrays.asList("/a", "/b"));
        assertEquals(Arrays.asList("/a", "/b"), invokeToList(GenericServletContainerManageComponent.class, values));
        values = Collections.enumeration(Arrays.asList("/a", "/b"));
        assertEquals(Arrays.asList("/a", "/b"), invokeToList(TomcatContainerManageComponent.class, values));
        values = Collections.enumeration(Arrays.asList("/a", "/b"));
        assertEquals(Arrays.asList("/a", "/b"), invokeToList(WeblogicContainerManageComponent.class, values));

        values = Collections.enumeration(Arrays.asList("/a", "/b"));
        Method patterns = SpringFrameworkManageComponent.class.getDeclaredMethod("patternStrings", Object.class);
        patterns.setAccessible(true);
        assertEquals(Arrays.asList("/a", "/b"), patterns.invoke(new SpringFrameworkManageComponent(), values));
    }

    private ArrayList invokeToList(Class<?> type, Object value) throws Exception {
        Method method = type.getDeclaredMethod("toList", Object.class);
        method.setAccessible(true);
        return (ArrayList) method.invoke(null, value);
    }

    @Test
    void frameworkMutationUsesV2OperationShape() throws Exception {
        Map<String, Object> response = invoke(new JavaWebFrameworkManageComponent(), "removeController");
        assertEquals("NOT_FOUND", response.get("status"));
        assertEquals(0, response.get("matched"));
        assertEquals(0, response.get("changed"));
        assertEquals(Boolean.TRUE, response.get("verified"));
        assertEquals(Set.of("status", "matched", "changed", "verified", "code"), response.keySet());
    }

    private Map<String, Object> invoke(Object component, Object methodName) throws Exception {
        HashMap<String, Object> params = new HashMap<>();
        params.put("methodName", methodName);
        HashMap<String, Object> results = new HashMap<>();
        setField(component, "params", params);
        setField(component, "results", results);
        component.getClass().getDeclaredMethod("invoke").invoke(component);
        return results;
    }

    private int code(Map<String, Object> response) {
        return ((Number) response.get("code")).intValue();
    }

    private void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private void assertTransformedRunnable(String componentId) throws Exception {
        String className = "org.leo.generated." + componentId + System.nanoTime();
        byte[] bytecode = CloneWithJavassist.cloneClass(componentId, className);
        Class<?> transformed = new BytecodeLoader().define(className, bytecode);
        assertTrue(Runnable.class.isAssignableFrom(transformed));
        assertTrue(transformed.getDeclaredConstructor().newInstance() instanceof Runnable);
    }

    private static class ParentHolder {
        private String value = "original";
    }

    private static final class ChildHolder extends ParentHolder {
    }

    private static final class FakeWeblogicContext {
        private final FakeFilterManager filterManager;

        private FakeWeblogicContext(FakeFilterManager filterManager) {
            this.filterManager = filterManager;
        }

        public Object getFilterManager() {
            return filterManager;
        }
    }

    private static final class FakeFilterManager {
        private final HashMap filters = new HashMap();
        private final ArrayList<FakeFilterPattern> filterPatternList = new ArrayList<>();
    }

    private static final class FakeFilter {
        private final String filterClassName;

        private FakeFilter(String filterClassName) {
            this.filterClassName = filterClassName;
        }
    }

    private static final class FakeFilterPattern {
        private final String filterName;
        private final String servletName;
        private final FakePatternMap map;

        private FakeFilterPattern(String filterName, String servletName, String pattern) {
            this.filterName = filterName;
            this.servletName = servletName;
            this.map = new FakePatternMap(pattern);
        }

        public String getFilterName() {
            return filterName;
        }

        public String getServletName() {
            return servletName;
        }

        public Object getMap() {
            return map;
        }
    }

    private static final class FakePatternMap {
        private final String pattern;

        private FakePatternMap(String pattern) {
            this.pattern = pattern;
        }

        public Object keys() {
            return new String[]{pattern};
        }
    }

    private static final class FakeServletContext {
        private final Map<String, Object> filters = new HashMap<>();
        private final Map<String, Object> servlets = new HashMap<>();

        public Map<String, Object> getFilterRegistrations() {
            return filters;
        }

        public Map<String, Object> getServletRegistrations() {
            return servlets;
        }
    }

    private static final class FakeFilterRegistration {
        private final String className;
        private final Collection<String> urlMappings;
        private final Collection<String> servletMappings;

        private FakeFilterRegistration(String className, Collection<String> urlMappings,
                                       Collection<String> servletMappings) {
            this.className = className;
            this.urlMappings = urlMappings;
            this.servletMappings = servletMappings;
        }

        public String getClassName() {
            return className;
        }

        public Collection<String> getUrlPatternMappings() {
            return urlMappings;
        }

        public Collection<String> getServletNameMappings() {
            return servletMappings;
        }
    }

    private static final class FakeServletRegistration {
        private final String className;
        private final Collection<String> mappings;

        private FakeServletRegistration(String className, Collection<String> mappings) {
            this.className = className;
            this.mappings = mappings;
        }

        public String getClassName() {
            return className;
        }

        public Collection<String> getMappings() {
            return mappings;
        }
    }

    private static final class BytecodeLoader extends ClassLoader {
        private Class<?> define(String name, byte[] bytecode) {
            return defineClass(name, bytecode, 0, bytecode.length);
        }
    }
}
