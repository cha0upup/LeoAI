package org.leo.web.application.ai;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.leo.ai.audit.AiAuditLogStore;
import org.leo.ai.platform.PlatformAiState;
import org.leo.ai.platform.PlatformAiStateStore;
import org.leo.ai.runtime.AiTurnCoordinator;
import org.leo.ai.runtime.AiTurnOrchestrator;
import org.leo.ai.runtime.AiTurnOutcome;
import org.leo.ai.service.AiUserInputService;
import org.leo.ai.thread.AiConversationStoreService;
import org.leo.core.ai.AiRuntimeState;
import org.leo.core.entity.AiChatAuditEntry;
import org.leo.core.entity.AiExecutionPolicy;
import org.leo.core.entity.AiThreadRecord;
import org.leo.core.session.AgentNodeSession;
import org.leo.core.session.AgentNodeSessionContainer;
import org.leo.core.session.AiThread;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AiTurnApplicationServiceTest {

    @AfterEach
    void cleanState() {
        PlatformAiStateStore.remove("thread-1");
        AgentNodeSessionContainer.clearAllSessions();
    }

    @Test
    void closesLostNodeSessionInsteadOfRequeueingForever() {
        Fixture fixture = fixture(true);
        AgentNodeSessionContainer.clearAllSessions();

        assertTrue(fixture.application.execute(fixture.turn).join());

        verify(fixture.protocol).failStart("turn-1", "节点会话已失效，请重新连接节点后重新提交任务", null);
        verify(fixture.protocol, never()).requeue(anyString());
        verifyNoInteractions(fixture.claims);
        fixture.verifyReleased(false);
    }

    @Test
    void threadResolutionFailureStillClosesTheClaimedProtocolTurn() {
        Fixture fixture = fixture(true);
        when(fixture.agentThreads.ensureThreadReady(any(), eq("thread-1"), nullable(Integer.class)))
                .thenThrow(new IllegalStateException("thread no longer accessible"));

        assertTrue(fixture.application.execute(fixture.turn).join());

        verify(fixture.protocol).failStart("turn-1", "thread no longer accessible", null);
        verify(fixture.protocol, never()).requeue(anyString());
        verifyNoInteractions(fixture.claims);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void completesProtocolAndReleasesLeaseOnlyAfterRuntimeFutureCompletes(boolean agent) {
        Fixture fixture = fixture(agent);

        CompletableFuture<Boolean> terminal = fixture.application.execute(fixture.turn);

        assertFalse(terminal.isDone());
        verify(fixture.protocol, never()).completeFromRuntime(
                anyString(), anyString(), nullable(String.class), anyString());
        fixture.verifyReleased(false);

        fixture.runtime.complete(new AiTurnOrchestrator.TerminalResult(
                AiTurnOutcome.COMPLETED, null));

        assertTrue(terminal.join());
        verify(fixture.protocol).completeFromRuntime(
                "turn-1", "completed", null, "lease-1");
        fixture.verifyReleased(true);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void propagatesRuntimeFailureStatusAndMessageToProtocolTerminal(boolean agent) {
        Fixture fixture = fixture(agent);

        CompletableFuture<Boolean> terminal = fixture.application.execute(fixture.turn);
        fixture.runtime.complete(new AiTurnOrchestrator.TerminalResult(
                AiTurnOutcome.FAILED, "模型调用失败"));

        assertTrue(terminal.join());
        verify(fixture.protocol).completeFromRuntime(
                "turn-1", "failed", "模型调用失败", "lease-1");
        fixture.verifyReleased(true);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void mapsRuntimeCancellationToInterruptedProtocolStatus(boolean agent) {
        Fixture fixture = fixture(agent);

        CompletableFuture<Boolean> terminal = fixture.application.execute(fixture.turn);
        fixture.runtime.complete(new AiTurnOrchestrator.TerminalResult(
                AiTurnOutcome.CANCELLED, "用户手动停止"));

        assertTrue(terminal.join());
        verify(fixture.protocol).completeFromRuntime(
                "turn-1", "cancelled", "用户手动停止", "lease-1");
        fixture.verifyReleased(true);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void preservesWaitingForUserStatus(boolean agent) {
        Fixture fixture = fixture(agent);
        var terminal = fixture.application.execute(fixture.turn);
        fixture.state.markWaitingForUserInput();
        fixture.runtime.complete(new AiTurnOrchestrator.TerminalResult(AiTurnOutcome.COMPLETED, null));

        assertTrue(terminal.join());
        verify(fixture.protocol).completeFromRuntime("turn-1", "waiting_for_user", null, "lease-1");
        fixture.verifyReleased(true);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void exceptionalRuntimeCompletionFailsTurnAndReleasesLease(boolean agent) {
        Fixture fixture = fixture(agent);
        var terminal = fixture.application.execute(fixture.turn);
        fixture.runtime.completeExceptionally(new IllegalStateException("runtime failed"));

        assertTrue(terminal.join());
        verify(fixture.protocol).failStart("turn-1", "runtime failed", "lease-1");
        fixture.verifyFailed(true);
        fixture.verifyReleased(true);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void lateFailureDoesNotReleaseOrFailTheNextTurn(boolean agent) {
        Fixture fixture = fixture(agent);
        var terminal = fixture.application.execute(fixture.turn);
        fixture.state.bindActiveTurnId("turn-2");
        fixture.state.bindActiveLeaseToken("lease-2");
        fixture.runtime.completeExceptionally(new IllegalStateException("late failure"));

        assertTrue(terminal.join());
        verify(fixture.protocol).failStart("turn-1", "late failure", "lease-1");
        fixture.verifyFailed(false);
        fixture.verifyReleased(false);
        assertTrue(fixture.state.getAiSseEventQueue().stream().noneMatch(event -> "turn/completed".equals(event.name())));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void protocolFailureStillReleasesLease(boolean agent) {
        Fixture fixture = fixture(agent);
        when(fixture.protocol.completeFromRuntime("turn-1", "completed", null, "lease-1"))
                .thenThrow(new IllegalStateException("database unavailable"));
        var terminal = fixture.application.execute(fixture.turn);
        fixture.runtime.complete(new AiTurnOrchestrator.TerminalResult(AiTurnOutcome.COMPLETED, null));

        assertThrows(java.util.concurrent.CompletionException.class, terminal::join);
        fixture.verifyReleased(true);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void preservesPreparedMessageAttachmentsAndProtocolIdentity(boolean agent) {
        Fixture fixture = fixture(agent);
        long beforeStart = System.currentTimeMillis();
        fixture.application.execute(fixture.turn);

        ArgumentCaptor<AiTurnExecutionRequest> captured = ArgumentCaptor.forClass(AiTurnExecutionRequest.class);
        if (agent) {
            verify(fixture.agentTurns).executeChat(
                    eq(AgentNodeSessionContainer.getSession("session-1")), eq((AiThread) fixture.state), captured.capture());
        } else {
            verify(fixture.platformTurns).executeChat(eq((PlatformAiState) fixture.state), captured.capture());
        }
        AiTurnExecutionRequest request = captured.getValue();
        assertEquals("session-1", request.sessionId());
        assertEquals("hello", request.userMessage());
        assertEquals("resumed: guarded hello", request.messageForAgent());
        assertEquals("high", request.reasoningEffort());
        assertEquals(List.of(Map.of("name", "context.txt", "content", "context")), request.attachments());
        assertEquals("turn-1", request.turnId());
        assertEquals("user-1", request.userItemId());
        assertEquals("assistant-1", request.assistantItemId());
        assertNotNull(request.audit());
        assertEquals(agent ? AiChatAuditEntry.SessionType.AGENT : AiChatAuditEntry.SessionType.PLATFORM,
                request.audit().getSessionType());
        assertEquals(agent ? "session-1" : null, request.audit().getSessionId());
        assertEquals("actor-1", request.audit().getUserId());
        assertEquals("alice", request.audit().getUserName());
        assertEquals("normal", request.audit().getPrivilege());
        assertEquals("hello", request.audit().getMessageSummary());
        verify(fixture.auditLogStore).append(request.audit());
        assertTrue(request.startMs() >= beforeStart && request.startMs() <= System.currentTimeMillis());
        fixture.runtime.complete(new AiTurnOrchestrator.TerminalResult(AiTurnOutcome.COMPLETED, null));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void leaseDenialRequeuesWithoutStartingExecutionOrWritingAudit(boolean agent) {
        Fixture fixture = fixture(agent);
        doReturn(false).when(fixture.claims).tryClaim(eq(fixture.state), eq("thread-1"), any(), any());

        assertFalse(fixture.application.execute(fixture.turn).join());

        verify(fixture.protocol).requeue("turn-1");
        assertNull(fixture.state.getActiveLeaseToken());
        verifyNoInteractions(fixture.auditLogStore, fixture.platformTurns, fixture.agentTurns);
        fixture.verifyFailed(false);
        fixture.verifyReleased(false);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void lostLeaseStopsTheOwningRuntimeAndPreservesPlatformWarnings(boolean agent) {
        Fixture fixture = fixture(agent);
        var terminal = fixture.application.execute(fixture.turn);
        ArgumentCaptor<Runnable> callback = ArgumentCaptor.forClass(Runnable.class);
        verify(fixture.claims).tryClaim(eq(fixture.state), eq("thread-1"), callback.capture(), any());

        callback.getValue().run();

        assertTrue(fixture.state.isStopRequested());
        assertEquals("执行租约已转移", fixture.state.getStopReason());
        assertEquals(!agent, fixture.state.getAiSseEventQueue().stream()
                .anyMatch(event -> "warn".equals(event.name()) && "执行租约已转移".equals(event.data())));
        fixture.runtime.complete(new AiTurnOrchestrator.TerminalResult(AiTurnOutcome.CANCELLED, "执行租约已转移"));
        assertTrue(terminal.join());
        fixture.verifyReleased(true);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void auditFailureClosesTheTurnAndReleasesItsLeaseBeforeExecution(boolean agent) {
        Fixture fixture = fixture(agent);
        doThrow(new IllegalStateException("audit unavailable"))
                .when(fixture.auditLogStore).append(any(AiChatAuditEntry.class));

        assertTrue(fixture.application.execute(fixture.turn).join());

        verify(fixture.protocol).failStart("turn-1", "audit unavailable", "lease-1");
        verifyNoInteractions(fixture.platformTurns, fixture.agentTurns);
        fixture.verifyFailed(true);
        fixture.verifyReleased(true);
    }

    private static Fixture fixture(boolean agent) {
        AiTurnProtocolService protocol = mock(AiTurnProtocolService.class);
        AiConversationStoreService store = mock(AiConversationStoreService.class);
        PlatformAiThreadService platformThreads = mock(PlatformAiThreadService.class);
        PlatformAiTurnService platformTurns = mock(PlatformAiTurnService.class);
        NodeAiThreadService agentThreads = mock(NodeAiThreadService.class);
        NodeAiTurnService agentTurns = mock(NodeAiTurnService.class);
        AiAuditLogStore auditLogStore = mock(AiAuditLogStore.class);
        AiUserInputService userInputService = mock(AiUserInputService.class);
        AiExecutionClaimService claims = mock(AiExecutionClaimService.class);
        AiTurnCoordinator coordinator = mock(AiTurnCoordinator.class);
        when(userInputService.resumePrompt(anyString(), nullable(String.class), anyString()))
                .thenAnswer(invocation -> "resumed: " + invocation.getArgument(2));
        AiTurnApplicationService application = new AiTurnApplicationService(
                protocol, store, platformThreads, platformTurns,
                agentThreads, agentTurns, auditLogStore, userInputService, claims, coordinator);

        AiThreadRecord persisted = new AiThreadRecord();
        persisted.setThreadId("thread-1");
        when(store.findThread("thread-1")).thenReturn(persisted);

        AiRuntimeState state;
        CompletableFuture<AiTurnOrchestrator.TerminalResult> runtime = new CompletableFuture<>();
        if (agent) {
            AiThread thread = new AiThread("thread-1", "test");
            state = thread;
            AgentNodeSession session = new AgentNodeSession();
            session.setSessionId("session-1");
            AgentNodeSessionContainer.addSession("session-1", session);
            when(agentThreads.ensureThreadReady(session, "thread-1", null))
                    .thenReturn(new NodeAiThreadService.ThreadResolution(thread, null));
            when(agentTurns.executeChat(eq(session), eq(thread), any(AiTurnExecutionRequest.class)))
                    .thenReturn(runtime);
        } else {
            PlatformAiState platformState = PlatformAiStateStore.getOrCreate("thread-1");
            state = platformState;
            when(platformTurns.executeChat(eq(platformState), any(AiTurnExecutionRequest.class)))
                    .thenReturn(runtime);
        }
        when(claims.tryClaim(eq(state), eq("thread-1"), any(), any())).thenAnswer(invocation -> {
            state.bindActiveLeaseToken("lease-1");
            return true;
        });
        String scope = agent ? AiTurnCommandPayload.SCOPE_AGENT : AiTurnCommandPayload.SCOPE_PLATFORM;

        AiTurnCommandPayload command = AiTurnCommandPayload.create(
                scope,
                "session-1", "hello", "guarded hello", null, "high",
                List.of(Map.of("name", "context.txt", "content", "context")),
                new AiExecutionPolicy("actor-1", "alice", "normal")).answerTo("question-1");
        AiTurnProtocolService.TurnSnapshot turn = new AiTurnProtocolService.TurnSnapshot(
                "turn-1", "thread-1", "running", "client-1",
                "user-1", "assistant-1", 1L, 2L, null,
                false, null, scope,
                command.toJson(), "question-1");
        when(protocol.completeFromRuntime(
                eq("turn-1"), anyString(), nullable(String.class), eq("lease-1")))
                .thenAnswer(invocation -> completedTurn(
                        invocation.getArgument(1), invocation.getArgument(2)));

        when(protocol.failStart(eq("turn-1"), anyString(), nullable(String.class)))
                .thenAnswer(invocation -> completedTurn("failed", invocation.getArgument(1)));
        return new Fixture(application, protocol, platformTurns, agentTurns, agentThreads,
                claims, coordinator, auditLogStore, state, runtime, turn);
    }

    private static AiTurnProtocolService.TurnSnapshot completedTurn(
            String runtimeStatus, String errorMessage) {
        String protocolStatus = switch (runtimeStatus) {
            case "completed", "waiting_for_user" -> "completed";
            case "cancelled" -> "interrupted";
            default -> "failed";
        };
        return new AiTurnProtocolService.TurnSnapshot(
                "turn-1", "thread-1", protocolStatus, "client-1",
                "user-1", "assistant-1", 1L, 2L, 3L,
                false, errorMessage, AiTurnCommandPayload.SCOPE_PLATFORM, "{}", null);
    }

    private record Fixture(
            AiTurnApplicationService application,
            AiTurnProtocolService protocol,
            PlatformAiTurnService platformTurns,
            NodeAiTurnService agentTurns,
            NodeAiThreadService agentThreads,
            AiExecutionClaimService claims,
            AiTurnCoordinator coordinator,
            AiAuditLogStore auditLogStore,
            AiRuntimeState state,
            CompletableFuture<AiTurnOrchestrator.TerminalResult> runtime,
            AiTurnProtocolService.TurnSnapshot turn) {
        private void verifyReleased(boolean released) {
            verify(claims, times(released ? 1 : 0)).release(state, "thread-1");
        }

        private void verifyFailed(boolean failed) {
            verify(coordinator, times(failed ? 1 : 0)).failAndRelease(state);
        }
    }
}
