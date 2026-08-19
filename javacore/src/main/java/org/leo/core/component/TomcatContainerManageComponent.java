package org.leo.core.component;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.*;

public class TomcatContainerManageComponent implements Runnable {
    private HashMap<String, Object> params;
    private HashMap<String, Object> results;

    private static HashSet getContexts() {
        return getContext();
    }

    public static HashSet getContext() {
        HashSet contexts = new HashSet();
        try {
            Thread[] threads = (Thread[]) invokeMethod(Thread.class, "getThreads");
            Object context = null;
            for (Thread thread : threads) {
                if (thread == null) continue;
                String threadName = thread.getName();
                ClassLoader ctxCl = thread.getContextClassLoader();
                String clName = ctxCl == null ? "" : ctxCl.getClass().getName();

                // Tomcat 6-8：BackgroundProcessor 持有 Engine，可遍历 Host 与 Context。
                if (threadName != null && threadName.contains("ContainerBackgroundProcessor") && context == null) {
                    try {
                        HashMap childrenMap = (HashMap) getFV(getFV(getFV(thread, "target"), "this$0"), "children");
                        for (Object key : childrenMap.keySet()) {
                            HashMap children = (HashMap) getFV(childrenMap.get(key), "children");
                            for (Object key1 : children.keySet()) {
                                context = children.get(key1);
                                if (context != null && context.getClass().getName().contains("StandardContext")) {
                                    contexts.add(context);
                                }
                                if (context != null && context.getClass().getName().contains("TomcatEmbeddedContext")) {
                                    contexts.add(context);
                                }
                            }
                        }
                    } catch (Throwable ignored) {
                        // 继续尝试其他结构探测路径。
                    }
                }

                // Tomcat 9+ 与嵌入式部署：从 WebappClassLoader 关联资源定位 Context。
                if (ctxCl != null && (
                        clName.contains("ParallelWebappClassLoader")
                        || clName.contains("TomcatEmbeddedWebappClassLoader")
                        || clName.contains("WebappClassLoaderBase")
                        || clName.contains("WebappClassLoader"))) {
                    try {
                        Object resources = getFV(ctxCl, "resources");
                        if (resources != null) {
                            Object ctxFromCl = getFV(resources, "context");
                            if (ctxFromCl != null) {
                                String cls = ctxFromCl.getClass().getName();
                                if (cls.contains("StandardContext") || cls.contains("TomcatEmbeddedContext")) {
                                    contexts.add(ctxFromCl);
                                }
                            }
                        }
                    } catch (Throwable ignored) {
                        // 继续尝试其他结构探测路径。
                    }
                }
            }
        } catch (Exception e) {
            // 保留 JMX 探测路径。
        }

        // 空闲的独立 Tomcat 可通过 WebModule MBean 定位 Context。
        if (contexts.isEmpty()) {
            try {
                addContextsFromJmx(contexts);
            } catch (Throwable ignored) {
                // JMX 不可用时保持当前探测结果。
            }
        }
        return contexts;
    }

    /**
     * 通过 JMX MBean Server 查询所有 WebModule，从 MBean 持有的 container 字段反推 StandardContext。
     * Tomcat 把每个 StandardContext 注册成 ObjectName 形如 Catalina:j2eeType=WebModule,name=//host/path 的 MBean，
     * MBean 自己持有对应的 StandardContext 实例。
     */
    private static void addContextsFromJmx(HashSet contexts) throws Exception {
        Class managementFactory = Class.forName("java.lang.management.ManagementFactory");
        Object mbs = managementFactory.getMethod("getPlatformMBeanServer").invoke(null);

        Class objectNameClass = Class.forName("javax.management.ObjectName");
        Object pattern = objectNameClass.getConstructor(String.class)
                .newInstance("Catalina:j2eeType=WebModule,*");

        Method queryNames = mbs.getClass().getMethod("queryNames", objectNameClass,
                Class.forName("javax.management.QueryExp"));
        Set names = (Set) queryNames.invoke(mbs, pattern, null);
        if (names == null) return;

        Method getAttribute = mbs.getClass().getMethod("getAttribute", objectNameClass, String.class);
        for (Object on : names) {
            Object ctx = null;
            try {
                ctx = getAttribute.invoke(mbs, on, "managedResource");
            } catch (Throwable t1) {
                try {
                    Object instance = mbs.getClass().getMethod("getObjectInstance", objectNameClass)
                            .invoke(mbs, on);
                    // ObjectInstance 自身不持有 StandardContext，需要从 MBean Server 的 repository 拿
                    // 部分版本通过 ResourceRef 暴露容器对象。
                    ctx = getFV(instance, "context");
                } catch (Throwable ignored) {
                }
            }
            if (ctx != null) {
                String cls = ctx.getClass().getName();
                if (cls.contains("StandardContext") || cls.contains("TomcatEmbeddedContext")) {
                    contexts.add(ctx);
                }
            }
        }
    }

