package org.leo.ai.agent;

import dev.langchain4j.service.tool.BeforeToolExecution;
import org.leo.core.ai.AiToolRegistry;
import org.leo.core.entity.AiPlan;
import org.leo.core.session.AiThread;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 工具确认拦截回调。
 *
 * <p>作为 {@code beforeToolExecution} 的 Consumer 注册到 LangChain4j 流式 Agent，
 * 在每个工具调用执行前判断是否需要用户确认：
 *
 * <ol>
 *   <li>查询 {@link AiToolRegistry} 获取工具类别（非高影响工具直接放行）</li>
 *   <li>检查会话级授权（{@code sessionGrantedAll} 或 {@code sessionGrantedTypes}）</li>
 *   <li>检查当前计划步骤是否已预批准（{@code preApproved}）</li>
 *   <li>上述均不满足时，通过 {@link AiThread#registerAndAwaitConfirmation} 阻塞等待用户响应</li>
 * </ol>
 *
 * <p>拒绝时抛出 {@link ToolExecutionDeniedException}，LangChain4j 会将其作为工具错误返回给 AI。
 */
public class AiToolConfirmationCallback implements Consumer<BeforeToolExecution> {

    private static final Logger log = LoggerFactory.getLogger(AiToolConfirmationCallback.class);

    /** 确认等待超时（毫秒），超过后自动拒绝。 */
    private static final long DEFAULT_TIMEOUT_MS = 2 * 60 * 1000L; // 2 分钟

    private final AiThread thread;

    public AiToolConfirmationCallback(AiThread thread) {
        this.thread = thread;
    }

    @Override
    public void accept(BeforeToolExecution execution) {
        if (execution == null || execution.request() == null) return;

        String toolName = execution.request().name();
        if (toolName == null || toolName.isBlank()) return;

        // 1. 查询工具类别
        String normalized = toolName.trim().toLowerCase(Locale.ROOT);
        String categoryKey = AiToolRegistry.getCategoryKey(normalized);
        if (categoryKey == null) {
            // 非高影响工具，直接放行
            return;
        }

        // 2. 检查会话级授权
        if (thread.isSessionGranted(categoryKey)) {
            log.debug("ToolConfirm: 会话已授权 [{}] 类型，放行 {}", categoryKey, toolName);
            return;
        }

        // 3. 检查计划步骤预批准
        AiPlan plan = thread.getCurrentPlan();
        if (plan != null && plan.hasPreApprovedActiveStep()) {
            log.debug("ToolConfirm: 当前计划步骤已预批准，放行 {}", toolName);
            return;
        }

        // 4. 需要用户确认 — 构造确认请求并阻塞
        AiToolRegistry.ToolCategory category = AiToolRegistry.getCategory(categoryKey);
        String callId = "confirm-" + UUID.randomUUID().toString().substring(0, 8);
        String label = category != null ? category.label() : categoryKey;
        String impact = category != null ? category.impactDesc() : "高影响操作";
        String argsPreview = truncateArgs(execution.request().arguments());

        log.info("ToolConfirm: 拦截工具 {} ({}), callId={}", toolName, categoryKey, callId);

        try {
            CompletableFuture<Boolean> future = thread.registerAndAwaitConfirmation(
                    new org.leo.core.entity.AiConfirmationRequest(
                            callId, toolName, categoryKey, label, argsPreview, impact));

            Boolean approved = future.get(DEFAULT_TIMEOUT_MS, TimeUnit.MILLISECONDS);

            if (!Boolean.TRUE.equals(approved)) {
                throw new ToolExecutionDeniedException(
                        "用户拒绝执行 " + toolName + " (" + label + ")");
            }
            log.info("ToolConfirm: 用户批准 {} ({}), callId={}", toolName, categoryKey, callId);
        } catch (ToolExecutionDeniedException e) {
            throw e;
        } catch (java.util.concurrent.TimeoutException e) {
            log.warn("ToolConfirm: 确认超时, callId={}", callId);
            thread.resolveConfirmation(callId, false);
            throw new ToolExecutionDeniedException("工具确认超时，操作已拒绝: " + toolName);
        } catch (Exception e) {
            log.warn("ToolConfirm: 确认等待异常, callId={}: {}", callId, e.getMessage());
            throw new ToolExecutionDeniedException("工具确认失败: " + e.getMessage());
        }
    }

    private static String truncateArgs(String args) {
        if (args == null) return "{}";
        return args.length() > 500 ? args.substring(0, 500) + "..." : args;
    }
}
