package org.leo.core.component;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.*;

public class SpringFrameworkManageComponent implements Runnable {

    private HashMap params;
    private HashMap results;
    // 注意：曾经这里有 `private static Object context` 缓存，第一次 idle 拿不到就永远空。
    // 现在每次 invoke 都重新解析。
    private static HashMap interceptorMap=new HashMap();


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

    /**
     * 每次执行操作前现取 Spring ApplicationContext。
     * 跨方法调用之间不缓存：避免首次 idle 拿不到就永久卡死，
     * 也防止 contextRefreshed 后引用旧的过期 context。
     */
    private Object context;


    public void invoke() throws Exception {
        // 每次现取，不缓存——idle 环境下首次失败也不会污染后续调用
        context = getContext();
        String methodName = (String) params.get("methodName");
        if ("getFrameworkInfo".equals(methodName)) {
            results.put("frameworkInfo",getFrameworkInfo());
        }
        if ("unLoadController".equals(methodName)) {
            String mappingInfo= (String) params.get("mappingInfo");
            unLoadController(mappingInfo);
        }
        if ("unLoadInterceptor".equals(methodName)) {
            String interceptorId= (String) params.get("interceptorId");
            unLoadInterceptor(interceptorId);
        }
        results.put("code", 200);
    }


    private HashMap getFrameworkInfo() throws Exception {
        HashMap frameworkInfo=new HashMap();
        try {frameworkInfo.put("allController",getAllController());}catch (Exception e){}
        try {frameworkInfo.put("allMappedInterceptor",getAllMappedInterceptor());}catch (Exception e){}
        return frameworkInfo;
    }
    public ArrayList getAllController() throws Exception {
        Object abstractHandlerMapping = invokeMethod(context, "getBean", new Class[]{String.class}, new Object[]{"requestMappingHandlerMapping"});
        Object mappingRegistry;
        try {
            mappingRegistry=invokeMethod(abstractHandlerMapping,"getMappingRegistry");
        }catch (Exception e){
            mappingRegistry=getFV(abstractHandlerMapping,"mappingRegistry");
        }
        Map registry;
        try{
            registry= (Map) invokeMethod(mappingRegistry,"getRegistrations");
        }catch (Exception e){
            registry= (Map) getFV(mappingRegistry,"registry");
        }
        ArrayList allController=new ArrayList();
        Iterator s=registry.keySet().iterator();
        while (s.hasNext()){
            HashMap mapinfo=new HashMap();
            Object key=s.next();
            Object mappingRegistration=registry.get(key);
            mapinfo.put("mappingInfo",key.toString());
            mapinfo.put("mappingName",invokeMethod(mappingRegistration,"getMappingName"));
            try {
                Set directPaths= (Set) invokeMethod(mappingRegistration,"getDirectPaths");
                mapinfo.put("directPaths",directPaths.toArray());
            }catch (Exception e){
                List directUrls= (List) invokeMethod(mappingRegistration,"getDirectUrls");
                mapinfo.put("directPaths",directUrls.toArray());
            }

            Object handlerMethod=invokeMethod(mappingRegistration,"getHandlerMethod");
            mapinfo.put("description",handlerMethod.toString());
            allController.add(mapinfo);
        }
        return allController;
    }


    public void unLoadController(String mappingInfo) throws InvocationTargetException, NoSuchMethodException {
        Object abstractHandlerMapping = invokeMethod(context, "getBean", new Class[]{String.class}, new Object[]{"requestMappingHandlerMapping"});

        Object mappingRegistry = invokeMethod(abstractHandlerMapping, "getMappingRegistry");
        Map registry = (Map) invokeMethod(mappingRegistry, "getRegistrations");
        Iterator it = new ArrayList(registry.keySet()).iterator();

        while (it.hasNext()) {
            Object key = it.next();
            if (key.toString().equals(mappingInfo)) {invokeMethod(mappingRegistry, "unregister", new Class[]{Object.class}, new Object[]{key});
            }
        }
    }



