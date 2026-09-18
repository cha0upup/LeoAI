package org.leo.web.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.leo.ai.agent.PuppetNodeAgent;
import org.leo.ai.platform.PlatformAiState;
import org.leo.ai.runtime.AiTurnCoordinator;
import org.leo.ai.runtime.AiTurnFailure;
import org.leo.ai.runtime.AiTurnOrchestrator;
import org.leo.ai.runtime.AiTurnOutcome;
import org.leo.ai.runtime.AiTurnTelemetryRegistry;
import org.leo.ai.runtime.AiTurnTransaction;
import org.leo.ai.service.AiErrorClassifier;
import org.leo.ai.thread.AiConversationStoreService;
import org.leo.core.entity.AiModelConfig;
import org.leo.core.session.AiThread;
import org.leo.core.session.PuppetNodeSession;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PuppetNodeAiDelegationServiceTest {

    @AfterEach
    void clearInterrupt() {
        Thread.interrupted();
    }

    @Test
    void parentCancellationStopsChildBeforeReleasingLease() {
        Fixture fixture = fixture();
        AtomicInteger cancellations = new AtomicInteger();
        when(fixture.orchestrator.execute(any(), any())).thenAnswer(call -> {
            var completion = installCancellation(call.getArgument(0), call.getArgument(1), cancellations);
            fixture.parent.stop("用户停止");
            return completion;
        });
        doAnswer(call -> {
            assertEquals(1, cancellations.get());
            assertEquals("cancelled", fixture.thread.getRunStatus());
            return null;
        }).when(fixture.leases).release("child-1");

        assertThrows(IllegalStateException.class, () -> execute(fixture));

        assertEquals(1, cancellations.get());
        assertFalse(fixture.thread.isExecuting());
        assertNull(fixture.thread.getActiveLeaseToken());
        verify(fixture.leases).release("child-1");
    }

    @Test
    void interruptedAwaitCancelsChildBeforeReleasingLease() throws Exception {
        Fixture fixture = fixture();
        AtomicInteger cancellations = new AtomicInteger();
        CountDownLatch started = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        when(fixture.orchestrator.execute(any(), any())).thenAnswer(call -> {
            var completion = installCancellation(call.getArgument(0), call.getArgument(1), cancellations);
            started.countDown();
            return completion;
        });
        doAnswer(call -> {
            assertEquals(1, cancellations.get());
            return null;
        }).when(fixture.leases).release("child-1");
        Thread worker = new Thread(() -> {
            try {
                execute(fixture);
            } catch (Throwable error) {
                failure.set(error);
            }
        });
        try {
            worker.start();
            assertTrue(started.await(5, TimeUnit.SECONDS));
            worker.interrupt();
            worker.join(5_000);
            assertFalse(worker.isAlive());
            assertInstanceOf(IllegalStateException.class, failure.get());
            assertEquals(1, cancellations.get());
            assertEquals("cancelled", fixture.thread.getRunStatus());
            assertFalse(fixture.thread.isExecuting());
            verify(fixture.leases).release("child-1");
        } finally {
            worker.interrupt();
            worker.join(5_000);
        }
    }

    @Test
    void alreadyCancelledParentCannotStartChildModel() {
        Fixture fixture = fixture();
        fixture.parent.stop("已停止");
        fixture.parent.clearExecuting();

        assertThrows(IllegalStateException.class, () -> execute(fixture));

        verifyNoInteractions(fixture.orchestrator, fixture.agents);
        verify(fixture.leases).release("child-1");
        assertEquals("cancelled", fixture.thread.getRunStatus());
    }

    @Test
    void keepsLeaseUntilOrchestratorFinishesTerminalWrites() throws Exception {
        Fixture fixture = fixture();
        CountDownLatch presented = new CountDownLatch(1);
        CountDownLatch released = new CountDownLatch(1);
        CompletableFuture<AiTurnOrchestrator.TerminalResult> completion = new CompletableFuture<>();
        CompletableFuture<Void> result = new CompletableFuture<>();
        var terminal = new AiTurnOrchestrator.TerminalResult(AiTurnOutcome.COMPLETED, null);
        when(fixture.orchestrator.execute(any(), any())).thenAnswer(call -> {
            AiTurnOrchestrator.Request request = call.getArgument(0);
            AiTurnOrchestrator.Lifecycle lifecycle = call.getArgument(1);
            request.command().execution().finish(AiTurnOutcome.COMPLETED, () ->
                    lifecycle.onCommitted(new AiTurnTransaction.CompletedTurn(
                            "done", 0, Map.of(), Map.of(), List.of())));
            presented.countDown();
            return completion;
        });
        doAnswer(call -> {
            released.countDown();
            return null;
        }).when(fixture.leases).release("child-1");
        Thread worker = new Thread(() -> {
            try {
                execute(fixture);
                result.complete(null);
            } catch (Throwable error) {
                result.completeExceptionally(error);
            }
        });
        try {
            worker.start();
            assertTrue(presented.await(5, TimeUnit.SECONDS));
            assertFalse(released.await(100, TimeUnit.MILLISECONDS));
            completion.complete(terminal);
            result.get(5, TimeUnit.SECONDS);
            assertEquals(0, released.getCount());
        } finally {
            completion.complete(terminal);
            worker.interrupt();
            worker.join(5_000);
        }
    }

    private void execute(Fixture fixture) {
        fixture.service.execute(fixture.session, fixture.thread,
                "检查", "检查", null, "invocation-1", event -> {}, fixture.parent);
    }

    private CompletableFuture<AiTurnOrchestrator.TerminalResult> installCancellation(
            AiTurnOrchestrator.Request request, AiTurnOrchestrator.Lifecycle lifecycle,
            AtomicInteger cancellations) {
        CompletableFuture<AiTurnOrchestrator.TerminalResult> completion = new CompletableFuture<>();
        request.command().execution().registerCancellation(() -> {
            cancellations.incrementAndGet();
            try {
                request.command().execution().finish(AiTurnOutcome.CANCELLED, () ->
                        lifecycle.onDiscarded(
                                new AiTurnTransaction.FailedTurn(AiTurnOutcome.CANCELLED,
                                        "cancelled", "已停止", null),
                                new AiTurnFailure(new InterruptedException("已停止"),
                                        AiTurnOutcome.CANCELLED, "已停止")));
                completion.complete(new AiTurnOrchestrator.TerminalResult(AiTurnOutcome.CANCELLED, "已停止"));
            } catch (Exception error) {
                throw new IllegalStateException(error);
            }
        });
        return completion;
    }

    private Fixture fixture() {
        AiConversationStoreService store = mock(AiConversationStoreService.class);
        PuppetNodeAiAgentRegistry agents = mock(PuppetNodeAiAgentRegistry.class);
        AiTurnOrchestrator orchestrator = mock(AiTurnOrchestrator.class);
        AiExecutionLeaseService leases = mock(AiExecutionLeaseService.class);
        AiModelChannelResolver channels = mock(AiModelChannelResolver.class);
        PuppetNodeAiDelegationPresenter presenter = new PuppetNodeAiDelegationPresenter(
                store, new AiErrorClassifier(), new AiTurnTelemetryRegistry());
        PuppetNodeSession session = new PuppetNodeSession();
        session.setSessionId("session-1");
        AiThread thread = session.createAiThread("child-1", "child");
        thread.setAiConfigId(7);
        AiModelConfig config = new AiModelConfig();
        config.setId(7);
        when(channels.require(7)).thenReturn(config);
        when(agents.resolve(session, thread, config, null)).thenReturn(
                new PuppetNodeAiAgentRegistry.Runtime("key", mock(PuppetNodeAgent.class), "{}", 7, null));
        when(leases.tryAcquireToken(eq("child-1"), any())).thenReturn("lease-1");
        when(store.beginTurn(isNull(), isNull(), isNull(), eq("child-1"), eq(7),
                anyString(), anyString(), isNull(), anyLong(), anyString(), any(), eq("lease-1")))
                .thenReturn(new AiConversationStoreService.PersistedTurn(
                        "turn-1", "run-1", "child-1", "user-1", "assistant-1", 1L, "lease-1"));
        PuppetNodeAiDelegationService service = new PuppetNodeAiDelegationService(
                store, agents, presenter, new AiTurnCoordinator(), orchestrator, leases, channels);
        return new Fixture(service, session, thread, new PlatformAiState("parent-1"),
                orchestrator, agents, leases);
    }

    private record Fixture(PuppetNodeAiDelegationService service,
                           PuppetNodeSession session, AiThread thread, PlatformAiState parent,
                           AiTurnOrchestrator orchestrator, PuppetNodeAiAgentRegistry agents,
                           AiExecutionLeaseService leases) {}
}
