package org.leo.phpcore.rpc;

import org.junit.jupiter.api.Test;
import org.leo.core.entity.Disguise;
import org.leo.core.net.Communication;
import org.leo.core.net.layer.RequestLayer;
import org.leo.core.net.layer.ResponseLayer;
import org.leo.phpcore.payload.PhpPayloadCodec;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PhpRpcClientTest {
    private static final String KEY = "php-test-key";

    @Test
    void sendsCoreTestMethodAndKeepsDirectResponseFields() throws Exception {
        Disguise portable = new PortableDisguise();
        Communication communication = data -> {
            Map<String, Object> request = codec().decode(data);
            assertEquals("PING", request.get("operation"));
            assertEquals(Map.of(), request.get("params"));
            return codec().encode(Map.of(
                    "requestId", request.get("requestId"),
                    "code", 200,
                    "data", Map.of(
                            "msg", "pong",
                            "hostId", "php-host",
                            "components", List.of("BasicInfoComponent"))
            ));
        };

        PhpRpcClient client = new PhpRpcClient(communication,
                List.of(new RequestLayer("/", Map.of(), portable)),
                List.of(new ResponseLayer(portable)), KEY);
        Map<String, Object> result = client.ping();

        assertEquals(200, result.get("code"));
        assertEquals("pong", result.get("msg"));
        assertEquals("php-host", result.get("hostId"));
        assertTrue(result.get("components") instanceof List<?>);
    }

    @Test
    void wrapsInnerRequestWithCoreForwardMethod() throws Exception {
        Disguise portable = new PortableDisguise();
        Communication communication = data -> {
            Map<String, Object> relay = codec().decode(data);
            assertEquals("RELAY", relay.get("operation"));
            Map<?, ?> relayParams = (Map<?, ?>) relay.get("params");
            assertEquals("/inner", relayParams.get("url"));
            Map<?, ?> relayHeaders = (Map<?, ?>) relayParams.get("headers");
            assertEquals("application/octet-stream", relayHeaders.get("Content-Type"));
            assertEquals("identity", relayHeaders.get("Accept-Encoding"));
            Map<String, Object> inner = codec().decode((byte[]) relayParams.get("body"));
            assertEquals("PING", inner.get("operation"));
            byte[] innerResponse = codec().encode(Map.of(
                    "requestId", inner.get("requestId"),
                    "code", 200,
                    "data", Map.of("value", "ok")));
            return codec().encode(Map.of(
                    "requestId", relay.get("requestId"),
                    "code", 200,
                    "data", Map.of("body", innerResponse)));
        };

        PhpRpcClient client = new PhpRpcClient(communication,
                List.of(new RequestLayer("/inner", Map.of("X-Layer", "inner"), portable),
                        new RequestLayer("/outer", Map.of(), portable)),
                List.of(new ResponseLayer(portable), new ResponseLayer(portable)), KEY);

        Map<String, Object> result = client.ping();
        assertEquals(200, result.get("code"));
        assertEquals("ok", result.get("value"));
    }

    @Test
    void rejectsResponseWithoutMatchingRequestId() {
        Disguise portable = new PortableDisguise();
        int[] calls = {0};
        Communication communication = data -> {
            calls[0]++;
            Map<String, Object> request = codec().decode(data);
            return codec().encode(Map.of("code", 200, "hostId", "invalid"));
        };

        PhpRpcClient client = new PhpRpcClient(communication,
                List.of(new RequestLayer("/", Map.of(), portable)),
                List.of(new ResponseLayer(portable)), KEY);

        assertThrows(IllegalStateException.class, client::ping);
        assertEquals(1, calls[0]);
    }

    @Test
    void retriesWithBoundedBackoffAndKeepsRequestIdentity() throws Exception {
        Disguise portable = new PortableDisguise();
        AtomicInteger attempts = new AtomicInteger();
        List<String> requestIds = new ArrayList<>();
        List<Long> delays = new ArrayList<>();
        Communication communication = data -> {
            Map<String, Object> request = codec().decode(data);
            requestIds.add(String.valueOf(request.get("requestId")));
            if (attempts.incrementAndGet() < 3) throw new java.io.IOException("temporary");
            return codec().encode(Map.of(
                    "requestId", request.get("requestId"),
                    "code", 200,
                    "data", Map.of("msg", "pong")));
        };

        PhpRpcClient client = new PhpRpcClient(communication,
                List.of(new RequestLayer("/", Map.of(), portable)),
                List.of(new ResponseLayer(portable)), KEY);
        client.setMaxReqCount(3);
        client.setRetryBackoff(100, 1_000);
        client.setRetrySleeper(delays::add);

        assertEquals("pong", client.ping().get("msg"));
        assertEquals(3, attempts.get());
        assertEquals(1, requestIds.stream().distinct().count());
        assertEquals(2, delays.size());
        assertTrue(delays.get(0) >= 75 && delays.get(0) <= 125);
        assertTrue(delays.get(1) >= 150 && delays.get(1) <= 250);
    }

    @Test
    void retriesTypedHostMismatchThenRecoversWithoutReplayingTheOperation() throws Exception {
        Disguise portable = new PortableDisguise();
        AtomicInteger attempts = new AtomicInteger();
        AtomicInteger recoveries = new AtomicInteger();
        List<String> requestIds = new ArrayList<>();
        Communication communication = data -> {
            Map<String, Object> request = codec().decode(data);
            attempts.incrementAndGet();
            requestIds.add(String.valueOf(request.get("requestId")));
            assertEquals("php-host-1", request.get("hostId"));
            return codec().encode(Map.of(
                    "requestId", request.get("requestId"),
                    "code", 409,
                    "error", Map.of(
                            "errorCode", "HOST_ID_MISMATCH",
                            "hostId", "php-host-2",
                            "message", "wrong instance")));
        };
        PhpRpcClient client = new PhpRpcClient(communication,
                List.of(new RequestLayer("/", Map.of(), portable)),
                List.of(new ResponseLayer(portable)), KEY);
        client.setHostId("php-host-1");
        client.setMaxReqCount(3);
        client.setRetryBackoff(0, 0);
        client.setHostIdMismatchRecovery(expectedHostId -> {
            recoveries.incrementAndGet();
            assertEquals("php-host-1", expectedHostId);
            return new LinkedHashMap<>(Map.of(
                    "code", 409,
                    "errorCode", "HOST_ID_REBOUND",
                    "hostId", "php-host-2"));
        });

        Map<String, Object> result = client.invokeComponent(
                "BasicInfoComponent", "a".repeat(64), Map.of("action", "get"));

        assertEquals(409, result.get("code"));
        assertEquals("HOST_ID_REBOUND", result.get("errorCode"));
        assertEquals(8, attempts.get());
        assertEquals(1, requestIds.stream().distinct().count());
        assertEquals(1, recoveries.get());
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

    private static PhpPayloadCodec codec() {
        return new PhpPayloadCodec(KEY);
    }
}
