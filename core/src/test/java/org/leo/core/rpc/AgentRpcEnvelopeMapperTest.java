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

        assertEquals(Map.of(
                "requestId", "request-1", "operation", "COMPONENT_INVOKE", "hostId", "host-1",
                "component", "ExecCommand", "action", "exec", "params", Map.of("cmd", "whoami")), envelope);
    }

    @Test
    void mapsSuccessAndErrorResponsesBackToExistingServiceShape() {
        Map<String, Object> successMap = Map.of("requestId", "request-3", "code", 200,
                "data", Map.of("data", "ok", "exitCode", 0));
        assertTrue(AgentRpcEnvelopeMapper.isEnvelopeResponse(successMap, "request-3"));
        assertFalse(AgentRpcEnvelopeMapper.isEnvelopeResponse(successMap, "another-request"));
        assertEquals(Map.of("code", 200, "data", "ok", "exitCode", 0),
                AgentRpcEnvelopeMapper.toResultMap(
                        AgentRpcEnvelopeMapper.responseFromMap(successMap)));

        Map<String, Object> failureMap = Map.of("requestId", "request-4", "code", 424,
                "error", Map.of("type", "COMPONENT_NOT_FOUND", "message", "missing"));
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
                () -> new AgentRpcRequest("r", null, null, null, null, Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> AgentRpcEnvelopeMapper.responseFromMap(Map.of("requestId", "r", "code", "200")));
        assertThrows(IllegalArgumentException.class,
                () -> AgentRpcEnvelopeMapper.responseFromMap(
                        Map.of("requestId", "r", "code", 200, "data", "old-result")));
        assertThrows(IllegalArgumentException.class,
                () -> AgentRpcEnvelopeMapper.responseFromMap(
                        Map.of("requestId", "r", "code", 500, "error", "old-error")));
        assertThrows(IllegalArgumentException.class,
                () -> AgentRpcEnvelopeMapper.responseFromMap(Map.of("requestId", "r", "data", Map.of())));
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
