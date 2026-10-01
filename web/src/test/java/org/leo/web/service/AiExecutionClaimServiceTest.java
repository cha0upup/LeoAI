package org.leo.web.service;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.leo.ai.platform.PlatformAiState;
import org.leo.ai.runtime.AiTurnCoordinator;
import org.leo.core.ai.AiRuntimeState;
import org.leo.core.session.AiThread;
import org.mockito.ArgumentCaptor;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class AiExecutionClaimServiceTest {

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void bindsLeaseAndRejectsConcurrentExecution(boolean agent) {
        Fixture fixture = new Fixture(agent, new AiTurnCoordinator());
        when(fixture.leases.tryAcquireToken(eq("thread"), any())).thenReturn("lease");

        assertTrue(fixture.claim());
        assertTrue(fixture.runtime.isExecuting());
        assertEquals("lease", fixture.runtime.getActiveLeaseToken());
        assertFalse(fixture.claim());
        verify(fixture.leases, times(1)).tryAcquireToken(eq("thread"), any());

        fixture.release();
        verify(fixture.leases).release("thread");
        assertNull(fixture.runtime.getActiveLeaseToken());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void leaseDenialReleasesLocalClaimForRetry(boolean agent) {
        Fixture fixture = new Fixture(agent, new AiTurnCoordinator());
        when(fixture.leases.tryAcquireToken(eq("thread"), any())).thenReturn(null, "retry-lease");

        assertFalse(fixture.claim());
        assertFalse(fixture.runtime.isExecuting());
        assertNull(fixture.runtime.getActiveLeaseToken());
        assertTrue(fixture.claim());
        assertEquals("retry-lease", fixture.runtime.getActiveLeaseToken());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void leaseFailureReleasesLocalClaimForRetry(boolean agent) {
        Fixture fixture = new Fixture(agent, new AiTurnCoordinator());
        when(fixture.leases.tryAcquireToken(eq("thread"), any()))
                .thenThrow(new IllegalStateException("database unavailable")).thenReturn("retry-lease");

        assertFalse(fixture.claim());
        assertFalse(fixture.runtime.isExecuting());
        assertTrue(fixture.claim());
        assertEquals("retry-lease", fixture.runtime.getActiveLeaseToken());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void lostLeaseCancelsTheOwningRuntime(boolean agent) {
        Fixture fixture = new Fixture(agent, new AiTurnCoordinator());
        when(fixture.leases.tryAcquireToken(eq("thread"), any())).thenReturn("lease");
        assertTrue(fixture.claim());
        ArgumentCaptor<Runnable> callback = ArgumentCaptor.forClass(Runnable.class);
        verify(fixture.leases).tryAcquireToken(eq("thread"), callback.capture());

        callback.getValue().run();

        assertTrue(fixture.runtime.isStopRequested());
        assertEquals("执行租约已转移", fixture.runtime.getStopReason());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void cancelledTurnMustReleaseBeforeAnotherLeaseIsAcquired(boolean agent) throws Exception {
        CountDownLatch waiting = new CountDownLatch(1);
        AiTurnCoordinator coordinator = new AiTurnCoordinator() {
            @Override
            public boolean tryClaimAfterRelease(org.leo.core.ai.AiTurnRuntime runtime,
                                                BooleanSupplier executing, long waitMillis) {
                waiting.countDown();
                return super.tryClaimAfterRelease(runtime, executing, waitMillis);
            }
        };
        Fixture fixture = new Fixture(agent, coordinator);
        fixture.runtime.claimExecution();
        fixture.runtime.markCancelled();
        when(fixture.leases.tryAcquireToken(eq("thread"), any())).thenReturn("next-lease");
        var executor = Executors.newSingleThreadExecutor();
        try {
            var claimed = executor.submit(fixture::claim);
            assertTrue(waiting.await(1, TimeUnit.SECONDS));
            assertFalse(claimed.isDone());
            verifyNoInteractions(fixture.leases);
            fixture.runtime.clearExecuting();
            assertTrue(claimed.get(1, TimeUnit.SECONDS));
            assertEquals("next-lease", fixture.runtime.getActiveLeaseToken());
            assertFalse(fixture.runtime.isStopRequested());
        } finally {
            executor.shutdownNow();
        }
    }

    private static final class Fixture {
        private final AiExecutionLeaseService leases = mock(AiExecutionLeaseService.class);
        private final AiRuntimeState runtime;
        private final PlatformAiTurnService platform;
        private final NodeAiTurnService agent;

        private Fixture(boolean isAgent, AiTurnCoordinator coordinator) {
            AiExecutionClaimService claims = new AiExecutionClaimService(coordinator, leases);
            runtime = isAgent ? new AiThread("thread", "test") : new PlatformAiState("thread");
            platform = new PlatformAiTurnService(null, null, null, null, coordinator, null, claims, null);
            agent = new NodeAiTurnService(null, null, null, coordinator, null, claims, null);
        }

        private boolean claim() {
            return runtime instanceof AiThread thread ? agent.tryClaimExecution(thread)
                    : platform.tryClaimExecution((PlatformAiState) runtime);
        }

        private void release() {
            if (runtime instanceof AiThread thread) agent.releaseExecutionLease(thread);
            else platform.releaseExecutionLease((PlatformAiState) runtime);
        }
    }
}
