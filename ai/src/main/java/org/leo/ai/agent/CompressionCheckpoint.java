package org.leo.ai.agent;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * 一次压缩对应的原始消息边界。
 *
 * <p>checkpoint 保存压缩时的原始消息指纹快照和其中已被摘要覆盖的数量。
 * 新消息追加或窗口从头淘汰时可重新定位边界，无需对同一历史重复调用模型。
 */
final class CompressionCheckpoint {

    private final List<String> sourceFingerprints;
    private final int summarizedMessageCount;
    private final SystemMessage summaryMessage;

    private CompressionCheckpoint(List<String> sourceFingerprints,
                                  int summarizedMessageCount,
                                  SystemMessage summaryMessage) {
        this.sourceFingerprints = List.copyOf(sourceFingerprints);
        this.summarizedMessageCount = Math.max(0,
                Math.min(summarizedMessageCount, sourceFingerprints.size()));
        this.summaryMessage = summaryMessage;
    }

    static CompressionCheckpoint create(List<ChatMessage> sourceMessages,
                                        int summarizedMessageCount,
                                        SystemMessage summaryMessage) {
        return new CompressionCheckpoint(fingerprints(sourceMessages),
                summarizedMessageCount, summaryMessage);
    }

    ProjectedView project(List<ChatMessage> currentMessages) {
        List<String> current = fingerprints(currentMessages);
        if (current.equals(sourceFingerprints)) {
            return projected(currentMessages, summarizedMessageCount);
        }

        int overlap = longestSuffixPrefixOverlap(sourceFingerprints, current);
        if (overlap == 0 && !sourceFingerprints.isEmpty() && !current.isEmpty()) {
            return null;
        }
        int dropped = sourceFingerprints.size() - overlap;
        int currentBoundary = Math.max(0, summarizedMessageCount - dropped);
        return projected(currentMessages, currentBoundary);
    }

    CompressionCheckpoint afterAppend(List<ChatMessage> currentMessages, ChatMessage appended) {
        List<String> expected = new ArrayList<>(sourceFingerprints.size() + 1);
        expected.addAll(sourceFingerprints);
        expected.add(fingerprint(appended));

        List<String> current = fingerprints(currentMessages);
        int dropped = expected.size() - current.size();
        if (dropped < 0 || !expected.subList(dropped, expected.size()).equals(current)) {
            return null;
        }
        return new CompressionCheckpoint(current,
                Math.max(0, summarizedMessageCount - dropped), summaryMessage);
    }

    private ProjectedView projected(List<ChatMessage> currentMessages, int boundary) {
        int safeBoundary = Math.max(0, Math.min(boundary, currentMessages.size()));
        List<ChatMessage> view = new ArrayList<>(currentMessages.size() - safeBoundary + 1);
        view.add(summaryMessage);
        view.addAll(currentMessages.subList(safeBoundary, currentMessages.size()));
        return new ProjectedView(view, safeBoundary);
    }

    private static int longestSuffixPrefixOverlap(List<String> previous, List<String> current) {
        int max = Math.min(previous.size(), current.size());
        for (int length = max; length > 0; length--) {
            if (previous.subList(previous.size() - length, previous.size())
                    .equals(current.subList(0, length))) {
                return length;
            }
        }
        return 0;
    }

    static String sourceHash(List<ChatMessage> messages) {
        MessageDigest digest = sha256();
        for (String fingerprint : fingerprints(messages)) {
            digest.update(fingerprint.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static List<String> fingerprints(List<ChatMessage> messages) {
        List<String> result = new ArrayList<>(messages.size());
        for (ChatMessage message : messages) {
            result.add(fingerprint(message));
        }
        return result;
    }

    static String fingerprint(ChatMessage message) {
        MessageDigest digest = sha256();
        digest.update(message.type().name().getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
        digest.update(messageText(message).getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest.digest());
    }

    static DurableMessage durableMessage(ChatMessage message) {
        if (message instanceof UserMessage userMessage) {
            StringBuilder text = new StringBuilder();
            for (var content : userMessage.contents()) {
                if (content instanceof TextContent textContent) text.append(textContent.text());
                else return null;
            }
            return durableMessage("user", text.toString());
        }
        if (message instanceof AiMessage aiMessage
                && !aiMessage.hasToolExecutionRequests()
                && aiMessage.text() != null) {
            return durableMessage("assistant", aiMessage.text());
        }
        return null;
    }

    static DurableMessage durableMessage(String role, String content) {
        if (role == null || content == null || content.isBlank()) return null;
        String normalizedRole = role.trim().toLowerCase(java.util.Locale.ROOT);
        if (!"user".equals(normalizedRole) && !"assistant".equals(normalizedRole)) {
            return null;
        }
        return new DurableMessage(
                normalizedRole, content, durableFingerprint(normalizedRole, content));
    }

    static String durableFingerprint(String role, String content) {
        MessageDigest digest = sha256();
        digest.update(role.trim().toLowerCase(java.util.Locale.ROOT)
                .getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
        digest.update(content.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String messageText(ChatMessage message) {
        if (message instanceof SystemMessage systemMessage) return systemMessage.text();
        if (message instanceof UserMessage userMessage) {
            StringBuilder text = new StringBuilder();
            for (var content : userMessage.contents()) {
                if (content instanceof TextContent textContent) text.append(textContent.text());
                else text.append(content);
            }
            return text.toString();
        }
        if (message instanceof AiMessage aiMessage) {
            StringBuilder text = new StringBuilder(aiMessage.text() == null ? "" : aiMessage.text());
            if (aiMessage.toolExecutionRequests() != null) {
                aiMessage.toolExecutionRequests().forEach(request -> text
                        .append('\n').append(request.id())
                        .append('\n').append(request.name())
                        .append('\n').append(request.arguments()));
            }
            return text.toString();
        }
        if (message instanceof ToolExecutionResultMessage toolResult) {
            return toolResult.id() + '\n' + toolResult.toolName() + '\n' + toolResult.text();
        }
        return message.toString();
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    record ProjectedView(List<ChatMessage> messages, int summarizedSourceCount) {
    }

    record DurableMessage(String role, String content, String fingerprint) {
    }
}
