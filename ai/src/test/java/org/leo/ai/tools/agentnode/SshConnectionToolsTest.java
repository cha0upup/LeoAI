package org.leo.ai.tools.agentnode;

import dev.langchain4j.service.tool.ToolService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.leo.ai.agent.AiToolContext;
import org.leo.ai.agent.AiToolException;
import org.leo.ai.service.AiUserInputService;
import org.leo.ai.thread.AiUserInputRepository;
import org.leo.core.entity.AiExecutionPolicy;
import org.leo.core.entity.AiUserInputRequest;
import org.leo.core.entity.User;
import org.leo.core.util.json.JsonUtil;
import org.leo.service.ssh.SshConnectionException;
import org.leo.service.ssh.SshConnectionOperations;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SshConnectionToolsTest {
    final SshConnectionOperations connections = mock(SshConnectionOperations.class);
    final AiUserInputRepository requests = mock(AiUserInputRepository.class);
    final SshConnectionTools tools = new SshConnectionTools(connections, requests);
    final String pin = "SHA256:" + "A".repeat(43);
    final String id = "cfe5054d-84ea-4a81-b9c5-f2f8795f99dc";

    @BeforeEach void context() {
        AiToolContext.setFromMemoryId("ssh-session:ssh-thread");
        User actor = new User(); actor.setUserId("actor"); actor.setStatus(1);
        AiToolContext.setExecutionPolicy(AiExecutionPolicy.from(actor));
    }

    @AfterEach void cleanup() { AiToolContext.clear(); }

    @Test void delegatesAllCrudUsingTheAuthenticatedActorAndImplicitSession() {
        Map<String, Object> config = Map.of("host", "ssh.internal", "username", "ops", "password", "fixture-secret");
        Map<String, Object> patch = Map.of("name", "renamed");
        Map<String, Object> profile = Map.of("profileId", id, "version", 1L);
        when(connections.list("actor", "ssh-session")).thenReturn(List.of(profile));
        when(connections.get("actor", "ssh-session", id)).thenReturn(profile);
        when(connections.create("actor", "ssh-session", config)).thenReturn(profile);
        when(connections.update("actor", "ssh-session", id, 1L, patch)).thenReturn(profile);
        when(connections.delete("actor", "ssh-session", id, 1L)).thenReturn(Map.of("deleted", true));

        assertEquals(List.of(profile), tools.listSshConnections());
        assertEquals(profile, tools.getSshConnection(id));
        assertEquals(profile, tools.createSshConnection(config));
        assertEquals(profile, tools.updateSshConnection(id, 1L, patch));
        assertEquals(true, tools.deleteSshConnection(id, 1L).get("deleted"));
        verify(connections).create("actor", "ssh-session", config);
        verify(connections).update("actor", "ssh-session", id, 1L, patch);
        verify(connections).delete("actor", "ssh-session", id, 1L);
        verifyNoInteractions(requests);
    }

    @Test void testsWithTheSavedPinWithoutRequestingUserConfirmationAgain() {
        when(connections.test("actor", "ssh-session", id, 1L, null)).thenReturn(Map.of("connected", true));
        assertEquals(true, tools.testSshConnection(id, 1L, null, null).get("connected"));
        verifyNoInteractions(requests);
    }

    @Test void returnsTheExactArgumentsToConfirmWhenAHostKeyIsUnknownOrChanged() {
        when(connections.test("actor", "ssh-session", id, 1L, null)).thenReturn(Map.of(
                "profileId", id, "version", 1L, "fingerprint", pin, "confirmationRequired", true));
        assertEquals(Map.of("profileId", id, "version", 1L, "fingerprint", pin),
                tools.testSshConnection(id, 1L, null, null).get("confirmationArguments"));
        verifyNoInteractions(requests);
    }

    @Test void sendsAnExplicitPinOnlyAfterAtomicallyConsumingTheMatchingUserConfirmation() {
        confirmedRequest();
        when(requests.consumeConfirmation(eq("question-1"), eq("ssh-thread"), eq("testSshConnection"), anyString(), anyLong()))
                .thenReturn(true);
        when(connections.test("actor", "ssh-session", id, 1L, pin)).thenReturn(Map.of("connected", true));

        assertEquals(true, tools.testSshConnection(id, 1L, pin, "question-1").get("connected"));
        var order = inOrder(requests, connections);
        order.verify(requests).consumeConfirmation(eq("question-1"), eq("ssh-thread"),
                eq("testSshConnection"), anyString(), anyLong());
        order.verify(connections).test("actor", "ssh-session", id, 1L, pin);
    }

    @Test void rejectsMissingRejectedExpiredConsumedAndCrossThreadConfirmationsBeforeConnecting() {
        assertConfirmationRequired(null);
        AiUserInputRequest request = confirmedRequest();
        request.setAnswer("cancel"); assertConfirmationRequired("question-1");
        request.setAnswer("confirm"); request.setExpiresAt(1L); assertConfirmationRequired("question-1");
        request.setExpiresAt(Long.MAX_VALUE); request.setConfirmationConsumedAt(1L); assertConfirmationRequired("question-1");
        request.setConfirmationConsumedAt(null); request.setThreadId("other-thread"); assertConfirmationRequired("question-1");
        request.setThreadId("ssh-thread"); request.setToolName("otherTool"); assertConfirmationRequired("question-1");
        verifyNoInteractions(connections);
        verify(requests, never()).consumeConfirmation(any(), any(), any(), any(), anyLong());
    }

    @Test void rejectsChangedArgumentsAndConfirmationReplayBeforeConnecting() {
        confirmedRequest();
        assertThrows(AiToolException.class, () -> tools.testSshConnection(id, 2L, pin, "question-1"));
        assertThrows(AiToolException.class, () -> tools.testSshConnection(id, 1L,
                "SHA256:" + "B".repeat(43), "question-1"));
        // A racing call has already consumed this confirmation.
        when(requests.consumeConfirmation(any(), any(), any(), any(), anyLong())).thenReturn(false);
        assertConfirmationRequired("question-1");
        verifyNoInteractions(connections);
    }

    @Test void requiresAuthenticatedIdentityRatherThanFallingBackToTheSessionOwner() {
        AiToolContext.setExecutionPolicy(null);
        assertThrows(SecurityException.class, tools::listSshConnections);
        verifyNoInteractions(connections);
    }

    @Test void translatesTypedApplicationFailuresWithoutLosingVersionConflictSemantics() {
        when(connections.get("actor", "ssh-session", id)).thenThrow(new SshConnectionException(
                SshConnectionException.Kind.CONFLICT, "配置已被修改"));
        AiToolException error = assertThrows(AiToolException.class, () -> tools.getSshConnection(id));
        assertEquals("SSH_CONFLICT", error.code()); assertEquals(AiToolException.Recovery.MODEL, error.recovery());
    }

    @Test void exposesAllSixToolsWithOnlyThePinAndReceiptOptionalForTesting() {
        var declarations = ToolService.findTools(tools);
        assertEquals(6, declarations.size());
        var schema = declarations.stream().filter(tool -> tool.name().equals("testSshConnection"))
                .findFirst().orElseThrow().toolSpecification().parameters();
        assertEquals(List.of("profileId", "version"), schema.required());
        assertTrue(schema.properties().keySet().containsAll(List.of("fingerprint", "confirmationRequestId")));
    }

    private AiUserInputRequest confirmedRequest() {
        AiUserInputRequest request = new AiUserInputRequest();
        request.setRequestId("question-1"); request.setThreadId("ssh-thread");
        request.setRequestType(AiUserInputRequest.TYPE_CONFIRMATION);
        request.setStatus(AiUserInputRequest.STATUS_ANSWERED); request.setToolName("testSshConnection");
        request.setArgumentsHash(AiUserInputService.confirmationArgumentsHash(JsonUtil.toJsonString(
                Map.of("profileId", id, "version", 1L, "fingerprint", pin))));
        request.setOptionsJson("[{\"label\":\"确认\",\"value\":\"confirm\",\"intent\":\"CONFIRM\"},"
                + "{\"label\":\"取消\",\"value\":\"cancel\",\"intent\":\"REJECT\"}]");
        request.setAnswer("confirm"); request.setExpiresAt(Long.MAX_VALUE);
        when(requests.find("question-1")).thenReturn(request);
        return request;
    }

    private void assertConfirmationRequired(String requestId) {
        AiToolException error = assertThrows(AiToolException.class,
                () -> tools.testSshConnection(id, 1L, pin, requestId));
        assertEquals("SSH_HOST_KEY_CONFIRMATION_REQUIRED", error.code());
    }
}
