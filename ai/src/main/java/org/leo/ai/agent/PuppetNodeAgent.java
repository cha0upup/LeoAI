package org.leo.ai.agent;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;

/**
 * PuppetNode 侧 AI Agent 接口。
 *
 * <p>使用 LangChain4j AiService 机制，由 {@link AgentConfig} 构建实例。
 * memoryId 使用 sessionId + threadId 组合作为会话隔离键。
 *
 * <p>提供流式和非流式两种调用方式：
 * - chat: 流式，用于 SSE 实时推送
 * - chatSync: 非流式，返回完整响应（含 thinking）
 */
public interface PuppetNodeAgent {

    TokenStream chat(@MemoryId String memoryId, @UserMessage String message);

    String chatSync(@MemoryId String memoryId, @UserMessage String message);
}
