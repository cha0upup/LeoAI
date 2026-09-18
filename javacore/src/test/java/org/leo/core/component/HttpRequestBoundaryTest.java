package org.leo.core.component;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.leo.core.component.ComponentParameterBoundaryTest.*;

class HttpRequestBoundaryTest {
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void preservesRedirectErrorBodyAndBinaryResponseBehavior(boolean payload) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().set("Location", "/error");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/error", exchange -> {
            byte[] bytes = "错误响应".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=unsupported-test-charset");
            exchange.sendResponseHeaders(418, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.createContext("/binary", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
            exchange.sendResponseHeaders(200, 3);
            exchange.getResponseBody().write(new byte[]{0, 1, 2});
            exchange.close();
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            Map<String, Object> redirect = invoke(payload, Map.of("url", base + "/redirect",
                    "followRedirects", utf8("false")));
            assertEquals(302, redirect.get("statusCode"));
            Map<String, Object> followed = invoke(payload, Map.of("url", base + "/redirect",
                    "followRedirects", utf8("true")));
            assertEquals(200, followed.get("code"));
            assertEquals(418, followed.get("statusCode"));
            assertEquals("错误响应", followed.get("body"));
            assertEquals("text", followed.get("bodyType"));
            assertEquals("UTF-8", followed.get("charsetFallback"));
            Map<String, Object> binary = invoke(payload, Map.of("url", base + "/binary"));
            assertArrayEquals(new byte[]{0, 1, 2}, (byte[]) binary.get("body"));
            assertEquals(3, binary.get("bodySize"));
            assertEquals("binary", binary.get("bodyType"));
        } finally { server.stop(0); }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void sendsBodyAndCustomHeadersAndLeavesHeadResponseBodyAbsent(boolean payload) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> body = new AtomicReference<>();
        AtomicReference<String> header = new AtomicReference<>();
        server.createContext("/echo", exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            header.set(exchange.getRequestHeaders().getFirst("User-Agent"));
            exchange.sendResponseHeaders(201, -1);
            exchange.close();
        });
        server.createContext("/head", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "text/plain");
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            Map<String, Object> posted = invoke(payload, Map.of("url", base + "/echo", "method", "POST",
                    "body", utf8("sample"), "headers", Map.of("user-agent", "component-test"),
                    "connectTimeout", utf8("1000"), "readTimeout", utf8("1000")));
            assertEquals(201, posted.get("statusCode"));
            assertEquals("sample", body.get());
            assertEquals("component-test", header.get());
            Map<String, Object> head = invoke(payload, Map.of("url", base + "/head", "method", "HEAD"));
            assertEquals(200, head.get("statusCode"));
            assertFalse(head.containsKey("body"));
            assertFalse(head.containsKey("bodyType"));
        } finally { server.stop(0); }
    }

    private Map<String, Object> invoke(boolean payload, Map<String, Object> params) throws Exception {
        Object component = component("HttpRequestComponent", payload);
        Map<String, Object> result = prepare(component, params);
        call(component, "invoke", new Class[0]);
        return result;
    }
}
