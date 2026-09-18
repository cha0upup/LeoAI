package org.leo.web.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.leo.ai.audit.AiAuditLogStore;
import org.leo.core.ai.AiRuntimeState;
import org.leo.core.session.AiThread;
import org.leo.core.session.PuppetNodeSession;
import org.leo.core.session.PuppetNodeSessionContainer;
import org.leo.ai.platform.PlatformAiState;
import org.leo.ai.platform.PlatformAiStateStore;
import org.leo.ai.runtime.AiTurnOrchestrator;
import org.leo.ai.runtime.AiTurnOutcome;
import org.leo.ai.service.AiUserInputService;
import org.leo.ai.thread.AiConversationStoreService;
import org.leo.core.entity.AiChatAuditEntry;
import org.leo.core.entity.AiExecutionPolicy;
import org.leo.core.entity.AiThreadRecord;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.times;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiTurnApplicationServiceTest {

    @AfterEach
    void cleanState() {
        PlatformAiStateStore.remove("thread-1");
        PuppetNodeSessionContainer.clearAllSessions();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void completesProtocolAndReleasesLeaseOnlyAfterRuntimeFutureCompletes(boolean puppet) {
        Fixture fixture = fixture(puppet);

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
    void propagatesRuntimeFailureStatusAndMessageToProtocolTerminal(boolean puppet) {
        Fixture fixture = fixture(puppet);

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
    void mapsRuntimeCancellationToInterruptedProtocolStatus(boolean puppet) {
        Fixture fixture = fixture(puppet);

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
    void preservesWaitingForUserStatus(boolean puppet) {
        Fixture fixture = fixture(puppet);
        var terminal = fixture.application.execute(fixture.turn);
        fixture.state.markWaitingForUserInput();
        fixture.runtime.complete(new AiTurnOrchestrator.TerminalResult(AiTurnOutcome.COMPLETED, null));

        assertTrue(terminal.join());
        verify(fixture.protocol).completeFromRuntime("turn-1", "waiting_for_user", null, "lease-1");
        fixture.verifyReleased(true);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void exceptionalRuntimeCompletionFailsTurnAndReleasesLease(boolean puppet) {
        Fixture fixture = fixture(puppet);
        var terminal = fixture.application.execute(fixture.turn);
        fixture.runtime.completeExceptionally(new IllegalStateException("runtime failed"));

        assertTrue(terminal.join());
        verify(fixture.protocol).failStart("turn-1", "runtime failed", "lease-1");
        fixture.verifyFailed(true);
        fixture.verifyReleased(true);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void lateFailureDoesNotReleaseOrFailTheNextTurn(boolean puppet) {
        Fixture fixture = fixture(puppet);
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
    void protocolFailureStillReleasesLease(boolean puppet) {
        Fixture fixture = fixture(puppet);
        when(fixture.protocol.completeFromRuntime("turn-1", "completed", null, "lease-1"))
                .thenThrow(new IllegalStateException("database unavailable"));
        var terminal = fixture.application.execute(fixture.turn);
        fixture.runtime.complete(new AiTurnOrchestrator.TerminalResult(AiTurnOutcome.COMPLETED, null));

        assertThrows(java.util.concurrent.CompletionException.class, terminal::join);
        fixture.verifyReleased(true);
    }

    private static Fixture fixture(boolean puppet) {
        AiTurnProtocolService protocol = mock(AiTurnProtocolService.class);
        AiConversationStoreService store = mock(AiConversationStoreService.class);
        PlatformAiThreadService platformThreads = mock(PlatformAiThreadService.class);
        PlatformAiTurnService platformTurns = mock(PlatformAiTurnService.class);
        PuppetNodeAiThreadService puppetThreads = mock(PuppetNodeAiThreadService.class);
        PuppetNodeAiTurnService puppetTurns = mock(PuppetNodeAiTurnService.class);
        AiAuditLogStore auditLogStore = mock(AiAuditLogStore.class);
        AiUserInputService userInputService = mock(AiUserInputService.class);
        when(userInputService.resumePrompt(anyString(), nullable(String.class), anyString()))
                .thenAnswer(invocation -> invocation.getArgument(2));
        AiTurnApplicationService application = new AiTurnApplicationService(
                protocol, store, platformThreads, platformTurns,
                puppetThreads, puppetTurns, auditLogStore, userInputService);

        AiThreadRecord persisted = new AiThreadRecord();
        persisted.setThreadId("thread-1");
        when(store.findThread("thread-1")).thenReturn(persisted);

        AiRuntimeState state;
        CompletableFuture<AiTurnOrchestrator.TerminalResult> runtime = new CompletableFuture<>();
        if (puppet) {
            AiThread thread = new AiThread("thread-1", "test");
            state = thread;
            PuppetNodeSession session = new PuppetNodeSession();
            session.setSessionId("session-1");
            PuppetNodeSessionContainer.addSession("session-1", session);
            when(puppetThreads.ensureThreadReady(session, "thread-1", null))
                    .thenReturn(new PuppetNodeAiThreadService.ThreadResolution(thread, false, false, null));
            when(puppetTurns.tryClaimExecution(thread)).thenAnswer(invocation -> {
                thread.bindActiveLeaseToken("lease-1");
                return true;
            });
            when(puppetTurns.executeChat(eq(session), eq(thread), eq("thread-1"), eq("hello"),
                    any(AiChatAuditEntry.class), isNull(), anyLong(), nullable(String.class), eq("hello"),
                    any(), eq("turn-1"), eq("user-1"), eq("assistant-1"))).thenReturn(runtime);
        } else {
            PlatformAiState platformState = PlatformAiStateStore.create("thread-1");
            state = platformState;
            when(platformTurns.tryClaimExecution(platformState)).thenAnswer(invocation -> {
                platformState.bindActiveLeaseToken("lease-1");
                return true;
            });
            AiChatAuditEntry audit = mock(AiChatAuditEntry.class);
            when(platformTurns.appendChatAudit(any(AiExecutionPolicy.class), eq("hello"))).thenReturn(audit);
            when(platformTurns.executeChat(
                    eq(platformState), eq("session-1"), eq("hello"), eq("hello"),
                    eq(audit), isNull(), anyLong(), nullable(String.class),
                    any(), eq("turn-1"), eq("user-1"), eq("assistant-1"))).thenReturn(runtime);
        }
        String scope = puppet ? AiTurnCommandPayload.SCOPE_PUPPET : AiTurnCommandPayload.SCOPE_PLATFORM;

        AiTurnCommandPayload command = AiTurnCommandPayload.create(
                scope,
                "session-1", "hello", "hello", null, null,
                List.of(), AiExecutionPolicy.defaultPolicy());
        AiTurnProtocolService.TurnSnapshot turn = new AiTurnProtocolService.TurnSnapshot(
                "turn-1", "thread-1", "running", "client-1",
                "user-1", "assistant-1", 1L, 2L, null,
                false, null, scope,
                command.toJson());
        when(protocol.completeFromRuntime(
                eq("turn-1"), anyString(), nullable(String.class), eq("lease-1")))
                .thenAnswer(invocation -> completedTurn(
                        invocation.getArgument(1), invocation.getArgument(2)));

        when(protocol.failStart(eq("turn-1"), anyString(), nullable(String.class)))
                .thenAnswer(invocation -> completedTurn("failed", invocation.getArgument(1)));
        return new Fixture(application, protocol, platformTurns, puppetTurns, state, runtime, turn);
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
                false, errorMessage, AiTurnCommandPayload.SCOPE_PLATFORM, "{}");
    }

    private record Fixture(
            AiTurnApplicationService application,
            AiTurnProtocolService protocol,
            PlatformAiTurnService platformTurns,
            PuppetNodeAiTurnService puppetTurns,
            AiRuntimeState state,
            CompletableFuture<AiTurnOrchestrator.TerminalResult> runtime,
            AiTurnProtocolService.TurnSnapshot turn) {
        private void verifyReleased(boolean released) {
            if (state instanceof AiThread thread) verify(puppetTurns, times(released ? 1 : 0)).releaseExecutionLease(thread);
            else verify(platformTurns, times(released ? 1 : 0)).releaseExecutionLease((PlatformAiState) state);
        }

        private void verifyFailed(boolean failed) {
            if (state instanceof AiThread thread) verify(puppetTurns, times(failed ? 1 : 0)).failDetachedExecution(thread);
            else verify(platformTurns, times(failed ? 1 : 0)).failDetachedExecution((PlatformAiState) state);
        }
    }
}
