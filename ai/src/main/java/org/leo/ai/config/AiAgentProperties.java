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
 *       puppet-node:
 *         main:
 *           max-parallel-tools: 5
 *           max-tokens-before-summary: 40000
 *           max-context-tokens: 180000        # 主 Agent token 滑动窗口上限
 *         sub-max-parallel-tools: 3            # 子 Agent 并行工具数上限
 *         sub-max-context-tokens: 16000        # 子 Agent token 滑动窗口上限
 *       platform:
 *         main:
 *           max-parallel-tools: 5
 *           max-tokens-before-summary: 40000
 *           max-context-tokens: 180000
 *         sub-max-parallel-tools: 3
 *         sub-max-context-tokens: 16000
 *       interceptor:
 *         max-duplicate-calls: 3               # 工具去重阈值
 *         confirmation-timeout-minutes: 2
 * </pre>
 */
@Component
@ConfigurationProperties(prefix = "leo.ai.agent")
public class AiAgentProperties {

    private final PuppetNodeConfig puppetNode = new PuppetNodeConfig();
    private final PlatformConfig platform = new PlatformConfig();
    private final InterceptorConfig interceptor = new InterceptorConfig();

    // ── Getters ──────────────────────────────────────────────────────────────

    public PuppetNodeConfig getPuppetNode() { return puppetNode; }
    public PlatformConfig getPlatform() { return platform; }
    public InterceptorConfig getInterceptor() { return interceptor; }

    // ── PuppetNode ────────────────────────────────────────────────────────────

    public static class PuppetNodeConfig {
        private final MainAgentConfig main = new MainAgentConfig(5, 40000);

        public MainAgentConfig getMain() { return main; }
    }

    // ── Platform ──────────────────────────────────────────────────────────────

    public static class PlatformConfig {
        private final MainAgentConfig main = new MainAgentConfig(5, 40000);

        public MainAgentConfig getMain() { return main; }
    }

    // ── MainAgentConfig ───────────────────────────────────────────────────────

    public static class MainAgentConfig {
        private int maxParallelTools;
        private int maxTokensBeforeSummary;
        /** 主 Agent token 滑动窗口上限，默认 180000（Claude 200k 窗口预留 system + tools 空间）。 */
        private int maxContextTokens = 180000;

        public MainAgentConfig(int maxParallelTools, int maxTokensBeforeSummary) {
            this.maxParallelTools = maxParallelTools;
            this.maxTokensBeforeSummary = maxTokensBeforeSummary;
        }

        public int getMaxParallelTools()       { return maxParallelTools; }
        public int getMaxTokensBeforeSummary() { return maxTokensBeforeSummary; }
        public int getMaxContextTokens()       { return maxContextTokens; }

        public void setMaxParallelTools(int v)       { this.maxParallelTools = v; }
        public void setMaxTokensBeforeSummary(int v) { this.maxTokensBeforeSummary = v; }
        public void setMaxContextTokens(int v)       { this.maxContextTokens = v; }
    }

    // ── InterceptorConfig ─────────────────────────────────────────────────────

    public static class InterceptorConfig {
        /** 同一工具+同一参数的最大允许调用次数，超过后视为无效重复并拦截。 */
        private int maxDuplicateCalls = 3;

        public int getMaxDuplicateCalls()           { return maxDuplicateCalls; }
        public void setMaxDuplicateCalls(int v)     { this.maxDuplicateCalls = v; }
    }
}
