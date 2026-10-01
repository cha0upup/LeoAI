package org.leo.ai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * AI Agent 运行参数配置。
 *
 * <p>所有参数均可通过 {@code application.yml} 或环境变量覆盖，无需重新编译。
 *
 * <pre>
 * leo:
 *   ai:
 *     agent:
 *       agent-node:
 *         main:
 *           max-parallel-tools: 5
 *           max-context-tokens: 180000        # 主 Agent token 滑动窗口上限
 *           tool-timeout-ms: 180000
 *           max-tool-result-chars: 12000
 *       platform:
 *         main:
 *           max-parallel-tools: 5
 *           max-context-tokens: 180000
 * </pre>
 */
@Component
@ConfigurationProperties(prefix = "leo.ai.agent")
public class AiAgentProperties {

    private final NodeAiSettings agentNode = new NodeAiSettings();
    private final PlatformAiSettings platform = new PlatformAiSettings();

    // ── Getters ──────────────────────────────────────────────────────────────

    public NodeAiSettings getAgentNode() { return agentNode; }
    public PlatformAiSettings getPlatform() { return platform; }

    // ── AgentNode ────────────────────────────────────────────────────────────

    public static class NodeAiSettings {
        private final AiExecutionSettings main = new AiExecutionSettings(5);

        public AiExecutionSettings getMain() { return main; }
    }

    // ── Platform ──────────────────────────────────────────────────────────────

    public static class PlatformAiSettings {
        private final AiExecutionSettings main = new AiExecutionSettings(5);

        public AiExecutionSettings getMain() { return main; }
    }

    // ── AiExecutionSettings ───────────────────────────────────────────────────────

    public static class AiExecutionSettings {
        private int maxParallelTools;
        /** 主 Agent token 滑动窗口上限，默认 180000（Claude 200k 窗口预留 system + tools 空间）。 */
        private int maxContextTokens = 180000;
        /** 单次工具执行硬超时。 */
        private long toolTimeoutMs = 180_000L;
        /** 发送给模型的单个工具结果字符上限，完整结果会进入短期归档。 */
        private int maxToolResultChars = 12_000;
        /** 写操作 tool-call ID 去重记录保留时间。 */
        private long toolIdempotencyTtlMs = 30 * 60 * 1000L;
        /** 大型工具结果归档保留时间。 */
        private long toolArchiveTtlMs = 30 * 60 * 1000L;

        public AiExecutionSettings(int maxParallelTools) {
            this.maxParallelTools = maxParallelTools;
        }

        public int getMaxParallelTools()       { return maxParallelTools; }
        public int getMaxContextTokens()       { return maxContextTokens; }
        public long getToolTimeoutMs()         { return toolTimeoutMs; }
        public int getMaxToolResultChars()     { return maxToolResultChars; }
        public long getToolIdempotencyTtlMs()  { return toolIdempotencyTtlMs; }
        public long getToolArchiveTtlMs()      { return toolArchiveTtlMs; }

        public void setMaxParallelTools(int value)       { this.maxParallelTools = value; }
        public void setMaxContextTokens(int value)       { this.maxContextTokens = value; }
        public void setToolTimeoutMs(long value)         { this.toolTimeoutMs = Math.max(1L, value); }
        public void setMaxToolResultChars(int value)     { this.maxToolResultChars = Math.max(512, value); }
        public void setToolIdempotencyTtlMs(long value)  { this.toolIdempotencyTtlMs = Math.max(1_000L, value); }
        public void setToolArchiveTtlMs(long value)      { this.toolArchiveTtlMs = Math.max(1_000L, value); }
    }
}