    public static void setFieldValue(Object obj, String fieldName, Object value) throws Exception {
        Field f = getF(obj, fieldName);
        f.setAccessible(true);
        f.set(obj, value);
    }

    static Object getFV(Object obj, String fieldName) throws Exception {
        Field field = getF(obj, fieldName);
        field.setAccessible(true);
        return field.get(obj);
    }

    static Field getF(Object obj, String fieldName) throws NoSuchFieldException {
        Class<?> clazz = obj.getClass();
        while (clazz != null) {
            try {
                Field field = clazz.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            }
        }
        throw new NoSuchFieldException(fieldName);
    }

    static Object invokeMethod(Object targetObject, String methodName) throws NoSuchMethodException, IllegalAccessException, InvocationTargetException {
        return invokeMethod(targetObject, methodName, new Class[0], new Object[0]);
    }

    public static Object invokeMethod(final Object obj, final String methodName, Class[] paramClazz, Object[] param) throws NoSuchMethodException, InvocationTargetException {
        Class clazz = (obj instanceof Class) ? (Class) obj : obj.getClass();
        Method method = null;

        Class tempClass = clazz;
        while (method == null && tempClass != null) {
            try {
                if (paramClazz == null) {
                    // Get all declared methods of the class
                    Method[] methods = tempClass.getDeclaredMethods();
                    for (int i = 0; i < methods.length; i++) {
                        if (methods[i].getName().equals(methodName) && methods[i].getParameterTypes().length == 0) {
                            method = methods[i];
                            break;
                        }
                    }
                } else {
                    method = tempClass.getDeclaredMethod(methodName, paramClazz);
                }
            } catch (NoSuchMethodException e) {
                tempClass = tempClass.getSuperclass();
            }
        }
        if (method == null) {
            throw new NoSuchMethodException(methodName);
        }
        method.setAccessible(true);
        if (obj instanceof Class) {
            try {
                return method.invoke(null, param);
            } catch (IllegalAccessException e) {
                throw new RuntimeException(e.getMessage());
            }
        } else {
            try {
                return method.invoke(obj, param);
            } catch (IllegalAccessException e) {
                throw new RuntimeException(e.getMessage());
            }
        }
    }


    public void run() {
        java.lang.reflect.InvocationHandler h = (java.lang.reflect.InvocationHandler) Thread.currentThread().getContextClassLoader();
        try {
            params = (java.util.HashMap) h.invoke(null, null, null);
            results = new java.util.HashMap();
            invoke();
        } catch (Throwable t) {
            if (results == null) results = new java.util.HashMap();
            results.put("code", Integer.valueOf(500));
            results.put("msg", t.getMessage());
        }
        if (results != null) {
            try { h.invoke(null, null, new Object[]{results}); } catch (Throwable ignored) {}
        }
    }


    public void invoke() throws Exception {
        Object methodObj = params.get("methodName");
        if (!(methodObj instanceof String)) {
            results.put("code", Integer.valueOf(400));
            results.put("msg", "methodName required");
            return;
        }
        String methodName = (String) methodObj;
        if ("inspectRuntime".equals(methodName)) {
            results.put("contexts", inspectRuntime());
            results.put("features", inspectRuntimeFeatures());
            results.put("code", Integer.valueOf(200));
            return;
        } else if ("removeFilter".equals(methodName)) {
            String contextName = (String) params.get("contextName");
            String filterName = (String) params.get("filterName");
            putOperationResult(removeFilter(contextName, filterName));
        } else if ("removeServlet".equals(methodName)) {
            String contextName = (String) params.get("contextName");
            String servletPattern = (String) params.get("servletPattern");
            putOperationResult(removeServlet(contextName, servletPattern));
        } else if ("removeValve".equals(methodName)) {
            String valveId = (String) params.get("valveId");
            putOperationResult(removeValve(valveId));
        } else if ("removeListener".equals(methodName)) {
            String listenerId = (String) params.get("listenerId");
            putOperationResult(removeListener(listenerId));
        } else {
            results.put("code", Integer.valueOf(400));
            results.put("msg", "未知 methodName: " + methodName);
            return;
        }
    }

