package org.leo.ai.agent;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.UserMessage;

/**
 * 侦察子 Agent：负责端口扫描、浏览器数据提取、凭据采集、剪贴板操作。
 *
 * <p>由主 Agent 通过 SubAgentDispatchTools 调度，非流式返回结果。
 */
public interface ReconSubAgent {

    String chat(@MemoryId String memoryId, @UserMessage String task);
}
