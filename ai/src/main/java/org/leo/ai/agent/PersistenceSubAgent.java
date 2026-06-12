package org.leo.ai.agent;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.UserMessage;

/**
 * 持久化/服务管理子 Agent：负责计划任务、服务管理、事件日志、Tomcat 内存马、Java 插件。
 *
 * <p>由主 Agent 通过 SubAgentDispatchTools 调度，非流式返回结果。
 */
public interface PersistenceSubAgent {

    String chat(@MemoryId String memoryId, @UserMessage String task);
}
