package org.leo.ai.tools.puppetnode;

import dev.langchain4j.agent.tool.Tool;
import org.leo.ai.agent.AiToolContext;
import org.leo.ai.agent.AiToolKind;
import org.leo.ai.agent.AiToolOperation;
import org.leo.ai.agent.AiToolPolicy;
import org.leo.ai.util.PuppetNodeSessionUtils;
import org.leo.core.puppet.capability.NetworkProbeCapable;
import org.springframework.stereotype.Component;

import java.util.Map;

/** AI tools for the unified node-side network probe plan. */
@Component
@AiToolPolicy(kind = AiToolKind.COMMAND, operation = AiToolOperation.WRITE)
public class ScanTools {

    @Tool("查询节点支持的网络探测原子阶段。")
    @AiToolPolicy(kind = AiToolKind.QUERY, operation = AiToolOperation.READ_ONLY, parallelizable = true)
    public Map<String, Object> networkProbeCapabilities() throws Exception {
        return node().networkProbeCapabilities();
    }

    @Tool("提交统一网络探测计划。plan.targets 为目标对象数组，plan.stages 可选 tcp-connect、tcp-exchange、http-head、http-request、tls-handshake，返回 taskId。")
    public Map<String, Object> startNetworkProbe(Map<String, Object> plan) throws Exception {
        if (plan == null || plan.isEmpty()) throw new IllegalArgumentException("plan 不能为空");
        return node().startNetworkProbe(plan);
    }

    @Tool("分页查询统一网络探测任务的增量 observations。cursor 为上次确认位置；服务端已持久化后可调用 ackNetworkProbe。")
    @AiToolPolicy(kind = AiToolKind.QUERY, operation = AiToolOperation.READ_ONLY, parallelizable = true)
    public Map<String, Object> queryNetworkProbe(String taskId, long cursor,
                                                 int maxItems, int maxBytes,
                                                 boolean includeEvidence) throws Exception {
        return node().queryNetworkProbe(taskId, cursor, maxItems, maxBytes, includeEvidence);
    }

    @Tool("确认统一网络探测任务已持久化到指定游标。")
    public Map<String, Object> ackNetworkProbe(String taskId, long cursor) throws Exception {
        return node().ackNetworkProbe(taskId, cursor);
    }

    @Tool("暂停统一网络探测任务。")
    public Map<String, Object> pauseNetworkProbe(String taskId) throws Exception {
        return node().pauseNetworkProbe(taskId);
    }

    @Tool("恢复已暂停的统一网络探测任务。")
    public Map<String, Object> resumeNetworkProbe(String taskId) throws Exception {
        return node().resumeNetworkProbe(taskId);
    }

    @Tool("停止统一网络探测任务并释放节点资源。")
    @AiToolPolicy(kind = AiToolKind.COMMAND, operation = AiToolOperation.WRITE, exclusive = true)
    public Map<String, Object> stopNetworkProbe(String taskId) throws Exception {
        return node().stopNetworkProbe(taskId);
    }

    private NetworkProbeCapable node() {
        String sessionId = AiToolContext.requireSessionId();
        return PuppetNodeSessionUtils.requireCapability(sessionId, NetworkProbeCapable.class);
    }
}
