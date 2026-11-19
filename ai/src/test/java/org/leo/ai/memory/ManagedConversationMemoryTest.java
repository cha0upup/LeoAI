package org.leo.ai.memory;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.memory.chat.TokenWindowChatMemory;
import dev.langchain4j.service.memory.ChatMemoryAccess;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.leo.ai.agent.CharBasedTokenEstimator;
import org.leo.ai.thread.AiMessageRepository;
import org.leo.ai.thread.ConversationMessage;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ManagedConversationMemoryTest {
    private final AiMessageRepository messages = mock(AiMessageRepository.class);
    private final ManagedConversationMemory managedMemory = new ManagedConversationMemory(messages);

    @ParameterizedTest(name = "{0} window, rebuild={1}")
    @CsvSource({"message,false", "message,true", "token,false", "token,true"})
    void clearsTransientMessagesWhenPersistedHistoryIsEmpty(String window, boolean rebuild) {
        String memoryId = rebuild ? "session-1:fresh-thread" : "fresh-thread";
        ChatMemory memory = window.equals("token")
                ? TokenWindowChatMemory.builder().id(memoryId).maxTokens(256, new CharBasedTokenEstimator()).build()
                : MessageWindowChatMemory.builder().id(memoryId).maxMessages(20).build();
        memory.add(UserMessage.from("失败请求"));
        assertFalse(memory.messages().isEmpty());
        when(messages.contextMessages("fresh-thread", 200)).thenReturn(List.of());

        if (rebuild) {
            ChatMemoryAccess agent = mock(ChatMemoryAccess.class);
            when(agent.getChatMemory(memoryId)).thenReturn(memory);
            assertTrue(managedMemory.rebuild(agent, memoryId));
        } else {
            assertSame(memory, managedMemory.initialize(memory));
        }
        assertTrue(memory.messages().isEmpty());
        verify(messages).contextMessages("fresh-thread", 200);
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
        when(messages.contextMessages("thread-1", 200)).thenReturn(List.of(
                new ConversationMessage(null, "user", "已提交问题"),
                new ConversationMessage(null, "assistant", "已提交回答")));

        boolean rebuilt = managedMemory.rebuild(agent, memoryId);

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

        assertFalse(managedMemory.rebuild(agent, "missing"));
    }
}