    public ArrayList getAllMappedInterceptor() throws Exception {
        Object abstractHandlerMapping = invokeMethod(context, "getBean", new Class[]{String.class}, new Object[]{"requestMappingHandlerMapping"});
        Object[] adaptedInterceptors= (Object[]) invokeMethod(abstractHandlerMapping,"getAdaptedInterceptors");
        ArrayList AllMappedInterceptor=new ArrayList();
        for (Object adaptedInterceptor: adaptedInterceptors) {
            HashMap interceptorInfo=new HashMap();
            if (adaptedInterceptor.getClass().getName().equals("org.springframework.web.servlet.handler.MappedInterceptor")){
                Object pathPatterns=invokeMethod(adaptedInterceptor,"getPathPatterns");
                Object interceptor=invokeMethod(adaptedInterceptor,"getInterceptor");
                String interceptorId= String.valueOf(UUID.randomUUID());
                interceptorMap.put(interceptorId,adaptedInterceptor);
                Object[] excludePatterns= (Object[]) getFV(adaptedInterceptor,"excludePatterns");
                ArrayList excludePatternList=new ArrayList();
                if (excludePatterns!=null){
                    for (Object excludePattern:excludePatterns) {
                        excludePatternList.add(invokeMethod(excludePattern,"getPatternString"));
                    }
                }
                interceptorInfo.put("pathPatterns",pathPatterns);
                interceptorInfo.put("interceptorName",interceptor.getClass().getName());
                interceptorInfo.put("excludePatterns",excludePatternList.toArray());
                interceptorInfo.put("interceptorId",interceptorId);
                AllMappedInterceptor.add(interceptorInfo);
            }else {
                String interceptorId= String.valueOf(UUID.randomUUID());
                interceptorMap.put(interceptorId,adaptedInterceptor);
                interceptorInfo.put("pathPatterns",new String[]{"/*"});
                interceptorInfo.put("interceptorName",adaptedInterceptor.getClass().getName());
                interceptorInfo.put("excludePatterns",null);
                interceptorInfo.put("interceptorId",interceptorId);
                AllMappedInterceptor.add(interceptorInfo);
            }
        }
        return AllMappedInterceptor;
    }
    public void unLoadInterceptor(String interceptorId) throws Exception {
        Map handlerMappings= (Map) invokeMethod(context, "getBeansOfType", new Class[]{Class.class}, new Object[]{Class.forName("org.springframework.web.servlet.HandlerMapping",false,Thread.currentThread().getContextClassLoader())});
        Set keys=handlerMappings.keySet();
        for (Object key: keys) {
            Object handler=handlerMappings.get(key);
            ArrayList<Object> adaptedInterceptors = (ArrayList<Object>) getFV(handler, "adaptedInterceptors");
            if (adaptedInterceptors!=null){
                adaptedInterceptors.remove(interceptorMap.get(interceptorId));
            }
            ArrayList<Object> interceptors = (ArrayList<Object>) getFV(handler, "interceptors");
            if (interceptors!=null){
                interceptors.remove(interceptorMap.get(interceptorId));
            }
        }
        interceptorMap.remove(interceptorId);
    }

    public static Object getContext() {
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        Object context = null;

        // 路径 1：从当前请求线程绑定的 RequestAttributes 直接拿 ServletContext
        // 只在请求线程里有效（puppet 在请求线程上执行时才走得通），idle 时返回 null
        try {
            Object requestAttributes = invokeMethod(classLoader.loadClass("org.springframework.web.context.request.RequestContextHolder"), "getRequestAttributes");
            Object httprequest = invokeMethod(requestAttributes, "getRequest");
            Object session = invokeMethod(httprequest, "getSession");
            Object servletContext = invokeMethod(session, "getServletContext");
            context = invokeMethod(classLoader.loadClass("org.springframework.web.context.support.WebApplicationContextUtils"), "getWebApplicationContext", new Class[]{classLoader.loadClass("javax.servlet.ServletContext")}, new Object[]{servletContext});
        } catch (Exception e) {
        }

        // 路径 2（Spring 5.x）：LiveBeansView.applicationContexts
        // Spring 5.3 起 @Deprecated，6+ 移除；保留作为老版本兜底
        if (context == null) {
            try {
                LinkedHashSet applicationContexts = (LinkedHashSet) getFV(classLoader.loadClass("org.springframework.context.support.LiveBeansView").newInstance(), "applicationContexts");
                Object applicationContext = applicationContexts.iterator().next();
                if (classLoader.loadClass("org.springframework.web.context.WebApplicationContext").isAssignableFrom(applicationContext.getClass())) {
                    context = applicationContext;
                }
            } catch (Exception ignored) {
            }
        }

        // 路径 3：从 Tomcat StandardContext 反推 ServletContext → WebApplicationContext
        // 解决 idle Tomcat + 全局 CL 注入场景（路径 1/2 都失效时的最终兜底）
        if (context == null) {
            try {
                context = getContextFromTomcat(classLoader);
            } catch (Throwable ignored) {
            }
        }

        return context;
    }

