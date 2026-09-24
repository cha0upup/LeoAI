package org.leo.web.dto.puppetnode.ai;

import org.leo.web.dto.ai.AiFileAttachment;

import java.util.List;

public record PuppetAiChatRequest(String sessionId,
                                  String threadId,
                                  String message,
                                  Integer configId,
                                  String reasoningEffort,
                                  List<AiFileAttachment> attachments,
                                  String clientUserMessageId,
                                  String answerToQuestionId) {
}
