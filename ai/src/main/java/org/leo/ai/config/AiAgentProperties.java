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
    private final SubAgentConfig subagent = new SubAgentConfig();

    // ── Getters ──────────────────────────────────────────────────────────────

    public PuppetNodeConfig getPuppetNode() { return puppetNode; }
    public PlatformConfig getPlatform() { return platform; }
    public InterceptorConfig getInterceptor() { return interceptor; }
    public SubAgentConfig getSubagent() { return subagent; }

    // ── PuppetNode ────────────────────────────────────────────────────────────

    public static class PuppetNodeConfig {
        private final MainAgentConfig main = new MainAgentConfig(5, 40000);
        /** 子 Agent 最大并行工具数，默认 3（低于主 Agent，避免目标侧资源竞争）。 */
        private int subMaxParallelTools = 3;
        /** 子 Agent token 滑动窗口上限，默认 16000。 */
        private int subMaxContextTokens = 16000;

        public MainAgentConfig getMain() { return main; }
        public int getSubMaxParallelTools() { return subMaxParallelTools; }
        public int getSubMaxContextTokens() { return subMaxContextTokens; }
        public void setSubMaxParallelTools(int v) { this.subMaxParallelTools = v; }
        public void setSubMaxContextTokens(int v) { this.subMaxContextTokens = v; }
    }

    // ── Platform ──────────────────────────────────────────────────────────────

    public static class PlatformConfig {
        private final MainAgentConfig main = new MainAgentConfig(5, 40000);
        /** 子 Agent 最大并行工具数，默认 3。 */
        private int subMaxParallelTools = 3;
        /** 子 Agent token 滑动窗口上限，默认 16000。 */
        private int subMaxContextTokens = 16000;

        public MainAgentConfig getMain() { return main; }
        public int getSubMaxParallelTools() { return subMaxParallelTools; }
        public int getSubMaxContextTokens() { return subMaxContextTokens; }
        public void setSubMaxParallelTools(int v) { this.subMaxParallelTools = v; }
        public void setSubMaxContextTokens(int v) { this.subMaxContextTokens = v; }
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

        /** 用户确认等待超时（分钟），超时后自动拒绝，避免 Agent 线程永久阻塞。 */
        private int confirmationTimeoutMinutes = 2;

        /**
         * 是否在线程创建/恢复时自动授予 session 级全量工具权限。
         * 开启后所有工具调用跳过用户确认，消除确认等待对并行执行的阻塞。
         * 默认 true（适用于渗透测试等高自动化场景）。
         */
        private boolean autoGrantSession = true;

        public int getMaxDuplicateCalls()           { return maxDuplicateCalls; }
        public int getConfirmationTimeoutMinutes()  { return confirmationTimeoutMinutes; }
        public boolean isAutoGrantSession()         { return autoGrantSession; }

        public void setMaxDuplicateCalls(int v)           { this.maxDuplicateCalls = v; }
        public void setConfirmationTimeoutMinutes(int v)  { this.confirmationTimeoutMinutes = v; }
        public void setAutoGrantSession(boolean v)        { this.autoGrantSession = v; }
    }

    // ── SubAgentConfig ─────────────────────────────────────────────────────

    /**
     * 子 Agent 派发开关与限制。
     *
     * <p>{@code enabled=false} 时，{@code dispatchSubAgent} 工具调用直接返回提示信息。
     */
    public static class SubAgentConfig {
        /** 是否启用 dispatchSubAgent 工具。 */
        private boolean enabled = false;
        /** 单次会话内允许派发的最大子 Agent 数量。 */
        private int maxPerSession = 8;
        /** 子 Agent 单次执行的超时（秒）。 */
        private int timeoutSeconds = 300;
        /** 子 Agent 最大推理轮次，超过后强制终止。 */
        private int maxIterations = 15;
        /**
         * 子 Agent 派发线程池上限。超出后由调用线程兜底执行（CallerRunsPolicy）。
         * 默认 64，按"预期同时活跃会话数 × maxPerSession"上下浮动。
         */
        private int dispatchMaxThreads = 64;

        public boolean isEnabled()         { return enabled; }
        public int getMaxPerSession()      { return maxPerSession; }
        public int getTimeoutSeconds()     { return timeoutSeconds; }
        public int getMaxIterations()      { return maxIterations; }
        public int getDispatchMaxThreads() { return dispatchMaxThreads; }

        public void setEnabled(boolean v)         { this.enabled = v; }
        public void setMaxPerSession(int v)       { this.maxPerSession = v; }
        public void setTimeoutSeconds(int v)      { this.timeoutSeconds = v; }
        public void setMaxIterations(int v)       { this.maxIterations = v; }
        public void setDispatchMaxThreads(int v)  { this.dispatchMaxThreads = v; }
    }
}
