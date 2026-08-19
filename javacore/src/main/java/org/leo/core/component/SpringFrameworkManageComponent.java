package org.leo.core.component;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.*;

public class SpringFrameworkManageComponent implements Runnable {

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

    private Object context;

    public void invoke() throws Exception {
        Object methodObj = params.get("methodName");
        if (!(methodObj instanceof String)) {
            results.put("code", Integer.valueOf(400));
            results.put("msg", "methodName required");
            return;
        }
        String methodName = (String) methodObj;
        if (!"getFrameworkInfo".equals(methodName)
                && !"removeController".equals(methodName)
                && !"removeInterceptor".equals(methodName)) {
            results.put("code", Integer.valueOf(400));
            results.put("msg", "未知 methodName: " + methodName);
            return;
        }
        context = getContext();
        if ("getFrameworkInfo".equals(methodName)) {
            results.put("frameworkInfo",getFrameworkInfo());
            results.put("code", Integer.valueOf(200));
            return;
        } else if ("removeController".equals(methodName)) {
            String mappingInfo= (String) params.get("mappingInfo");
            putOperationResult(removeController(mappingInfo));
        } else if ("removeInterceptor".equals(methodName)) {
            String interceptorId= (String) params.get("interceptorId");
            putOperationResult(removeInterceptor(interceptorId));
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


    private HashMap getFrameworkInfo() throws Exception {
        HashMap frameworkInfo=new HashMap();
        frameworkInfo.put("allController", new ArrayList());
        frameworkInfo.put("allMappedInterceptor", new ArrayList());
        frameworkInfo.put("contextAvailable", Boolean.valueOf(context != null));
        if (context != null) {
            frameworkInfo.put("contextClassName", context.getClass().getName());
        }
        try {frameworkInfo.put("allController",getAllController());}catch (Exception ignored){}
        try {frameworkInfo.put("allMappedInterceptor",getAllMappedInterceptor());}catch (Exception ignored){}
        return frameworkInfo;
    }
    public ArrayList getAllController() throws Exception {
        Object abstractHandlerMapping = invokeMethod(context, "getBean", new Class[]{String.class}, new Object[]{"requestMappingHandlerMapping"});
        Object mappingRegistry = getMappingRegistry(abstractHandlerMapping);
        Map registry = getRegistrations(mappingRegistry);
        ArrayList allController=new ArrayList();
        Iterator s=registry.keySet().iterator();
        while (s.hasNext()){
            HashMap mapinfo=new HashMap();
            Object key=s.next();
            Object mappingRegistration=registry.get(key);
            mapinfo.put("mappingInfo",key.toString());
            mapinfo.put("mappingName",String.valueOf(invokeMethod(mappingRegistration,"getMappingName")));
            try {
                Set directPaths= (Set) invokeMethod(mappingRegistration,"getDirectPaths");
                mapinfo.put("directPaths",new ArrayList(directPaths));
            }catch (Exception e){
                List directUrls= (List) invokeMethod(mappingRegistration,"getDirectUrls");
                mapinfo.put("directPaths",new ArrayList(directUrls));
            }

            Object handlerMethod=invokeMethod(mappingRegistration,"getHandlerMethod");
            mapinfo.put("description",handlerMethod.toString());
            allController.add(mapinfo);
        }
        return allController;
    }


    public Boolean removeController(String mappingInfo) throws Exception {
        if (context == null) return Boolean.FALSE;
        Object abstractHandlerMapping = invokeMethod(context, "getBean", new Class[]{String.class}, new Object[]{"requestMappingHandlerMapping"});

        Object mappingRegistry = getMappingRegistry(abstractHandlerMapping);
        Map registry = getRegistrations(mappingRegistry);
        Iterator it = new ArrayList(registry.keySet()).iterator();
        boolean removed = false;

        while (it.hasNext()) {
            Object key = it.next();
            if (key.toString().equals(mappingInfo)) {
                invokeMethod(mappingRegistry, "unregister", new Class[]{key.getClass()}, new Object[]{key});
                removed = true;
            }
        }
        if (!removed) return Boolean.FALSE;
        Iterator verify = getRegistrations(mappingRegistry).keySet().iterator();
        while (verify.hasNext()) {
            if (String.valueOf(verify.next()).equals(mappingInfo)) return Boolean.FALSE;
        }
        return Boolean.TRUE;
    }

    private Object getMappingRegistry(Object handlerMapping) throws Exception {
        try {
            return invokeMethod(handlerMapping, "getMappingRegistry");
        } catch (Exception ignored) {
            return getFV(handlerMapping, "mappingRegistry");
        }
    }

    private Map getRegistrations(Object mappingRegistry) throws Exception {
        try {
            return (Map) invokeMethod(mappingRegistry, "getRegistrations");
        } catch (Exception ignored) {
            return (Map) getFV(mappingRegistry, "registry");
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
                String interceptorId= Integer.toHexString(System.identityHashCode(adaptedInterceptor));
                Object[] excludePatterns= (Object[]) getFV(adaptedInterceptor,"excludePatterns");
                ArrayList excludePatternList=new ArrayList();
                if (excludePatterns!=null){
                    for (Object excludePattern:excludePatterns) {
                        excludePatternList.add(invokeMethod(excludePattern,"getPatternString"));
                    }
                }
                interceptorInfo.put("pathPatterns",patternStrings(pathPatterns));
                interceptorInfo.put("interceptorName",interceptor.getClass().getName());
                interceptorInfo.put("excludePatterns",excludePatternList);
                interceptorInfo.put("interceptorId",interceptorId);
                AllMappedInterceptor.add(interceptorInfo);
            }else {
                String interceptorId= Integer.toHexString(System.identityHashCode(adaptedInterceptor));
                interceptorInfo.put("pathPatterns",java.util.Collections.singletonList("/*"));
                interceptorInfo.put("interceptorName",adaptedInterceptor.getClass().getName());
                interceptorInfo.put("excludePatterns",null);
                interceptorInfo.put("interceptorId",interceptorId);
                AllMappedInterceptor.add(interceptorInfo);
            }
        }
        return AllMappedInterceptor;
    }
    public Boolean removeInterceptor(String interceptorId) throws Exception {
        if (context == null) return Boolean.FALSE;
        boolean removed = false;
        Map handlerMappings= (Map) invokeMethod(context, "getBeansOfType", new Class[]{Class.class}, new Object[]{Class.forName("org.springframework.web.servlet.HandlerMapping",false,Thread.currentThread().getContextClassLoader())});
        Set keys=handlerMappings.keySet();
        for (Object key: keys) {
            Object handler=handlerMappings.get(key);
            ArrayList<Object> adaptedInterceptors = (ArrayList<Object>) getFV(handler, "adaptedInterceptors");
            if (adaptedInterceptors!=null){
                removed = removeInterceptorById(adaptedInterceptors, interceptorId) || removed;
            }
            ArrayList<Object> interceptors = (ArrayList<Object>) getFV(handler, "interceptors");
            if (interceptors!=null){
                removed = removeInterceptorById(interceptors, interceptorId) || removed;
            }
        }
        return Boolean.valueOf(removed);
    }

    private ArrayList patternStrings(Object value) {
        ArrayList answer = new ArrayList();
        if (value == null) return answer;
        if (value instanceof Iterable) {
            Iterator iterator = ((Iterable) value).iterator();
            while (iterator.hasNext()) answer.add(patternString(iterator.next()));
        } else if (value instanceof Enumeration) {
            Enumeration enumeration = (Enumeration) value;
            while (enumeration.hasMoreElements()) answer.add(patternString(enumeration.nextElement()));
        } else if (value.getClass().isArray()) {
            int length = java.lang.reflect.Array.getLength(value);
            for (int i = 0; i < length; i++) answer.add(patternString(java.lang.reflect.Array.get(value, i)));
        } else {
            answer.add(patternString(value));
        }
        return answer;
    }

    private String patternString(Object value) {
        if (value == null) return "";
        try { return String.valueOf(invokeMethod(value, "getPatternString")); }
        catch (Throwable ignored) { return String.valueOf(value); }
    }

    private boolean removeInterceptorById(ArrayList interceptors, String interceptorId) {
        Iterator iterator = interceptors.iterator();
        while (iterator.hasNext()) {
            Object value = iterator.next();
            if (value != null && interceptorId.equals(
                    Integer.toHexString(System.identityHashCode(value)))) {
                iterator.remove();
                return true;
            }
        }
        return false;
    }

    public static Object getContext() {
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        Object context = null;

        // 路径 0：标准 ContextLoader 静态入口，覆盖大多数独立 Servlet 容器。
        try {
            context = invokeMethod(classLoader.loadClass(
                    "org.springframework.web.context.ContextLoader"),
                    "getCurrentWebApplicationContext");
        } catch (Exception ignored) {
        }

        // 路径 1：从当前请求线程绑定的 RequestAttributes 获取 ServletContext。
        if (context == null) try {
            Object requestAttributes = invokeMethod(classLoader.loadClass("org.springframework.web.context.request.RequestContextHolder"), "getRequestAttributes");
            Object httprequest = invokeMethod(requestAttributes, "getRequest");
            Object session = invokeMethod(httprequest, "getSession");
            Object servletContext = invokeMethod(session, "getServletContext");
            Class servletContextClass = loadServletContextClass(classLoader);
            context = invokeMethod(classLoader.loadClass("org.springframework.web.context.support.WebApplicationContextUtils"), "getWebApplicationContext", new Class[]{servletContextClass}, new Object[]{servletContext});
        } catch (Exception e) {
        }

        // 路径 2：Spring 5.2 及更早版本的 LiveBeansView。
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

        // 路径 3：Spring Boot 2.3+ 的 shutdown hook 持有仍存活的应用上下文；
        // 对 WebFlux（没有 ServletContext）尤其重要。
        if (context == null) {
            try {
                Class hookClass = classLoader.loadClass("org.springframework.boot.SpringApplicationShutdownHook");
                Field instanceField = hookClass.getDeclaredField("INSTANCE");
                instanceField.setAccessible(true);
                Object hook = instanceField.get(null);
                Object contexts = getFV(hook, "contexts");
                if (contexts instanceof Collection && !((Collection) contexts).isEmpty()) {
                    context = ((Collection) contexts).iterator().next();
                }
            } catch (Exception ignored) {
            }
        }

        // 路径 4：从 Tomcat StandardContext 获取 WebApplicationContext。
        if (context == null) {
            try {
                context = getContextFromTomcat(classLoader);
            } catch (Throwable ignored) {
            }
        }

        return context;
    }

    /**
     * 复用 TomcatContainerManageComponent 的扫描逻辑拿到所有 StandardContext，
     * 再从每个 context 的 servletContext 走 WebApplicationContextUtils.getWebApplicationContext()。
     *
     * 故意走反射而不是直接 import，避免 puppet 端没有 TomcatContainerManageComponent 时编译失败。
     */
    private static Object getContextFromTomcat(ClassLoader cl) throws Throwable {
        Class tomcatComp;
        try {
            tomcatComp = cl.loadClass("org.leo.core.component.TomcatContainerManageComponent");
        } catch (Throwable t) {
            // TomcatContainerManageComponent 还没被 puppet 端加载，自己扫一遍 MBean
            return getContextFromTomcatMbean(cl);
        }
        Method getCtx = tomcatComp.getDeclaredMethod("getContext");
        getCtx.setAccessible(true);
        HashSet standardContexts = (HashSet) getCtx.invoke(null);
        return resolveWebAppContext(standardContexts, cl);
    }

    /** TomcatContainerManageComponent 没加载时，自己走 PlatformMBeanServer 查 WebModule。 */
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
        Class servletCtxClass = loadServletContextClass(cl);
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

    private static Class loadServletContextClass(ClassLoader cl) throws ClassNotFoundException {
        try {
            return cl.loadClass("jakarta.servlet.ServletContext");
        } catch (ClassNotFoundException ignored) {
            return cl.loadClass("javax.servlet.ServletContext");
        }
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


    static Object invokeMethod(Object targetObject, String methodName) throws NoSuchMethodException, InvocationTargetException {
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

}
