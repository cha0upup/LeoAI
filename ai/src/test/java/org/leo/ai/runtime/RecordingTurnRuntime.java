package org.leo.ai.runtime;

import org.leo.core.ai.AiTurnRuntime;

final class RecordingTurnRuntime implements AiTurnRuntime {
    boolean claimed;
    boolean stopRequested;
    String stopReason;
    private Runnable stopCallback;
    AiTurnOutcome outcome;
    int clearCount;

    @Override
    public boolean claimExecution() {
        if (claimed) return false;
        claimed = true;
        stopRequested = false;
        stopReason = null;
        return true;
    }

    @Override
    public void markExecuting(Thread thread) {
        claimed = true;
    }

    @Override
    public void clearExecuting() {
        claimed = false;
        stopRequested = false;
        stopCallback = null;
        clearCount++;
    }

    @Override
    public boolean isStopRequested() {
        return stopRequested;
    }

    @Override
    public String getStopReason() {
        return stopReason;
    }

    @Override
    public void setStopCallback(Runnable callback) {
        stopCallback = callback;
    }

    @Override
    public void markCompleted() {
        outcome = AiTurnOutcome.COMPLETED;
    }

    @Override
    public void markFailed() {
        outcome = AiTurnOutcome.FAILED;
    }

    @Override
    public void markCancelled() {
        outcome = AiTurnOutcome.CANCELLED;
    }

    void requestStop(String reason) {
        stopRequested = true;
        stopReason = reason;
        if (stopCallback != null) {
            stopCallback.run();
        }
    }
}
