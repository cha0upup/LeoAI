package org.leo.web.dto.puppetnode.ai;

public record PuppetAiChatRequest(String sessionId,
                                  String threadId,
                                  String message,
                                  Integer configId) {
}