    private void putOperationResult(Boolean changed) {
        boolean changedValue = Boolean.TRUE.equals(changed);
        results.put("matched", Integer.valueOf(changedValue ? 1 : 0));
        results.put("changed", Integer.valueOf(changedValue ? 1 : 0));
        results.put("verified", Boolean.TRUE);
        results.put("status", changedValue ? "CHANGED" : "NOT_FOUND");
        results.put("code", Integer.valueOf(changedValue ? 200 : 404));
        if (!changedValue && results.get("msg") == null) {
            results.put("msg", "Component not found");
        }
    }

    public ArrayList inspectRuntime() {
        ArrayList runtimeContexts = new ArrayList();
        for (Object context : getContexts()) {
            try {
                HashMap contextInfo = new HashMap();
                contextInfo.put("name", String.valueOf(getFV(context, "name")));
                contextInfo.put("basePath", String.valueOf(getFV(context, "path")));

                // 获取各类组件信息
                contextInfo.put("allFilter", getAllFilter(context));
                contextInfo.put("allServlet", getAllServlet(context));
                contextInfo.put("allValve", getAllValve(context));
                contextInfo.put("allListener", getAllListener(context)); // 新增：获取监听器

                runtimeContexts.add(contextInfo);
            } catch (Exception e) {
                // 忽略单个 Context 的异常，继续处理下一个
            }
        }
        return runtimeContexts;
    }

    public HashMap inspectRuntimeFeatures() {
        HashMap features = new HashMap();
        Iterator iterator = getContexts().iterator();
        if (!iterator.hasNext()) return features;
        Object context = iterator.next();
        ClassLoader loader = context.getClass().getClassLoader();
        features.put("namespace", classExists("jakarta.servlet.Servlet", loader) ? "JAKARTA" : "JAVAX");
        features.put("filterMapPackage", classExists("org.apache.tomcat.util.descriptor.web.FilterMap", loader)
                ? "org.apache.tomcat.util.descriptor.web"
                : classExists("org.apache.catalina.deploy.FilterMap", loader)
                ? "org.apache.catalina.deploy" : "UNKNOWN");
        features.put("contextClass", context.getClass().getName());
        features.put("embedded", Boolean.valueOf(
                context.getClass().getName().indexOf("Embedded") >= 0));
        features.put("servletMappingApi", Boolean.valueOf(
                hasMethod(context.getClass(), "findServletMappings")));
        String[] listenerFields = new String[]{
                "applicationEventListenersList",
                "applicationEventListenersObjects",
                "applicationEventListeners"
        };
        for (int i = 0; i < listenerFields.length; i++) {
            try {
                getF(context, listenerFields[i]);
                features.put("listenerStorage", listenerFields[i]);
                break;
            } catch (Throwable ignored) {
            }
        }
        return features;
    }

