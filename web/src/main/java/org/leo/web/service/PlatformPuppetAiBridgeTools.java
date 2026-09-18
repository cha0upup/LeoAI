package org.leo.web.service;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import org.leo.ai.audit.AiAuditLogStore;
import org.leo.ai.platform.PlatformAiState;
import org.leo.ai.platform.PlatformAiStateStore;
import org.leo.ai.thread.AiConversationStoreService;
import org.leo.core.entity.AiChatAuditEntry;
import org.leo.core.entity.AiExecutionPolicy;
import org.leo.core.entity.AiSubagentInvocation;
import org.leo.core.entity.AiThreadRecord;
import org.leo.core.entity.Puppet;
import org.leo.core.entity.User;
import org.leo.core.ai.AiRunStatus;
import org.leo.core.session.AiThread;
import org.leo.core.session.PuppetNodeSession;
import org.leo.core.session.PuppetNodeSessionContainer;
import org.leo.core.util.session.PuppetNodeSessionWorkDirUtil;
import org.leo.service.user.UserService;
import org.leo.web.dto.puppetnode.PuppetInitResponse;
import org.leo.web.security.PermissionService;
import org.leo.web.util.ControllerUtil;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 平台 AI 到 Puppet AI 的受控委派桥。 */
@Component
@org.leo.ai.agent.AiToolPolicy(
        kind = org.leo.ai.agent.AiToolKind.DELEGATION,
        operation = org.leo.ai.agent.AiToolOperation.WRITE,
        business = false)
public class PlatformPuppetAiBridgeTools {

    private static final int MAX_SUMMARY_CHARS = 12_000;

    private final PuppetNodeAiThreadService threadService;
    private final PuppetNodeAiDelegationService delegationService;
    private final PuppetNodeLifecycleService lifecycleService;
    private final PermissionService permissionService;
    private final UserService userService;
    private final AiConversationStoreService conversationStore;
    private final AiAuditLogStore auditLogStore;

    public PlatformPuppetAiBridgeTools(PuppetNodeAiThreadService threadService,
                                       PuppetNodeAiDelegationService delegationService,
                                       PuppetNodeLifecycleService lifecycleService,
                                       PermissionService permissionService,
                                       UserService userService,
                                       AiConversationStoreService conversationStore,
                                       AiAuditLogStore auditLogStore) {
        this.threadService = threadService;
        this.delegationService = delegationService;
        this.lifecycleService = lifecycleService;
        this.permissionService = permissionService;
        this.userService = userService;
        this.conversationStore = conversationStore;
        this.auditLogStore = auditLogStore;
    }

    @Tool(name = "list_puppet_ai_targets",
            value = "列出当前用户可委派 Puppet AI 的活跃会话。返回 sessionId、puppetId、cacheMode 和最后活跃时间。")
    @org.leo.ai.agent.AiToolPolicy(
            kind = org.leo.ai.agent.AiToolKind.QUERY,
            operation = org.leo.ai.agent.AiToolOperation.READ_ONLY,
            parallelizable = true)
    public List<Map<String, Object>> listTargets(@ToolMemoryId String parentThreadId) {
        Caller caller = requireCaller(parentThreadId);
        List<Map<String, Object>> result = new ArrayList<>();
        for (PuppetNodeSession session : PuppetNodeSessionContainer.getAllSession().values()) {
            if (!canAccessSession(session, caller.user())) continue;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("sessionId", session.getSessionId());
            item.put("puppetId", resolvePuppetId(session));
            item.put("cacheMode", session.isCacheMode());
            item.put("lastActiveAt", session.getLastActiveTime());
            item.put("executing", session.listAiThreads().stream().anyMatch(AiThread::isExecuting));
            result.add(item);
        }
        result.sort(Comparator.comparingLong(item -> -asLong(item.get("lastActiveAt"))));
        return result;
    }

