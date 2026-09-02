package org.leo.ai.tools.puppetnode;

import org.leo.ai.agent.AiToolContext;
import org.leo.ai.agent.AiToolException;
import org.leo.ai.util.PuppetNodeSessionUtils;
import org.leo.core.puppet.capability.ScanCapable;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Map;

@Component
@org.leo.ai.agent.AiToolPolicy(kind = org.leo.ai.agent.AiToolKind.COMMAND,
        operation = org.leo.ai.agent.AiToolOperation.WRITE)
public class ScanTools {

    @Tool("在 puppet 侧启动端口扫描任务。scanHost 为目标 IP 或域名，scanPorts 为端口数组，"
            + "scanTimeout 为单端口超时毫秒，threadsNum 为并发线程数。"
            + "返回 taskId，用 queryScanPortResult 轮询进度、开放端口和轻量服务识别结果。")
    public Map<String, Object> startScanPort(String scanHost, int[] scanPorts, int scanTimeout, int threadsNum) throws Exception {
        String sessionId = AiToolContext.requireSessionId();
        ScanCapable scanNode = PuppetNodeSessionUtils.requireCapability(sessionId, ScanCapable.class);
        return scanNode.startScanPort(scanHost, scanPorts, scanTimeout, threadsNum);
    }

    @Tool("查询端口扫描任务的当前进度、已发现的开放端口列表及完成状态。任务完成后调用 stopScanPort 释放资源。")
    @org.leo.ai.agent.AiToolPolicy(kind = org.leo.ai.agent.AiToolKind.QUERY,
            operation = org.leo.ai.agent.AiToolOperation.READ_ONLY, parallelizable = true)
    public Map<String, Object> queryScanPortResult(String taskId) throws Exception {
        String sessionId = AiToolContext.requireSessionId();
        ScanCapable scanNode = PuppetNodeSessionUtils.requireCapability(sessionId, ScanCapable.class);
        return scanNode.queryScanPortResult(taskId);
    }

    @Tool("暂停端口扫描任务（临时中止，不终止）。")
    public Map<String, Object> pauseScanPort(String taskId) throws Exception {
        String sessionId = AiToolContext.requireSessionId();
        ScanCapable scanNode = PuppetNodeSessionUtils.requireCapability(sessionId, ScanCapable.class);
        return scanNode.pauseScanPort(taskId);
    }

    @Tool("恢复已暂停的端口扫描任务。")
    public Map<String, Object> resumeScanPort(String taskId) throws Exception {
        String sessionId = AiToolContext.requireSessionId();
        ScanCapable scanNode = PuppetNodeSessionUtils.requireCapability(sessionId, ScanCapable.class);
        return scanNode.resumeScanPort(taskId);
    }

    @org.leo.ai.agent.AiToolPolicy(kind = org.leo.ai.agent.AiToolKind.COMMAND,
            operation = org.leo.ai.agent.AiToolOperation.WRITE, exclusive = true)
    @Tool("停止端口扫描任务（终止扫描释放资源）。")
    public Map<String, Object> stopScanPort(String taskId) throws Exception {
        String sessionId = AiToolContext.requireSessionId();
        ScanCapable scanNode = PuppetNodeSessionUtils.requireCapability(sessionId, ScanCapable.class);
        return scanNode.stopScanPort(taskId);
    }

    @Tool("批量检测主机存活情况。scanHostsList 为 IP 列表，scanTimeout 为每个主机的超时毫秒。返回各主机是否可达。")
    public Map<String, Object> scanReachableHost(ArrayList<String> scanHostsList, int scanTimeout) throws Exception {
        String sessionId = AiToolContext.requireSessionId();
        if (scanHostsList == null || scanHostsList.isEmpty()) {
            throw AiToolException.modelCorrectable(
                    "MISSING_REQUIRED_ARGUMENT",
                    "scanHostsList 不能为空。",
                    "提供至少一个合法 IP 地址或主机名后重新调用。");
        }
        ScanCapable scanNode = PuppetNodeSessionUtils.requireCapability(sessionId, ScanCapable.class);
        return scanNode.scanReachableHost(scanHostsList, scanTimeout);
    }

}
