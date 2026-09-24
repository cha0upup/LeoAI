package org.leo.ai.channel;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.leo.core.entity.AiProvider;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class AiModelDiscoveryServiceTest {
    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"data\":[{\"id\":123}]}", "{\"data\":[{}]}", "[]", "invalid-json"})
    void rejectsMalformedModelLists(String json) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/gateway/v2/models", exchange -> {
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            assertThrows(IllegalArgumentException.class, () -> new AiModelDiscoveryService().fetch(provider(server)));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void discoveryUsesTheInferenceRootAndCustomAuthentication() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> auth = new AtomicReference<>();
        server.createContext("/gateway/v2/models", exchange -> {
            auth.set(exchange.getRequestHeaders().getFirst("X-Gateway-Key"));
            byte[] bytes = "{\"data\":[{\"id\":\"z\"},{\"id\":\"a\"},{\"id\":\"a\"}]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            AiProvider provider = provider(server);
            assertEquals(List.of("a", "z"), new AiModelDiscoveryService().fetch(provider));
            assertEquals("test-secret", auth.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void redirectsCannotForwardCredentialsToAnotherEndpoint() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger forwarded = new AtomicInteger();
        server.createContext("/gateway/v2/models", exchange -> {
            exchange.getResponseHeaders().set("Location", "/unexpected");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/unexpected", exchange -> {
            forwarded.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        try {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> new AiModelDiscoveryService().fetch(provider(server)));
            assertTrue(error.getMessage().contains("302"));
            assertEquals(0, forwarded.get());
        } finally {
            server.stop(0);
        }
    }

    private AiProvider provider(HttpServer server) {
        AiProvider provider = new AiProvider();
        provider.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/gateway");
        provider.setProtocol("responses");
        provider.setCompletionsPath("/v2/responses");
        provider.setApiKey("test-key");
        provider.setHeadersJson("{\"X-Gateway-Key\":\"test-secret\"}");
        return provider;
    }
}
