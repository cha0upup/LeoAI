package org.leo.ai.agent;

import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * 限流包装 ExecutorService：使用 Semaphore 控制并发执行的最大任务数。
 *
 * <p>LangChain4j 的 {@code executeToolsConcurrently} 会把一次 AI 回复中的所有工具调用
 * 提交到 ExecutorService 并行执行。本包装器在任务运行时获取许可，
 * 任务完成后释放许可，从而限制同时运行的工具数，避免目标侧资源竞争。
 *
 * <p>注：信号量在任务进入 {@code run()} 时才尝试获取，因此底层池可能有任务排队
 * 等待许可（占用线程但不消耗下游资源）。底层池容量应大于本限流阈值。
 */
public class ThrottledExecutorService extends AbstractExecutorService {

    private final ExecutorService delegate;
    private final Semaphore semaphore;

    /**
     * @param delegate      底层执行器
     * @param maxConcurrent 最大并发任务数（&lt;= 0 则不限流，直接代理）
     */
    public ThrottledExecutorService(ExecutorService delegate, int maxConcurrent) {
        this.delegate = delegate;
        this.semaphore = maxConcurrent > 0 ? new Semaphore(maxConcurrent) : null;
    }

    /**
     * 唯一需要重写的执行入口：所有 {@code submit(...)} 调用最终都会走到这里
     * （由 {@link AbstractExecutorService} 的默认实现转发）。
     */
    @Override
    public void execute(Runnable command) {
        if (semaphore == null) {
            delegate.execute(command);
            return;
        }
        delegate.execute(() -> {
            try {
                semaphore.acquire();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RejectedExecutionException("Interrupted while waiting for throttle permit", e);
            }
            try {
                command.run();
            } finally {
                semaphore.release();
            }
        });
    }

    // ── 生命周期方法委托给底层执行器 ──────────────────────────────────────────

    @Override public void shutdown() { delegate.shutdown(); }

    @Override public List<Runnable> shutdownNow() { return delegate.shutdownNow(); }

    @Override public boolean isShutdown() { return delegate.isShutdown(); }

    @Override public boolean isTerminated() { return delegate.isTerminated(); }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        return delegate.awaitTermination(timeout, unit);
    }

    /** 获取当前可用许可数（调试/监控用）。 */
    public int availablePermits() {
        return semaphore != null ? semaphore.availablePermits() : Integer.MAX_VALUE;
    }
}
