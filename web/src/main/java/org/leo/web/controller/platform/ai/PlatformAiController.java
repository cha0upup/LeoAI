package org.leo.web.controller.platform.ai;

import jakarta.servlet.http.HttpServletRequest;
import org.leo.ai.platform.PlatformAiState;
import org.leo.core.entity.AiChatAuditEntry;
import org.leo.core.entity.AiExecutionPolicy;
import org.leo.core.entity.AiPlan;
import org.leo.core.entity.User;
import org.leo.core.util.ApiResponse;
import org.leo.web.dto.platform.ai.PlatformAiDtos;
import org.leo.web.dto.platform.ai.PlatformAiDtos.AgentConfigRequest;
import org.leo.web.dto.platform.ai.PlatformAiDtos.ChatRequest;
import org.leo.web.dto.platform.ai.PlatformAiDtos.ConfirmRequest;
import org.leo.web.dto.platform.ai.PlatformAiDtos.EventsRequest;
import org.leo.web.dto.platform.ai.PlatformAiDtos.GrantRequest;
import org.leo.web.dto.platform.ai.PlatformAiDtos.MessagesRequest;
import org.leo.web.exception.ApiException;
import org.leo.web.service.PlatformAiService;
import org.leo.web.util.AiControllerUtil;
import org.leo.web.util.ControllerUtil;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@RestController
@RequestMapping("/platform/ai")
public class PlatformAiController {

