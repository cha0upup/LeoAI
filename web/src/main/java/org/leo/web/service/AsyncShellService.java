package org.leo.web.service;

import org.leo.core.entity.AsyncShellTask;
import org.leo.core.puppet.impl.JavaPuppetNode;
import org.leo.core.session.PuppetNodeSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * 后台异步 Shell 任务服务。
 *
 * <p>在独立线程池中提交命令到目标节点执行，不阻塞 HTTP 请求线程。
 * 任务状态与输出保存在 {@link AsyncShellTask} 并附加到 {@link PuppetNodeSession}，
 * 前端可通过轮询接口获取实时状态与输出。
 */
@Service
public class AsyncShellService {

    private static final Logger log = LoggerFactory.getLogger(AsyncShellService.class);

    /** 单条命令输出最大保留长度，防止超大输出撑爆内存（128 KB）。 */
    private static final int MAX_OUTPUT_LEN = 128 * 1024;

    /** 专用线程池：最多 8 线程，队列深度 200，避免与 Spring 默认 @Async 池互相干扰。 */
    private TaskExecutor shellExecutor;

    @PostConstruct
    private void initExecutor() {
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(4);
        exec.setMaxPoolSize(8);
        exec.setQueueCapacity(200);
        exec.setThreadNamePrefix("async-shell-");
        exec.setDaemon(true);
        exec.initialize();
        shellExecutor = exec;
    }

    /**
     * 在目标节点上异步执行命令。
     *
     * <p>创建 {@link AsyncShellTask}，加入 session 任务列表后立即返回，
     * 实际执行在独立线程池中完成。
     *
     * @param session 目标会话（必须包含有效的 JavaPuppetNode）
     * @param command 要执行的 Shell 命令
     * @return 新创建的任务（status=PENDING）
     */
    public AsyncShellTask submit(PuppetNodeSession session, String command) {
        AsyncShellTask task = new AsyncShellTask(command);
        session.addAsyncShellTask(task);
        // Submit to dedicated thread pool — avoids Spring @Async self-invocation proxy issue
        CompletableFuture<?> future = CompletableFuture.runAsync(
                () -> executeTask(session, task), shellExecutor);
        task.setFuture(future);
        return task;
    }

    /**
     * 取消指定任务。
     *
     * @param session 会话
     * @param taskId  任务 ID
     * @return 是否成功取消
     */
    public boolean cancel(PuppetNodeSession session, String taskId) {
        Optional<AsyncShellTask> opt = session.findAsyncShellTask(taskId);
        return opt.map(AsyncShellTask::cancel).orElse(false);
    }

    /**
     * 清理已完成/已失败/已取消的任务。
     *
     * @return 清理数量
     */
    public int clearFinished(PuppetNodeSession session) {
        return session.clearFinishedAsyncShellTasks();
    }

    // ── private execution ─────────────────────────────────────────────────────

    private void executeTask(PuppetNodeSession session, AsyncShellTask task) {
        task.setStatus(AsyncShellTask.TaskStatus.RUNNING);
        JavaPuppetNode node = session.getJavaPuppetNode();
        if (node == null) {
            task.setStatus(AsyncShellTask.TaskStatus.FAILED);
            task.setOutput("节点未连接");
            task.setEndTime(System.currentTimeMillis());
            return;
        }
        try {
            Map<String, Object> result = node.execSimpleCommand(task.getCommand());
            String output = extractOutput(result);
            if (output.length() > MAX_OUTPUT_LEN) {
                output = output.substring(0, MAX_OUTPUT_LEN)
                        + "\n\n...(输出已截断，原始长度 " + output.length() + " 字节)";
            }
            task.setOutput(output);

            Object exitCodeObj = result.get("exitCode");
            int exitCode = exitCodeObj != null ? ((Number) exitCodeObj).intValue() : 0;
            task.setExitCode(exitCode);
            task.setStatus(exitCode == 0 ? AsyncShellTask.TaskStatus.DONE : AsyncShellTask.TaskStatus.FAILED);

        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            task.setStatus(AsyncShellTask.TaskStatus.CANCELLED);
            task.appendOutput("\n\n[任务被中断]");

        } catch (Exception e) {
            log.debug("AsyncShell 执行失败 taskId={}: {}", task.getTaskId(), e.getMessage());
            task.setStatus(AsyncShellTask.TaskStatus.FAILED);
            task.appendOutput("\n\n[执行错误] " + e.getMessage());

        } finally {
            if (!task.isFinished()) {
                task.setStatus(AsyncShellTask.TaskStatus.DONE);
            }
            task.setEndTime(System.currentTimeMillis());
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static String extractOutput(Map<String, Object> result) {
        if (result == null) return "";
        Object data = result.get("data");
        if (data instanceof byte[]) return new String((byte[]) data).trim();
        if (data instanceof String)  return ((String) data).trim();
        Object output = result.get("output");
        if (output instanceof String) return ((String) output).trim();
        return "";
    }
}
