package org.leo.core.component;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.*;

public class WeblogicCatalinaManageComponent implements Runnable {
    private HashMap params;
    private HashMap results;
    // 注意：曾经这里把 contexts 用 static 缓存（`static HashSet contexts = getContext()`），
    // 类加载时执行一次，第一次扫描到空就永远空。现在改为每次现扫。
    private static ClassLoader classLoader = Thread.currentThread().getContextClassLoader();


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
            results.put("catalinaInfo",getCatalinaInfo());
        }
        if ("unLoadFilter".equals(methodName)) {
            String contextName= (String) params.get("contextName");
            String filterName= (String) params.get("filterName");
            unLoadFilter(contextName,filterName);
        }
        if ("unLoadServlet".equals(methodName)) {
            String contextName= (String) params.get("contextName");
            String filterName= (String) params.get("servletPattern");
            unLoadServlet(contextName,filterName);
        }
        results.put("code", 200);
    }

    public ArrayList getCatalinaInfo() {
        HashSet contexts = getContext();
        ArrayList catalinaInfo=new ArrayList();
        for (Object context:contexts) {
            try {
                HashMap contextInfo=new HashMap();
                contextInfo.put("name",getFV(context,"contextName"));
                contextInfo.put("basePath",getFV(context,"contextPath"));
                contextInfo.put("workDir",getFV(context,"docroot"));
                contextInfo.put("allFilter",getAllFilter(context));
                contextInfo.put("allServlet",getAllServlet(context));
                catalinaInfo.add(contextInfo);
            }catch (Exception e){
                e.printStackTrace();
            }
        }
        return catalinaInfo;
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
                filterInfo.put("servletName",getFV(filterPattern, servletName));
                filterInfo.put("urlPatterns",urlPatterns);
                Object filter=filters.get(filterName);
                filterInfo.put("filterClass",getFV(filter, "filterClassName"));
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
                    servletInfo.put("url",invokeMethod(exactValue,"getPattern"));
                    Object servletStub=invokeMethod(exactValue,"getServletStub");
                    servletInfo.put("wrapperName",getFV(servletStub, "name"));
                    servletInfo.put("servletClass",invokeMethod(servletStub,"getClassName"));
                    servlets.add(servletInfo);
                } catch (Exception var9) {
                }
            }
            return servlets;
        } catch (Exception var10) {
            return null;
        }
    }

    public Boolean unLoadFilter(String contextName,String filterName) throws Exception {
        for (Object standardContext:getContext()){
            if (contextName.equals(getFV(standardContext,"contextName"))){
                Object filterManager = invokeMethod(standardContext, "getFilterManager");
                HashMap filters= (HashMap) getFV(filterManager,"filters");
                ArrayList filterPatternList= (ArrayList) getFV(filterManager,"filterPatternList");
                filters.remove(filterName);
                for (Object filterPattern:filterPatternList) {
                    if (invokeMethod(filterPattern,"getFilterName").equals(filterName)){
                        filterPatternList.remove(filterPattern);
                    }
                }
            }
        }
        return true;
    }

    public Boolean unLoadServlet(String contextName,String servletPattern) throws Exception {
        for (Object standardContext:getContext()){
            if (contextName.equals(getFV(standardContext,"contextName"))){
                Object servletMapping=getFV(standardContext,"servletMapping");
                Map matchMap = (Map)getFV(servletMapping, "matchMap");
                Object fullMatchNode=matchMap.get(servletPattern);
                Object exactValue=getFV(fullMatchNode,"exactValue");
                Object servletStub=invokeMethod(exactValue,"getServletStub");
                invokeMethod(standardContext,"removeServletStub",new Class[]{servletStub.getClass(),boolean.class},new Object[]{servletStub,false});
                invokeMethod(servletMapping,"removePattern",new Class[]{String.class},new Object[]{servletPattern});
            }
        }
        return true;
    }


    public static Object[] getContextsByMbean() throws Throwable {
        HashSet webappContexts = new HashSet();
        // 优先用类加载时记录的 CL（puppet 注入时拿到的 CL，通常是 weblogic.utils.classloaders.* 之一），
        // 不行就 fallback 到 system classloader 再试一次（覆盖 puppet 在执行线程上 CL 被替换的情况）
        ClassLoader[] candidates = new ClassLoader[]{classLoader, ClassLoader.getSystemClassLoader()};
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
                Object workEntry = getFV(thread, "workEntry");
                if (workEntry != null) {
                    try {
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
                    } catch (Throwable e) {

                    }
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
        // 前两条路径都依赖 ServerRuntime 静态实例或活跃请求线程，
        // idle 部署 / 普通 CL 注入时可能都拿不到。
        // 兜底走 PlatformMBeanServer 查 ApplicationRuntime + ServletRuntime 的 MBean，
        // 通过 MBean 反射出对应的 WebAppServletContext。
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
        ClassLoader[] candidates = new ClassLoader[]{classLoader, ClassLoader.getSystemClassLoader()};
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

    static synchronized Object invokeMethod(Object targetObject, String methodName) throws NoSuchMethodException, IllegalAccessException, InvocationTargetException {
        return invokeMethod(targetObject, methodName, new Class[0], new Object[0]);
    }

    public static synchronized Object invokeMethod(final Object obj, final String methodName, Class[] paramClazz, Object[] param) throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {
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

}
