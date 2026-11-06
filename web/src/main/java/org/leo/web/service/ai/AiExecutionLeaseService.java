package org.leo.web.service.ai;

import org.leo.ai.thread.AiConversationStoreService;
import org.leo.core.entity.AiThreadLeaseRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 数据库支持的线程执行租约。进程内 claim 负责低成本互斥，本服务负责跨实例互斥。
 */
@Service
public class AiExecutionLeaseService {

    private static final Logger logger =
            LoggerFactory.getLogger(AiExecutionLeaseService.class);

    private final AiConversationStoreService store;
    private final ConcurrentMap<String, LocalLease> localLeases =
            new ConcurrentHashMap<>();
    private final String ownerId =
            ManagementFactory.getRuntimeMXBean().getName() + ":" + UUID.randomUUID();

    @Value("${leo.ai.lease.ttl-ms:30000}")
    private long ttlMillis = 30_000L;

    public AiExecutionLeaseService(AiConversationStoreService store) {
        this.store = store;
    }

    /**
     * 原子获取线程租约并返回本次执行的 fencing token。
     * 调用方必须直接保存该返回值，不能在执行开始后再次从本地表查询，
     * 否则心跳线程并发移除租约时可能把已经获取过的 Token 降级为 null。
     */
    public String tryAcquireToken(String threadId, Runnable onLeaseLost) {
        if (threadId == null || threadId.isBlank()) return null;
        if (localLeases.containsKey(threadId)) return null;
        long now = System.currentTimeMillis();
        AiThreadLeaseRecord row = lease(
                threadId, UUID.randomUUID().toString(), now);
        if (!store.acquireThreadLease(row)) return null;
        LocalLease local = new LocalLease(
                row, onLeaseLost != null ? onLeaseLost : () -> {});
        LocalLease previous = localLeases.putIfAbsent(threadId, local);
        if (previous != null) {
            store.releaseThreadLease(row);
            return null;
        }
        return row.getLeaseToken();
    }

    public void release(String threadId) {
        if (threadId == null) return;
        LocalLease local = localLeases.remove(threadId);
        if (local != null) store.releaseThreadLease(local.row());
    }

    @Scheduled(
            fixedDelayString = "${leo.ai.lease.heartbeat-ms:5000}",
            initialDelayString = "${leo.ai.lease.heartbeat-ms:5000}")
    public void heartbeatAndRecover() {
        renewLocalLeases();
        recoverExpiredLeases();
        recoverLeaselessStuckTurns();
    }

    void renewLocalLeases() {
        long now = System.currentTimeMillis();
        for (LocalLease local : List.copyOf(localLeases.values())) {
            AiThreadLeaseRecord row = local.row();
            row.setHeartbeatAt(now);
            row.setExpiresAt(now + safeTtlMillis());
            if (store.renewThreadLease(row)) {
                if (store.hasInterruptRequestedTurn(row.getThreadId())
                        && local.interruptDelivered().compareAndSet(false, true)) {
                    local.stopExecution().run();
                }
                continue;
            }
            if (localLeases.remove(row.getThreadId(), local)) {
                logger.warn("AI 执行租约丢失, threadId={}, owner={}",
                        row.getThreadId(), ownerId);
                try {
                    local.stopExecution().run();
                } catch (RuntimeException error) {
                    logger.warn("停止失去租约的 AI 执行失败, threadId={}: {}",
                            row.getThreadId(), error.getMessage());
                }
            }
        }
    }

    void recoverExpiredLeases() {
        long now = System.currentTimeMillis();
        for (AiThreadLeaseRecord expired : store.listExpiredThreadLeases(now)) {
            AiThreadLeaseRecord recovery = lease(
                    expired.getThreadId(), UUID.randomUUID().toString(), now);
            recoverWithLease(expired.getThreadId(), now, recovery,
                    () -> store.claimExpiredThreadLease(expired, recovery),
                    recovered -> logger.warn(
                            "已收口 {} 个孤儿 AI Run, threadId={}, previousOwner={}",
                            recovered, expired.getThreadId(), expired.getOwnerId()));
        }
    }

    /**
     * 收口没有 Lease 记录但 dispatch_status 仍为 running 的孤儿 Turn。
     * 这类 Turn 通常由进程崩溃或 SSE 中断后 Lease 被释放但 Turn 未终结造成。
     */
    void recoverLeaselessStuckTurns() {
        long now = System.currentTimeMillis();
        for (String threadId : store.listThreadsWithStuckRunningTurns(now)) {
            if (localLeases.containsKey(threadId)) continue;
            AiThreadLeaseRecord recovery = lease(
                    threadId, UUID.randomUUID().toString(), now);
            recoverWithLease(threadId, now, recovery,
                    () -> store.acquireThreadLease(recovery),
                    recovered -> logger.warn(
                            "已收口 {} 个无租约孤儿 AI Run, threadId={}",
                            recovered, threadId));
        }
    }

    private void recoverWithLease(String threadId, long now,
                                  AiThreadLeaseRecord recovery,
                                  BooleanSupplier claim,
                                  java.util.function.IntConsumer onRecovered) {
        if (!claim.getAsBoolean()) return;
        try {
            int recovered = store.recoverOrphanedRuns(threadId, now).size();
            if (recovered > 0) onRecovered.accept(recovered);
        } finally {
            store.releaseThreadLease(recovery);
        }
    }

    private AiThreadLeaseRecord lease(String threadId, String token, long now) {
        AiThreadLeaseRecord row = new AiThreadLeaseRecord();
        row.setThreadId(threadId);
        row.setOwnerId(ownerId);
        row.setLeaseToken(token);
        row.setAcquiredAt(now);
        row.setHeartbeatAt(now);
        row.setExpiresAt(now + safeTtlMillis());
        return row;
    }

    private long safeTtlMillis() {
        return Math.max(10_000L, ttlMillis);
    }

    private record LocalLease(AiThreadLeaseRecord row,
                              Runnable stopExecution,
                              AtomicBoolean interruptDelivered) {
        private LocalLease(AiThreadLeaseRecord row, Runnable stopExecution) {
            this(row, stopExecution, new AtomicBoolean(false));
        }
    }
}
