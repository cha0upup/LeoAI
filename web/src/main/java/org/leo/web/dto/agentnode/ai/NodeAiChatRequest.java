package org.leo.web.dto.agentnode.ai;

import org.leo.web.dto.ai.AiFileAttachment;

import java.util.List;

public record NodeAiChatRequest(String sessionId,
                                  String threadId,
                                  String message,
                                  Integer configId,
                                  String reasoningEffort,
                                  List<AiFileAttachment> attachments,
                                  String clientUserMessageId,
                                  String answerToQuestionId) {
}
