package org.leo.web.application.ai;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.leo.ai.runtime.AiTurnCoordinator;
import org.leo.core.ai.AiRuntimeState;

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
    void bindsLeaseRejectsConcurrentExecutionAndClearsReleasedToken(boolean releaseFails) {
        Fixture fixture = new Fixture(new AiTurnCoordinator());
        when(fixture.leases.tryAcquireToken(eq("thread"), any())).thenReturn("lease");

        assertTrue(fixture.claim());
        assertTrue(fixture.runtime.isExecuting());
        assertEquals("lease", fixture.runtime.getActiveLeaseToken());
        assertFalse(fixture.claim());
        verify(fixture.leases, times(1)).tryAcquireToken(eq("thread"), any());

        RuntimeException failure = new IllegalStateException("database release failed");
        if (releaseFails) doThrow(failure).when(fixture.leases).release("thread");
        if (releaseFails) assertSame(failure, assertThrows(IllegalStateException.class, fixture::release));
        else fixture.release();
        verify(fixture.leases).release("thread");
        assertNull(fixture.runtime.getActiveLeaseToken());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void unsuccessfulLeaseAcquisitionReleasesLocalClaimForRetry(boolean leaseFails) {
        Fixture fixture = new Fixture(new AiTurnCoordinator());
        if (leaseFails) {
            when(fixture.leases.tryAcquireToken(eq("thread"), any()))
                    .thenThrow(new IllegalStateException("database unavailable")).thenReturn("retry-lease");
        } else {
            when(fixture.leases.tryAcquireToken(eq("thread"), any())).thenReturn(null, "retry-lease");
        }

        assertFalse(fixture.claim());
        assertFalse(fixture.runtime.isExecuting());
        assertNull(fixture.runtime.getActiveLeaseToken());
        assertTrue(fixture.claim());
        assertEquals("retry-lease", fixture.runtime.getActiveLeaseToken());
    }

    @Test
    void cancelledTurnMustReleaseBeforeAnotherLeaseIsAcquired() throws Exception {
        CountDownLatch waiting = new CountDownLatch(1);
        AiTurnCoordinator coordinator = new AiTurnCoordinator() {
            @Override
            public boolean tryClaimAfterRelease(org.leo.core.ai.AiTurnRuntime runtime,
                                                BooleanSupplier executing, long waitMillis) {
                waiting.countDown();
                return super.tryClaimAfterRelease(runtime, executing, waitMillis);
            }
        };
        Fixture fixture = new Fixture(coordinator);
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
        private final AiRuntimeState runtime = new AiRuntimeState();
        private final AiExecutionClaimService claims;

        private Fixture(AiTurnCoordinator coordinator) {
            claims = new AiExecutionClaimService(coordinator, leases);
        }

        private boolean claim() {
            return claims.tryClaim(runtime, "thread", () -> {}, error -> {});
        }

        private void release() {
            claims.release(runtime, "thread");
        }
    }
}