    /** 专用线程池，处理 SSE chat 异步请求。 */
    private static final ExecutorService SSE_EXECUTOR =
            Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "platform-ai-sse");
                t.setDaemon(true);
                return t;
            });

    private final PlatformAiService platformAiService;

    public PlatformAiController(PlatformAiService platformAiService) {
        this.platformAiService = platformAiService;
    }

    /**
     * SSE 流式对话接口。
     *
     * <p>事件类型：
     * <ul>
     *   <li>{@code thinking} — AI 思考日志（JSON）</li>
     *   <li>{@code tool}     — 工具调用日志（JSON）</li>
     *   <li>{@code confirm}  — 工具调用确认请求（JSON）</li>
     *   <li>{@code delta}    — 回复正文增量片段（纯文本）</li>
     *   <li>{@code reply}    — 最终回复文本（纯文本）</li>
     *   <li>{@code warn}     — 上下文轮次警告（纯文本）</li>
     *   <li>{@code error}    — 错误信息（纯文本）</li>
     * </ul>
     */
    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@RequestBody ChatRequest body, HttpServletRequest request) {
        SseEmitter emitter = new SseEmitter(0L);
        long startMs = System.currentTimeMillis();

        String message = body != null ? body.message() : null;
        if (message == null || message.isBlank()) {
            AiControllerUtil.safeSendError(emitter, "缺少 message");
            return emitter;
        }

        PlatformAiState state = platformAiService.getState(request.getSession());
        if (state == null) {
            AiControllerUtil.safeSendError(emitter, "AI 会话不存在，请先调用 createAgent");
            return emitter;
        }
        if (!state.claimExecution()) {
            AiControllerUtil.safeSendError(emitter, "当前平台 AI 正在执行中，请等待完成或先停止后再发送新消息");
            return emitter;
        }

        try {
            AiExecutionPolicy policy = ControllerUtil.buildAiExecutionPolicy(request);
            state.setExecutionPolicy(policy);

            AiChatAuditEntry audit = platformAiService.appendChatAudit(policy, message);
            String guardedMessage = ControllerUtil.buildAiPolicyPrompt(policy, message);

            SSE_EXECUTOR.submit(() -> platformAiService.executeChat(
                    state, request.getSession().getId(), message, guardedMessage, audit, emitter, startMs));
        } catch (RuntimeException e) {
            state.clearExecuting();
            AiControllerUtil.safeSendError(emitter, "启动平台 AI 对话失败: " + e.getMessage());
            return emitter;
        }

        // SSE 断开只表示当前浏览器订阅结束，不代表用户要求停止后台任务。
        // 显式停止请通过 /stop 接口触发 state.stopGeneration()。

        return emitter;
    }

    @PostMapping("/createAgent")
    public Map<String, Object> createAgent(@RequestBody(required = false) AgentConfigRequest body,
                                           HttpServletRequest request) {
        User user = ControllerUtil.getCurrentUser(request);
        Integer configId = body != null ? body.configId() : null;
        String mode = body != null ? body.mode() : null;
        return ApiResponse.success(platformAiService.createAgent(request.getSession(), user, configId, mode));
    }

    /**
     * 热切换 AI 通道。
     */
    @PostMapping("/switchChannel")
    public Map<String, Object> switchChannel(@RequestBody(required = false) AgentConfigRequest body,
                                             HttpServletRequest request) {
        User user = ControllerUtil.getCurrentUser(request);
        Integer configId = body != null ? body.configId() : null;
        platformAiService.switchChannel(request.getSession(), user, configId);
        return ApiResponse.success(true);
    }

    @PostMapping("/switchMode")
    public Map<String, Object> switchMode(@RequestBody PlatformAiDtos.SwitchModeRequest body,
                                          HttpServletRequest request) {
        User user = ControllerUtil.getCurrentUser(request);
        String mode = body != null ? body.mode() : null;
        return ApiResponse.success(platformAiService.switchMode(request.getSession(), user, mode));
    }

    // ── 线程管理 ─────────────────────────────────────────────────────────────

    /**
     * 列出当前用户的所有平台 AI 线程。
     */
    @PostMapping("/threads")
    public Map<String, Object> threads(HttpServletRequest request) {
        User user = ControllerUtil.getCurrentUser(request);
        return ApiResponse.success(platformAiService.listThreads(user));
    }

    /**
     * 创建新线程。
     */
    @PostMapping("/thread/create")
    public Map<String, Object> createThread(@RequestBody(required = false) PlatformAiDtos.CreateThreadRequest body,
                                            HttpServletRequest request) {
        User user = ControllerUtil.getCurrentUser(request);
        String title = body != null ? body.title() : null;
        Integer configId = body != null ? body.configId() : null;
        return ApiResponse.success(platformAiService.createThread(request.getSession(), user, title, configId));
    }

    /**
     * 删除指定线程。
     */
    @PostMapping("/thread/delete")
    public Map<String, Object> deleteThread(@RequestBody PlatformAiDtos.ThreadIdRequest body,
                                            HttpServletRequest request) {
        platformAiService.deleteThread(request.getSession(), body.threadId());
        return ApiResponse.success(true);
    }

    /**
     * 重命名指定线程。
     */
    @PostMapping("/thread/rename")
    public Map<String, Object> renameThread(@RequestBody PlatformAiDtos.ThreadRenameRequest body) {
        platformAiService.renameThread(body.threadId(), body.title());
        return ApiResponse.success(true);
    }

    /**
     * 切换到指定线程（恢复 in-memory 状态）。
     */
    @PostMapping("/thread/activate")
    public Map<String, Object> activateThread(@RequestBody PlatformAiDtos.ThreadIdRequest body,
                                               HttpServletRequest request) {
        platformAiService.activateThread(request.getSession(), body.threadId());
        return ApiResponse.success(true);
    }

    /**
     * 显式停止当前平台 AI 执行：interrupt 后端线程 + 取消所有挂起确认。
     */
    @PostMapping("/stop")
    public Map<String, Object> stop(HttpServletRequest request) {
        PlatformAiState state = platformAiService.requireState(request.getSession(), "AI 会话不存在");
        state.stopGeneration();
        return ApiResponse.success(true);
    }

    /**
     * 获取当前平台 AI 最近 SSE 事件，用于 SSE 中断后的补拉恢复。
     */
    @PostMapping("/events")
    public Map<String, Object> events(@RequestBody(required = false) EventsRequest body,
                                      HttpServletRequest request) {
        Long afterSeq = body != null ? body.afterSeq() : null;
        Integer limit = body != null ? body.limit() : null;
        return ApiResponse.success(platformAiService.events(request.getSession(), afterSeq, limit));
    }

    /**
     * 加载当前平台 AI 会话的历史消息（分页）。
     */
    @PostMapping("/messages")
    public Map<String, Object> messages(@RequestBody(required = false) MessagesRequest body,
                                        HttpServletRequest request) {
        Integer offset = body != null ? body.offset() : null;
        Integer limit = body != null ? body.limit() : null;
        return ApiResponse.success(platformAiService.messages(request.getSession(), offset, limit));
    }

    /**
     * 用户确认或拒绝工具调用。前端收到 {@code confirm} SSE 事件后，
     * 用户操作完成时调用此接口解除拦截器的阻塞等待。
     */
    @PostMapping("/confirm")
    public Map<String, Object> confirm(@RequestBody ConfirmRequest body,
                                       HttpServletRequest request) {
        String callId = requireText(body != null ? body.callId() : null, "缺少 callId");
        PlatformAiState state = platformAiService.requireState(request.getSession(), "AI 会话不存在");

        boolean approved = Boolean.TRUE.equals(body != null ? body.approved() : null);
        boolean resolved = state.resolveConfirmation(callId, approved);
        if (!resolved) {
            throw ApiException.notFound("未找到对应的确认请求，可能已超时或已处理");
        }
        return ApiResponse.success(true);
    }

    /**
     * 会话级工具类型授权。
     *
     * <p>用户在确认弹窗中选择「本次会话不再询问」时，前端调用此接口将对应类型写入
     * 平台 AI 会话状态，后续同类工具调用无需再弹出确认弹窗。
     */
    @PostMapping("/grant")
    public Map<String, Object> grant(@RequestBody GrantRequest body,
                                     HttpServletRequest request) {
        PlatformAiState state = platformAiService.requireState(
                request.getSession(), "AI 会话不存在，请先调用 createAgent");

        boolean grantAll = ControllerUtil.isAdmin(request)
                && Boolean.TRUE.equals(body != null ? body.grantAll() : null);
        if (grantAll) {
            state.grantSessionAll();
            return ApiResponse.success(true);
        }

        String toolType = requireText(body != null ? body.toolType() : null, "缺少 toolType");
        state.grantSessionType(toolType);
        return ApiResponse.success(true);
    }

    /**
     * 获取当前平台 AI 会话的活跃任务计划。
     */
    @PostMapping("/plan")
    public Map<String, Object> plan(HttpServletRequest request) {
        PlatformAiState state = platformAiService.requireState(request.getSession(), "AI 会话不存在");
        AiPlan plan = state.getCurrentPlan();
        return ApiResponse.success(plan);
    }

    /**
     * 获取当前平台 AI 会话的所有历史任务计划（按创建时间升序）。
     */
    @PostMapping("/plans")
    public Map<String, Object> plans(HttpServletRequest request) {
        PlatformAiState state = platformAiService.requireState(request.getSession(), "AI 会话不存在");
        List<AiPlan> history = state.getPlanHistory();
        return ApiResponse.success(history);
    }

    private String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw ApiException.badRequest(message);
        }
        return value.trim();
    }
}
