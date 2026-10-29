package org.leo.ai.thread;

import org.leo.core.entity.AiMessageRecord;

/** Separates the public message body from private, resumable model memory. */
public final class AiMessageContent {

    private AiMessageContent() {}

    public static String modelContext(AiMessageRecord message) {
        String recovery = message.getRecoveryContext();
        return isInterruptedAssistant(message) && recovery != null && !recovery.isBlank()
                ? recovery : message.getContent();
    }

    private static boolean isInterruptedAssistant(AiMessageRecord message) {
        return "assistant".equals(message.getRole()) && "discarded".equals(message.getStatus());
    }
}