    /**
     * 复用 TomcatCatalinaManageComponent 的扫描逻辑拿到所有 StandardContext，
     * 再从每个 context 的 servletContext 走 WebApplicationContextUtils.getWebApplicationContext()。
     *
     * 故意走反射而不是直接 import，避免 puppet 端没有 TomcatCatalinaManageComponent 时编译失败。
     */
    private static Object getContextFromTomcat(ClassLoader cl) throws Throwable {
        Class tomcatComp;
        try {
            tomcatComp = cl.loadClass("org.leo.core.component.TomcatCatalinaManageComponent");
        } catch (Throwable t) {
            // TomcatCatalinaManageComponent 还没被 puppet 端加载，自己扫一遍 MBean
            return getContextFromTomcatMbean(cl);
        }
        Method getCtx = tomcatComp.getDeclaredMethod("getContext");
        getCtx.setAccessible(true);
        HashSet standardContexts = (HashSet) getCtx.invoke(null);
        return resolveWebAppContext(standardContexts, cl);
    }

    /** TomcatCatalinaManageComponent 没加载时，自己走 PlatformMBeanServer 查 WebModule。 */
    private static Object getContextFromTomcatMbean(ClassLoader cl) throws Throwable {
        Class mfClass = Class.forName("java.lang.management.ManagementFactory");
        Object mbs = mfClass.getMethod("getPlatformMBeanServer").invoke(null);
        Class onClass = Class.forName("javax.management.ObjectName");
        Object pattern = onClass.getConstructor(String.class).newInstance("Catalina:j2eeType=WebModule,*");
        Method queryNames = mbs.getClass().getMethod("queryNames", onClass, Class.forName("javax.management.QueryExp"));
        Set names = (Set) queryNames.invoke(mbs, pattern, null);
        if (names == null || names.isEmpty()) return null;
        Method getAttribute = mbs.getClass().getMethod("getAttribute", onClass, String.class);
        HashSet ctxs = new HashSet();
        for (Object on : names) {
            try {
                Object ctx = getAttribute.invoke(mbs, on, "managedResource");
                if (ctx != null) ctxs.add(ctx);
            } catch (Throwable ignored) {
            }
        }
        return resolveWebAppContext(ctxs, cl);
    }

    /** 从一组 StandardContext 里找出第一个能解析出 WebApplicationContext 的。 */
    private static Object resolveWebAppContext(HashSet standardContexts, ClassLoader cl) throws Throwable {
        if (standardContexts == null || standardContexts.isEmpty()) return null;
        Class waCtxUtils = cl.loadClass("org.springframework.web.context.support.WebApplicationContextUtils");
        Class servletCtxClass = cl.loadClass("javax.servlet.ServletContext");
        Method getCtx = waCtxUtils.getMethod("getWebApplicationContext", servletCtxClass);
        for (Object stdCtx : standardContexts) {
            try {
                // StandardContext.getServletContext() 返回 ApplicationContext（Tomcat 的 facade）
                Method getServletCtx = stdCtx.getClass().getMethod("getServletContext");
                Object servletCtx = getServletCtx.invoke(stdCtx);
                if (servletCtx == null) continue;
                Object waCtx = getCtx.invoke(null, servletCtx);
                if (waCtx != null) {
                    return waCtx;
                }
            } catch (Throwable ignored) {
                // 这个 context 不是 Spring 应用，跳过
            }
        }
        return null;
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


    static synchronized Object invokeMethod(Object targetObject, String methodName) throws NoSuchMethodException, InvocationTargetException {
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

}