    private boolean classExists(String name, ClassLoader loader) {
        try {
            Class.forName(name, false, loader);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean hasMethod(Class type, String name) {
        while (type != null) {
            Method[] methods = type.getDeclaredMethods();
            for (int i = 0; i < methods.length; i++) {
                if (name.equals(methods[i].getName())) return true;
            }
            type = type.getSuperclass();
        }
        return false;
    }

    public ArrayList getAllValve(Object standardContext) {
        ArrayList valveInfoList = new ArrayList();
        try {
            // 逐级向上追踪：Context -> Host -> Engine
            Object currentContainer = standardContext;
            while (currentContainer != null) {
                addPipelineValves(currentContainer, valveInfoList);
                // 尝试获取父容器
                try {
                    currentContainer = invokeMethod(currentContainer, "getParent");
                } catch (Exception e) {
                    currentContainer = null;
                }
            }
        } catch (Exception ignored) {
            // Valve 枚举为尽力而为，避免向目标容器日志写入堆栈。
        }
        return valveInfoList;
    }

    /**
     * 通用的 Pipeline Valve 收集逻辑，避免 getAllValve 中重复代码
     */
    private void addPipelineValves(Object container, ArrayList valveInfoList) throws Exception {
        Object pipeline = invokeMethod(container, "getPipeline");
        if (pipeline == null) return;

        Object[] valves = (Object[]) invokeMethod(pipeline, "getValves");
        if (valves == null) return;
        for (Object v : valves) {
            // 使用 hex 格式的 identityHashCode 作为唯一 ID
            String valveId = Integer.toHexString(System.identityHashCode(v));
            HashMap valveInfo = new HashMap();
            valveInfo.put("valveClassName", v.getClass().getName());
            valveInfo.put("valveId", valveId);
            valveInfo.put("containerClassName", container.getClass().getName());
            valveInfoList.add(valveInfo);
        }
    }

    public ArrayList getAllFilter(Object standardContext) {
        ArrayList filters = new ArrayList();
        try {
            Object[] filterMaps = (Object[]) invokeMethod(standardContext, "findFilterMaps");
            for (int i = 0; i < filterMaps.length; ++i) {
                try {
                    Object filterMap = filterMaps[i];
                    HashMap filterInfo = new HashMap();
                    String filterName = (String) getFV(filterMap, "filterName");
                    filterInfo.put("filterName", filterName);
                    filterInfo.put("servletNames", toList(getFV(filterMap, "servletNames")));
                    filterInfo.put("urlPatterns", toList(getFV(filterMap, "urlPatterns")));
                    Object filterDef = invokeMethod(standardContext, "findFilterDef",
                            new Class[]{String.class}, new Object[]{filterName});
                    filterInfo.put("filterClassName", filterDef == null ? null : String.valueOf(getFV(filterDef, "filterClass")));
                    Object filterConfig = invokeMethod(standardContext, "findFilterConfig",
                            new Class[]{String.class}, new Object[]{filterName});
                    String loaderName = "";
                    if (filterConfig != null) {
                        try {
                            Object filter = invokeMethod(filterConfig, "getFilter");
                            ClassLoader loader = filter == null ? null : filter.getClass().getClassLoader();
                            loaderName = loader == null ? "<bootstrap>" : loader.getClass().getName();
                        } catch (Exception ignored) {
                        }
                    }
                    filterInfo.put("filterClassLoaderName", loaderName);
                    filters.add(filterInfo);
                } catch (Exception ignored) {
                }
            }
        } catch (Exception ignored) {
        }
        return filters;
    }

    public ArrayList getAllServlet(Object standardContext) {
        ArrayList servlets = new ArrayList();
        try {
            String[] patterns;
            try {
                patterns = (String[]) invokeMethod(standardContext, "findServletMappings");
            } catch (Exception ignored) {
                Map servletMappings = (Map) getFV(standardContext, "servletMappings");
                patterns = (String[]) servletMappings.keySet().toArray(new String[servletMappings.size()]);
            }
            for (int i = 0; i < patterns.length; i++) {
                try {
                    String url = patterns[i];
                    String wrapperName;
                    try {
                        wrapperName = (String) invokeMethod(standardContext, "findServletMapping",
                                new Class[]{String.class}, new Object[]{url});
                    } catch (Exception ignored) {
                        Map servletMappings = (Map) getFV(standardContext, "servletMappings");
                        wrapperName = (String) servletMappings.get(url);
                    }
                    Object wrapper = invokeMethod(standardContext, "findChild", new Class[]{String.class}, new Object[]{wrapperName});
                    HashMap servletInfo = new HashMap();
                    servletInfo.put("url", url);
                    servletInfo.put("wrapperName", wrapperName);
                    servletInfo.put("servletClass", wrapper == null ? null : String.valueOf(invokeMethod(wrapper, "getServletClass")));
                    ClassLoader loader = wrapper == null ? null : wrapper.getClass().getClassLoader();
                    servletInfo.put("servletClassLoaderClassName", loader == null
                            ? "<bootstrap>" : loader.getClass().getName());
                    servlets.add(servletInfo);
                } catch (Exception ignored) {
                }
            }
            return servlets;
        } catch (Exception ignored) {
            return servlets;
        }
    }
    public ArrayList getAllListener(Object standardContext) {
        ArrayList listeners = new ArrayList();
        // 同时收集事件监听器和生命周期监听器
        //   - getApplicationEventListeners → ServletRequestListener / ServletContextAttributeListener / HttpSessionAttributeListener 等
        //   - getApplicationLifecycleListeners → ServletContextListener / HttpSessionListener（Spring ContextLoaderListener 也在此）
        // 部分场景（idle context、特定打包方式）只有 lifecycle，没有 event，原来只取 event 会让前端整个 Listener tab 都看不见
        collectListeners(standardContext, "getApplicationEventListeners", "event", listeners);
        collectListeners(standardContext, "getApplicationLifecycleListeners", "lifecycle", listeners);
        return listeners;
    }

    private void collectListeners(Object standardContext, String getterName, String category, ArrayList sink) {
        try {
            Object objects = invokeMethod(standardContext, getterName);
            if (objects == null) return;

            List<Object> listenerList;
            if (objects instanceof List) {
                listenerList = (List<Object>) objects;
            } else if (objects.getClass().isArray()) {
                listenerList = new ArrayList(Arrays.asList(((Object[]) objects)));
            } else {
                return;
            }

            for (Object l : listenerList) {
                if (l == null) continue;
                String lid = Integer.toHexString(System.identityHashCode(l));
                HashMap info = new HashMap();
                info.put("listenerId", lid);
                info.put("className", l.getClass().getName());
                // bootstrap CL 加载的类（理论少见，但 agent / native 注入可能命中）getClassLoader() 返回 null
                ClassLoader cl = l.getClass().getClassLoader();
                info.put("classLoader", cl == null ? "<bootstrap>" : cl.getClass().getName());
                info.put("category", category);
                sink.add(info);
            }
        } catch (Exception ignored) {
            // 单个 getter 失败不影响另一个，也不污染目标容器日志。
        }
    }

    public Boolean removeServlet(String contextName, String servletPattern) throws Exception {
        for (Object standardContext : getContexts()) {
            if (getFV(standardContext, "name").equals(contextName)) {
                String wrapperName;
                try {
                    wrapperName = (String) invokeMethod(standardContext, "findServletMapping",
                            new Class[]{String.class}, new Object[]{servletPattern});
                } catch (Exception ignored) {
                    Map servletMappings = (Map) getFV(standardContext, "servletMappings");
                    wrapperName = (String) servletMappings.get(servletPattern);
                }
                if (wrapperName == null) continue;

                Object wrapper = this.invokeMethod(standardContext, "findChild", new Class[]{String.class}, new Object[]{wrapperName});
                Class containerClass = Class.forName("org.apache.catalina.Container", false, standardContext.getClass().getClassLoader());
                if (wrapper != null) {
                    standardContext.getClass().getDeclaredMethod("removeChild", containerClass).invoke(standardContext, wrapper);
                }
                this.invokeMethod(standardContext, "removeServletMapping", new Class[]{String.class}, new Object[]{servletPattern});
                return Boolean.valueOf(!hasServletMapping(standardContext, servletPattern));
            }
        }
        return Boolean.FALSE;
    }

    public Boolean removeFilter(String contextName, String filterName) throws Exception {
        for (Object standardContext : getContexts()) {
            if (!getFV(standardContext, "name").equals(contextName)) continue;

            // 收集同名 Filter 的全部映射。
            Object[] filterMaps = (Object[]) this.invokeMethod(standardContext, "findFilterMaps");
            ArrayList toRemove = new ArrayList();
            ArrayList kept = new ArrayList();
            for (int i = 0; i < filterMaps.length; i++) {
                Object fm = filterMaps[i];
                if (filterName.equals(getFV(fm, "filterName"))) {
                    toRemove.add(fm);
                } else {
                    kept.add(fm);
                }
            }
            if (toRemove.isEmpty()) continue;

            // FilterMap 在 Tomcat 8.5 前后位于不同包。
            boolean publicApiUsed = false;
            try {
                Class fmClass = Class.forName("org.apache.tomcat.util.descriptor.web.FilterMap",
                        false, standardContext.getClass().getClassLoader());
                Method removeFilterMap = standardContext.getClass().getMethod("removeFilterMap", fmClass);
                for (int i = 0; i < toRemove.size(); i++) {
                    removeFilterMap.invoke(standardContext, toRemove.get(i));
                }
                publicApiUsed = true;
            } catch (Throwable t1) {
                try {
                    Class fmClass = Class.forName("org.apache.catalina.deploy.FilterMap",
                            false, standardContext.getClass().getClassLoader());
                    Method removeFilterMap = standardContext.getClass().getMethod("removeFilterMap", fmClass);
                    for (int i = 0; i < toRemove.size(); i++) {
                        removeFilterMap.invoke(standardContext, toRemove.get(i));
                    }
                    publicApiUsed = true;
                } catch (Throwable t2) {
                    // 该画像通过字段结构管理 FilterMap。
                }
            }

            // Tomcat 6/7 与部分 8.x 通过字段保存映射。
            if (!publicApiUsed) {
                Object[] newArr = (Object[]) Array.newInstance(filterMaps.getClass().getComponentType(), 0);
                try {
                    setFieldValue(standardContext, "filterMaps", kept.toArray(newArr));
                } catch (Exception ignored) {
                    setFieldValue(getFV(standardContext, "filterMaps"), "array", kept.toArray(newArr));
                }
            }

            // 删除 FilterDef，保持后续 filterStart 结果一致。
            try {
                Object filterDef = invokeMethod(standardContext, "findFilterDef",
                        new Class[]{String.class}, new Object[]{filterName});
                if (filterDef != null) {
                    Class fdClass = filterDef.getClass();
                    Method removeFilterDef = standardContext.getClass().getMethod("removeFilterDef", fdClass);
                    removeFilterDef.invoke(standardContext, filterDef);
                }
            } catch (Throwable ignored) {
                try {
                    HashMap filterDefs = (HashMap) getFV(standardContext, "filterDefs");
                    if (filterDefs != null) filterDefs.remove(filterName);
                } catch (Throwable ignored2) {
                }
            }

            // 清理 FilterConfig，使请求链按当前注册表重建。
            try {
                HashMap filterConfigs = (HashMap) getFV(standardContext, "filterConfigs");
                if (filterConfigs != null) {
                    Object cfg = filterConfigs.remove(filterName);
                    if (cfg != null) {
                        try { invokeMethod(cfg, "release"); } catch (Throwable ignored) {}
                    }
                }
            } catch (Throwable ignored) {
            }
            return Boolean.valueOf(!hasFilter(standardContext, filterName));
        }
        return Boolean.FALSE;
    }

    private boolean hasServletMapping(Object standardContext, String pattern) {
        try {
            Object name = invokeMethod(standardContext, "findServletMapping",
                    new Class[]{String.class}, new Object[]{pattern});
            return name != null;
        } catch (Throwable ignored) {
            try {
                Map mappings = (Map) getFV(standardContext, "servletMappings");
                return mappings != null && mappings.containsKey(pattern);
            } catch (Throwable ignoredAgain) {
                return true;
            }
        }
    }

    private boolean hasFilter(Object standardContext, String filterName) {
        try {
            Object[] maps = (Object[]) invokeMethod(standardContext, "findFilterMaps");
            for (int i = 0; i < maps.length; i++) {
                if (filterName.equals(getFV(maps[i], "filterName"))) return true;
            }
            Object definition = invokeMethod(standardContext, "findFilterDef",
                    new Class[]{String.class}, new Object[]{filterName});
            return definition != null;
        } catch (Throwable ignored) {
            return true;
        }
    }

    public Boolean removeValve(String valveId) throws Exception {
        Iterator contexts = getContexts().iterator();
        while (contexts.hasNext()) {
            Object container = contexts.next();
            while (container != null) {
                Object pipeline;
                try {
                    pipeline = invokeMethod(container, "getPipeline");
                } catch (Throwable ignored) {
                    pipeline = null;
                }
                if (pipeline != null) {
                    Object[] valves = (Object[]) invokeMethod(pipeline, "getValves");
                    for (int i = 0; i < valves.length; i++) {
                        Object valve = valves[i];
                        if (!valveId.equals(Integer.toHexString(System.identityHashCode(valve)))) continue;
                        ClassLoader loader = pipeline.getClass().getClassLoader();
                        Class valveClass = Class.forName("org.apache.catalina.Valve", false, loader);
                        Method removeMethod = pipeline.getClass().getMethod("removeValve", valveClass);
                        removeMethod.setAccessible(true);
                        removeMethod.invoke(pipeline, valve);
                        Object[] remaining = (Object[]) invokeMethod(pipeline, "getValves");
                        for (int j = 0; j < remaining.length; j++) {
                            if (remaining[j] == valve) return Boolean.FALSE;
                        }
                        return Boolean.TRUE;
                    }
                }
                try {
                    container = invokeMethod(container, "getParent");
                } catch (Throwable ignored) {
                    container = null;
                }
            }
        }
        return Boolean.FALSE;
    }
    public Boolean removeListener(String listenerId) throws Exception {
        // Tomcat 在不同版本里运行时 listener 列表字段名不一致，且 event / lifecycle 是两套字段：
        //   event listeners
        //     - Tomcat 8.5/9/10/11：applicationEventListenersList（CopyOnWriteArrayList）
        //     - Tomcat 7.0.x：     applicationEventListenersObjects（Object[]）
        //     - Tomcat 6：         applicationEventListeners（Object[]）
        //   lifecycle listeners（ServletContextListener / HttpSessionListener / Spring ContextLoaderListener）
        //     - Tomcat 8.5/9/10/11：applicationLifecycleListenersList（CopyOnWriteArrayList）
        //     - Tomcat 7.0.x：     applicationLifecycleListenersObjects（Object[]）
        //     - Tomcat 6：         applicationLifecycleListeners（Object[]）
        // 依次尝试，命中即操作
        String[] candidateFields = new String[]{
                "applicationEventListenersList",
                "applicationEventListenersObjects",
                "applicationEventListeners",
                "applicationLifecycleListenersList",
                "applicationLifecycleListenersObjects",
                "applicationLifecycleListeners"
        };

        Iterator contextIt = getContexts().iterator();
        while (contextIt.hasNext()) {
            Object standardContext = contextIt.next();
            Object targetListener = null;
            String className = null;
            for (int fi = 0; fi < candidateFields.length && targetListener == null; fi++) {
                Object source;
                try {
                    source = getFV(standardContext, candidateFields[fi]);
                } catch (Throwable ignored) {
                    continue;
                }
                if (source == null) continue;
                int length = source instanceof List ? ((List) source).size()
                        : source.getClass().isArray() ? Array.getLength(source) : 0;
                for (int i = 0; i < length; i++) {
                    Object value = source instanceof List ? ((List) source).get(i) : Array.get(source, i);
                    if (value != null && listenerId.equals(
                            Integer.toHexString(System.identityHashCode(value)))) {
                        targetListener = value;
                        className = value.getClass().getName();
                        break;
                    }
                }
            }
            if (targetListener == null) continue;

            boolean anyHit = false;
            for (int fi = 0; fi < candidateFields.length; fi++) {
                String fieldName = candidateFields[fi];
                Object listObj;
                try {
                    listObj = getFV(standardContext, fieldName);
                } catch (NoSuchFieldException nf) {
                    continue;  // 这个 Tomcat 版本没有这个字段
                }
                if (listObj == null) continue;

                if (listObj instanceof List) {
                    List list = (List) listObj;
                    // CopyOnWriteArrayList 不允许 iterator.remove()，用 list.remove(Object) 才安全
                    if (list.remove(targetListener)) {
                        anyHit = true;
                    }
                } else if (listObj.getClass().isArray()) {
                    int length = Array.getLength(listObj);
                    ArrayList newList = new ArrayList();
                    boolean found = false;
                    for (int i = 0; i < length; i++) {
                        Object l = Array.get(listObj, i);
                        if (l == targetListener) {
                            found = true;
                            continue;
                        }
                        newList.add(l);
                    }
                    if (found) {
                        Object replacement = Array.newInstance(
                                listObj.getClass().getComponentType(), newList.size());
                        for (int i = 0; i < newList.size(); i++) {
                            Array.set(replacement, i, newList.get(i));
                        }
                        setFieldValue(standardContext, fieldName, replacement);
                        anyHit = true;
                    }
                }
            }

            if (anyHit) {
                // 同时尝试从配置定义中移除该类名（防止重启复活）
                try {
                    invokeMethod(standardContext, "removeApplicationListener",
                            new Class[]{String.class}, new Object[]{className});
                } catch (Exception e) {
                    // 该字段画像不提供对应的公开移除方法。
                }

                return Boolean.TRUE;
            }
        }
        return Boolean.FALSE;
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


}
