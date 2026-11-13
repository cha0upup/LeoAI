package org.leo.web.application.ai;

import org.junit.jupiter.api.Test;
import org.leo.ai.thread.AiConversationStoreService;
import org.leo.core.entity.AiThreadLeaseRecord;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiExecutionLeaseServiceTest {

    @Test
    void acquiresRenewsAndReleasesOwnedLease() {
        AiConversationStoreService store = mock(AiConversationStoreService.class);
        when(store.acquireThreadLease(any())).thenReturn(true);
        when(store.renewThreadLease(any())).thenReturn(true);
        AiExecutionLeaseService service = new AiExecutionLeaseService(store);

        String leaseToken = service.tryAcquireToken("thread-1", () -> {});
        assertNotNull(leaseToken);
        assertFalse(service.tryAcquireToken("thread-1", () -> {}) != null);
        service.renewLocalLeases();
        service.release("thread-1");

        verify(store).renewThreadLease(any());
        verify(store).releaseThreadLease(any());
    }

    @Test
    void stopsLocalExecutionWhenLeaseRenewalIsRejected() {
        AiConversationStoreService store = mock(AiConversationStoreService.class);
        when(store.acquireThreadLease(any())).thenReturn(true);
        when(store.renewThreadLease(any())).thenReturn(false);
        AiExecutionLeaseService service = new AiExecutionLeaseService(store);
        AtomicBoolean stopped = new AtomicBoolean(false);

        assertTrue(service.tryAcquireToken("thread-2", () -> stopped.set(true)) != null);
        service.renewLocalLeases();

        assertTrue(stopped.get());
    }

    @Test
    void atomicallyClaimsExpiredLeaseBeforeRecoveringOrphan() {
        AiConversationStoreService store = mock(AiConversationStoreService.class);
        AiThreadLeaseRecord expired = new AiThreadLeaseRecord();
        expired.setThreadId("thread-3");
        expired.setOwnerId("dead-instance");
        expired.setLeaseToken("old-token");
        expired.setExpiresAt(1L);
        when(store.listExpiredThreadLeases(anyLong()))
                .thenReturn(List.of(expired));
        when(store.claimExpiredThreadLease(any(), any())).thenReturn(true);
        when(store.recoverOrphanedRuns(any(), anyLong()))
                .thenReturn(List.of());
        AiExecutionLeaseService service = new AiExecutionLeaseService(store);

        service.recoverExpiredLeases();

        ArgumentCaptor<AiThreadLeaseRecord> recovery =
                ArgumentCaptor.forClass(AiThreadLeaseRecord.class);
        verify(store).claimExpiredThreadLease(
                org.mockito.ArgumentMatchers.eq(expired), recovery.capture());
        assertTrue(recovery.getValue().getExpiresAt()
                > recovery.getValue().getAcquiredAt());
        verify(store).recoverOrphanedRuns(
                org.mockito.ArgumentMatchers.eq("thread-3"), anyLong());
        verify(store).releaseThreadLease(recovery.getValue());
    }
}
