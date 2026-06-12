package org.leo.core.component;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.*;

public class TomcatCatalinaManageComponent implements Runnable {
    private static HashSet contexts = null;
    private HashMap params;
    private HashMap results;

    private static HashSet getContexts() {
        if (contexts == null) {
            contexts = getContext();
        }
        return contexts;
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
                // 适配 v5/v6/7/8
                if (thread.getName().contains("ContainerBackgroundProcessor") && context == null) {
                    HashMap childrenMap = (HashMap) getFV(getFV(getFV(thread, "target"), "this$0"), "children");
                    // 原: map.get("localhost")
                    // 之前没有对 StandardHost 进行遍历，只考虑了 localhost 的情况，如果目标自定义了 host,则会获取不到对应的 context，导致注入失败
                    for (Object key : childrenMap.keySet()) {
                        HashMap children = (HashMap) getFV(childrenMap.get(key), "children");
                        // 原: context = children.get("");
                        // 之前没有对context map进行遍历，只考虑了 ROOT context 存在的情况，如果目标tomcat不存在 ROOT context，则会注入失败
                        for (Object key1 : children.keySet()) {
                            context = children.get(key1);
                            if (context != null && context.getClass().getName().contains("StandardContext"))
                                contexts.add(context);
                            // 兼容 spring boot 2.x embedded tomcat
                            if (context != null && context.getClass().getName().contains("TomcatEmbeddedContext"))
                                contexts.add(context);
                        }
                    }
                }
                // 适配 tomcat v9
                else if (thread.getContextClassLoader() != null && (thread.getContextClassLoader().getClass().toString().contains("ParallelWebappClassLoader") || thread.getContextClassLoader().getClass().toString().contains("TomcatEmbeddedWebappClassLoader"))) {
                    context = getFV(getFV(thread.getContextClassLoader(), "resources"), "context");
                    if (context != null && context.getClass().getName().contains("StandardContext"))
                        contexts.add(context);
                    if (context != null && context.getClass().getName().contains("TomcatEmbeddedContext"))
                        contexts.add(context);
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return contexts;
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
        try {
            // 获取运行时实例列表
            Object objects = invokeMethod(standardContext,"getApplicationEventListeners");
            if (objects instanceof List){
                List listenerList = (List) objects;
                for (Object l : listenerList) {
                    String lid = Integer.toHexString(System.identityHashCode(l));
                    // 存入缓存供卸载使用
                    listenerMap.put(lid,l);
                    HashMap info = new HashMap();
                    info.put("listenerId", lid);
                    info.put("className", l.getClass().getName());
                    info.put("classLoader", l.getClass().getClassLoader().getClass().getName());
                    listeners.add(info);
                }
            }else {
                List listenerList = new ArrayList(Arrays.asList(((Object[]) objects)));
                for (Object l : listenerList) {
                    String lid = Integer.toHexString(System.identityHashCode(l));
                    // 存入缓存供卸载使用
                    listenerMap.put(lid,l);
                    HashMap info = new HashMap();
                    info.put("listenerId", lid);
                    info.put("className", l.getClass().getName());
                    info.put("classLoader", l.getClass().getClassLoader().getClass().getName());
                    listeners.add(info);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return listeners;
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
            if (getFV(standardContext, "name").equals(contextName)) {
                ArrayList arrayList = new ArrayList();
                Object[] filterMaps = (Object[]) this.invokeMethod(standardContext, "findFilterMaps");
                if (filterMaps.length >= 1) {
                    for (int i = 0; i < filterMaps.length; ++i) {
                        Object filterMap = filterMaps[i];
                        if (!filterName.equals(getFV(filterMap, "filterName"))) {
                            arrayList.add(filterMap);
                        }
                    }
                    try {
                        setFieldValue(standardContext, "filterMaps", arrayList.toArray((Object[]) Array.newInstance(filterMaps.getClass().getComponentType(), 0)));
                    } catch (Exception var7) {
                        setFieldValue(getFV(standardContext, "filterMaps"), "array", arrayList.toArray((Object[]) Array.newInstance(filterMaps.getClass().getComponentType(), 0)));
                    }
                }
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
        boolean totallyRemoved = false;

        Iterator contextIt = getContexts().iterator();
        while (contextIt.hasNext()) {
            Object standardContext = contextIt.next();
            // 获取该 Context 的实例列表
            Object listObj = getFV(standardContext, "applicationEventListenersList");

            if (listObj instanceof List) {
                List list = (List) listObj;
                // JDK 5 遍历移除逻辑
                Iterator it = list.iterator();
                while (it.hasNext()) {
                    Object l = it.next();
                    if (l == targetListener) { // 精确匹配内存引用
                        // 注意：如果 Tomcat 使用的是 CopyOnWriteArrayList，直接 it.remove() 可能会报错
                        // 此时建议通过 list.remove(l)
                        list.remove(l);
                        totallyRemoved = true;
                        break;
                    }
                }
            }
            // 兼容极老版本 Tomcat 使用 Object 数组的情况
            else if (listObj != null && listObj.getClass().isArray()) {
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
                    setFieldValue(standardContext, "applicationEventListenersList", newList.toArray());
                    totallyRemoved = true;
                }
            }

            if (totallyRemoved) {
                // 3. 同时尝试从配置定义中移除该类名（防止重启复活）
                try {
                    // 加载对应的类以匹配方法签名
                    invokeMethod(standardContext, "removeApplicationListener",
                            new Class[]{String.class}, new Object[]{className});
                } catch (Exception e) {
                    // 忽略找不到配置定义的情况
                }

                listenerMap.remove(listenerId);
                results.put("msg", "Successfully uninstalled Listener: " + className);
                return true;
            }
        }
        return false;
    }


}
