package org.leo.web.controller.platform.session;

import org.leo.core.entity.AsyncShellTask;
import org.leo.core.session.PuppetNodeSession;
import org.leo.core.util.ApiResponse;
import org.leo.web.dto.platform.session.AsyncShellDtos.CancelResponse;
import org.leo.web.dto.platform.session.AsyncShellDtos.ClearResponse;
import org.leo.web.dto.platform.session.AsyncShellDtos.SessionRequest;
import org.leo.web.dto.platform.session.AsyncShellDtos.StartRequest;
import org.leo.web.dto.platform.session.AsyncShellDtos.StartResponse;
import org.leo.web.dto.platform.session.AsyncShellDtos.TaskDetail;
import org.leo.web.dto.platform.session.AsyncShellDtos.TaskListResponse;
import org.leo.web.dto.platform.session.AsyncShellDtos.TaskRequest;
import org.leo.web.dto.platform.session.AsyncShellDtos.TaskResponse;
import org.leo.web.dto.platform.session.AsyncShellDtos.TaskSummary;
import org.leo.web.exception.ApiException;
import org.leo.web.service.AsyncShellService;
import org.leo.web.util.ControllerUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 后台异步 Shell 任务控制器。
 *
 * <p>端点：
 * <ul>
 *   <li>{@code POST /platform/session/async-shell/start}  — 提交命令（异步执行）</li>
 *   <li>{@code POST /platform/session/async-shell/list}   — 查询所有任务列表</li>
 *   <li>{@code POST /platform/session/async-shell/task}   — 查询单个任务状态与输出</li>
 *   <li>{@code POST /platform/session/async-shell/cancel} — 取消任务</li>
 *   <li>{@code POST /platform/session/async-shell/clear}  — 清理已结束任务</li>
 * </ul>
 */
@RestController
@RequestMapping("/platform/session/async-shell")
public class AsyncShellController {

    private final AsyncShellService asyncShellService;

    @Autowired
    public AsyncShellController(AsyncShellService asyncShellService) {
        this.asyncShellService = asyncShellService;
    }

    /**
     * 提交命令，异步在目标节点执行。
     *
     * <p>请求参数：{@code sessionId}、{@code command}（必填）。
     * 立即返回 {@code taskId}，命令在后台线程执行。
     */
    @PostMapping("/start")
    public Map<String, Object> start(@RequestBody StartRequest request) {
        return shellCall("提交命令失败", () -> {
            String sessionId = requireText(request.sessionId(), "sessionId");
            String command = requireText(request.command(), "command");

            PuppetNodeSession session = ControllerUtil.getPuppetNodeSession(sessionId);
            AsyncShellTask task = asyncShellService.submit(session, command);

            return ApiResponse.success(new StartResponse(
                    sessionId,
                    task.getTaskId(),
                    task.getStatus().name()));
        });
    }

    /**
     * 查询会话内所有后台 Shell 任务列表（不含输出）。
     *
     * <p>请求参数：{@code sessionId}。
     */
    @PostMapping("/list")
    public Map<String, Object> list(@RequestBody SessionRequest request) {
        return shellCall("查询任务列表失败", () -> {
            String sessionId = requireText(request.sessionId(), "sessionId");
            PuppetNodeSession session = ControllerUtil.getPuppetNodeSession(sessionId);

            List<TaskSummary> tasks = session.getAsyncShellTasks()
                    .stream()
                    .map(AsyncShellController::toSummary)
                    .toList();

            return ApiResponse.success(new TaskListResponse(sessionId, tasks, tasks.size()));
        });
    }

    /**
     * 查询单个任务详情（含完整输出）。
     *
     * <p>请求参数：{@code sessionId}、{@code taskId}。
     */
    @PostMapping("/task")
    public Map<String, Object> getTask(@RequestBody TaskRequest request) {
        return shellCall("查询任务失败", () -> {
            String sessionId = requireText(request.sessionId(), "sessionId");
            String taskId = requireText(request.taskId(), "taskId");

            PuppetNodeSession session = ControllerUtil.getPuppetNodeSession(sessionId);
            Optional<AsyncShellTask> task = session.findAsyncShellTask(taskId);
            if (task.isEmpty()) {
                throw ApiException.badRequest("任务不存在: " + taskId);
            }

            return ApiResponse.success(new TaskResponse(sessionId, toDetail(task.get())));
        });
    }

    /**
     * 取消任务。
     *
     * <p>请求参数：{@code sessionId}、{@code taskId}。
     */
    @PostMapping("/cancel")
    public Map<String, Object> cancel(@RequestBody TaskRequest request) {
        return shellCall("取消任务失败", () -> {
            String sessionId = requireText(request.sessionId(), "sessionId");
            String taskId = requireText(request.taskId(), "taskId");

            PuppetNodeSession session = ControllerUtil.getPuppetNodeSession(sessionId);
            boolean cancelled = asyncShellService.cancel(session, taskId);

            return ApiResponse.success(new CancelResponse(
                    sessionId,
                    taskId,
                    cancelled,
                    cancelled ? "任务已取消" : "任务不存在或已完成"));
        });
    }

    /**
     * 清理所有已结束（DONE/FAILED/CANCELLED）的任务。
     *
     * <p>请求参数：{@code sessionId}。
     */
    @PostMapping("/clear")
    public Map<String, Object> clear(@RequestBody SessionRequest request) {
        return shellCall("清理任务失败", () -> {
            String sessionId = requireText(request.sessionId(), "sessionId");
            PuppetNodeSession session = ControllerUtil.getPuppetNodeSession(sessionId);

            int cleared = asyncShellService.clearFinished(session);

            return ApiResponse.success(new ClearResponse(
                    sessionId,
                    cleared,
                    "已清理 " + cleared + " 条已结束任务"));
        });
    }

    private Map<String, Object> shellCall(String failureMessage, ShellAction action) {
        try {
            return action.execute();
        } catch (ApiException e) {
            throw e;
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest(e.getMessage());
        } catch (Exception e) {
            throw ApiException.serverError(failureMessage + ": " + e.getMessage());
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw ApiException.badRequest(name + "不能为空");
        }
        return value.trim();
    }

    private static TaskSummary toSummary(AsyncShellTask task) {
        return new TaskSummary(
                task.getTaskId(),
                task.getCommand(),
                task.getStatus().name(),
                task.getStartTime(),
                task.getEndTime(),
                task.getExitCode());
    }

    private static TaskDetail toDetail(AsyncShellTask task) {
        return new TaskDetail(
                task.getTaskId(),
                task.getCommand(),
                task.getStatus().name(),
                task.getStartTime(),
                task.getEndTime(),
                task.getExitCode(),
                task.getOutput());
    }

    @FunctionalInterface
    private interface ShellAction {
        Map<String, Object> execute() throws Exception;
    }
}
