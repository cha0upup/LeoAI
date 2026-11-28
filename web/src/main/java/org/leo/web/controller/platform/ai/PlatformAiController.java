package org.leo.web.controller.platform.ai;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.leo.ai.channel.AiModelConfigService;
import org.leo.ai.channel.ModelRequestOptions;
import org.leo.ai.platform.PlatformAiState;
import org.leo.ai.runtime.AiTurnTelemetryRegistry;
import org.leo.core.entity.AiExecutionPolicy;
import org.leo.core.entity.AiModelConfig;
import org.leo.core.entity.AiPlan;
import org.leo.core.entity.ProviderCapabilities;
import org.leo.core.entity.User;
import org.leo.core.util.ApiResponse;
import org.leo.web.adapter.ai.AiAttachmentPrompt;
import org.leo.web.adapter.ai.AiEventSubscriptionService;
import org.leo.web.application.ai.AiThreadQueryService;
import org.leo.web.application.ai.AiTurnCommandPayload;
import org.leo.web.application.ai.AiTurnProtocolService;
import org.leo.web.application.ai.AiTurnQueueService;
import org.leo.web.application.ai.PlatformAiThreadService;
import org.leo.web.dto.platform.ai.PlatformAiDtos;
import org.leo.web.dto.platform.ai.PlatformAiDtos.ChatRequest;
import org.leo.web.dto.platform.ai.PlatformAiDtos.EventsRequest;
import org.leo.web.dto.platform.ai.PlatformAiDtos.MessagesRequest;
import org.leo.web.exception.ApiException;
import org.leo.web.security.AdminOnlyEndpoint;
import org.leo.web.util.ControllerUtil;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/platform/ai")
public class PlatformAiController {

    private static final String SESSION_ATTR_PLATFORM_AI_STATE_ID = "platformAiStateId";
    private final PlatformAiThreadService threadService;
    private final AiThreadQueryService threadQueries;
    private final AiModelConfigService aiModelConfigService;
    private final AiTurnTelemetryRegistry telemetryRegistry;
    private final AiEventSubscriptionService eventSubscriptionService;
    private final AiTurnProtocolService turnProtocolService;
    private final AiTurnQueueService turnQueueService;

    public PlatformAiController(PlatformAiThreadService threadService,
                                AiThreadQueryService threadQueries,
                                AiModelConfigService aiModelConfigService,
                                AiTurnTelemetryRegistry telemetryRegistry,
                                AiEventSubscriptionService eventSubscriptionService,
                                AiTurnProtocolService turnProtocolService,
                                AiTurnQueueService turnQueueService) {
        this.threadService = threadService;
        this.threadQueries = threadQueries;
        this.aiModelConfigService = aiModelConfigService;
        this.telemetryRegistry = telemetryRegistry;
        this.eventSubscriptionService = eventSubscriptionService;
        this.turnProtocolService = turnProtocolService;
        this.turnQueueService = turnQueueService;
    }

    /**
     * 返回平台 AI 是否已具备可用通道。该接口只暴露布尔状态和计数，
     * 避免主工作台为了判断入口可用性读取后台配置列表。
     */
    @GetMapping("/availability")
    public Map<String, Object> availability() {
        int enabledCount = availableModelCatalog().size();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("available", enabledCount > 0);
        data.put("enabledCount", enabledCount);
        return ApiResponse.success(data);
    }

    /**
     * 普通用户可读取的脱敏模型目录，不返回 API Key、Base URL 或自定义 Headers。
     */
    @GetMapping("/models")
    public Map<String, Object> models() {
        return ApiResponse.success(availableModelCatalog());
    }

    /** 管理员查看当前进程的 Turn 终态聚合与最近 trace。 */
    @AdminOnlyEndpoint
    @GetMapping("/telemetry")
    public Map<String, Object> telemetry() {
        return ApiResponse.success(telemetryRegistry.snapshot());
    }

    private List<Map<String, Object>> availableModelCatalog() {
        List<Map<String, Object>> items = new ArrayList<>();
        for (AiModelConfig config : aiModelConfigService.listEnabled()) {
            ProviderCapabilities capabilities = aiModelConfigService.capabilitiesForModel(config);
            if (capabilities.supportsTextGeneration()
                    && capabilities.supportsStreaming()
                    && capabilities.maxOutputTokens() > 0) {
                items.add(toCatalogItem(config, capabilities));
            }
        }
        return items;
    }

