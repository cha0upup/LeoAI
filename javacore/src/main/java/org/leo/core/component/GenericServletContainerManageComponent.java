package org.leo.core.component;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * Servlet 容器通用管理组件。
 *
 * <p>该组件只依赖 JDK，通过 Servlet 3.x 的注册表反射接口提供 Jetty、Undertow、
 * WildFly/JBoss、WebSphere、Resin、Apusic、GlassFish/Payara、TongWeb 和 BES
 * 等容器的统一 Servlet 注册表视图。标准 Servlet API 没有运行期删除注册项的接口，
 * 因此该适配器严格只读；修改能力由经过版本验证的容器专用适配器提供。</p>
 */
public class GenericServletContainerManageComponent implements Runnable {

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
        if ("inspectRuntime".equals(methodName)) {
            results.put("contexts", inspectRuntime());
            results.put("code", Integer.valueOf(200));
            return;
        }
        results.put("code", Integer.valueOf(400));
        results.put("msg", "未知 methodName: " + methodName);
    }

    public ArrayList inspectRuntime() {
        ArrayList answer = new ArrayList();
        Set contexts = discoverServletContexts();
        Iterator iterator = contexts.iterator();
        while (iterator.hasNext()) {
            Object context = iterator.next();
            try {
                HashMap info = new HashMap();
                String contextPath = stringValue(tryInvoke(context, "getContextPath"));
                String displayName = stringValue(tryInvoke(context, "getServletContextName"));
                info.put("name", contextPath.length() == 0 ? displayName : contextPath);
                info.put("basePath", contextPath);
                info.put("workDir", resolveWorkDir(context));
                info.put("containerClassName", context.getClass().getName());
                info.put("allFilter", getAllFilter(context));
                info.put("allServlet", getAllServlet(context));
                info.put("allListener", getAllListener(context));
                info.put("allValve", new ArrayList());
                answer.add(info);
            } catch (Throwable ignored) {
            }
        }
        return answer;
    }

    public ArrayList getAllFilter(Object context) {
        ArrayList filters = new ArrayList();
        Object registrations = tryInvoke(context, "getFilterRegistrations");
        if (!(registrations instanceof Map)) return filters;
        Iterator entries = ((Map) registrations).entrySet().iterator();
        while (entries.hasNext()) {
            Map.Entry entry = (Map.Entry) entries.next();
            Object registration = entry.getValue();
            HashMap info = new HashMap();
            String name = String.valueOf(entry.getKey());
            String className = stringValue(tryInvoke(registration, "getClassName"));
            info.put("filterName", name);
            info.put("filterClassName", className);
            info.put("urlPatterns", toList(tryInvoke(registration, "getUrlPatternMappings")));
            info.put("servletNames", toList(tryInvoke(registration, "getServletNameMappings")));
            filters.add(info);
        }
        return filters;
    }

    public ArrayList getAllServlet(Object context) {
        ArrayList servlets = new ArrayList();
        Object registrations = tryInvoke(context, "getServletRegistrations");
        if (!(registrations instanceof Map)) return servlets;
        Iterator entries = ((Map) registrations).entrySet().iterator();
        while (entries.hasNext()) {
            Map.Entry entry = (Map.Entry) entries.next();
            Object registration = entry.getValue();
            String name = String.valueOf(entry.getKey());
            String className = stringValue(tryInvoke(registration, "getClassName"));
            Object mappings = tryInvoke(registration, "getMappings");
            ArrayList paths = toList(mappings);
            if (paths.isEmpty()) {
                servlets.add(servletInfo(name, className, ""));
            } else {
                for (int i = 0; i < paths.size(); i++) {
                    servlets.add(servletInfo(name, className, String.valueOf(paths.get(i))));
                }
            }
        }
        return servlets;
    }

    public ArrayList getAllListener(Object context) {
        ArrayList listeners = new ArrayList();
        IdentityHashMap seen = new IdentityHashMap();
        collectListenerFields(context, context, 0, seen, listeners);
        Object owner = unwrapContextOwner(context);
        if (owner != context) collectListenerFields(owner, context, 0, seen, listeners);
        return listeners;
    }

    private HashMap servletInfo(String name, String className, String url) {
        HashMap info = new HashMap();
        info.put("url", url);
        info.put("wrapperName", name);
        info.put("servletClass", className);
        return info;
    }

    private Set discoverServletContexts() {
        Set contexts = Collections.newSetFromMap(new IdentityHashMap());
        addProviderContexts(contexts);
        IdentityHashMap inspected = new IdentityHashMap();
        try {
            Set threads = Thread.getAllStackTraces().keySet();
            Iterator iterator = threads.iterator();
            while (iterator.hasNext()) {
                Thread thread = (Thread) iterator.next();
                inspectForContext(thread.getContextClassLoader(), 0, contexts, inspected);
                inspectForContext(thread, 0, contexts, inspected);
            }
        } catch (Throwable ignored) {
            inspectForContext(Thread.currentThread().getContextClassLoader(), 0, contexts, inspected);
        }
        return contexts;
    }

    private void addProviderContexts(Set contexts) {
        String[][] providers = new String[][]{
                {"io.undertow.servlet.handlers.ServletRequestContext", "current", "getCurrentServletContext"},
                {"org.eclipse.jetty.server.handler.ContextHandler", "getCurrentContext", "getContextHandler"},
                {"com.caucho.server.webapp.WebApp", "getCurrent", "getServletContext"},
                {"javax.faces.context.FacesContext", "getCurrentInstance", "getExternalContext"},
                {"jakarta.faces.context.FacesContext", "getCurrentInstance", "getExternalContext"}
        };
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        for (int i = 0; i < providers.length; i++) {
            try {
                Class provider = Class.forName(providers[i][0], false, loader);
                Object current = tryInvoke(provider, providers[i][1]);
                Object candidate = tryInvoke(current, providers[i][2]);
                if (providers[i][0].indexOf("faces") >= 0) candidate = tryInvoke(candidate, "getContext");
                addContextCandidate(candidate, contexts);
                addContextCandidate(current, contexts);
            } catch (Throwable ignored) {
            }
        }
    }

    private void inspectForContext(Object object, int depth, Set contexts, IdentityHashMap seen) {
        if (object == null || depth > 4 || seen.containsKey(object)) return;
        seen.put(object, Boolean.TRUE);
        addContextCandidate(object, contexts);

        String[] getters = new String[]{"getServletContext", "getContext", "getContextHandler",
                "getWebApp", "getDeployment", "getApplication"};
        for (int i = 0; i < getters.length; i++) {
            Object child = tryInvoke(object, getters[i]);
            if (isContainerObject(child)) inspectForContext(child, depth + 1, contexts, seen);
        }
        String[] fields = new String[]{"servletContext", "_servletContext", "context", "_context",
                "webApp", "_webApp", "deployment", "_deployment", "handler", "_handler",
                "resources", "this$0"};
        for (int i = 0; i < fields.length; i++) {
            Object child = tryGetField(object, fields[i]);
            if (isContainerObject(child)) inspectForContext(child, depth + 1, contexts, seen);
        }
    }

    private void addContextCandidate(Object candidate, Set contexts) {
        if (candidate == null) return;
        if (hasNoArgMethod(candidate.getClass(), "getServletRegistrations")
                && hasNoArgMethod(candidate.getClass(), "getFilterRegistrations")) {
            contexts.add(candidate);
            return;
        }
        Object nested = tryInvoke(candidate, "getServletContext");
        if (nested != null && hasNoArgMethod(nested.getClass(), "getServletRegistrations")) {
            contexts.add(nested);
        }
    }

    private Object unwrapContextOwner(Object context) {
        Object owner = firstNonNull(tryInvoke(context, "getContextHandler"),
                tryGetField(context, "this$0"));
        return owner == null ? context : owner;
    }

    private String resolveWorkDir(Object context) {
        String[] attributes = new String[]{
                "javax.servlet.context.tempdir", "jakarta.servlet.context.tempdir"
        };
        for (int i = 0; i < attributes.length; i++) {
            Object value = tryInvoke(context, "getAttribute", new Class[]{String.class},
                    new Object[]{attributes[i]});
            if (value != null) return String.valueOf(value);
        }
        Object realPath = tryInvoke(context, "getRealPath", new Class[]{String.class}, new Object[]{"/"});
        return stringValue(realPath);
    }

    private void collectListenerFields(Object object, Object context, int depth,
                                       IdentityHashMap seen, ArrayList sink) {
        if (object == null || depth > 4 || seen.containsKey(object)) return;
        seen.put(object, Boolean.TRUE);
        Class type = object.getClass();
        while (type != null && !type.getName().startsWith("java.")) {
            Field[] fields = type.getDeclaredFields();
            for (int i = 0; i < fields.length; i++) {
                Field field = fields[i];
                if (Modifier.isStatic(field.getModifiers())) continue;
                try {
                    field.setAccessible(true);
                    Object value = field.get(object);
                    String fieldName = field.getName().toLowerCase();
                    if (fieldName.indexOf("listener") >= 0) {
                        addListenerValues(value, object, fieldName, seen, sink);
                    } else if (isContainerObject(value) && depth < 2
                            && (fieldName.indexOf("event") >= 0 || fieldName.indexOf("deployment") >= 0
                            || fieldName.indexOf("context") >= 0 || fieldName.indexOf("handler") >= 0)) {
                        collectListenerFields(value, context, depth + 1, seen, sink);
                    }
                } catch (Throwable ignored) {
                }
            }
            type = type.getSuperclass();
        }
    }

    private void addListenerValues(Object value, Object owner, String category,
                                   IdentityHashMap seen, ArrayList sink) {
        if (value == null) return;
        if (value instanceof Map) {
            Iterator iterator = ((Map) value).values().iterator();
            while (iterator.hasNext()) addListener(iterator.next(), owner, category, seen, sink);
        } else if (value instanceof Collection) {
            Iterator iterator = ((Collection) value).iterator();
            while (iterator.hasNext()) addListener(iterator.next(), owner, category, seen, sink);
        } else if (value.getClass().isArray()) {
            int length = Array.getLength(value);
            for (int i = 0; i < length; i++) addListener(Array.get(value, i), owner, category, seen, sink);
        } else {
            addListener(value, owner, category, seen, sink);
        }
    }

    private void addListener(Object listener, Object owner, String category,
                             IdentityHashMap seen, ArrayList sink) {
        if (listener == null || seen.containsKey(listener)) return;
        String className = listener.getClass().getName();
        if (className.startsWith("java.") || className.startsWith("sun.")) return;
        seen.put(listener, Boolean.TRUE);
        String id = Integer.toHexString((category + "|" + className).hashCode());
        HashMap info = new HashMap();
        info.put("listenerId", id);
        info.put("className", className);
        info.put("category", category);
        ClassLoader loader = listener.getClass().getClassLoader();
        info.put("classLoader", loader == null ? "<bootstrap>" : loader.getClass().getName());
        sink.add(info);
    }

    private static Object tryInvoke(Object object, String name) {
        try {
            return invokeMethod(object, name, new Class[0], new Object[0]);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object tryInvoke(Object object, String name, Class[] types, Object[] arguments) {
        try {
            return invokeMethod(object, name, types, arguments);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object invokeMethod(Object object, String name, Class[] types, Object[] arguments)
            throws Exception {
        if (object == null) throw new NoSuchMethodException(name);
        Class type = object instanceof Class ? (Class) object : object.getClass();
        Method method = null;
        Class cursor = type;
        while (cursor != null && method == null) {
            try {
                method = cursor.getDeclaredMethod(name, types);
            } catch (NoSuchMethodException ignored) {
                cursor = cursor.getSuperclass();
            }
        }
        if (method == null) {
            Method[] methods = type.getMethods();
            for (int i = 0; i < methods.length; i++) {
                if (methods[i].getName().equals(name)
                        && methods[i].getParameterTypes().length == types.length) {
                    method = methods[i];
                    break;
                }
            }
        }
        if (method == null) throw new NoSuchMethodException(name);
        method.setAccessible(true);
        return method.invoke(object instanceof Class ? null : object, arguments);
    }

    private static Object tryGetField(Object object, String name) {
        if (object == null) return null;
        Class type = object.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(object);
            } catch (Throwable ignored) {
                type = type.getSuperclass();
            }
        }
        return null;
    }

    private static boolean hasNoArgMethod(Class type, String name) {
        Method[] methods = type.getMethods();
        for (int i = 0; i < methods.length; i++) {
            if (methods[i].getName().equals(name) && methods[i].getParameterTypes().length == 0) return true;
        }
        return false;
    }

    private static boolean isContainerObject(Object value) {
        if (value == null) return false;
        Class type = value.getClass();
        if (type.isPrimitive() || type.isEnum() || type.isArray()
                || value instanceof String || value instanceof Number || value instanceof Boolean
                || value instanceof Class || value instanceof ClassLoader) return false;
        String name = type.getName();
        return !name.startsWith("java.lang.") && !name.startsWith("java.time.");
    }

    private static ArrayList toList(Object value) {
        ArrayList answer = new ArrayList();
        if (value == null) return answer;
        if (value instanceof Iterable) {
            Iterator iterator = ((Iterable) value).iterator();
            while (iterator.hasNext()) answer.add(String.valueOf(iterator.next()));
            return answer;
        }
        if (value instanceof Enumeration) {
            Enumeration enumeration = (Enumeration) value;
            while (enumeration.hasMoreElements()) answer.add(String.valueOf(enumeration.nextElement()));
            return answer;
        }
        if (value.getClass().isArray()) {
            int length = Array.getLength(value);
            for (int i = 0; i < length; i++) answer.add(String.valueOf(Array.get(value, i)));
            return answer;
        }
        answer.add(String.valueOf(value));
        return answer;
    }

    private static Object firstNonNull(Object first, Object second) {
        return first == null ? second : first;
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }


}
