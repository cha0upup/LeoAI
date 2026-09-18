package org.leo.web.service;

import org.leo.ai.runtime.AiTurnCoordinator;
import org.leo.core.ai.AiRuntimeState;
import org.leo.core.ai.AiRunStatus;
import org.springframework.stereotype.Service;

import java.util.function.Consumer;

/** Owns acquisition and release of the local execution claim and database lease. */
@Service
public class AiExecutionClaimService {

    private final AiTurnCoordinator turnCoordinator;
    private final AiExecutionLeaseService executionLeaseService;

    public AiExecutionClaimService(AiTurnCoordinator turnCoordinator,
                                   AiExecutionLeaseService executionLeaseService) {
        this.turnCoordinator = turnCoordinator;
        this.executionLeaseService = executionLeaseService;
    }

    /**
     * Waits for a cancelled turn to release its local claim before acquiring a new lease.
     * A failed or exceptional lease acquisition always releases that local claim.
     */
    public boolean tryClaim(AiRuntimeState runtime,
                            String threadId,
                            Runnable onLeaseLost,
                            Consumer<RuntimeException> onFailure) {
        boolean localClaimed = turnCoordinator.tryClaim(runtime);
        if (!localClaimed && AiRunStatus.CANCELLED.equals(runtime.getRunStatus())) {
            localClaimed = turnCoordinator.tryClaimAfterRelease(runtime, runtime::isExecuting, 5_000L);
        }
        if (!localClaimed) return false;
        try {
            String leaseToken = executionLeaseService.tryAcquireToken(
                    threadId, onLeaseLost);
            if (leaseToken != null) {
                runtime.bindActiveLeaseToken(leaseToken);
                return true;
            }
        } catch (RuntimeException error) {
            turnCoordinator.releaseClaim(runtime);
            if (onFailure != null) onFailure.accept(error);
            return false;
        }
        turnCoordinator.releaseClaim(runtime);
        return false;
    }

    public void release(AiRuntimeState runtime, String threadId) {
        if (runtime == null) return;
        executionLeaseService.release(threadId);
        runtime.bindActiveLeaseToken(null);
    }

}