    private Map<String, Object> toCatalogItem(AiModelConfig config, ProviderCapabilities capabilities) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", config.getId());
        item.put("name", config.getName());
        item.put("providerKey", config.getProviderKey());
        item.put("providerName", config.getProviderName());
        item.put("model", config.getModel());
        item.put("protocol", config.getProtocol());
        item.put("isActive", config.getIsActive());
        item.put("enabled", config.getEnabled());
        item.put("contextWindowTokens", ModelRequestOptions.effectiveInputWindow(config, capabilities));
        item.put("maxOutputTokens", config.getMaxOutputTokens() != null
                ? Math.min(config.getMaxOutputTokens(), capabilities.maxOutputTokens())
                : capabilities.maxOutputTokens());
        item.put("supportsReasoning", capabilities.supportsReasoning());
        item.put("thinkingEnabled", config.getThinkingEnabled());
        item.put("reasoningEffort", config.getReasoningEffort());
        item.put("requestOptions", ModelRequestOptions.forConfiguration(config, capabilities));
        item.put("supportsFunctionCalling", capabilities.supportsFunctionCalling());
        item.put("supportsParallelToolCalls", capabilities.supportsParallelToolCalls());
        return item;
    }

    /**
     * Codex 式 Turn 启动：命令接口立即返回稳定 Turn，后台执行和事件订阅彼此独立。
     */
    @PostMapping("/turn/start")
    public Map<String, Object> startTurn(@RequestBody ChatRequest body,
                                         HttpServletRequest request) {
        String message = body != null ? body.message() : null;
        if (message == null || message.isBlank()) {
            throw ApiException.badRequest("缺少 message");
        }
        PlatformAiState state = threadService.requireOwnedRuntime(
                ControllerUtil.getCurrentUser(request), body.threadId());

        AiExecutionPolicy policy = ControllerUtil.buildAiExecutionPolicy(request);
        String guardedMessage = AiAttachmentPrompt.appendTo(
                ControllerUtil.buildAiPolicyPrompt(policy, message), body.attachments());
        AiTurnCommandPayload command = AiTurnCommandPayload.create(
                AiTurnCommandPayload.SCOPE_PLATFORM,
                request.getSession().getId(), message, guardedMessage,
                body.configId(), body.reasoningEffort(),
                AiAttachmentPrompt.metadata(body.attachments()), policy)
                .answerTo(body.answerToQuestionId());
        AiTurnProtocolService.Reservation reservation;
        try {
            reservation = turnProtocolService.begin(
                    state.getStateId(), body.clientUserMessageId(),
                    command.getScope(), command.toJson(),
                    message, body.attachments(), body.answerToQuestionId());
        } catch (RuntimeException error) {
            throw ApiException.badRequest(error.getMessage());
        }
        AiTurnProtocolService.TurnSnapshot turn = reservation.turn();
        turnQueueService.signalPersistedTurn(state.getStateId(), reservation);
        return ApiResponse.success(Map.of("turn", turn.toMap()));
    }

    /** 请求中断指定 Turn；最终状态由 turn/completed 通知确认。 */
    @PostMapping("/turn/interrupt")
    public Map<String, Object> interruptTurn(
            @RequestBody PlatformAiDtos.TurnInterruptRequest body,
            HttpServletRequest request) {
        PlatformAiState state = threadService.requireOwnedRuntime(
                ControllerUtil.getCurrentUser(request),
                body != null ? body.threadId() : null);
        String turnId = body != null ? body.turnId() : null;
        try {
            AiTurnProtocolService.TurnSnapshot turn =
                    turnProtocolService.requestInterrupt(
                            state.getStateId(), turnId);
            if (turn.id().equals(state.getActiveTurnId())) {
                state.stopGeneration();
            }
            return ApiResponse.success(Map.of("turn", turn.toMap()));
        } catch (RuntimeException error) {
            throw ApiException.badRequest(error.getMessage());
        }
    }

    /** 创建初始对话并将其绑定到当前 HTTP 会话，不登记接入节点。 */
    @PostMapping("/thread/initialize")
    public Map<String, Object> initializeThread(
            @RequestBody(required = false) PlatformAiDtos.InitializeThreadRequest body, HttpServletRequest request) {
        User user = ControllerUtil.getCurrentUser(request);
        Integer configId = body != null ? body.configId() : null;
        HttpSession session = request.getSession();
        Object previous = session.getAttribute(SESSION_ATTR_PLATFORM_AI_STATE_ID);
        var thread = threadService.initializeThread(session.getId(),
                previous instanceof String stateId ? stateId : null, user, configId);
        session.setAttribute(SESSION_ATTR_PLATFORM_AI_STATE_ID, thread.threadId());
        return ApiResponse.success(thread);
    }

    /**
     * 热切换 AI 通道。
     */
    @PostMapping("/switchChannel")
    public Map<String, Object> switchChannel(@RequestBody(required = false) PlatformAiDtos.SwitchChannelRequest body,
                                             HttpServletRequest request) {
        Integer configId = body != null ? body.configId() : null;
        PlatformAiState state = threadService.requireOwnedRuntime(
                ControllerUtil.getCurrentUser(request), body != null ? body.threadId() : null);
        threadService.switchChannel(state, configId);
        return ApiResponse.success(true);
    }

    // ── 线程管理 ─────────────────────────────────────────────────────────────

    /**
     * 列出当前用户的所有平台 AI 线程。
     */
    @PostMapping("/threads")
    public Map<String, Object> threads(HttpServletRequest request) {
        User user = ControllerUtil.getCurrentUser(request);
        return ApiResponse.success(threadQueries.listPlatformThreads(user.getUserId()));
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
        HttpSession session = request.getSession();
        var thread = threadService.createThread(session.getId(), user, title, configId);
        session.setAttribute(SESSION_ATTR_PLATFORM_AI_STATE_ID, thread.threadId());
        return ApiResponse.success(thread);
    }

    /**
     * 删除指定线程。
     */
    @PostMapping("/thread/delete")
    public Map<String, Object> deleteThread(@RequestBody PlatformAiDtos.ThreadIdRequest body,
                                            HttpServletRequest request) {
        threadService.deleteThread(ControllerUtil.getCurrentUser(request), body.threadId());
        HttpSession session = request.getSession();
        if (body.threadId() != null
                && body.threadId().equals(session.getAttribute(SESSION_ATTR_PLATFORM_AI_STATE_ID))) {
            session.removeAttribute(SESSION_ATTR_PLATFORM_AI_STATE_ID);
        }
        return ApiResponse.success(true);
    }

    /**
     * 重命名指定线程。
     */
    @PostMapping("/thread/rename")
    public Map<String, Object> renameThread(@RequestBody PlatformAiDtos.ThreadRenameRequest body,
                                            HttpServletRequest request) {
        threadService.renameThread(
                ControllerUtil.getCurrentUser(request), body.threadId(), body.title());
        return ApiResponse.success(true);
    }

    /**
     * 切换到指定线程（恢复 in-memory 状态）。
     */
    @PostMapping("/thread/activate")
    public Map<String, Object> activateThread(@RequestBody PlatformAiDtos.ThreadIdRequest body,
                                               HttpServletRequest request) {
        PlatformAiState state = threadService.activateThread(
                ControllerUtil.getCurrentUser(request), body.threadId());
        request.getSession().setAttribute(SESSION_ATTR_PLATFORM_AI_STATE_ID, state.getStateId());
        return ApiResponse.success(true);
    }

    /** 通过显式 threadId 重新附着运行中的平台 AI 事件流。 */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestBody EventsRequest body,
                             HttpServletRequest request) {
        PlatformAiState state = threadService.requireOwnedRuntime(
                ControllerUtil.getCurrentUser(request), body.threadId());
        return eventSubscriptionService.subscribe(
                "Platform AI subscription", body.threadId(), state, body.afterSeq());
    }

    /**
     * 获取当前平台 AI 最近 SSE 事件，用于 SSE 中断后的补拉恢复。
     */
    @PostMapping("/events")
    public Map<String, Object> events(@RequestBody EventsRequest body,
                                      HttpServletRequest request) {
        return ApiResponse.success(threadService.events(
                ControllerUtil.getCurrentUser(request), body.threadId(),
                body.afterSeq(), body.limit()));
    }

    /**
     * 加载当前平台 AI 会话的历史消息（分页）。
     */
    @PostMapping("/messages")
    public Map<String, Object> messages(@RequestBody MessagesRequest body,
                                        HttpServletRequest request) {
        return ApiResponse.success(threadService.messages(
                ControllerUtil.getCurrentUser(request), body.threadId(),
                body.offset(), body.limit(), body.beforeSequence()));
    }

    /** 加载当前平台 AI 线程派发过的 Node AI 子任务。 */
    @PostMapping("/subagents")
    public Map<String, Object> subagents(
            @RequestBody PlatformAiDtos.ThreadIdRequest body,
            HttpServletRequest request) {
        return ApiResponse.success(threadService.subagentInvocations(
                ControllerUtil.getCurrentUser(request), body.threadId()));
    }

    /**
     * 获取当前平台 AI 会话的活跃任务计划。
     */
    @PostMapping("/plan")
    public Map<String, Object> plan(
            @RequestBody PlatformAiDtos.ThreadIdRequest body,
            HttpServletRequest request) {
        PlatformAiState state = threadService.requireOwnedRuntime(
                ControllerUtil.getCurrentUser(request), body.threadId());
        AiPlan plan = state.getCurrentPlan();
        return ApiResponse.success(plan);
    }

    /**
     * 获取当前平台 AI 会话的所有历史任务计划（按创建时间升序）。
     */
    @PostMapping("/plans")
    public Map<String, Object> plans(
            @RequestBody PlatformAiDtos.ThreadIdRequest body,
            HttpServletRequest request) {
        PlatformAiState state = threadService.requireOwnedRuntime(
                ControllerUtil.getCurrentUser(request), body.threadId());
        List<AiPlan> history = state.getPlanHistory();
        return ApiResponse.success(history);
    }

}
