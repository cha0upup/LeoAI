package org.leo.ai.agent;

import org.leo.core.entity.AiExecutionPolicy;
import org.leo.core.entity.AiPlan;
import org.leo.core.entity.AiRuntimeStats;
import org.leo.core.entity.AiSseEvent;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * AI 运行时状态访问接口。
 *
 * <p>为工具层和服务层提供统一的状态读写入口，屏蔽 PuppetNode / Platform 两侧差异。
 */
public interface AiStateAccessor {

    AiRuntimeStats getRuntimeStats();

    AiExecutionPolicy getExecutionPolicy();

    boolean isValid();

    boolean isStopRequested();

    CompletableFuture<Boolean> awaitConfirmation(String callId, String toolName,
            String toolType, String toolTypeLabel, String argsPreview, String impact);

    CompletableFuture<Boolean> awaitConfirmation(String callId, String toolName,
            String toolType, String toolTypeLabel, String argsPreview, String impact,
            long timeoutMs);

    boolean resolveConfirmation(String callId, boolean approved);

    void cancelAllPendingConfirmations();

    void grantSessionType(String toolType);

    void grantSessionAll();

    boolean isSessionGranted(String toolType);

    void offerWarnMessage(String message);

    void setCurrentPlan(AiPlan plan);

    AiPlan getCurrentPlan();

    List<AiPlan> getPlanHistory();

    void notifyPlanUpdated();

    LinkedBlockingQueue<AiSseEvent> getAiSseEventQueue();
}
