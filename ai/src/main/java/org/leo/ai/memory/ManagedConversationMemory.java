package org.leo.ai.memory;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.service.memory.ChatMemoryAccess;
import org.leo.ai.thread.AiMessageRepository;
import org.leo.ai.thread.ConversationMessage;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 以数据库中 committed 消息和可恢复的中断进度为事实来源管理 ChatMemory。
 */
@Component
public class ManagedConversationMemory {

    private final AiMessageRepository messages;

    public ManagedConversationMemory(AiMessageRepository messages) {
        this.messages = messages;
    }

    /**
     * 初始化新创建的 ChatMemory，并从数据库加载该会话的 committed 历史。
     */
    public ChatMemory initialize(ChatMemory memory) {
        if (memory != null) loadHistory(memory, memory.id());
        return memory;
    }

    /**
     * 使用已提交消息和中断前可恢复进度重建指定 Agent 的短期记忆。
     *
     * <p>没有产生任何进度的 discarded Turn 仍会被排除；已经产生可见输出、工具结果
     * 或计划快照的 Turn 会作为恢复上下文重新进入模型。
     */
    public boolean rebuild(ChatMemoryAccess agent, Object memoryId) {
        if (agent == null || memoryId == null) {
            return false;
        }
        ChatMemory memory = agent.getChatMemory(memoryId);
        if (memory == null) {
            return false;
        }
        loadHistory(memory, memoryId);
        return true;
    }

    private void loadHistory(ChatMemory memory, Object memoryId) {
        String threadId = threadIdFromMemoryId(String.valueOf(memoryId));
        memory.set(toChatMessages(messages.contextMessages(threadId, 200)));
    }

    /** 平台记忆直接使用线程 ID，节点记忆使用 Session 前缀区分运行实例。 */
    public static String threadIdFromMemoryId(String memoryId) {
        if (memoryId == null) return "";
        int separator = memoryId.lastIndexOf(':');
        return separator >= 0 && separator < memoryId.length() - 1
                ? memoryId.substring(separator + 1)
                : memoryId;
    }

    private static List<ChatMessage> toChatMessages(List<ConversationMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        List<ChatMessage> result = new ArrayList<>();
        for (ConversationMessage message : messages) {
            if (message == null || message.content() == null || message.content().isBlank()) {
                continue;
            }
            if ("user".equalsIgnoreCase(message.role())) {
                result.add(UserMessage.from(message.content()));
            } else if ("assistant".equalsIgnoreCase(message.role())) {
                result.add(AiMessage.from(message.content()));
            }
        }
        return result;
    }
}
