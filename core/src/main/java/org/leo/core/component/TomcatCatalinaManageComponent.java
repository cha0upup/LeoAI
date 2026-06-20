package org.leo.core.component;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.*;

public class TomcatCatalinaManageComponent implements Runnable {
    // 注意：曾经这里有个 `private static HashSet contexts` 缓存，
    // 第一次扫描如果命中失败就把空集合缓存住，之后永远返回空。
    // 现在每次都重新扫描线程列表。
    private HashMap params;
    private HashMap results;

    private static HashSet getContexts() {
        return getContext();
    }

    private static HashMap valveMap=new HashMap();
    private static HashMap pipelineMap=new HashMap();
    private static HashMap listenerMap = new HashMap();

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

                // 适配 v5/v6/7/8：BackgroundProcessor 持有 Engine 引用，能拿到 host->context 全局 map
                if (threadName != null && threadName.contains("ContainerBackgroundProcessor") && context == null) {
                    try {
                        HashMap childrenMap = (HashMap) getFV(getFV(getFV(thread, "target"), "this$0"), "children");
                        // 遍历所有 host（不止 localhost）
                        for (Object key : childrenMap.keySet()) {
                            HashMap children = (HashMap) getFV(childrenMap.get(key), "children");
                            // 遍历所有 context（不止 ROOT）
                            for (Object key1 : children.keySet()) {
                                context = children.get(key1);
                                if (context != null && context.getClass().getName().contains("StandardContext")) {
                                    contexts.add(context);
                                }
                                // 兼容 spring boot 2.x embedded tomcat
                                if (context != null && context.getClass().getName().contains("TomcatEmbeddedContext")) {
                                    contexts.add(context);
                                }
                            }
                        }
                    } catch (Throwable ignored) {
                        // 字段结构在不同 Tomcat 版本可能不一致，吞掉继续走下一种规则
                    }
                }

