package org.leo.web.application.ai;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.leo.ai.thread.AiExecutionLeaseRepository;
import org.leo.ai.thread.AiProtocolTurnRepository;
import org.leo.core.entity.AiThreadLeaseRecord;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AiExecutionLeaseServiceTest {
    private final AiExecutionLeaseRepository leases = mock(AiExecutionLeaseRepository.class);
    private final AiProtocolTurnRepository protocolTurns = mock(AiProtocolTurnRepository.class);
    private final AiExecutionLeaseService service = new AiExecutionLeaseService(leases, protocolTurns);

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void failingStopCallbacksDoNotPreventOtherLeasesFromBeingProcessed(boolean renewed) {
        when(leases.acquire(any())).thenReturn(true);
        when(leases.renew(any())).thenReturn(renewed);
        when(protocolTurns.hasInterruptRequest(any())).thenReturn(true);
        AtomicInteger stopped = new AtomicInteger();
        Runnable callback = () -> {
            stopped.incrementAndGet();
            throw new IllegalStateException("fixture stop failure");
        };
        assertNotNull(service.tryAcquireToken("thread-1", callback));
        assertNotNull(service.tryAcquireToken("thread-2", callback));

        assertDoesNotThrow(service::renewLocalLeases);
        assertEquals(2, stopped.get());
        assertDoesNotThrow(service::renewLocalLeases);
        assertEquals(2, stopped.get());
    }

    @Test
    void acquiresRenewsAndReleasesOwnedLease() {
        when(leases.acquire(any())).thenReturn(true);
        when(leases.renew(any())).thenReturn(true);

        String leaseToken = service.tryAcquireToken("thread-1", () -> {});
        assertNotNull(leaseToken);
        assertNull(service.tryAcquireToken("thread-1", () -> {}));
        service.renewLocalLeases();
        service.release("thread-1");
        service.release("thread-1");

        var lease = ArgumentCaptor.forClass(AiThreadLeaseRecord.class);
        verify(leases).acquire(lease.capture());
        assertEquals(leaseToken, lease.getValue().getLeaseToken());
        assertEquals("thread-1", lease.getValue().getThreadId());
        verify(leases).renew(same(lease.getValue()));
        verify(leases).release(same(lease.getValue()));
    }

    @Test
    void stopsLocalExecutionWhenLeaseRenewalIsRejected() {
        when(leases.acquire(any())).thenReturn(true);
        when(leases.renew(any())).thenReturn(false);
        AtomicInteger stopped = new AtomicInteger();

        assertNotNull(service.tryAcquireToken("thread-2", stopped::incrementAndGet));
        service.renewLocalLeases();
        service.renewLocalLeases();

        assertEquals(1, stopped.get());
        assertNotNull(service.tryAcquireToken("thread-2", () -> {}));
        verifyNoInteractions(protocolTurns);
    }

    @Test
    void deliversAnInterruptOnlyOnceWhileContinuingToRenewTheLease() {
        when(leases.acquire(any())).thenReturn(true);
        when(leases.renew(any())).thenReturn(true);
        when(protocolTurns.hasInterruptRequest("thread")).thenReturn(false, true);
        AtomicInteger stopped = new AtomicInteger();
        assertNotNull(service.tryAcquireToken("thread", stopped::incrementAndGet));

        service.renewLocalLeases();
        assertEquals(0, stopped.get());
        service.renewLocalLeases();
        service.renewLocalLeases();

        assertEquals(1, stopped.get());
        verify(leases, times(3)).renew(any());
    }

    @Test
    void atomicallyClaimsExpiredLeaseBeforeRecoveringOrphan() {
        AiThreadLeaseRecord expired = expiredLease();
        when(leases.listExpired(anyLong()))
                .thenReturn(List.of(expired));
        when(leases.claimExpired(any(), any())).thenReturn(true);
        when(leases.recoverOrphanedRuns(any(), anyLong()))
                .thenReturn(List.of());

        service.recoverExpiredLeases();

        ArgumentCaptor<AiThreadLeaseRecord> recovery =
                ArgumentCaptor.forClass(AiThreadLeaseRecord.class);
        var order = inOrder(leases);
        order.verify(leases).claimExpired(eq(expired), recovery.capture());
        assertTrue(recovery.getValue().getExpiresAt()
                > recovery.getValue().getAcquiredAt());
        assertNotEquals(expired.getLeaseToken(), recovery.getValue().getLeaseToken());
        order.verify(leases).recoverOrphanedRuns(eq("thread-3"), anyLong());
        order.verify(leases).release(same(recovery.getValue()));
    }

    @ParameterizedTest
    @CsvSource({"expired,true", "expired,false", "leaseless,true", "leaseless,false"})
    void recoveryRequiresAClaimAndAlwaysReleasesItAfterFailure(String kind, boolean claimed) {
        Runnable recover;
        if (kind.equals("expired")) {
            when(leases.listExpired(anyLong())).thenReturn(List.of(expiredLease()));
            when(leases.claimExpired(any(), any())).thenReturn(claimed);
            recover = service::recoverExpiredLeases;
        } else {
            when(leases.listStuckThreads(anyLong())).thenReturn(List.of("thread-3"));
            when(leases.acquire(any())).thenReturn(claimed);
            recover = service::recoverLeaselessStuckTurns;
        }
        var failure = new IllegalStateException("fixture database failure");
        when(leases.recoverOrphanedRuns(anyString(), anyLong())).thenThrow(failure);

        if (claimed) {
            assertSame(failure, assertThrows(IllegalStateException.class, recover::run));
            var recovery = ArgumentCaptor.forClass(AiThreadLeaseRecord.class);
            verify(leases).release(recovery.capture());
            assertEquals("thread-3", recovery.getValue().getThreadId());
            if (kind.equals("expired")) verify(leases).claimExpired(any(), same(recovery.getValue()));
            else verify(leases).acquire(same(recovery.getValue()));
        } else {
            assertDoesNotThrow(recover::run);
            verify(leases, never()).recoverOrphanedRuns(anyString(), anyLong());
            verify(leases, never()).release(any());
        }
    }

    @Test
    void leaselessRecoverySkipsExecutionsWithAnActiveLocalLease() {
        when(leases.acquire(any())).thenReturn(true);
        assertNotNull(service.tryAcquireToken("local", () -> {}));
        when(leases.listStuckThreads(anyLong())).thenReturn(List.of("local", "orphan"));

        service.recoverLeaselessStuckTurns();

        verify(leases, never()).recoverOrphanedRuns(eq("local"), anyLong());
        verify(leases).recoverOrphanedRuns(eq("orphan"), anyLong());
        var recovery = ArgumentCaptor.forClass(AiThreadLeaseRecord.class);
        verify(leases).release(recovery.capture());
        assertEquals("orphan", recovery.getValue().getThreadId());
    }

    private static AiThreadLeaseRecord expiredLease() {
        var expired = new AiThreadLeaseRecord();
        expired.setThreadId("thread-3");
        expired.setOwnerId("dead-instance");
        expired.setLeaseToken("old-token");
        expired.setExpiresAt(1L);
        return expired;
    }
}
