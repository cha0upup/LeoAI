package org.leo.core.net.impl;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class HttpCommunicationCookieTest {

    @ParameterizedTest @ValueSource(ints = {401, 408, 503, 302, 307, 308})
    void passesOriginalResponseWithoutHiddenRetriesOrRedirects(int status) throws Exception {
        byte[] body = "encoded-rpc-response".getBytes(StandardCharsets.UTF_8);
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/unauthorized", exchange -> {
            calls.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Retry-After", "0");
            exchange.getResponseHeaders().set("Location", "/redirected");
            exchange.sendResponseHeaders(status, body.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.createContext("/redirected", exchange -> {
            calls.incrementAndGet();
            write(exchange, "unexpected redirect");
        });

        server.start();
        try {
            String endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/unauthorized";
            try (HttpCommunication communication = new HttpCommunication(endpoint, "POST", null, Proxy.NO_PROXY)) {
                assertArrayEquals(body, communication.sendRequest(new byte[]{1, 2}));
            }
            assertEquals(1, calls.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void learnsSetCookieWithPathScope() throws Exception {
        AtomicReference<String> appCookie = new AtomicReference<>();
        AtomicReference<String> otherCookie = new AtomicReference<>();

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/login", exchange -> {
            exchange.getResponseHeaders().add("Set-Cookie", "sid=abc; Path=/app; HttpOnly");
            write(exchange, "login");
        });
        server.createContext("/app/ping", exchange -> {
            appCookie.set(exchange.getRequestHeaders().getFirst("Cookie"));
            write(exchange, "app");
        });
        server.createContext("/other/ping", exchange -> {
            otherCookie.set(exchange.getRequestHeaders().getFirst("Cookie"));
            write(exchange, "other");
        });

        server.start();
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        try (HttpCommunication communication = new HttpCommunication(baseUrl + "/login", "POST", null, Proxy.NO_PROXY)) {
            communication.sendRequest(new byte[0]);
            communication.setRequestUrl(baseUrl + "/app/ping");
            communication.sendRequest(new byte[0]);
            communication.setRequestUrl(baseUrl + "/other/ping");
            communication.sendRequest(new byte[0]);

            assertEquals("sid=abc", appCookie.get());
            assertNull(otherCookie.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void configuredCookieWinsAndAutoCookieIsMerged() throws Exception {
        AtomicReference<String> nextCookie = new AtomicReference<>();

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/login", exchange -> {
            exchange.getResponseHeaders().add("Set-Cookie", "sid=server; Path=/");
            exchange.getResponseHeaders().add("Set-Cookie", "extra=1; Path=/");
            write(exchange, "login");
        });
        server.createContext("/next", exchange -> {
            nextCookie.set(exchange.getRequestHeaders().getFirst("Cookie"));
            write(exchange, "next");
        });

        server.start();
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        try (HttpCommunication communication = new HttpCommunication(baseUrl + "/login", "POST",
                Map.of("Cookie", "sid=user; theme=dark"), Proxy.NO_PROXY)) {
            communication.sendRequest(new byte[0]);
            communication.setRequestUrl(baseUrl + "/next");
            communication.sendRequest(new byte[0]);

            assertEquals("sid=user; theme=dark; extra=1", nextCookie.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void resettingAffinityDropsLearnedCookieButKeepsConfiguredCookie() throws Exception {
        AtomicReference<String> nextCookie = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/login", exchange -> {
            exchange.getResponseHeaders().add("Set-Cookie", "route=backend-a; Path=/");
            write(exchange, "login");
        });
        server.createContext("/next", exchange -> {
            nextCookie.set(exchange.getRequestHeaders().getFirst("Cookie"));
            write(exchange, "next");
        });
        server.start();
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        try (HttpCommunication communication = new HttpCommunication(baseUrl + "/login", "POST",
                Map.of("Cookie", "user=explicit"), Proxy.NO_PROXY)) {
            communication.sendRequest(new byte[0]);
            communication.resetSessionAffinity();
            communication.setRequestUrl(baseUrl + "/next");
            communication.sendRequest(new byte[0]);

            assertEquals("user=explicit", nextCookie.get());
        } finally {
            server.stop(0);
        }
    }

    private static void write(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