                // 适配 tomcat v9+/Spring Boot embedded：从请求处理线程的 contextClassLoader 反推 context
                // 旧代码只识别 ParallelWebappClassLoader / TomcatEmbeddedWebappClassLoader，
                // 这里放宽到所有 WebappClassLoader 变种（WebappClassLoaderBase 是它们的基类）
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
                        // resources 字段在某些 Tomcat 版本里在父类，已用 getFV 递归找了；这里防御 null 等异常
                    }
                }
            }
        } catch (Exception e) {
            // 线程扫描本身失败也不能阻断 JMX 兜底
        }

        // 兜底路径：从 PlatformMBeanServer 查 Catalina:j2eeType=WebModule,*
        // 这条路对独立 Tomcat（puppet 通过 common.loader 加载、webapp 处于 idle）极其有效，
        // 因为前两条路径都依赖于「能撞上活跃的请求线程或 BackgroundProcessor 线程」，
        // 在没有请求活动的环境下会拿不到任何 context。
        if (contexts.isEmpty()) {
            try {
                addContextsFromJmx(contexts);
            } catch (Throwable ignored) {
                // JMX 不可用（极少见，比如 -Dcom.sun.management.jmxremote 被禁），保持空集合
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

        // MBeanServer.getAttribute(name, "managedResource") 在 Tomcat 里能拿到 StandardContext 实例
        // 但有些版本是私有 attribute；getObjectInstance(name) 拿到 ObjectInstance 后从内部字段反推也行
        Method getAttribute = mbs.getClass().getMethod("getAttribute", objectNameClass, String.class);
        for (Object on : names) {
            Object ctx = null;
            // 优先用 ContainerBase.managedResource（Tomcat 5+ 都有）
            try {
                ctx = getAttribute.invoke(mbs, on, "managedResource");
            } catch (Throwable t1) {
                // 老 Tomcat 没有 managedResource attribute，退化到走 MBeanRegistration 内部字段
                try {
                    Object instance = mbs.getClass().getMethod("getObjectInstance", objectNameClass)
                            .invoke(mbs, on);
                    // ObjectInstance 自身不持有 StandardContext，需要从 MBean Server 的 repository 拿
                    // 这条退化路径在主流 Tomcat 上很少触发，留个空指针保护
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
        Field f = null;
        if (obj instanceof Field) {
            f = (Field) obj;
        } else {
            f = obj.getClass().getDeclaredField(fieldName);
        }

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

    static synchronized Object invokeMethod(Object targetObject, String methodName) throws NoSuchMethodException, IllegalAccessException, InvocationTargetException {
        return invokeMethod(targetObject, methodName, new Class[0], new Object[0]);
    }

    public static synchronized Object invokeMethod(final Object obj, final String methodName, Class[] paramClazz, Object[] param) throws NoSuchMethodException, InvocationTargetException {
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
        String methodName = (String) params.get("methodName");

        if ("getCatalinaInfo".equals(methodName)) {
            results.put("catalinaInfo", getCatalinaInfo());
        }
        if ("unLoadFilter".equals(methodName)) {
            // Filter 卸载通常基于 contextName + filterName (因为同名 Filter 在一个 Context 下唯一)
            String contextName = (String) params.get("contextName");
            String filterName = (String) params.get("filterName");
            unLoadFilter(contextName, filterName);
        }
        if ("unLoadServlet".equals(methodName)) {
            String contextName = (String) params.get("contextName");
            String servletPattern = (String) params.get("servletPattern");
            unLoadServlet(contextName, servletPattern);
        }
        if ("unLoadValve".equals(methodName)) {
            String valveId = (String) params.get("valveId");
            unLoadValve(valveId);
        }
        if ("unLoadListener".equals(methodName)) {
            String listenerId = (String) params.get("listenerId");
            unLoadListener(listenerId);
        }

        results.put("code", 200);
    }

    public ArrayList getCatalinaInfo() {
        // 1. 刷新列表前清空所有组件缓存，防止内存泄漏
        valveMap.clear();
        pipelineMap.clear();
        listenerMap.clear(); // 新增：清理监听器缓存

        ArrayList catalinaInfo = new ArrayList();
        for (Object context : getContexts()) {
            try {
                HashMap contextInfo = new HashMap();
                contextInfo.put("name", getFV(context, "name"));
                contextInfo.put("basePath", getFV(context, "path"));

                // 获取各类组件信息
                contextInfo.put("allFilter", getAllFilter(context));
                contextInfo.put("allServlet", getAllServlet(context));
                contextInfo.put("allValve", getAllValve(context));
                contextInfo.put("allListener", getAllListener(context)); // 新增：获取监听器

                catalinaInfo.add(contextInfo);
            } catch (Exception e) {
                // 忽略单个 Context 的异常，继续处理下一个
            }
        }
        return catalinaInfo;
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
        } catch (Exception e) {
            e.printStackTrace();
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
            valveMap.put(valveId, v);
            pipelineMap.put(valveId, pipeline);

            HashMap valveInfo = new HashMap();
            valveInfo.put("valveClassName", v.getClass().getName());
            valveInfo.put("valveId", valveId);
            valveInfo.put("containerClassName", container.getClass().getName());
            valveInfoList.add(valveInfo);
        }
    }

    public ArrayList getAllFilter(Object standardContext) {
        try {
            Object[] filterMaps = (Object[]) this.invokeMethod(standardContext, "findFilterMaps");
            ArrayList filters = new ArrayList();
            for (int i = 0; i < filterMaps.length; ++i) {
                Object filterMap = filterMaps[i];
                HashMap filterInfo = new HashMap();
                String filterName = (String) getFV(filterMap, "filterName");
                filterInfo.put("filterName", filterName);
                filterInfo.put("servletNames", getFV(filterMap, "servletNames"));
                filterInfo.put("urlPatterns", getFV(filterMap, "urlPatterns"));
                Object filterDef = invokeMethod(standardContext, "findFilterDef", new Class[]{String.class}, new Object[]{filterName});
                filterInfo.put("filterClassName", getFV(filterDef, "filterClass"));

                Object filterConfig=invokeMethod(standardContext,"findFilterConfig",new Class[]{String.class},new Object[]{filterName});
                if (filterConfig.getClass().getName().equals("org.apache.catalina.core.ApplicationFilterConfig")){
                    Object filter=invokeMethod(filterConfig,"getFilter");
                    filterInfo.put("filterClassLoaderName", filter.getClass().getClassLoader().getClass().getName());

                }else {
                    filterInfo.put("filterClassLoaderName", "");
                }
                filters.add(filterInfo);
            }
            return filters;
        } catch (Exception var6) {
            return null;
        }
    }

    public ArrayList getAllServlet(Object standardContext) {
        try {
            HashMap servletMappings = (HashMap) getFV(standardContext, "servletMappings");
            Iterator s = servletMappings.keySet().iterator();
            ArrayList servlets = new ArrayList();
            while (s.hasNext()) {
                try {
                    String url = (String) s.next();
                    String wrapperName = (String) servletMappings.get(url);
                    Object wrapper = invokeMethod(standardContext, "findChild", new Class[]{String.class}, new Object[]{wrapperName});
                    Object servlet =invokeMethod(wrapper,"loadServlet");
                    HashMap servletInfo = new HashMap();
                    servletInfo.put("url", url);
                    servletInfo.put("wrapperName", wrapperName);
                    servletInfo.put("servletClass", invokeMethod(wrapper, "getServletClass"));
                    servletInfo.put("servletClassLoaderClassName", servlet.getClass().getClassLoader().getClass().getName());
                    servlets.add(servletInfo);
                } catch (Exception var9) {
                }
            }
            return servlets;
        } catch (Exception var10) {
            return null;
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
                // 存入缓存供卸载使用
                listenerMap.put(lid, l);
                HashMap info = new HashMap();
                info.put("listenerId", lid);
                info.put("className", l.getClass().getName());
                // bootstrap CL 加载的类（理论少见，但 agent / native 注入可能命中）getClassLoader() 返回 null
                ClassLoader cl = l.getClass().getClassLoader();
                info.put("classLoader", cl == null ? "<bootstrap>" : cl.getClass().getName());
                info.put("category", category);
                sink.add(info);
            }
        } catch (Exception e) {
            // 单个 getter 失败不影响另一个
            e.printStackTrace();
        }
    }

    public Boolean unLoadServlet(String contextName, String servletPattern) throws Exception {
        for (Object standardContext : getContexts()) {
            if (getFV(standardContext, "name").equals(contextName)) {
                HashMap servletMappings = (HashMap) getFV(standardContext, "servletMappings");
                String wrapperName = (String) servletMappings.get(servletPattern);

                Object wrapper = this.invokeMethod(standardContext, "findChild", new Class[]{String.class}, new Object[]{wrapperName});
                Class containerClass = Class.forName("org.apache.catalina.Container", false, standardContext.getClass().getClassLoader());
                if (wrapper != null) {
                    standardContext.getClass().getDeclaredMethod("removeChild", containerClass).invoke(standardContext, wrapper);
                }
                this.invokeMethod(standardContext, "removeServletMapping", new Class[]{String.class}, new Object[]{servletPattern});
            }
        }
        return true;
    }

    public Boolean unLoadFilter(String contextName, String filterName) throws Exception {
        for (Object standardContext : getContexts()) {
            if (!getFV(standardContext, "name").equals(contextName)) continue;

            // 1) 收集要删的 FilterMap 实例（按 filterName 匹配，支持同名多映射）
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

            // 2) 优先走公开 API removeFilterMap(FilterMap)，能同步刷掉内部 filterMaps + filterMapsArray 缓存
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
                // 老版本 Tomcat（< 8.5）FilterMap 在 org.apache.catalina.deploy 包
                try {
                    Class fmClass = Class.forName("org.apache.catalina.deploy.FilterMap",
                            false, standardContext.getClass().getClassLoader());
                    Method removeFilterMap = standardContext.getClass().getMethod("removeFilterMap", fmClass);
                    for (int i = 0; i < toRemove.size(); i++) {
                        removeFilterMap.invoke(standardContext, toRemove.get(i));
                    }
                    publicApiUsed = true;
                } catch (Throwable t2) {
                    // 公开 API 都拿不到，退化到字段直写
                }
            }

            // 3) 公开 API 走不通时，回退到改 filterMaps 字段（兼容老 Tomcat 6/7）
            if (!publicApiUsed) {
                Object[] newArr = (Object[]) Array.newInstance(filterMaps.getClass().getComponentType(), 0);
                try {
                    setFieldValue(standardContext, "filterMaps", kept.toArray(newArr));
                } catch (Exception ignored) {
                    // Tomcat 8.5.50+ 改用了内部 FilterMap.Array 包装
                    setFieldValue(getFV(standardContext, "filterMaps"), "array", kept.toArray(newArr));
                }
            }

            // 4) 删 FilterDef（防止下次 filterStart 时被重新挂上）
            try {
                Object filterDef = invokeMethod(standardContext, "findFilterDef",
                        new Class[]{String.class}, new Object[]{filterName});
                if (filterDef != null) {
                    Class fdClass = filterDef.getClass();
                    Method removeFilterDef = standardContext.getClass().getMethod("removeFilterDef", fdClass);
                    removeFilterDef.invoke(standardContext, filterDef);
                }
            } catch (Throwable ignored) {
                // 老版本可能没有 removeFilterDef，直接改 filterDefs 字段
                try {
                    HashMap filterDefs = (HashMap) getFV(standardContext, "filterDefs");
                    if (filterDefs != null) filterDefs.remove(filterName);
                } catch (Throwable ignored2) {
                }
            }

            // 5) 清空 filterConfigs 缓存——这是 Tomcat 实际的 filter chain 来源
            //    清掉后下一次请求会通过 filterStart()/createFilterConfig() 重建，确保即时生效
            try {
                HashMap filterConfigs = (HashMap) getFV(standardContext, "filterConfigs");
                if (filterConfigs != null) {
                    Object cfg = filterConfigs.remove(filterName);
                    // ApplicationFilterConfig 持有 filter 实例，调它的 release() 触发 filter.destroy()
                    if (cfg != null) {
                        try { invokeMethod(cfg, "release"); } catch (Throwable ignored) {}
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return true;
    }

    public Boolean unLoadValve(String valveId) throws Exception {
        Object pipeline = pipelineMap.get(valveId);
        Object valve = valveMap.get(valveId);

        if (pipeline == null || valve == null) {
            results.put("msg", "Valve not found in cache");
            return false;
        }

        // 重点：跨类加载器反射调用。必须使用 Pipeline 所在的 ClassLoader 加载 Valve 接口
        ClassLoader loader = pipeline.getClass().getClassLoader();
        Class<?> valveClass = Class.forName("org.apache.catalina.Valve", false, loader);

        // 找到 removeValve(org.apache.catalina.Valve) 方法
        Method removeMethod = pipeline.getClass().getMethod("removeValve", valveClass);
        removeMethod.setAccessible(true);
        removeMethod.invoke(pipeline, valve);

        // 清理已移除的缓存
        valveMap.remove(valveId);
        pipelineMap.remove(valveId);
        return true;
    }
    public Boolean unLoadListener(String listenerId) throws Exception {
        // 1. 从缓存中找到具体的 Listener 实例
        Object targetListener = listenerMap.get(listenerId);
        if (targetListener == null) {
            results.put("msg", "Listener instance not found, please refresh list.");
            return false;
        }

        String className = targetListener.getClass().getName();

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

            // 注意：同一个 Listener 实例如果同时实现了 Lifecycle + Event 两类接口，
            // Tomcat 会同时塞进两个列表（应用层 register 时是一次，但内部分发到两条链）。
            // 这里必须遍历完所有候选字段，逐个 remove，不能命中即 break，否则会留一份「半挂载」状态继续触发。
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
                        setFieldValue(standardContext, fieldName, newList.toArray());
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
                    // 老版本可能没有这个方法，忽略
                }

                listenerMap.remove(listenerId);
                results.put("msg", "Successfully uninstalled Listener: " + className);
                return true;
            }
        }
        return false;
    }


}
