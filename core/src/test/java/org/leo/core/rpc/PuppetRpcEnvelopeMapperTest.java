package org.leo.core.rpc;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PuppetRpcEnvelopeMapperTest {

    @Test
    void mapsMinimalRequestWithoutProtocolMetadata() {
        PuppetRpcRequest request = new PuppetRpcRequest(
                " request-1 ", PuppetOperation.COMPONENT_INVOKE, " host-1 ",
                " ExecCommand ", " exec ", Map.of("cmd", "whoami"));

        Map<String, Object> envelope = PuppetRpcEnvelopeMapper.toMap(request);

        assertEquals("request-1", envelope.get("requestId"));
        assertEquals("COMPONENT_INVOKE", envelope.get("operation"));
        assertEquals("host-1", envelope.get("hostId"));
        assertEquals("ExecCommand", envelope.get("component"));
        assertEquals("exec", envelope.get("action"));
        assertEquals(Map.of("cmd", "whoami"), envelope.get("params"));
        assertFalse(envelope.containsKey("protocol"));
        assertFalse(envelope.containsKey("version"));
        assertFalse(envelope.containsKey("protocolVersion"));
        assertEquals(request, PuppetRpcEnvelopeMapper.requestFromMap(envelope));
    }

    @Test
    void mapsSuccessAndErrorResponsesBackToExistingServiceShape() {
        PuppetRpcResponse success = new PuppetRpcResponse(
                "request-3", 200, Map.of("data", "ok", "exitCode", 0), Map.of());
        Map<String, Object> successMap = PuppetRpcEnvelopeMapper.toMap(success);
        assertTrue(PuppetRpcEnvelopeMapper.isEnvelopeResponse(successMap, "request-3"));
        assertEquals(Map.of("code", 200, "data", "ok", "exitCode", 0),
                PuppetRpcEnvelopeMapper.toResultMap(
                        PuppetRpcEnvelopeMapper.responseFromMap(successMap)));

        PuppetRpcResponse failure = new PuppetRpcResponse(
                "request-4", 424, null,
                Map.of("type", "COMPONENT_NOT_FOUND", "message", "missing"));
        Map<String, Object> failureMap = PuppetRpcEnvelopeMapper.toMap(failure);
        PuppetRpcResponse parsed = PuppetRpcEnvelopeMapper.responseFromMap(failureMap);
        assertNull(parsed.data());
        assertEquals(Map.of("code", 424, "type", "COMPONENT_NOT_FOUND", "msg", "missing"),
                PuppetRpcEnvelopeMapper.toResultMap(parsed));
    }

    @Test
    void validatesRequiredCorrelationAndOperationFields() {
        assertThrows(IllegalArgumentException.class,
                () -> new PuppetRpcRequest(" ", PuppetOperation.PING, null, null, null, Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> PuppetRpcEnvelopeMapper.requestFromMap(Map.of("requestId", "r")));
        assertThrows(IllegalArgumentException.class,
                () -> PuppetRpcEnvelopeMapper.responseFromMap(Map.of("requestId", "r", "code", "200")));
        assertThrows(IllegalArgumentException.class,
                () -> PuppetRpcEnvelopeMapper.responseFromMap(
                        Map.of("requestId", "r", "code", 200, "data", "old-result")));
        assertThrows(IllegalArgumentException.class,
                () -> PuppetRpcEnvelopeMapper.responseFromMap(
                        Map.of("requestId", "r", "code", 500, "error", "old-error")));
        assertThrows(IllegalArgumentException.class,
                () -> PuppetRpcEnvelopeMapper.responseFromResult("r", Map.of("legacy", "result")));
    }

    @Test
    void acceptsOnlyCurrentPingShape() {
        assertTrue(PuppetRpcEnvelopeMapper.isCurrentPingResult(
                Map.of("code", 200, "hostId", "host-1", "components", List.of("FileComponent"))));
        assertFalse(PuppetRpcEnvelopeMapper.isCurrentPingResult(
                Map.of("code", "200", "hostId", "host-1", "components", List.of())));
        assertFalse(PuppetRpcEnvelopeMapper.isCurrentPingResult(
                Map.of("code", 200, "hostId", "host-1", "components", new String[0])));
        assertFalse(PuppetRpcEnvelopeMapper.isCurrentPingResult(
                Map.of("code", 200, "hostId", "host-1", "components", List.of(123))));
    }
}
