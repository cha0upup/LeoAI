package org.leo.ai.agent;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;

/**
 * 平台侧 AI Agent 接口。
 *
 * <p>负责平台管理功能（用户、团队、Puppet、Disguise、插件、指纹）。
 */
public interface PlatformAgent {

    TokenStream chat(@MemoryId String memoryId, @UserMessage String message);
}
