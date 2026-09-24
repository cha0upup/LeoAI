package org.leo.ai.memory;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.service.memory.ChatMemoryAccess;
import org.junit.jupiter.api.Test;
import org.leo.ai.thread.AiConversationStoreService.ConversationMessage;
import org.leo.ai.thread.AiConversationStoreService;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ManagedConversationMemoryTest {

    @Test
    void initializesFreshConversationWithEmptyMemory() {
        String memoryId = "platform-ai-fresh";
        var memory = MessageWindowChatMemory.builder()
                .id(memoryId)
                .maxMessages(20)
                .build();
        AiConversationStoreService conversationStore = mock(AiConversationStoreService.class);
        when(conversationStore.contextMessages(memoryId, 200)).thenReturn(List.of());

        ChatMemory initialized =
                new ManagedConversationMemory(conversationStore).initialize(memory);

        assertEquals(memory, initialized);
        assertTrue(initialized.messages().isEmpty());
    }

    @Test
    void rebuildsMemoryFromCommittedConversationMessages() {
        String memoryId = "session-1:thread-1";
        var memory = MessageWindowChatMemory.builder()
                .id(memoryId)
                .maxMessages(20)
                .build();
        memory.add(UserMessage.from("已提交问题"));
        memory.add(AiMessage.from("已提交回答"));
        memory.add(UserMessage.from("被取消的问题"));
        memory.add(AiMessage.from(ToolExecutionRequest.builder()
                .id("call-1")
                .name("getBasicInfo")
                .arguments("{}")
                .build()));

        ChatMemoryAccess agent = mock(ChatMemoryAccess.class);
        when(agent.getChatMemory(memoryId)).thenReturn(memory);
        AiConversationStoreService conversationStore = mock(AiConversationStoreService.class);
        when(conversationStore.contextMessages("thread-1", 200)).thenReturn(List.of(
                new ConversationMessage(null, "user", "已提交问题"),
                new ConversationMessage(null, "assistant", "已提交回答")));

        boolean rebuilt = new ManagedConversationMemory(conversationStore).rebuild(agent, memoryId);

        assertTrue(rebuilt);
        assertEquals(2, memory.messages().size());
        assertInstanceOf(UserMessage.class, memory.messages().get(0));
        assertInstanceOf(AiMessage.class, memory.messages().get(1));
        assertFalse(memory.messages().stream()
                .anyMatch(message -> message instanceof AiMessage ai
                        && ai.hasToolExecutionRequests()));
    }

    @Test
    void returnsFalseWhenAgentMemoryHasNotBeenCreated() {
        ChatMemoryAccess agent = mock(ChatMemoryAccess.class);
        when(agent.getChatMemory("missing")).thenReturn(null);

        assertFalse(new ManagedConversationMemory(mock(AiConversationStoreService.class))
                .rebuild(agent, "missing"));
    }

    @Test
    void clearsTransientMessagesWhenCommittedHistoryIsEmpty() {
        String memoryId = "session-1:fresh-thread";
        var memory = MessageWindowChatMemory.builder()
                .id(memoryId)
                .maxMessages(20)
                .build();
        memory.add(UserMessage.from("失败请求"));

        ChatMemoryAccess agent = mock(ChatMemoryAccess.class);
        when(agent.getChatMemory(memoryId)).thenReturn(memory);
        AiConversationStoreService conversationStore = mock(AiConversationStoreService.class);
        when(conversationStore.contextMessages("fresh-thread", 200)).thenReturn(List.of());

        assertTrue(new ManagedConversationMemory(conversationStore)
                .rebuild(agent, memoryId));
        assertTrue(memory.messages().isEmpty());
    }
}