    @Tool(name = "dispatch_puppet_ai",
            value = "把任务委派给一个 Puppet AI 并等待最终结果。优先传 sessionId；也可只传 puppetId，"
                    + "此时会复用当前用户可访问的活跃会话，若不存在则建立新的实时会话。"
                    + "每次委派都会创建隔离子线程并记录父子关系。task 必填；configId 可选，默认沿用平台 AI 当前模型。")
    public Map<String, Object> dispatch(@ToolMemoryId String parentThreadId,
                                        @P("交给 Puppet AI 的完整任务描述") String task,
                                        @P(value = "活跃 Puppet 会话 ID；与 puppetId 至少提供一个，优先使用此项",
                                                required = false) String sessionId,
                                        @P(value = "Puppet ID；sessionId 省略时用于查找或建立会话",
                                                required = false) String puppetId,
                                        @P(value = "AI 配置 ID；省略时沿用平台 AI 当前模型",
                                                required = false) Integer configId) {
        String normalizedTask = requireText(task, "task 不能为空");
        Caller caller = requireCaller(parentThreadId);
        PuppetNodeSession session = resolveSession(sessionId, puppetId, caller.user());
        session.touchLastActiveTime();

        Integer effectiveConfigId = configId != null ? configId : caller.state().getAiConfigId();
        String invocationId = UUID.randomUUID().toString();
        AiSubagentInvocation invocation = new AiSubagentInvocation();
        invocation.setInvocationId(invocationId);
        invocation.setParentThreadId(parentThreadId);
        invocation.setTask(normalizedTask);
        invocation.setStatus(AiSubagentInvocation.STATUS_PENDING);
        invocation.setCreatedAt(System.currentTimeMillis());
        conversationStore.insertSubagentInvocation(invocation);
        emitSubagentEvent(caller.state(), invocation, session.getSessionId(), resolvePuppetId(session));

        AiThread childThread = null;
        try {
            Map<String, Object> created = threadService.createChildThread(
                    session, childTitle(normalizedTask), effectiveConfigId, parentThreadId);
            String childThreadId = String.valueOf(created.get("threadId"));
            childThread = session.getAiThread(childThreadId);
            if (childThread == null) {
                throw new IllegalStateException("Puppet AI 子线程创建失败");
            }
            childThread.setExecutionPolicy(AiExecutionPolicy.from(caller.user()));

            invocation.setChildThreadId(childThreadId);
            invocation.setStatus(AiSubagentInvocation.STATUS_RUNNING);
            conversationStore.updateSubagentInvocation(invocation);
            emitSubagentEvent(caller.state(), invocation, session.getSessionId(), resolvePuppetId(session));

            AiExecutionPolicy policy = AiExecutionPolicy.from(caller.user());
            AiChatAuditEntry audit = AiChatAuditEntry.puppet(
                    session.getSessionId(), policy.getUserId(), policy.getUserName(), policy.getPrivilege(),
                    normalizedTask);
            auditLogStore.append(audit);
            String guardedMessage = ControllerUtil.buildAiPolicyPrompt(policy, normalizedTask);
            Map<String, Object> delegated = delegationService.execute(
                    session, childThread, normalizedTask, guardedMessage, audit,
                    invocationId, event -> forwardChildEvent(caller.state(), invocation, event),
                    caller.state());

            String status = String.valueOf(delegated.get("status"));
            if (!AiSubagentInvocation.STATUS_COMPLETED.equals(status)
                    && !AiSubagentInvocation.STATUS_WAITING_FOR_USER.equals(status)) {
                throw new IllegalStateException("Puppet AI 返回了非预期状态: " + status);
            }
            String summary = truncate(java.util.Objects.toString(delegated.get("summary"), ""), MAX_SUMMARY_CHARS);
            invocation.setSummary(summary);
            invocation.setStatus(status);
            invocation.setCompletedAt(AiSubagentInvocation.isTerminal(status)
                    ? System.currentTimeMillis() : null);
            conversationStore.updateSubagentInvocation(invocation);
            emitSubagentEvent(caller.state(), invocation, session.getSessionId(), resolvePuppetId(session));
            return result(invocation, session, summary, null);
        } catch (RuntimeException error) {
            invocation.setStatus(isCancelled(childThread, error)
                    ? AiSubagentInvocation.STATUS_CANCELLED : AiSubagentInvocation.STATUS_FAILED);
            invocation.setSummary(truncate(error.getMessage(), MAX_SUMMARY_CHARS));
            invocation.setCompletedAt(System.currentTimeMillis());
            conversationStore.updateSubagentInvocation(invocation);
            emitSubagentEvent(caller.state(), invocation, session.getSessionId(), resolvePuppetId(session));
            return result(invocation, session, null, error.getMessage());
        }
    }

