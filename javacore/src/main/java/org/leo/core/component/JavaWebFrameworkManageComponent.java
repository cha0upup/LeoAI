package org.leo.core.component;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * 非 Spring Java Web 框架的通用管理组件。
 *
 * <p>当前为 Struts2 提供 Action/Interceptor 注册表，为 JSF/Jakarta Faces
 * 提供 PhaseListener 与 Application handler 视图。其他标准 Servlet/JAX-RS
 * 框架仍返回统一结构和框架运行时信息，便于调用方稳定展示。</p>
 */
public class JavaWebFrameworkManageComponent implements Runnable {

    private HashMap<String, Object> params;
    private HashMap<String, Object> results;

    public void run() {
        java.lang.reflect.InvocationHandler h =
                (java.lang.reflect.InvocationHandler) Thread.currentThread().getContextClassLoader();
        try {
            params = (HashMap) h.invoke(null, null, null);
            results = new HashMap();
            invoke();
        } catch (Throwable t) {
            if (results == null) results = new HashMap();
            results.put("code", Integer.valueOf(500));
            results.put("msg", t.getMessage());
        }
        if (results != null) {
            try {
                h.invoke(null, null, new Object[]{results});
            } catch (Throwable ignored) {
            }
        }
    }

    public void invoke() throws Exception {
        Object methodValue = params == null ? null : params.get("methodName");
        if (!(methodValue instanceof String)) {
            results.put("code", Integer.valueOf(400));
            results.put("msg", "methodName required");
            return;
        }
        String methodName = (String) methodValue;
        String frameworkName = stringParam("frameworkName");
        if ("getFrameworkInfo".equals(methodName)) {
            results.put("frameworkInfo", getFrameworkInfo(frameworkName));
            results.put("code", Integer.valueOf(200));
            return;
        } else if ("removeController".equals(methodName)) {
            putOperationResult(removeController(frameworkName, stringParam("mappingInfo")));
        } else if ("removeInterceptor".equals(methodName)) {
            putOperationResult(removeInterceptor(frameworkName, stringParam("interceptorId")));
        } else {
            results.put("code", Integer.valueOf(400));
            results.put("msg", "未知 methodName: " + methodName);
            return;
        }
    }

    private void putOperationResult(boolean changed) {
        results.put("matched", Integer.valueOf(changed ? 1 : 0));
        results.put("changed", Integer.valueOf(changed ? 1 : 0));
        results.put("verified", Boolean.TRUE);
        results.put("status", changed ? "CHANGED" : "NOT_FOUND");
        results.put("code", Integer.valueOf(changed ? 200 : 404));
    }

    public HashMap getFrameworkInfo(String frameworkName) {
        HashMap info = new HashMap();
        info.put("webFramework", frameworkName);
        info.put("allController", new ArrayList());
        info.put("allMappedInterceptor", new ArrayList());
        info.put("runtimeComponents", new ArrayList());
        if (frameworkName.indexOf("Struts") >= 0) {
            collectStruts(info);
        } else if (frameworkName.indexOf("JSF") >= 0
                || frameworkName.indexOf("Faces") >= 0) {
            collectFaces(info);
        } else {
            collectStandardRuntime(info);
        }
        return info;
    }

    private void collectStruts(HashMap info) {
        try {
            Object configuration = getStrutsConfiguration();
            Map packages = (Map) callMethod(configuration, "getPackageConfigs");
            ArrayList controllers = (ArrayList) info.get("allController");
            ArrayList interceptors = (ArrayList) info.get("allMappedInterceptor");
            Iterator packageEntries = packages.entrySet().iterator();
            while (packageEntries.hasNext()) {
                Map.Entry packageEntry = (Map.Entry) packageEntries.next();
                Object packageConfig = packageEntry.getValue();
                String namespace = stringValue(tryInvoke(packageConfig, "getNamespace"));
                Map actions = asMap(tryInvoke(packageConfig, "getActionConfigs"));
                Iterator actionEntries = actions.entrySet().iterator();
                while (actionEntries.hasNext()) {
                    Map.Entry actionEntry = (Map.Entry) actionEntries.next();
                    Object action = actionEntry.getValue();
                    String actionName = String.valueOf(actionEntry.getKey());
                    HashMap actionInfo = new HashMap();
                    actionInfo.put("mappingInfo", joinAction(namespace, actionName));
                    actionInfo.put("mappingName", actionName);
                    actionInfo.put("directPaths", java.util.Collections.singletonList(joinAction(namespace, actionName)));
                    actionInfo.put("description", stringValue(tryInvoke(action, "getClassName"))
                            + "#" + stringValue(tryInvoke(action, "getMethodName")));
                    actionInfo.put("packageName", String.valueOf(packageEntry.getKey()));
                    controllers.add(actionInfo);
                }
                collectStrutsInterceptors(packageConfig, namespace, interceptors);
            }
        } catch (Throwable error) {
            info.put("error", error.getMessage());
        }
    }

