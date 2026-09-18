package org.leo.phpcore.component;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.leo.core.util.json.PortableJsonCodec;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.Files;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import static org.leo.phpcore.PhpTestSupport.phpAvailable;
import static org.leo.phpcore.PhpTestSupport.runJson;

class PhpHttpRequestComponentTest {

    private HttpServer server;
    private Path component;
    private String baseUrl;

    @BeforeEach
    void setUp() throws Exception {
        Assumptions.assumeTrue(phpAvailable(), "PHP CLI is not installed");
        URL resource = Objects.requireNonNull(getClass().getResource("/components/HttpRequestComponent.php"));
        component = Paths.get(resource.toURI());
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/echo", this::echo);
        server.createContext("/binary", exchange -> respond(exchange, 200,
                "application/octet-stream", new byte[]{0, 1, 2, (byte) 255}));
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().add("Location", "/echo");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/headers", exchange -> {
            String value = exchange.getRequestHeaders().getFirst("User-Agent") + "|"
                    + exchange.getRequestHeaders().getFirst("Accept-Language") + "|"
                    + exchange.getRequestHeaders().getFirst("Accept");
            respond(exchange, 200, "text/plain", value.getBytes(StandardCharsets.UTF_8));
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
    }

    @Test
    void sendsCustomMethodHeadersAndBodyThroughAvailableBackend() throws Exception {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("method", "PATCH");
        params.put("url", baseUrl + "/echo?source=php");
        params.put("headers", Map.of("X-Leo-Test", "header-ok", "Content-Type", "text/plain"));
        params.put("body", "payload-世界");
        params.put("connectTimeout", 2000);
        params.put("readTimeout", 3000);
        params.put("followRedirects", false);

        Map<String, Object> response = invoke(params);

        assertEquals(200, ((Number) response.get("code")).intValue());
        assertEquals(201, ((Number) response.get("statusCode")).intValue());
        assertEquals("Created", response.get("statusMessage"));
        assertEquals("text", response.get("bodyType"));
        assertEquals("PATCH|header-ok|payload-世界", response.get("body"));
        assertTrue(response.get("backend").equals("curl") || response.get("backend").equals("stream"));
        assertTrue(((Number) response.get("elapsedMs")).longValue() >= 0);
        Map<?, ?> headers = assertInstanceOf(Map.class, response.get("responseHeaders"));
        assertTrue(headers.keySet().stream().anyMatch(name -> "X-Multi".equalsIgnoreCase(String.valueOf(name))));
    }

    @Test
    void preservesBinaryBodiesAndFollowsRedirects() throws Exception {
        Map<String, Object> binary = invoke(Map.of("method", "GET", "url", baseUrl + "/binary"));
        assertEquals("binary", binary.get("bodyType"));
        assertArrayEquals(new byte[]{0, 1, 2, (byte) 255},
                assertInstanceOf(byte[].class, binary.get("body")));

        Map<String, Object> redirected = invoke(Map.of("method", "GET", "url", baseUrl + "/redirect",
                "followRedirects", true));
        assertEquals(201, ((Number) redirected.get("statusCode")).intValue());
        assertTrue(String.valueOf(redirected.get("body")).startsWith("GET||"));
    }

    @Test
    void fallsBackToHttpStreamWhenCurlIsUnavailable() throws Exception {
        Path original = component;
        Path streamOnly = Files.createTempFile("php-http-stream-", ".php");
        try {
            String source = Files.readString(original, StandardCharsets.UTF_8)
                    .replace("if ($available('curl_init')) return $sendCurl", "if (false) return $sendCurl");
            Files.writeString(streamOnly, source, StandardCharsets.UTF_8);
            component = streamOnly;

            Map<String, Object> response = invoke(Map.of("method", "POST", "url", baseUrl + "/echo",
                    "headers", Map.of("X-Leo-Test", "stream-ok"), "body", "fallback"));

            assertEquals("stream", response.get("backend"));
            assertEquals(201, ((Number) response.get("statusCode")).intValue());
            assertEquals("POST|stream-ok|fallback", response.get("body"));
        } finally {
            component = original;
            Files.deleteIfExists(streamOnly);
        }
    }

    @Test
    void usesStableOsConsistentDefaultsAndPreservesExplicitProfile() throws Exception {
        Map<String, Object> defaults = invoke(Map.of("method", "GET", "url", baseUrl + "/headers"));
        String body = String.valueOf(defaults.get("body"));
        String[] fields = body.split("\\|", 3);
        assertEquals(3, fields.length);
        assertTrue(fields[0].startsWith("Mozilla/5.0"));
        assertTrue(fields[0].contains("Macintosh") || fields[0].contains("Windows NT") || fields[0].contains("Linux"));
        assertTrue(fields[1].startsWith("zh-CN") || fields[1].startsWith("en-US"));
        assertTrue(fields[2].contains("text/html"));
        assertTrue(!fields[0].contains("Chrome/131"));

        Map<String, Object> explicit = invoke(Map.of("method", "GET", "url", baseUrl + "/headers",
                "headers", Map.of("User-Agent", "FixtureClient/1.0", "Accept-Language", "fr-FR")));
        assertTrue(String.valueOf(explicit.get("body")).startsWith("FixtureClient/1.0|fr-FR|"));
    }

    private void echo(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String marker = exchange.getRequestHeaders().getFirst("X-Leo-Test");
        byte[] response = (exchange.getRequestMethod() + "|" + (marker == null ? "" : marker) + "|" + body)
                .getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=UTF-8");
        exchange.getResponseHeaders().add("X-Multi", "one");
        exchange.getResponseHeaders().add("X-Multi", "two");
        exchange.sendResponseHeaders(201, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }

    private void respond(HttpExchange exchange, int status, String contentType, byte[] body) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", contentType);
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private Map<String, Object> invoke(Map<String, Object> params) throws Exception {
        String encoded = Base64.getEncoder().encodeToString(PortableJsonCodec.encode(params));
        String script = "function leo_binary($value){return array('$leoBinary'=>base64_encode($value));}"
                + "$component=require $argv[1];"
                + "$params=json_decode(base64_decode($argv[2]),true);"
                + "echo json_encode(call_user_func($component['handle'],'send',$params));";
        return runJson(15, "php", "-r", script, component.toString(), encoded);
    }
}
