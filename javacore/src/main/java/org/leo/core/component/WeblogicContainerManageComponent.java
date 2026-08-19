package org.leo.core.component;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.*;

public class WeblogicContainerManageComponent implements Runnable {
    private HashMap<String, Object> params;
    private HashMap<String, Object> results;

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
            results.put("contexts",inspectRuntime());
            results.put("code", Integer.valueOf(200));
            return;
        } else if ("removeFilter".equals(methodName)) {
            String contextName= (String) params.get("contextName");
            String filterName= (String) params.get("filterName");
            putOperationResult(removeFilter(contextName,filterName));
        } else if ("removeServlet".equals(methodName)) {
            String contextName= (String) params.get("contextName");
            String filterName= (String) params.get("servletPattern");
            putOperationResult(removeServlet(contextName,filterName));
        } else if ("removeListener".equals(methodName)) {
            putOperationResult(removeListener((String) params.get("listenerId")));
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
    }

    public ArrayList inspectRuntime() {
        HashSet contexts = getContext();
        ArrayList runtimeContexts=new ArrayList();
        for (Object context:contexts) {
            try {
                HashMap contextInfo=new HashMap();
                contextInfo.put("name",String.valueOf(getFV(context,"contextName")));
                contextInfo.put("basePath",String.valueOf(getFV(context,"contextPath")));
                contextInfo.put("workDir",String.valueOf(getFV(context,"docroot")));
                contextInfo.put("allFilter",getAllFilter(context));
                contextInfo.put("allServlet",getAllServlet(context));
                contextInfo.put("allListener",getAllListener(context));
                runtimeContexts.add(contextInfo);
            } catch (Exception ignored) {
                // 单个 Context 解析失败不影响其余上下文，也不向目标日志泄漏堆栈。
            }
        }
        return runtimeContexts;
    }

    public ArrayList getAllFilter(Object standardContext) {
        try {
            Object filterManager = invokeMethod(standardContext, "getFilterManager");
            HashMap filters= (HashMap) getFV(filterManager,"filters");

            ArrayList filterPatternList= (ArrayList) getFV(filterManager,"filterPatternList");
            ArrayList allFilterInfo=new ArrayList();


            for (Object filterPattern:filterPatternList) {
                HashMap filterInfo=new HashMap();
                String filterName= (String) invokeMethod(filterPattern,"getFilterName");
                String servletName= (String) invokeMethod(filterPattern,"getServletName");
                Object map=invokeMethod(filterPattern,"getMap");
                Object urlPatterns=invokeMethod(map,"keys");
                filterInfo.put("filterName",filterName);
                filterInfo.put("servletName", servletName);
                filterInfo.put("urlPatterns",toList(urlPatterns));
                Object filter=filters.get(filterName);
                filterInfo.put("filterClassName",String.valueOf(getFV(filter, "filterClassName")));
                allFilterInfo.add(filterInfo);
            }
            return allFilterInfo;
        } catch (Exception var6) {
            return null;
        }
    }

    public ArrayList getAllServlet(Object standardContext) {
        try {
            Object servletMapping=getFV(standardContext,"servletMapping");
            Map matchMap = (Map)getFV(servletMapping, "matchMap");
            Iterator s = matchMap.keySet().iterator();
            ArrayList servlets=new ArrayList();
            while (s.hasNext()) {
                try {
                    String key = (String)s.next();
                    Object fullMatchNode = matchMap.get(key);
                    Object exactValue=getFV(fullMatchNode,"exactValue");
                    if (exactValue==null){
                        exactValue=getFV(fullMatchNode,"patternValue");
                    }
                    HashMap servletInfo=new HashMap();
                    servletInfo.put("url",String.valueOf(invokeMethod(exactValue,"getPattern")));
                    Object servletStub=invokeMethod(exactValue,"getServletStub");
                    servletInfo.put("wrapperName",String.valueOf(getFV(servletStub, "name")));
                    servletInfo.put("servletClass",String.valueOf(invokeMethod(servletStub,"getClassName")));
                    servlets.add(servletInfo);
                } catch (Exception var9) {
                }
            }
            return servlets;
        } catch (Exception var10) {
            return null;
        }
    }

    /**
     * 收集 WebLogic WebAppServletContext 上注册的所有 Listener。
     *
     * <p>WebLogic 把 Listener 拆得比 Tomcat 更细，按事件类型分别存：
     * <ul>
     *   <li>{@code _servletContextListeners} / {@code _servletContextAttListeners}（lifecycle）</li>
     *   <li>{@code _sessionListeners} / {@code _sessionAttListeners} / {@code _sessionIdListeners}（session）</li>
     *   <li>{@code _requestListeners} / {@code _requestAttListeners}（request）</li>
     *   <li>{@code _asyncListeners}（async）</li>
     * </ul>
     * 这些字段挂在 {@code WebAppServletContext} 自身或其内部的 {@code eventsManager}/{@code _eventsManager} 上，
     * WebLogic 10.3 / 12.x / 14.x 的字段位置与命名前缀不同，通过画像字段读取。
     */
    public ArrayList getAllListener(Object standardContext) {
        ArrayList listeners = new ArrayList();
        // Listener 可能位于 context 或 eventsManager。
        Object[] holders = new Object[]{standardContext, tryGetField(standardContext, "eventsManager"), tryGetField(standardContext, "_eventsManager")};

        // 字段名与分类成对定义。
        String[][] fieldDefs = new String[][]{
                {"_servletContextListeners", "context"},
                {"servletContextListeners", "context"},
                {"_servletContextAttListeners", "context-attr"},
                {"servletContextAttListeners", "context-attr"},
                {"_sessionListeners", "session"},
                {"sessionListeners", "session"},
                {"_sessionAttListeners", "session-attr"},
                {"sessionAttListeners", "session-attr"},
                {"_sessionIdListeners", "session-id"},
                {"sessionIdListeners", "session-id"},
                {"_requestListeners", "request"},
                {"requestListeners", "request"},
                {"_requestAttListeners", "request-attr"},
                {"requestAttListeners", "request-attr"},
                {"_asyncListeners", "async"},
                {"asyncListeners", "async"}
        };

        // 已采集实例去重（同一 listener 可能同时落在多个分类）
        Set<String> seen = new HashSet();
        for (int hi = 0; hi < holders.length; hi++) {
            Object holder = holders[hi];
            if (holder == null) continue;
            for (int fi = 0; fi < fieldDefs.length; fi++) {
                Object listObj = tryGetField(holder, fieldDefs[fi][0]);
                if (listObj == null) continue;
                collectWeblogicListeners(listObj, holder, fieldDefs[fi][0],
                        fieldDefs[fi][1], listeners, seen);
            }
        }
        return listeners;
    }

    private static Object tryGetField(Object holder, String fieldName) {
        try {
            return getFV(holder, fieldName);
        } catch (Throwable t) {
            return null;
        }
    }

    private static void collectWeblogicListeners(Object listObj, Object holder, String fieldName,
                                                 String category, ArrayList sink, Set<String> seen) {
        List<Object> list;
        if (listObj instanceof List) {
            list = (List<Object>) listObj;
        } else if (listObj.getClass().isArray()) {
            list = new ArrayList(Arrays.asList((Object[]) listObj));
        } else {
            return;
        }

        for (int i = 0; i < list.size(); i++) {
            Object l = list.get(i);
            if (l == null) continue;
            String lid = Integer.toHexString(System.identityHashCode(l));
            if (!seen.add(lid)) continue;  // 同实例只记一次
            HashMap info = new HashMap();
            info.put("listenerId", lid);
            info.put("className", l.getClass().getName());
            // bootstrap CL 加载的类 getClassLoader() 可能返回 null
            ClassLoader cl = l.getClass().getClassLoader();
            info.put("classLoader", cl == null ? "<bootstrap>" : cl.getClass().getName());
            info.put("category", category);
            sink.add(info);
        }
    }

    public Boolean removeListener(String listenerId) throws Exception {
        String[] fieldNames = new String[]{
                "_servletContextListeners", "servletContextListeners",
                "_servletContextAttListeners", "servletContextAttListeners",
                "_sessionListeners", "sessionListeners",
                "_sessionAttListeners", "sessionAttListeners",
                "_sessionIdListeners", "sessionIdListeners",
                "_requestListeners", "requestListeners",
                "_requestAttListeners", "requestAttListeners",
                "_asyncListeners", "asyncListeners"
        };
        Iterator contexts = getContext().iterator();
        while (contexts.hasNext()) {
            Object context = contexts.next();
            Object[] holders = new Object[]{context, tryGetField(context, "eventsManager"),
                    tryGetField(context, "_eventsManager")};
            for (int hi = 0; hi < holders.length; hi++) {
                Object holder = holders[hi];
                if (holder == null) continue;
                for (int fi = 0; fi < fieldNames.length; fi++) {
                    if (removeListenerById(holder, fieldNames[fi], listenerId)) {
                        return Boolean.TRUE;
                    }
                }
            }
        }
        return Boolean.FALSE;
    }

    private boolean removeListenerById(Object holder, String fieldName, String listenerId)
            throws Exception {
        Object collection = tryGetField(holder, fieldName);
        if (collection instanceof List) {
            List listeners = (List) collection;
            for (int i = 0; i < listeners.size(); i++) {
                Object listener = listeners.get(i);
                if (listener != null && listenerId.equals(
                        Integer.toHexString(System.identityHashCode(listener)))) {
                    return listeners.remove(listener);
                }
            }
            return false;
        }
        if (collection == null || !collection.getClass().isArray()) return false;
        int length = Array.getLength(collection);
        ArrayList kept = new ArrayList();
        boolean removed = false;
        for (int i = 0; i < length; i++) {
            Object value = Array.get(collection, i);
            if (value != null && listenerId.equals(
                    Integer.toHexString(System.identityHashCode(value)))) removed = true;
            else kept.add(value);
        }
        if (!removed) return false;
        Object replacement = Array.newInstance(collection.getClass().getComponentType(), kept.size());
        for (int i = 0; i < kept.size(); i++) Array.set(replacement, i, kept.get(i));
        Field field = getF(holder, fieldName);
        field.setAccessible(true);
        field.set(holder, replacement);
        return true;
    }

    public Boolean removeFilter(String contextName,String filterName) throws Exception {
        for (Object standardContext:getContext()){
            if (contextName.equals(getFV(standardContext,"contextName"))){
                Object filterManager = invokeMethod(standardContext, "getFilterManager");
                return Boolean.valueOf(removeFilter(filterManager, filterName));
            }
        }
        return Boolean.FALSE;
    }

    private boolean removeFilter(Object filterManager, String filterName) throws Exception {
        HashMap filters = (HashMap) getFV(filterManager,"filters");
        ArrayList filterPatternList = (ArrayList) getFV(filterManager,"filterPatternList");
        boolean removed = filters.remove(filterName) != null;
        Iterator iterator = filterPatternList.iterator();
        while (iterator.hasNext()) {
            Object filterPattern = iterator.next();
            if (filterName.equals(invokeMethod(filterPattern,"getFilterName"))) {
                iterator.remove();
                removed = true;
            }
        }
        return removed && !filters.containsKey(filterName);
    }

    public Boolean removeServlet(String contextName,String servletPattern) throws Exception {
        for (Object standardContext:getContext()){
            if (contextName.equals(getFV(standardContext,"contextName"))){
                Object servletMapping=getFV(standardContext,"servletMapping");
                Map matchMap = (Map)getFV(servletMapping, "matchMap");
                Object fullMatchNode=matchMap.get(servletPattern);
                if (fullMatchNode == null) continue;
                Object exactValue=getFV(fullMatchNode,"exactValue");
                if (exactValue == null) exactValue = getFV(fullMatchNode, "patternValue");
                if (exactValue == null) continue;
                Object servletStub=invokeMethod(exactValue,"getServletStub");
                invokeMethod(standardContext,"removeServletStub",new Class[]{servletStub.getClass(),boolean.class},new Object[]{servletStub,false});
                invokeMethod(servletMapping,"removePattern",new Class[]{String.class},new Object[]{servletPattern});
                return Boolean.valueOf(!matchMap.containsKey(servletPattern));
            }
        }
        return Boolean.FALSE;
    }


    public static Object[] getContextsByMbean() throws Throwable {
        HashSet webappContexts = new HashSet();
        // 同时检查当前线程与系统类加载器中的 RuntimeAccess。
        ClassLoader[] candidates = new ClassLoader[]{
                Thread.currentThread().getContextClassLoader(), ClassLoader.getSystemClassLoader()};
        Class serverRuntimeClass = null;
        Class webAppServletContextClass = null;
        for (int i = 0; i < candidates.length; i++) {
            if (candidates[i] == null) continue;
            try {
                serverRuntimeClass = Class.forName("weblogic.t3.srvr.ServerRuntime", false, candidates[i]);
                webAppServletContextClass = Class.forName("weblogic.servlet.internal.WebAppServletContext", false, candidates[i]);
                break;
            } catch (Throwable t) {
                // 试下一个 CL
            }
        }
        if (serverRuntimeClass == null || webAppServletContextClass == null) {
            return webappContexts.toArray();
        }
        Method theOneMethod = serverRuntimeClass.getMethod("theOne");
        theOneMethod.setAccessible(true);
        Object serverRuntime = theOneMethod.invoke(null);

        Method getApplicationRuntimesMethod = serverRuntime.getClass().getMethod("getApplicationRuntimes");
        getApplicationRuntimesMethod.setAccessible(true);
        Object applicationRuntimes = getApplicationRuntimesMethod.invoke(serverRuntime);
        int applicationRuntimeSize = Array.getLength(applicationRuntimes);
        for (int i = 0; i < applicationRuntimeSize; i++) {
            Object applicationRuntime = Array.get(applicationRuntimes, i);

            try {
                Method getComponentRuntimesMethod = applicationRuntime.getClass().getMethod("getComponentRuntimes");
                Object componentRuntimes = getComponentRuntimesMethod.invoke(applicationRuntime);
                int componentRuntimeSize = Array.getLength(componentRuntimes);
                for (int j = 0; j < componentRuntimeSize; j++) {
                    Object context = getFV(Array.get(componentRuntimes, j), "context");
                    if (webAppServletContextClass.isInstance(context)) {
                        webappContexts.add(context);
                    }
                }
            } catch (Throwable e) {

            }

            try {
                Set childrenSet = (Set) getFV(applicationRuntime, "children");
                Iterator iterator = childrenSet.iterator();

                while (iterator.hasNext()) {
                    Object componentRuntime = iterator.next();
                    try {
                        Object context = getFV(componentRuntime, "context");
                        if (webAppServletContextClass.isInstance(context)) {
                            webappContexts.add(context);
                        }
                    } catch (Throwable e) {

                    }
                }

            } catch (Throwable e) {

            }
        }
        return webappContexts.toArray();
    }

    public static Object[] getContextsByThreads() throws Throwable {
        HashSet webappContexts = new HashSet();
        ThreadGroup threadGroup = Thread.currentThread().getThreadGroup();
        int threadCount = threadGroup.activeCount();
        Thread[] threads = new Thread[threadCount];
        threadGroup.enumerate(threads);
        for (int i = 0; i < threadCount; i++) {
            Thread thread = threads[i];
            if (thread != null) {
                try {
                    Object workEntry = getFV(thread, "workEntry");
                    if (workEntry != null) {
                        Object context = null;
                        Object connectionHandler = getFV(workEntry, "connectionHandler");
                        if (connectionHandler != null) {
                            Object request = getFV(connectionHandler, "request");
                            if (request != null) {
                                context = getFV(request, "context");
                            }
                        }
                        if (context == null) {
                            context = getFV(workEntry, "context");
                        }

                        if (context != null) {
                            webappContexts.add(context);
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        return webappContexts.toArray();
    }

    public static HashSet getContext() {
        HashSet webappContexts = new HashSet();
        try {
            webappContexts.addAll(Arrays.asList(getContextsByMbean()));
        } catch (Throwable e) {

        }
        try {
            webappContexts.addAll(Arrays.asList(getContextsByThreads()));
        } catch (Throwable e) {

        }
        // PlatformMBeanServer 提供 ApplicationRuntime 与 ServletRuntime 视图。
        if (webappContexts.isEmpty()) {
            try {
                webappContexts.addAll(Arrays.asList(getContextsByPlatformMbean()));
            } catch (Throwable e) {

            }
        }
        return webappContexts;
    }

    /**
     * 通过 PlatformMBeanServer 查 Weblogic 的 ApplicationRuntime / ServletRuntime MBean，
     * 从中反推 WebAppServletContext。
     *
     * <p>Weblogic 把每个应用注册成 ObjectName 形如 {@code com.bea:Name=*,Type=ApplicationRuntime,*}，
     * 该 MBean 持有的 implementation/applicationRuntime 字段就是 ApplicationRuntime 实例，
     * 后续的导航方式和 {@link #getContextsByMbean()} 一致。
     */
    public static Object[] getContextsByPlatformMbean() throws Throwable {
        HashSet webappContexts = new HashSet();

        // 选 ClassLoader：先用 puppet 注入时记录的 CL，失败就走 system CL
        ClassLoader[] candidates = new ClassLoader[]{
                Thread.currentThread().getContextClassLoader(), ClassLoader.getSystemClassLoader()};
        Class webAppServletContextClass = null;
        for (int i = 0; i < candidates.length; i++) {
            if (candidates[i] == null) continue;
            try {
                webAppServletContextClass = Class.forName(
                        "weblogic.servlet.internal.WebAppServletContext", false, candidates[i]);
                break;
            } catch (Throwable t) {
                // 试下一个 CL
            }
        }
        if (webAppServletContextClass == null) return webappContexts.toArray();

        Class mfClass = Class.forName("java.lang.management.ManagementFactory");
        Object mbs = mfClass.getMethod("getPlatformMBeanServer").invoke(null);
        Class onClass = Class.forName("javax.management.ObjectName");
        Object pattern = onClass.getConstructor(String.class)
                .newInstance("com.bea:Type=ApplicationRuntime,*");
        Method queryNames = mbs.getClass().getMethod("queryNames", onClass,
                Class.forName("javax.management.QueryExp"));
        Set names = (Set) queryNames.invoke(mbs, pattern, null);
        if (names == null) return webappContexts.toArray();

        Method getAttribute = mbs.getClass().getMethod("getAttribute", onClass, String.class);
        Iterator iter = names.iterator();
        while (iter.hasNext()) {
            Object on = iter.next();
            Object appRuntime = null;
            // Weblogic MBean 通过几个常见属性暴露 wrapped 实例
            String[] attrCandidates = new String[]{"Implementation", "ApplicationRuntime"};
            for (int i = 0; i < attrCandidates.length && appRuntime == null; i++) {
                try {
                    appRuntime = getAttribute.invoke(mbs, on, attrCandidates[i]);
                } catch (Throwable ignored) {
                }
            }
            if (appRuntime == null) continue;

            // 复用 getContextsByMbean 的导航逻辑：componentRuntimes / children
            try {
                Method getComponentRuntimes = appRuntime.getClass().getMethod("getComponentRuntimes");
                Object componentRuntimes = getComponentRuntimes.invoke(appRuntime);
                int size = Array.getLength(componentRuntimes);
                for (int j = 0; j < size; j++) {
                    try {
                        Object context = getFV(Array.get(componentRuntimes, j), "context");
                        if (webAppServletContextClass.isInstance(context)) {
                            webappContexts.add(context);
                        }
                    } catch (Throwable ignored) {
                    }
                }
            } catch (Throwable ignored) {
            }

            try {
                Set children = (Set) getFV(appRuntime, "children");
                Iterator cIter = children.iterator();
                while (cIter.hasNext()) {
                    try {
                        Object context = getFV(cIter.next(), "context");
                        if (webAppServletContextClass.isInstance(context)) {
                            webappContexts.add(context);
                        }
                    } catch (Throwable ignored) {
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return webappContexts.toArray();
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

    public static Object invokeMethod(final Object obj, final String methodName, Class[] paramClazz, Object[] param) throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {
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