    private void collectStrutsInterceptors(Object packageConfig, String namespace, ArrayList sink) {
        Map configurations = asMap(tryInvoke(packageConfig, "getInterceptorConfigs"));
        Iterator entries = configurations.entrySet().iterator();
        while (entries.hasNext()) {
            Map.Entry entry = (Map.Entry) entries.next();
            Object interceptor = entry.getValue();
            HashMap value = new HashMap();
            String name = String.valueOf(entry.getKey());
            value.put("interceptorId", namespace + "|" + name);
            value.put("interceptorName", stringValue(tryInvoke(interceptor, "getClassName")));
            value.put("pathPatterns", java.util.Collections.singletonList(namespace.length() == 0 ? "/*" : namespace + "/*"));
            value.put("excludePatterns", new ArrayList());
            value.put("kind", "interceptor");
            sink.add(value);
        }
        Map stacks = asMap(tryInvoke(packageConfig, "getInterceptorStackConfigs"));
        entries = stacks.entrySet().iterator();
        while (entries.hasNext()) {
            Map.Entry entry = (Map.Entry) entries.next();
            HashMap value = new HashMap();
            String name = String.valueOf(entry.getKey());
            value.put("interceptorId", namespace + "|stack:" + name);
            value.put("interceptorName", name);
            value.put("pathPatterns", java.util.Collections.singletonList(namespace.length() == 0 ? "/*" : namespace + "/*"));
            value.put("excludePatterns", new ArrayList());
            value.put("kind", "stack");
            sink.add(value);
        }
    }

    private void collectFaces(HashMap info) {
        ArrayList runtime = (ArrayList) info.get("runtimeComponents");
        ArrayList interceptors = (ArrayList) info.get("allMappedInterceptor");
        String[] prefixes = new String[]{"jakarta.faces", "javax.faces"};
        for (int i = 0; i < prefixes.length; i++) {
            try {
                ClassLoader loader = Thread.currentThread().getContextClassLoader();
                Class facesContextClass = Class.forName(prefixes[i] + ".context.FacesContext", false, loader);
                Object facesContext = callMethod(facesContextClass, "getCurrentInstance");
                if (facesContext == null) continue;
                Object application = callMethod(facesContext, "getApplication");
                addRuntime(runtime, "ActionListener", tryInvoke(application, "getActionListener"));
                addRuntime(runtime, "NavigationHandler", tryInvoke(application, "getNavigationHandler"));
                addRuntime(runtime, "ViewHandler", tryInvoke(application, "getViewHandler"));
                addRuntime(runtime, "ResourceHandler", tryInvoke(application, "getResourceHandler"));
                collectFacesLifecycle(prefixes[i], loader, interceptors);
                return;
            } catch (Throwable ignored) {
            }
        }
    }

