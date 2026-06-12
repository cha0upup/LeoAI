package org.leo.web.controller.puppetnode.ai;

import jakarta.servlet.http.HttpServletRequest;
import org.leo.ai.audit.AiAuditLogStore;
import org.leo.ai.tools.puppetnode.PlanTools;
import org.leo.core.entity.AiChatAuditEntry;
import org.leo.core.entity.AiExecutionPolicy;
import org.leo.core.entity.AiPlan;
import org.leo.core.session.AiThread;
import org.leo.core.session.PuppetNodeSession;
import org.leo.core.util.ApiResponse;
import org.leo.web.dto.puppetnode.ai.*;
import org.leo.web.service.PuppetNodeAiThreadService;
import org.leo.web.util.AiControllerUtil;
import org.leo.web.util.ControllerUtil;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.HashMap;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@RestController
@RequestMapping("/puppet-node/ai")
public class PuppetNodeAiController {

    /** 专用线程池，处理 SSE chat 异步请求。 */
    private static final ExecutorService SSE_EXECUTOR =
            Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "puppet-ai-sse");
                t.setDaemon(true);
                return t;
            });

    private final PuppetNodeAiThreadService aiThreadService;
    private final AiAuditLogStore auditLogStore;
    private final PlanTools planTools;

    public PuppetNodeAiController(PuppetNodeAiThreadService aiThreadService,
                                  AiAuditLogStore auditLogStore,
                                  PlanTools planTools) {
        this.aiThreadService = aiThreadService;
        this.auditLogStore = auditLogStore;
        this.planTools = planTools;
    }

    // ─── SSE 流式对话 ─────────────────────────────────────────────────────────

    /**
     * SSE 流式对话接口。
     *
     * <p>事件类型：
     * <ul>
     *   <li>{@code thinking} — AI 思考日志（JSON）</li>
     *   <li>{@code tool}     — 工具调用日志（JSON）</li>
     *   <li>{@code delta}    — 回复正文增量片段（纯文本）</li>
     *   <li>{@code reply}    — 最终回复文本（纯文本）</li>
     *   <li>{@code warn}     — 上下文轮次警告（纯文本）</li>
     *   <li>{@code error}    — 错误信息（纯文本）</li>
     * </ul>
     *
     * @param  {@code sessionId}, {@code threadId}, {@code message}
     */
    @RequestMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@RequestBody PuppetAiChatRequest body, HttpServletRequest request) {
        SseEmitter emitter = new SseEmitter(0L);
        long startMs = System.currentTimeMillis();

        String sessionId = body != null ? body.sessionId() : null;
        String threadId  = body != null ? body.threadId() : null;
        String message   = body != null ? body.message() : null;

        if (sessionId == null || sessionId.isEmpty()) {
            AiControllerUtil.safeSendError(emitter, "缺少 sessionId");
            return emitter;
        }
        if (threadId == null || threadId.isEmpty()) {
            AiControllerUtil.safeSendError(emitter, "缺少 threadId");
            return emitter;
        }
        if (message == null || message.isBlank()) {
            AiControllerUtil.safeSendError(emitter, "缺少 message");
            return emitter;
        }

        PuppetNodeSession session = ControllerUtil.getPuppetNodeSession(sessionId);
        session.touchLastActiveTime();

        Integer configId = body.configId();
        PuppetNodeAiThreadService.ThreadResolution resolution =
                aiThreadService.ensureThreadReady(session, threadId, configId);
        AiThread thread = resolution.thread();
        if (thread == null) {
            AiControllerUtil.safeSendError(emitter, "线程不存在，threadId: " + threadId);
            return emitter;
        }
        if (resolution.errorMessage() != null) {
            AiControllerUtil.safeSendError(emitter, resolution.errorMessage());
            return emitter;
        }
        if (!thread.claimExecution()) {
            AiControllerUtil.safeSendError(emitter, "当前对话正在执行中，请等待完成或先停止后再发送新消息");
            return emitter;
        }

        try {
            // 切换活跃线程
            session.switchActiveThread(threadId);
            thread.touchLastActiveAt();

            AiExecutionPolicy policy = ControllerUtil.buildAiExecutionPolicy(request);
            thread.setExecutionPolicy(policy);

            AiChatAuditEntry audit = AiChatAuditEntry.puppet(
                    sessionId, policy.getUserId(), policy.getUserName(), policy.getPrivilege(),
                    message, false);
            auditLogStore.append(audit);

            String guardedMessage = ControllerUtil.buildAiPolicyPrompt(policy, message);
            final String messageForAgent = resolution.restoredFromPersistence() && !resolution.hasPersistentCheckpoint()
                    ? aiThreadService.withPersistedHistoryContext(session, threadId, guardedMessage)
                    : guardedMessage;

            // 持久化 user 消息
            aiThreadService.persistMessage(session, threadId, "user", message);

            SSE_EXECUTOR.submit(() -> aiThreadService.executeChat(
                    session, thread, threadId, messageForAgent, audit, emitter, startMs));
        } catch (RuntimeException e) {
            thread.clearExecuting();
            AiControllerUtil.safeSendError(emitter, "启动 AI 对话失败: " + e.getMessage());
            return emitter;
        }

        // SSE 断开只表示当前浏览器订阅结束，不代表用户要求停止后台任务。
        // 显式停止请通过 /stop 接口触发 thread.stop()。

        return emitter;
    }

    // ─── 停止 ─────────────────────────────────────────────────────────────────

    /**
     * 干净停止指定线程的 AI 执行：interrupt 后端线程 + 取消所有挂起确认。
     *
     * @param params {@code sessionId}, {@code threadId}
     */
    @RequestMapping("/stop")
    public HashMap<String, Object> stop(@RequestBody AiThreadRequest body) {
        PuppetNodeSession session = requiredSession(body != null ? body.sessionId() : null);
        String threadId = requiredText(body != null ? body.threadId() : null, "缺少 threadId");
        AiThread thread = aiThreadService.requireThread(session, threadId);

        thread.stop();
        return ApiResponse.success(true);
    }

    // ─── 工具确认 ─────────────────────────────────────────────────────────────

    /**
     * 用户确认或拒绝工具调用。
     *
     * @param params {@code sessionId}, {@code threadId}, {@code callId}, {@code approved}(boolean)
     */
    @RequestMapping("/confirm")
    public HashMap<String, Object> confirm(@RequestBody AiConfirmRequest body) {
        PuppetNodeSession session = requiredSession(body != null ? body.sessionId() : null);
        String threadId = requiredText(body != null ? body.threadId() : null, "缺少 threadId");
        String callId = requiredText(body != null ? body.callId() : null, "缺少 callId");
        AiThread thread = aiThreadService.requireThread(session, threadId);

        boolean approved = Boolean.TRUE.equals(body != null ? body.approved() : null);
        boolean resolved = thread.resolveConfirmation(callId, approved);
        if (!resolved) return ApiResponse.notFound("未找到对应的确认请求，可能已超时或已处理");
        return ApiResponse.success(true);
    }

    // ─── 会话级授权 ───────────────────────────────────────────────────────────

    /**
     * 会话级工具类型授权。
     *
     * @param params {@code sessionId}, {@code threadId}, {@code toolType} 或 {@code grantAll}(boolean)
     */
    @RequestMapping("/grant")
    public HashMap<String, Object> grant(@RequestBody AiGrantRequest body,
                                         HttpServletRequest request) {
        PuppetNodeSession session = requiredSession(body != null ? body.sessionId() : null);
        String threadId = requiredText(body != null ? body.threadId() : null, "缺少 threadId");
        AiThread thread = aiThreadService.requireThread(session, threadId);

        boolean grantAll = ControllerUtil.isAdmin(request) && Boolean.TRUE.equals(body != null ? body.grantAll() : null);
        if (grantAll) {
            thread.grantSessionAll();
            return ApiResponse.success(true);
        }

        String toolType = requiredText(body != null ? body.toolType() : null, "缺少 toolType");
        thread.grantSessionType(toolType);
        return ApiResponse.success(true);
    }

    // ─── 线程管理 ─────────────────────────────────────────────────────────────

    /**
     * 列出当前 puppet 的所有 AI 对话线程（合并内存 + 持久化，去重，按 lastActiveAt 倒序）。
     *
     * @param params {@code sessionId}
     */
    @RequestMapping("/thread/list")
    public HashMap<String, Object> listThreads(@RequestBody AiSessionRequest body) {
        PuppetNodeSession session = requiredSession(body != null ? body.sessionId() : null);
        return ApiResponse.success(aiThreadService.listThreads(session));
    }

    /**
     * 创建新的 AI 对话线程。
     *
     * @param params {@code sessionId}, {@code title}（可选）
     * @return {@code threadId}, {@code reconSummaryLoaded}, {@code grantedTypesCount}
     */
    @RequestMapping("/thread/create")
    public HashMap<String, Object> createThread(@RequestBody AiThreadCreateRequest body) {
        PuppetNodeSession session = requiredSession(body != null ? body.sessionId() : null);
        String title = body != null ? body.title() : null;
        Integer configId = body != null ? body.configId() : null;
        String mode = body != null ? body.mode() : null;
        return ApiResponse.success(aiThreadService.createThread(session, title, configId, mode));
    }

    /**
     * 删除指定 AI 对话线程（内存 + 持久化）。
     *
     * @param params {@code sessionId}, {@code threadId}
     */
    @RequestMapping("/thread/delete")
    public HashMap<String, Object> deleteThread(@RequestBody AiThreadRequest body) {
        PuppetNodeSession session = requiredSession(body != null ? body.sessionId() : null);
        String threadId = requiredText(body != null ? body.threadId() : null, "缺少 threadId");
        aiThreadService.deleteThread(session, threadId);
        return ApiResponse.success(true);
    }

    /**
     * 重命名指定 AI 对话线程。
     *
     * @param params {@code sessionId}, {@code threadId}, {@code title}
     */
    @RequestMapping("/thread/rename")
    public HashMap<String, Object> renameThread(@RequestBody AiThreadRenameRequest body) {
        PuppetNodeSession session = requiredSession(body != null ? body.sessionId() : null);
        String threadId = requiredText(body != null ? body.threadId() : null, "缺少 threadId");
        String title = requiredText(body != null ? body.title() : null, "缺少 title");
        aiThreadService.renameThread(session, threadId, title);
        return ApiResponse.success(true);
    }

    /**
     * 加载指定线程的历史消息（分页）。
     *
     * @param params {@code sessionId}, {@code threadId}, {@code offset}(int, 默认0),
     *               {@code limit}(int, 默认50)
     */
    @RequestMapping("/thread/messages")
    public HashMap<String, Object> threadMessages(@RequestBody AiThreadMessagesRequest body) {
        PuppetNodeSession session = requiredSession(body != null ? body.sessionId() : null);
        String threadId = requiredText(body != null ? body.threadId() : null, "缺少 threadId");
        Integer offset = body != null ? body.offset() : null;
        Integer limit = body != null ? body.limit() : null;
        return ApiResponse.success(aiThreadService.threadMessages(session, threadId, offset, limit));
    }

    /**
     * 获取指定线程最近 SSE 事件，用于切换会话或断线后补看执行过程。
     */
    @RequestMapping("/thread/events")
    public HashMap<String, Object> threadEvents(@RequestBody AiThreadEventsRequest body) {
        PuppetNodeSession session = requiredSession(body != null ? body.sessionId() : null);
        String threadId = requiredText(body != null ? body.threadId() : null, "缺少 threadId");
        Long afterSeq = body != null ? body.afterSeq() : null;
        Integer limit = body != null ? body.limit() : null;
        return ApiResponse.success(aiThreadService.threadEvents(session, threadId, afterSeq, limit));
    }

    /**
     * 重置指定线程：清空 AI 状态（清除 LLM 上下文，保留持久化消息）。
     *
     * @param params {@code sessionId}, {@code threadId}, {@code configId}（可选）
     */
    @RequestMapping("/reset")
    public HashMap<String, Object> reset(@RequestBody AiThreadConfigRequest body) {
        PuppetNodeSession session = requiredSession(body != null ? body.sessionId() : null);
        String threadId = requiredText(body != null ? body.threadId() : null, "缺少 threadId");
        Integer configId = body != null ? body.configId() : null;
        return ApiResponse.success(aiThreadService.resetThread(session, threadId, configId));
    }

    /**
     * 热切换 AI 通道。
     *
     * @param params {@code sessionId}, {@code threadId}, {@code configId}
     */
    @RequestMapping("/switchChannel")
    public HashMap<String, Object> switchChannel(@RequestBody AiThreadConfigRequest body) {
        PuppetNodeSession session = requiredSession(body != null ? body.sessionId() : null);
        String threadId = requiredText(body != null ? body.threadId() : null, "缺少 threadId");
        Integer configId = body != null ? body.configId() : null;
        aiThreadService.switchChannel(session, threadId, configId);
        return ApiResponse.success(true);
    }

    @RequestMapping("/thread/switchMode")
    public HashMap<String, Object> switchMode(@RequestBody AiThreadModeRequest body) {
        PuppetNodeSession session = requiredSession(body != null ? body.sessionId() : null);
        String threadId = requiredText(body != null ? body.threadId() : null, "缺少 threadId");
        String mode = body != null ? body.mode() : null;
        return ApiResponse.success(aiThreadService.switchMode(session, threadId, mode));
    }

    // ─── 任务计划查询 ─────────────────────────────────────────────────────────

    /**
     * 获取指定线程的当前活跃任务计划。
     *
     * @param params {@code sessionId}, {@code threadId}
     */
    @RequestMapping("/thread/plan")
    public HashMap<String, Object> threadPlan(@RequestBody AiThreadRequest body) {
        PuppetNodeSession session = requiredSession(body != null ? body.sessionId() : null);
        String threadId = requiredText(body != null ? body.threadId() : null, "缺少 threadId");
        AiThread thread = aiThreadService.requireThread(session, threadId);
        AiPlan plan = thread.getCurrentPlan();
        return ApiResponse.success(plan);
    }

    /**
     * 获取指定线程的所有历史任务计划（按创建时间升序）。
     *
     * @param params {@code sessionId}, {@code threadId}
     */
    @RequestMapping("/thread/plans")
    public HashMap<String, Object> threadPlans(@RequestBody AiThreadRequest body) {
        PuppetNodeSession session = requiredSession(body != null ? body.sessionId() : null);
        String threadId = requiredText(body != null ? body.threadId() : null, "缺少 threadId");
        AiThread thread = aiThreadService.requireThread(session, threadId);
        List<AiPlan> history = thread.getPlanHistory();
        return ApiResponse.success(history);
    }

    // ─── 计划预批准 ─────────────────────────────────────────────────────────

    /**
     * 预批准指定计划步骤，步骤执行时高影响工具调用跳过用户确认。
     *
     * @param body {@code sessionId}, {@code threadId}, {@code stepIndex}（null 则预批准全部）
     */
    @RequestMapping("/plan/preApprove")
    public HashMap<String, Object> preApprovePlanStep(@RequestBody AiPlanPreApproveRequest body) {
        String sessionId = requiredText(body != null ? body.sessionId() : null, "缺少 sessionId");
        String threadId = requiredText(body != null ? body.threadId() : null, "缺少 threadId");
        requiredSession(sessionId);
        if (body.stepIndex() != null) {
            return ApiResponse.success(planTools.preApproveStep(sessionId, threadId, body.stepIndex()));
        } else {
            return ApiResponse.success(planTools.preApproveAllSteps(sessionId, threadId));
        }
    }

    private PuppetNodeSession requiredSession(String sessionId) {
        return ControllerUtil.getPuppetNodeSession(requiredText(sessionId, "缺少 sessionId"));
    }

    private String requiredText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw org.leo.web.exception.ApiException.badRequest(message);
        }
        return value.trim();
    }
}
