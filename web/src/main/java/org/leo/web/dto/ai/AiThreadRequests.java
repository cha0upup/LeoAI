package org.leo.web.dto.ai;

import java.util.List;

/** 平台和节点 AI 共用的对话请求。sessionId 只由节点入口校验和使用。 */
public final class AiThreadRequests {

    private AiThreadRequests() {
    }

    public record StartTurnRequest(String sessionId, String threadId, String message, Integer configId,
                                   String reasoningEffort, List<AiFileAttachment> attachments,
                                   String clientUserMessageId, String answerToQuestionId) {
    }

    public record InterruptTurnRequest(String sessionId, String threadId, String turnId) {
    }

    public record CreateThreadRequest(String sessionId, String title, Integer configId) {
    }

    public record ThreadRequest(String sessionId, String threadId) {
    }

    public record RenameThreadRequest(String sessionId, String threadId, String title) {
    }

    public record ThreadConfigRequest(String sessionId, String threadId, Integer configId) {
    }

    public record ThreadEventsRequest(String sessionId, String threadId, Long afterSeq, Integer limit) {
    }

    public record ThreadMessagesRequest(String sessionId, String threadId, Integer offset, Integer limit,
                                        Long beforeSequence) {
    }
}