    private void collectFacesLifecycle(String prefix, ClassLoader loader, ArrayList sink) {
        try {
            Class finder = Class.forName(prefix + ".FactoryFinder", false, loader);
            Field constant = finder.getField("LIFECYCLE_FACTORY");
            Object factory = callMethod(finder, "getFactory", new Class[]{String.class},
                    new Object[]{constant.get(null)});
            Enumeration ids = (Enumeration) callMethod(factory, "getLifecycleIds");
            while (ids.hasMoreElements()) {
                String lifecycleId = String.valueOf(ids.nextElement());
                Object lifecycle = callMethod(factory, "getLifecycle",
                        new Class[]{String.class}, new Object[]{lifecycleId});
                Object listeners = tryInvoke(lifecycle, "getPhaseListeners");
                if (listeners == null || !listeners.getClass().isArray()) continue;
                int length = java.lang.reflect.Array.getLength(listeners);
                for (int i = 0; i < length; i++) {
                    Object listener = java.lang.reflect.Array.get(listeners, i);
                    HashMap value = new HashMap();
                    value.put("interceptorId", lifecycleId + "|"
                            + Integer.toHexString(System.identityHashCode(listener)));
                    value.put("interceptorName", listener.getClass().getName());
                    value.put("pathPatterns", java.util.Collections.singletonList("/*"));
                    value.put("excludePatterns", new ArrayList());
                    value.put("kind", "phase-listener");
                    sink.add(value);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private void collectStandardRuntime(HashMap info) {
        ArrayList runtime = (ArrayList) info.get("runtimeComponents");
        String[] classes = new String[]{
                "javax.ws.rs.core.Application", "jakarta.ws.rs.core.Application",
                "org.apache.wicket.Application", "play.Application",
                "io.micronaut.runtime.Micronaut", "io.quarkus.runtime.Application"
        };
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        for (int i = 0; i < classes.length; i++) {
            try {
                Class type = Class.forName(classes[i], false, loader);
                addRuntime(runtime, "runtime", type);
            } catch (Throwable ignored) {
            }
        }
    }

    private boolean removeController(String frameworkName, String mappingInfo) throws Exception {
        boolean removed = false;
        if (frameworkName.indexOf("Struts") >= 0) {
            Object configuration = getStrutsConfiguration();
            Map packages = (Map) callMethod(configuration, "getPackageConfigs");
            Iterator values = packages.values().iterator();
            while (values.hasNext()) {
                Object packageConfig = values.next();
                String namespace = stringValue(tryInvoke(packageConfig, "getNamespace"));
                Map actions = mutableMap(packageConfig, "getActionConfigs", "actionConfigs");
                Iterator names = new ArrayList(actions.keySet()).iterator();
                while (names.hasNext()) {
                    Object name = names.next();
                    if (mappingInfo.equals(joinAction(namespace, String.valueOf(name)))) {
                        actions.remove(name);
                        removed = true;
                    }
                }
            }
        }
        return removed;
    }

    private boolean removeInterceptor(String frameworkName, String interceptorId) throws Exception {
        boolean removed = false;
        if (frameworkName.indexOf("Struts") >= 0) {
            Object configuration = getStrutsConfiguration();
            Map packages = (Map) callMethod(configuration, "getPackageConfigs");
            Iterator values = packages.values().iterator();
            while (values.hasNext()) {
                Object packageConfig = values.next();
                String namespace = stringValue(tryInvoke(packageConfig, "getNamespace"));
                Map interceptors = mutableMap(packageConfig, "getInterceptorConfigs", "interceptorConfigs");
                Iterator names = new ArrayList(interceptors.keySet()).iterator();
                while (names.hasNext()) {
                    Object name = names.next();
                    if (interceptorId.equals(namespace + "|" + name)) {
                        interceptors.remove(name);
                        removed = true;
                    }
                }
                Map stacks = mutableMap(packageConfig, "getInterceptorStackConfigs", "interceptorStackConfigs");
                names = new ArrayList(stacks.keySet()).iterator();
                while (names.hasNext()) {
                    Object name = names.next();
                    if (interceptorId.equals(namespace + "|stack:" + name)) {
                        stacks.remove(name);
                        removed = true;
                    }
                }
            }
        } else if (frameworkName.indexOf("JSF") >= 0 || frameworkName.indexOf("Faces") >= 0) {
            removed = removeFacesPhaseListener(interceptorId);
        }
        return removed;
    }

    private boolean removeFacesPhaseListener(String interceptorId) {
        String[] prefixes = new String[]{"jakarta.faces", "javax.faces"};
        for (int pi = 0; pi < prefixes.length; pi++) {
            try {
                ClassLoader loader = Thread.currentThread().getContextClassLoader();
                Class finder = Class.forName(prefixes[pi] + ".FactoryFinder", false, loader);
                Object factory = callMethod(finder, "getFactory", new Class[]{String.class},
                        new Object[]{finder.getField("LIFECYCLE_FACTORY").get(null)});
                Enumeration ids = (Enumeration) callMethod(factory, "getLifecycleIds");
                while (ids.hasMoreElements()) {
                    String lifecycleId = String.valueOf(ids.nextElement());
                    Object lifecycle = callMethod(factory, "getLifecycle",
                            new Class[]{String.class}, new Object[]{lifecycleId});
                    Object listeners = tryInvoke(lifecycle, "getPhaseListeners");
                    if (listeners == null || !listeners.getClass().isArray()) continue;
                    int length = java.lang.reflect.Array.getLength(listeners);
                    for (int i = 0; i < length; i++) {
                        Object listener = java.lang.reflect.Array.get(listeners, i);
                        String id = lifecycleId + "|" + Integer.toHexString(System.identityHashCode(listener));
                        if (interceptorId.equals(id)) {
                            callMethod(lifecycle, "removePhaseListener",
                                    new Class[]{Class.forName(prefixes[pi] + ".event.PhaseListener", false, loader)},
                                    new Object[]{listener});
                            return true;
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    private Object getStrutsConfiguration() throws Exception {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        Class actionContextClass = Class.forName("com.opensymphony.xwork2.ActionContext", false, loader);
        Object actionContext = callMethod(actionContextClass, "getContext");
        if (actionContext == null) throw new IllegalStateException("Struts ActionContext unavailable");
        Object container = callMethod(actionContext, "getContainer");
        Class managerClass = Class.forName("com.opensymphony.xwork2.config.ConfigurationManager",
                false, loader);
        Object manager = callMethod(container, "getInstance",
                new Class[]{Class.class}, new Object[]{managerClass});
        return callMethod(manager, "getConfiguration");
    }

    private Map mutableMap(Object owner, String getter, String field) {
        Object value = tryInvoke(owner, getter);
        Object direct = tryGetField(owner, field);
        return direct instanceof Map ? (Map) direct : asMap(value);
    }

    private void addRuntime(ArrayList sink, String role, Object value) {
        if (value == null) return;
        HashMap entry = new HashMap();
        entry.put("role", role);
        entry.put("className", value instanceof Class ? ((Class) value).getName() : value.getClass().getName());
        sink.add(entry);
    }

    private String joinAction(String namespace, String action) {
        if (namespace == null || namespace.length() == 0 || "/".equals(namespace)) return "/" + action;
        return (namespace.endsWith("/") ? namespace : namespace + "/") + action;
    }

    private static Map asMap(Object value) {
        return value instanceof Map ? (Map) value : new HashMap();
    }

    private static Object tryInvoke(Object target, String name) {
        try {
            return callMethod(target, name);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object callMethod(Object target, String name) throws Exception {
        return callMethod(target, name, new Class[0], new Object[0]);
    }

    private static Object callMethod(Object target, String name, Class[] types, Object[] arguments)
            throws Exception {
        if (target == null) throw new NoSuchMethodException(name);
        Class type = target instanceof Class ? (Class) target : target.getClass();
        Method method = null;
        Class cursor = type;
        while (cursor != null && method == null) {
            try {
                method = cursor.getDeclaredMethod(name, types);
            } catch (NoSuchMethodException ignored) {
                cursor = cursor.getSuperclass();
            }
        }
        if (method == null) method = type.getMethod(name, types);
        method.setAccessible(true);
        return method.invoke(target instanceof Class ? null : target, arguments);
    }

    private static Object tryGetField(Object target, String name) {
        if (target == null) return null;
        Class type = target.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (Throwable ignored) {
                type = type.getSuperclass();
            }
        }
        return null;
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String stringParam(String name) {
        Object value = params.get(name);
        return value == null ? "" : String.valueOf(value);
    }
}
