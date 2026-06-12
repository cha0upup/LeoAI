package org.leo.core.entity;

import java.util.UUID;
import java.util.concurrent.Future;

/**
 * 后台异步 Shell 任务。
 *
 * <p>存储在 {@link org.leo.core.session.PuppetNodeSession} 中，
 * 代表一个在远端节点异步执行的命令，可随时查询状态和输出。
 */
public class AsyncShellTask {

    public enum TaskStatus { PENDING, RUNNING, DONE, FAILED, CANCELLED }

    private final String taskId = UUID.randomUUID().toString();
    private final String command;
    private final long startTime = System.currentTimeMillis();

    private volatile TaskStatus status = TaskStatus.PENDING;
    /** 累积输出文本（读写均在单一线程或加锁场景下进行）。 */
    private volatile String output = "";
    private volatile int exitCode = -1;
    private volatile long endTime = -1;
    /** PTY processId（长运行命令使用）。 */
    private volatile String processId;
    /** 持有后台执行 Future，用于取消。 */
    private volatile transient Future<?> future;

    public AsyncShellTask(String command) {
        this.command = command;
    }

    // ── Getters ──────────────────────────────────────────────────────────────

    public String getTaskId()   { return taskId; }
    public String getCommand()  { return command; }
    public long   getStartTime(){ return startTime; }
    public TaskStatus getStatus(){ return status; }
    public String getOutput()   { return output; }
    public int    getExitCode() { return exitCode; }
    public long   getEndTime()  { return endTime; }
    public String getProcessId(){ return processId; }

    // ── Setters ──────────────────────────────────────────────────────────────

    public void setStatus(TaskStatus status)   { this.status = status; }
    public void setOutput(String output)       { this.output = output; }
    public void appendOutput(String chunk)     {
        this.output = this.output == null ? chunk : this.output + chunk;
    }
    public void setExitCode(int exitCode)      { this.exitCode = exitCode; }
    public void setEndTime(long endTime)       { this.endTime = endTime; }
    public void setProcessId(String processId) { this.processId = processId; }
    public void setFuture(Future<?> future)    { this.future = future; }

    /** 尝试取消后台 Future（仅中断 Java 线程，不保证远端进程终止）。 */
    public boolean cancel() {
        if (future != null && !future.isDone()) {
            future.cancel(true);
        }
        if (status == TaskStatus.PENDING || status == TaskStatus.RUNNING) {
            status = TaskStatus.CANCELLED;
            endTime = System.currentTimeMillis();
            return true;
        }
        return false;
    }

    public boolean isFinished() {
        return status == TaskStatus.DONE || status == TaskStatus.FAILED || status == TaskStatus.CANCELLED;
    }
}
