package org.leo.core.puppet.service;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.leo.core.entity.Disguise;
import org.leo.core.net.Communication;
import org.leo.core.net.impl.HttpCommunication;
import org.leo.core.payload.PayloadCodec;
import org.leo.core.net.layer.HeaderNoiseStrategy;
import org.leo.core.net.layer.PaddingStrategy;
import org.leo.core.net.layer.RequestLayer;
import org.leo.core.net.layer.ResponseLayer;
import org.leo.core.net.layer.UrlStrategy;
import org.leo.core.rpc.PuppetOperation;
import org.leo.core.util.request.ClassNameGenerator;
import org.leo.core.util.request.ComponentClassNameStrategy;
import org.objectweb.asm.ClassReader;

import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.net.InetSocketAddress;
import java.net.Proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ComponentServiceEnvelopeTest {

    @Test
    void boundsAndExpiresLoadedComponentHostCache() {
        long[] now = {0L};
        TestService service = service(data -> new byte[0], new PortableDisguise());
        service.setLoadedComponentCacheClock(() -> now[0]);
        service.setLoadedComponentCacheLimits(2, 2, 100L);

        service.seedLoadedComponents("host-a", java.util.Set.of("One"));
        service.seedLoadedComponents("host-a", java.util.Set.of("Two"));
        service.seedLoadedComponents("host-a", java.util.Set.of("Three"));
        assertEquals(java.util.Set.of("Two", "Three"), service.getLoadedComponentNames("host-a"));

        service.seedLoadedComponents("host-b", java.util.Set.of("Four"));
        service.getLoadedComponentNames("host-a");
        service.seedLoadedComponents("host-c", java.util.Set.of("Five"));
        assertTrue(service.getLoadedComponentNames("host-b").isEmpty());
        assertEquals(java.util.Set.of("Five"), service.getLoadedComponentNames("host-c"));

        now[0] = 100L;
        assertTrue(service.getLoadedComponentNames("host-a").isEmpty());
        assertTrue(service.getLoadedComponentNames("host-c").isEmpty());
    }

    @Test
    void loadsStableComponentBytecodeUsingTheHostClassProfile() throws Exception {
        PortableDisguise disguise = new PortableDisguise();
        List<byte[]> bytecodes = new ArrayList<>();
        Communication communication = data -> {
            Map<String, Object> request = WireCodec.decode(data);
            Map<?, ?> params = (Map<?, ?>) request.get("params");
            bytecodes.add((byte[]) params.get("bytecode"));
            return WireCodec.encode(Map.of(
                    "requestId", request.get("requestId"), "code", 200,
                    "data", Map.of("loaded", true)));
        };
        TestService service = service(communication, disguise);

        assertEquals(200, service.loadComponent("BasicInfoComponent").get("code"));
        Map<String, Object> cached = service.loadComponent("BasicInfoComponent");
        assertEquals(200, cached.get("code"));
        assertEquals(Boolean.TRUE, cached.get("cached"));

        assertEquals(1, bytecodes.size());
        String expected = ClassNameGenerator.generateComponentClassName(
                "host-1|java", "BasicInfoComponent").replace('.', '/');
        assertEquals(expected, new ClassReader(bytecodes.get(0)).getClassName());
        assertEquals(50, ((bytecodes.get(0)[6] & 0xff) << 8) | (bytecodes.get(0)[7] & 0xff));
    }

    @Test
    void appliesConfiguredComponentClassNameProfile() throws Exception {
        PortableDisguise disguise = new PortableDisguise();
        List<byte[]> bytecodes = new ArrayList<>();
        Communication communication = data -> {
            Map<String, Object> request = WireCodec.decode(data);
            bytecodes.add((byte[]) ((Map<?, ?>) request.get("params")).get("bytecode"));
            return WireCodec.encode(Map.of(
                    "requestId", request.get("requestId"), "code", 200,
                    "data", Map.of("loaded", true)));
        };
        TestService service = service(communication, disguise);
        ComponentClassNameStrategy strategy = new ComponentClassNameStrategy();
        strategy.setMode(ComponentClassNameStrategy.Mode.PROXY_SHAPED);
        service.setComponentClassNameStrategy(strategy);

        assertEquals(200, service.loadComponent("BasicInfoComponent").get("code"));
        assertTrue(new ClassReader(bytecodes.get(0)).getClassName()
                .matches(".+/proxy/\\$Proxy[0-9]+"));
    }

    @Test
    void coalescesConcurrentLoadsAcrossServicesSharingOneNodeRegistry() throws Exception {
        PortableDisguise disguise = new PortableDisguise();
        AtomicInteger requests = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Communication communication = data -> {
            requests.incrementAndGet();
            entered.countDown();
            assertTrue(release.await(2, TimeUnit.SECONDS));
            Map<String, Object> request = WireCodec.decode(data);
            return WireCodec.encode(Map.of(
                    "requestId", request.get("requestId"), "code", 200,
                    "data", Map.of("loaded", true)));
        };
        ComponentLoadRegistry registry = new ComponentLoadRegistry();
        TestService first = service(communication, disguise);
        TestService second = service(communication, disguise);
        first.setComponentLoadRegistry(registry);
        second.setComponentLoadRegistry(registry);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Map<String, Object>> one = executor.submit(
                    () -> first.loadComponent("BasicInfoComponent"));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            Future<Map<String, Object>> two = executor.submit(
                    () -> second.loadComponent("BasicInfoComponent"));
            release.countDown();

            assertEquals(200, one.get(2, TimeUnit.SECONDS).get("code"));
            assertEquals(200, two.get(2, TimeUnit.SECONDS).get("code"));
            assertEquals(1, requests.get());
            assertTrue(first.getLoadedComponentNames("host-1").contains("BasicInfoComponent"));
            assertTrue(second.getLoadedComponentNames("host-1").contains("BasicInfoComponent"));
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void coolsDownRepeatedComponentLoadFailures() throws Exception {
        PortableDisguise disguise = new PortableDisguise();
        AtomicInteger requests = new AtomicInteger();
        long[] now = {0L};
        Communication communication = data -> {
            requests.incrementAndGet();
            Map<String, Object> request = WireCodec.decode(data);
            return WireCodec.encode(Map.of(
                    "requestId", request.get("requestId"), "code", 500,
                    "message", "temporary"));
        };
        TestService service = service(communication, disguise);
        service.setLoadedComponentCacheClock(() -> now[0]);
        service.setComponentLoadFailurePolicy(2, 100L);

        assertEquals(500, service.loadComponent("BasicInfoComponent").get("code"));
        assertEquals(500, service.loadComponent("BasicInfoComponent").get("code"));
        Map<String, Object> cooling = service.loadComponent("BasicInfoComponent");
        assertEquals(503, cooling.get("code"));
        assertEquals(2, requests.get());

        now[0] = 100L;
        assertEquals(500, service.loadComponent("BasicInfoComponent").get("code"));
        assertEquals(3, requests.get());
    }

    @Test
    void cacheClearPreventsOlderInflightLoadFromRestoringState() throws Exception {
        PortableDisguise disguise = new PortableDisguise();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Communication communication = data -> {
            entered.countDown();
            assertTrue(release.await(2, TimeUnit.SECONDS));
            Map<String, Object> request = WireCodec.decode(data);
            return WireCodec.encode(Map.of(
                    "requestId", request.get("requestId"), "code", 200,
                    "data", Map.of("loaded", true)));
        };
        ComponentLoadRegistry registry = new ComponentLoadRegistry();
        TestService service = service(communication, disguise);
        service.setComponentLoadRegistry(registry);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Map<String, Object>> load = executor.submit(
                    () -> service.loadComponent("BasicInfoComponent"));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            registry.clear();
            release.countDown();

            assertEquals(200, load.get(2, TimeUnit.SECONDS).get("code"));
            assertTrue(service.getLoadedComponentNames("host-1").isEmpty());
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void sendsEnvelopeAndRestoresExistingComponentResultShape() {
        PortableDisguise disguise = new PortableDisguise();
        Communication communication = data -> {
            Map<String, Object> request = WireCodec.decode(data);
            assertEquals("COMPONENT_INVOKE", request.get("operation"));
            assertEquals("host-1", request.get("hostId"));
            assertEquals("ExecCommandComponent", request.get("component"));
            assertEquals("exec", request.get("action"));
            assertEquals(Map.of("cmd", "whoami"), request.get("params"));
            assertFalse(request.containsKey("protocol"));
            assertFalse(request.containsKey("version"));
            return WireCodec.encode(Map.of(
                    "requestId", request.get("requestId"),
                    "code", 200,
                    "data", Map.of("data", "root", "exitCode", 0)));
        };
        TestService service = service(communication, disguise);

        Map<String, Object> result = service.execute(
                PuppetOperation.COMPONENT_INVOKE, "ExecCommandComponent", "exec",
                new LinkedHashMap<>(Map.of("cmd", "whoami")));

        assertEquals(Map.of("code", 200, "data", "root", "exitCode", 0), result);
    }

    @Test
    void rejectsResponsesOutsideTheEnvelopeContractWithoutResending() {
        PortableDisguise disguise = new PortableDisguise();
        int[] calls = {0};
        Communication communication = data -> {
            calls[0]++;
            return WireCodec.encode(Map.of("code", 200, "hostId", "invalid"));
        };
        TestService service = service(communication, disguise);

        Map<String, Object> result = service.execute(
                PuppetOperation.PING, null, null, new LinkedHashMap<>());
        assertEquals(500, result.get("code"));
        assertEquals(1, calls[0]);
    }

    @Test
    void wrapsEveryRelayLayerInItsOwnEnvelope() {
        PortableDisguise disguise = new PortableDisguise();
        Communication communication = data -> {
            Map<String, Object> relay = WireCodec.decode(data);
            assertEquals("RELAY", relay.get("operation"));
            Map<?, ?> relayParams = (Map<?, ?>) relay.get("params");
            assertEquals("/inner", relayParams.get("url"));
            Map<?, ?> relayHeaders = (Map<?, ?>) relayParams.get("headers");
            assertEquals("application/octet-stream", relayHeaders.get("Content-Type"));
            assertEquals("identity", relayHeaders.get("Accept-Encoding"));
            Map<String, Object> inner = WireCodec.decode((byte[]) relayParams.get("body"));
            assertEquals("PING", inner.get("operation"));
            byte[] innerResponse = WireCodec.encode(Map.of(
                    "requestId", inner.get("requestId"),
                    "code", 200,
                    "data", Map.of("hostId", "host-1")));
            return WireCodec.encode(Map.of(
                    "requestId", relay.get("requestId"),
                    "code", 200,
                    "data", Map.of("body", innerResponse)));
        };
        TestService service = new TestService(communication,
                List.of(new RequestLayer("/inner", Map.of(), disguise),
                        new RequestLayer("/outer", Map.of(), disguise)),
                List.of(new ResponseLayer(disguise), new ResponseLayer(disguise)));
        service.setHostId("host-1");
        service.setMaxReqCount(1);

        Map<String, Object> result = service.execute(
                PuppetOperation.PING, null, null, new LinkedHashMap<>());

        assertEquals(200, result.get("code"));
        assertEquals("host-1", result.get("hostId"));
    }

    @Test
    void retriesWithBoundedBackoffAndKeepsRequestIdentity() {
        PortableDisguise disguise = new PortableDisguise();
        AtomicInteger attempts = new AtomicInteger();
        List<String> requestIds = new ArrayList<>();
        List<Long> delays = new ArrayList<>();
        Communication communication = data -> {
            Map<String, Object> request = WireCodec.decode(data);
            requestIds.add(String.valueOf(request.get("requestId")));
            if (attempts.incrementAndGet() < 3) throw new java.io.IOException("temporary");
            return WireCodec.encode(Map.of(
                    "requestId", request.get("requestId"), "code", 200,
                    "data", Map.of("hostId", "host-1")));
        };
        TestService service = service(communication, disguise);
        service.setMaxReqCount(3);
        service.setRetryBackoff(100, 1_000);
        service.setRetrySleeper(delays::add);

        Map<String, Object> result = service.execute(
                PuppetOperation.PING, null, null, new LinkedHashMap<>());

        assertEquals(200, result.get("code"));
        assertEquals(3, attempts.get());
        assertEquals(1, requestIds.stream().distinct().count());
        assertEquals(2, delays.size());
        assertTrue(delays.get(0) >= 75 && delays.get(0) <= 125);
        assertTrue(delays.get(1) >= 150 && delays.get(1) <= 250);
    }

    @Test
    void retriesTypedHostMismatchThenRecoversWithoutReplayingTheOperation() {
        PortableDisguise disguise = new PortableDisguise();
        AtomicInteger attempts = new AtomicInteger();
        AtomicInteger recoveries = new AtomicInteger();
        List<String> requestIds = new ArrayList<>();
        Communication communication = data -> {
            Map<String, Object> request = WireCodec.decode(data);
            attempts.incrementAndGet();
            requestIds.add(String.valueOf(request.get("requestId")));
            assertEquals("host-1", request.get("hostId"));
            return WireCodec.encode(Map.of(
                    "requestId", request.get("requestId"),
                    "code", 409,
                    "error", Map.of(
                            "errorCode", "HOST_ID_MISMATCH",
                            "hostId", "host-2",
                            "message", "wrong instance")));
        };
        TestService service = service(communication, disguise);
        service.setMaxReqCount(3);
        service.setRetryBackoff(0, 0);
        service.setHostIdMismatchRecovery(expectedHostId -> {
            recoveries.incrementAndGet();
            assertEquals("host-1", expectedHostId);
            return new LinkedHashMap<>(Map.of(
                    "code", 409,
                    "errorCode", "HOST_ID_REBOUND",
                    "hostId", "host-2"));
        });

        Map<String, Object> result = service.execute(
                PuppetOperation.COMPONENT_INVOKE, "ExecCommandComponent", "exec",
                new LinkedHashMap<>(Map.of("cmd", "whoami")));

        assertEquals(409, result.get("code"));
        assertEquals("HOST_ID_REBOUND", result.get("errorCode"));
        assertEquals(8, attempts.get());
        assertEquals(1, requestIds.stream().distinct().count());
        assertEquals(1, recoveries.get());
    }

    @Test
    void usesBucketPaddingWithDerivedFieldName() {
        PortableDisguise disguise = new PortableDisguise();
        Communication communication = data -> {
            Map<String, Object> request = WireCodec.decode(data);
            assertTrue(request.keySet().stream().anyMatch(key -> key.matches("_[a-f0-9]{12}")));
            return WireCodec.encode(Map.of(
                    "requestId", request.get("requestId"), "code", 200,
                    "data", Map.of("hostId", "host-1")));
        };
        TestService service = service(communication, disguise);
        service.setPaddingStrategy(new PaddingStrategy().setEnabled(true)
                .setMinBytes(64).setMaxBytes(600).setBucketSizes(new int[]{1024, 2048}));

        assertEquals(200, service.execute(
                PuppetOperation.PING, null, null, new LinkedHashMap<>()).get("code"));
    }

    @Test
    void keepsHttpRouteAndHeadersStableForHostSession() throws Exception {
        PortableDisguise disguise = new PortableDisguise();
        List<String> paths = new ArrayList<>();
        List<String> userAgents = new ArrayList<>();
        List<String> languages = new ArrayList<>();
        List<String> traceIds = new ArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            paths.add(exchange.getRequestURI().getPath());
            userAgents.add(exchange.getRequestHeaders().getFirst("User-Agent"));
            languages.add(exchange.getRequestHeaders().getFirst("Accept-Language"));
            traceIds.add(exchange.getRequestHeaders().getFirst("X-Trace-Id"));
            Map<String, Object> request = WireCodec.decode(exchange.getRequestBody().readAllBytes());
            byte[] response = WireCodec.encode(Map.of(
                    "requestId", request.get("requestId"), "code", 200,
                    "data", Map.of("hostId", "host-http")));
            exchange.sendResponseHeaders(200, response.length);
            try (java.io.OutputStream output = exchange.getResponseBody()) { output.write(response); }
        });
        server.start();
        try {
            String endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/entry";
            HttpCommunication communication = new HttpCommunication(endpoint, "POST", Map.of(), Proxy.NO_PROXY);
            TestService service = service(communication, disguise);
            service.setHostId("host-http");

            UrlStrategy url = new UrlStrategy();
            url.setEnabled(true); url.setMode(UrlStrategy.Mode.TEMPLATE);
            url.setUrlTemplate("/api/{rand}{ext}"); url.setExtensions(List.of(".png"));
            service.setUrlStrategy(url);
            HeaderNoiseStrategy noise = new HeaderNoiseStrategy();
            noise.setEnabled(true); noise.setMinHeaders(1); noise.setMaxHeaders(1);
            noise.setPrefixes(new String[]{"X-Trace-Id"});
            service.setHeaderNoiseStrategy(noise);

            assertEquals(200, service.execute(PuppetOperation.PING, null, null,
                    new LinkedHashMap<>()).get("code"));
            assertEquals(200, service.execute(PuppetOperation.PING, null, null,
                    new LinkedHashMap<>()).get("code"));

            assertEquals(2, paths.size());
            assertEquals(paths.get(0), paths.get(1));
            assertTrue(paths.get(0).startsWith("/api/"));
            assertTrue(paths.get(0).endsWith(".json"));
            assertEquals(userAgents.get(0), userAgents.get(1));
            assertEquals(languages.get(0), languages.get(1));
            assertEquals(traceIds.get(0), traceIds.get(1));
        } finally {
            server.stop(0);
        }
    }

    private TestService service(Communication communication, Disguise disguise) {
        TestService service = new TestService(communication,
                List.of(new RequestLayer("/", Map.of(), disguise)),
                List.of(new ResponseLayer(disguise)));
        service.setHostId("host-1");
        service.setMaxReqCount(1);
        return service;
    }

    private static final class TestService extends ComponentService {
        private TestService(Communication communication,
                            List<RequestLayer> requestLayers,
                            List<ResponseLayer> responseLayers) {
            super(communication, requestLayers, responseLayers);
            setPayloadCodec(new PayloadCodec("component-test-key"));
        }

        private Map<String, Object> execute(PuppetOperation operation, String component,
                                            String action, Map<String, Object> params) {
            return run(operation, component, action, params);
        }
    }

    private static final class PortableDisguise extends Disguise {
        private PortableDisguise() {
            setTrafficEncodeBody("public byte[] encodeTraffic(byte[] data){return data;}");
            setTrafficDecodeBody("public byte[] decodeTraffic(byte[] data){return data;}");
        }

        @Override
        public byte[] encodeTraffic(byte[] payload) {
            return payload;
        }

        @Override
        public byte[] decodeTraffic(byte[] data) {
            return data;
        }
    }

    private static final class WireCodec {
        private static final PayloadCodec CODEC = new PayloadCodec("component-test-key");

        private static byte[] encode(Map<String, Object> payload) throws Exception {
            return CODEC.encode(payload);
        }

        private static Map<String, Object> decode(byte[] data) throws Exception {
            return CODEC.decode(data);
        }
    }
}