    private boolean isCancelled(AiThread childThread, Throwable error) {
        if (childThread != null && AiRunStatus.CANCELLED.equals(childThread.getRunStatus())) return true;
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof InterruptedException
                    || cause instanceof java.util.concurrent.CancellationException) return true;
        }
        return Thread.currentThread().isInterrupted();
    }

    private Caller requireCaller(String parentThreadId) {
        String normalizedThreadId = requireText(parentThreadId, "平台 AI 线程上下文不存在");
        PlatformAiState state = PlatformAiStateStore.get(normalizedThreadId);
        AiThreadRecord record = conversationStore.findThread(normalizedThreadId);
        if (state == null || record == null
                || !AiConversationStoreService.SCOPE_PLATFORM.equals(record.getScope())) {
            throw new IllegalStateException("平台 AI 线程不存在或已失效");
        }
        AiExecutionPolicy policy = state.getExecutionPolicy();
        if (policy == null || policy.getUserId() == null
                || !policy.getUserId().equals(record.getUserId())) {
            throw new SecurityException("平台 AI 线程身份校验失败");
        }
        User user = userService.getUserById(policy.getUserId());
        if (user == null || !Integer.valueOf(1).equals(user.getStatus())) {
            throw new SecurityException("当前用户不存在或已禁用");
        }
        return new Caller(state, user);
    }

    private PuppetNodeSession resolveSession(String sessionId, String puppetId, User user) {
        if (sessionId != null && !sessionId.isBlank()) {
            PuppetNodeSession session = PuppetNodeSessionContainer.getSession(sessionId.trim());
            if (session == null) throw new IllegalArgumentException("Puppet 会话不存在或已过期");
            permissionService.requireSessionAccess(session, user, session.getSessionId());
            if (puppetId != null && !puppetId.isBlank()
                    && !puppetId.trim().equals(resolvePuppetId(session))) {
                throw new IllegalArgumentException("sessionId 与 puppetId 不匹配");
            }
            return session;
        }

        String normalizedPuppetId = requireText(puppetId, "sessionId 和 puppetId 至少提供一个");
        Puppet puppet = permissionService.requireAccessiblePuppetChain(normalizedPuppetId, user);
        PuppetNodeSession existing = PuppetNodeSessionContainer.getAllSession().values().stream()
                .filter(session -> normalizedPuppetId.equals(resolvePuppetId(session)))
                .filter(session -> canAccessSession(session, user))
                .max(Comparator.comparingLong(PuppetNodeSession::getLastActiveTime))
                .orElse(null);
        if (existing != null) return existing;

        try {
            PuppetInitResponse response = lifecycleService.initLiveSession(puppet, user);
            PuppetNodeSession created = PuppetNodeSessionContainer.getSession(response.sessionId());
            if (created == null) throw new IllegalStateException("Puppet 实时会话创建后未找到");
            return created;
        } catch (Exception e) {
            throw new IllegalStateException("建立 Puppet 实时会话失败: " + e.getMessage(), e);
        }
    }

    private boolean canAccessSession(PuppetNodeSession session, User user) {
        try {
            permissionService.requireSessionAccess(session, user, session.getSessionId());
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private void emitSubagentEvent(PlatformAiState state, AiSubagentInvocation invocation,
                                   String sessionId, String puppetId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("kind", "subtask");
        payload.put("subagentInvocationId", invocation.getInvocationId());
        payload.put("parentThreadId", invocation.getParentThreadId());
        payload.put("childThreadId", invocation.getChildThreadId());
        payload.put("sessionId", sessionId);
        payload.put("puppetId", puppetId);
        payload.put("task", invocation.getTask());
        payload.put("status", invocation.getStatus());
        payload.put("summary", invocation.getSummary());
        payload.put("createdAt", invocation.getCreatedAt());
        payload.put("completedAt", invocation.getCompletedAt());
        String eventName = AiSubagentInvocation.STATUS_PENDING.equals(invocation.getStatus())
                ? "node" : "patch";
        state.getAiSseEventQueue().offer(
                state.recordSseEvent(eventName, payload, invocation.getInvocationId()));
    }

    private void forwardChildEvent(PlatformAiState state,
                                   AiSubagentInvocation invocation,
                                   org.leo.core.entity.AiSseEvent childEvent) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("kind", "subtask_event");
        payload.put("subagentInvocationId", invocation.getInvocationId());
        payload.put("childThreadId", invocation.getChildThreadId());
        payload.put("eventName", childEvent.name());
        payload.put("eventData", childEvent.data());
        payload.put("childSeq", childEvent.seq());
        payload.put("childTimestamp", childEvent.timestamp());
        state.getAiSseEventQueue().offer(
                state.recordSseEvent("subagent_event", payload, invocation.getInvocationId()));
    }

    private Map<String, Object> result(AiSubagentInvocation invocation, PuppetNodeSession session,
                                       String summary, String error) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("invocationId", invocation.getInvocationId());
        result.put("sessionId", session.getSessionId());
        result.put("puppetId", resolvePuppetId(session));
        result.put("childThreadId", invocation.getChildThreadId());
        result.put("status", invocation.getStatus());
        if (summary != null) result.put("summary", summary);
        if (error != null) result.put("error", error);
        return result;
    }

    private static String resolvePuppetId(PuppetNodeSession session) {
        return PuppetNodeSessionWorkDirUtil.resolvePuppetId(session);
    }

    private static String childTitle(String task) {
        String compact = task.replaceAll("\\s+", " ").trim();
        return "平台 AI 委派 · " + (compact.length() > 48 ? compact.substring(0, 48) + "…" : compact);
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(message);
        return value.trim();
    }

    private static String truncate(String value, int maxChars) {
        if (value == null || value.length() <= maxChars) return value;
        return value.substring(0, maxChars) + "\n...(已截断)";
    }

    private static long asLong(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private record Caller(PlatformAiState state, User user) {}
}
