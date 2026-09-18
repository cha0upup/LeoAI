package org.leo.ai.runtime;

import org.leo.core.ai.AiTurnRuntime;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 统一平台 AI 与节点 AI 的单轮执行生命周期。
 *
 * <p>控制器负责抢占执行权，后台任务通过 {@link #attach(AiTurnRuntime)}
 * 绑定当前工作线程。完成、失败、取消以及同步启动异常最终都必须通过
 * {@link Execution#finish(AiTurnOutcome, TerminalAction)} 收口。
 */
@Component
public class AiTurnCoordinator {

    public boolean tryClaim(AiTurnRuntime runtime) {
        return Objects.requireNonNull(runtime, "runtime").claimExecution();
    }

    public void releaseClaim(AiTurnRuntime runtime) {
        if (runtime != null) {
            runtime.clearExecuting();
        }
    }

    /**
     * 取消后的下一条命令等待上一执行线程完成 finally 收口，再原子抢占执行权。
     */
    public boolean tryClaimAfterRelease(AiTurnRuntime runtime,
                                        BooleanSupplier stillExecuting,
                                        long waitMillis) {
        Objects.requireNonNull(runtime, "runtime");
        Objects.requireNonNull(stillExecuting, "stillExecuting");
        long deadline = System.currentTimeMillis() + Math.max(0L, waitMillis);
        while (stillExecuting.getAsBoolean() && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(25L);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return !stillExecuting.getAsBoolean() && tryClaim(runtime);
    }

    /** 后台入口发生未被 Turn Presenter 收口的异常时统一标记失败并释放执行权。 */
    public void failAndRelease(AiTurnRuntime runtime) {
        if (runtime == null) return;
        runtime.markFailed();
        runtime.clearExecuting();
    }

    public Execution attach(AiTurnRuntime runtime) {
        AiTurnRuntime required = Objects.requireNonNull(runtime, "runtime");
        required.markExecuting(Thread.currentThread());
        return new Execution(required);
    }

    @FunctionalInterface
    public interface TerminalAction {
        void run() throws Exception;
    }

    public static final class Execution {

        private static final String DEFAULT_CANCEL_REASON = "已停止";

        private final AiTurnRuntime runtime;
        private final AtomicBoolean finished = new AtomicBoolean(false);
        private final AtomicReference<String> cancellationReason = new AtomicReference<>();
        private final AtomicReference<Runnable> cancellationAction = new AtomicReference<>();

        private Execution(AiTurnRuntime runtime) {
            this.runtime = runtime;
        }

        public boolean isCancellationRequested() {
            if (!finished.get() && runtime.isStopRequested()) {
                cancellationReason.compareAndSet(null, runtimeCancellationReason());
            }
            return cancellationReason.get() != null;
        }

        public boolean isCancellation(Throwable error) {
            return isCancellationRequested() || hasInterruptedCause(error);
        }

        public String cancellationReason() {
            String captured = cancellationReason.get();
            return captured != null ? captured : runtimeCancellationReason();
        }

        private String runtimeCancellationReason() {
            String reason = runtime.getStopReason();
            return reason != null && !reason.isBlank() ? reason : DEFAULT_CANCEL_REASON;
        }

        public void registerCancellation(Runnable callback) {
            cancellationAction.set(Objects.requireNonNull(callback, "callback"));
            runtime.setStopCallback(() -> cancel(runtimeCancellationReason()));
            if (isCancellationRequested()) invokeCancellation();
        }

        /** 取消信号属于本轮，运行时释放后仍保留，以拒绝迟到的工具调用。 */
        public void cancel(String reason) {
            if (finished.get()) return;
            cancellationReason.compareAndSet(null,
                    reason != null && !reason.isBlank() ? reason : DEFAULT_CANCEL_REASON);
            invokeCancellation();
        }

        private void invokeCancellation() {
            Runnable action = cancellationAction.getAndSet(null);
            if (action != null) action.run();
        }

        /**
         * 原子完成本轮执行。多个异步终止信号竞争时，只有第一个调用执行终态动作。
         *
         * @return 当前调用是否赢得终结权
         */
        public boolean finish(AiTurnOutcome outcome, TerminalAction action) throws Exception {
            Objects.requireNonNull(outcome, "outcome");
            Objects.requireNonNull(action, "action");
            if (!finished.compareAndSet(false, true)) {
                return false;
            }
            markOutcome(outcome);
            try {
                action.run();
            } catch (Throwable error) {
                runtime.markFailed();
                if (error instanceof Exception exception) {
                    throw exception;
                }
                throw (Error) error;
            } finally {
                cancellationAction.set(null);
                runtime.clearExecuting();
            }
            return true;
        }

        public boolean isFinished() {
            return finished.get();
        }

        private void markOutcome(AiTurnOutcome outcome) {
            switch (outcome) {
                case COMPLETED -> runtime.markCompleted();
                case FAILED -> runtime.markFailed();
                case CANCELLED -> runtime.markCancelled();
            }
        }

        private static boolean hasInterruptedCause(Throwable error) {
            Throwable current = error;
            while (current != null) {
                if (current instanceof InterruptedException) {
                    return true;
                }
                current = current.getCause();
            }
            return false;
        }
    }
}
