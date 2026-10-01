package org.leo.core.rpc;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentRpcEnvelopeMapperTest {

    @Test
    void mapsMinimalRequestWithoutProtocolMetadata() {
        AgentRpcRequest request = new AgentRpcRequest(
                " request-1 ", AgentOperation.COMPONENT_INVOKE, " host-1 ",
                " ExecCommand ", " exec ", Map.of("cmd", "whoami"));

        Map<String, Object> envelope = AgentRpcEnvelopeMapper.toMap(request);

        assertEquals("request-1", envelope.get("requestId"));
        assertEquals("COMPONENT_INVOKE", envelope.get("operation"));
        assertEquals("host-1", envelope.get("hostId"));
        assertEquals("ExecCommand", envelope.get("component"));
        assertEquals("exec", envelope.get("action"));
        assertEquals(Map.of("cmd", "whoami"), envelope.get("params"));
        assertFalse(envelope.containsKey("protocol"));
        assertFalse(envelope.containsKey("version"));
        assertFalse(envelope.containsKey("protocolVersion"));
        assertEquals(request, AgentRpcEnvelopeMapper.requestFromMap(envelope));
    }

    @Test
    void mapsSuccessAndErrorResponsesBackToExistingServiceShape() {
        AgentRpcResponse success = new AgentRpcResponse(
                "request-3", 200, Map.of("data", "ok", "exitCode", 0), Map.of());
        Map<String, Object> successMap = AgentRpcEnvelopeMapper.toMap(success);
        assertTrue(AgentRpcEnvelopeMapper.isEnvelopeResponse(successMap, "request-3"));
        assertEquals(Map.of("code", 200, "data", "ok", "exitCode", 0),
                AgentRpcEnvelopeMapper.toResultMap(
                        AgentRpcEnvelopeMapper.responseFromMap(successMap)));

        AgentRpcResponse failure = new AgentRpcResponse(
                "request-4", 424, null,
                Map.of("type", "COMPONENT_NOT_FOUND", "message", "missing"));
        Map<String, Object> failureMap = AgentRpcEnvelopeMapper.toMap(failure);
        AgentRpcResponse parsed = AgentRpcEnvelopeMapper.responseFromMap(failureMap);
        assertNull(parsed.data());
        assertEquals(Map.of("code", 424, "type", "COMPONENT_NOT_FOUND", "msg", "missing"),
                AgentRpcEnvelopeMapper.toResultMap(parsed));
    }

    @Test
    void validatesRequiredCorrelationAndOperationFields() {
        assertThrows(IllegalArgumentException.class,
                () -> new AgentRpcRequest(" ", AgentOperation.PING, null, null, null, Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> AgentRpcEnvelopeMapper.requestFromMap(Map.of("requestId", "r")));
        assertThrows(IllegalArgumentException.class,
                () -> AgentRpcEnvelopeMapper.responseFromMap(Map.of("requestId", "r", "code", "200")));
        assertThrows(IllegalArgumentException.class,
                () -> AgentRpcEnvelopeMapper.responseFromMap(
                        Map.of("requestId", "r", "code", 200, "data", "old-result")));
        assertThrows(IllegalArgumentException.class,
                () -> AgentRpcEnvelopeMapper.responseFromMap(
                        Map.of("requestId", "r", "code", 500, "error", "old-error")));
        assertThrows(IllegalArgumentException.class,
                () -> AgentRpcEnvelopeMapper.responseFromResult("r", Map.of("legacy", "result")));
    }

    @Test
    void acceptsOnlyCurrentPingShape() {
        assertTrue(AgentRpcEnvelopeMapper.isCurrentPingResult(
                Map.of("code", 200, "hostId", "host-1", "components", List.of("FileComponent"))));
        assertFalse(AgentRpcEnvelopeMapper.isCurrentPingResult(
                Map.of("code", "200", "hostId", "host-1", "components", List.of())));
        assertFalse(AgentRpcEnvelopeMapper.isCurrentPingResult(
                Map.of("code", 200, "hostId", "host-1", "components", new String[0])));
        assertFalse(AgentRpcEnvelopeMapper.isCurrentPingResult(
                Map.of("code", 200, "hostId", "host-1", "components", List.of(123))));
    }
}
