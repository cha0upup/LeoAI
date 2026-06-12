package org.leo.ai.tools.puppetnode;

import org.leo.ai.agent.AiToolContext;
import org.leo.ai.util.PuppetNodeSessionUtils;
import org.leo.core.puppet.impl.JavaPuppetNode;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Map;

@Component
public class ScanTools {

    @Tool("在 puppet 侧启动端口扫描任务。scanHost 为目标 IP 或 CIDR，scanPorts 为端口数组，"
            + "scanTimeout 为单端口超时毫秒，threadsNum 为并发线程数。"
            + "返回 taskId，用 queryScanPortResult 轮询进度和开放端口。")
    public Map<String, Object> startScanPort(String scanHost, int[] scanPorts, int scanTimeout, int threadsNum) throws Exception {
        String sessionId = AiToolContext.requireSessionId();
        JavaPuppetNode javaPuppetNode = PuppetNodeSessionUtils.getJavaPuppetNode(sessionId);
        return javaPuppetNode.startScanPort(scanHost, scanPorts, scanTimeout, threadsNum);
    }

    @Tool("查询端口扫描任务的当前进度、已发现的开放端口列表及完成状态。任务完成后调用 stopScanPort 释放资源。")
    public Map<String, Object> queryScanPortResult(String taskId) throws Exception {
        String sessionId = AiToolContext.requireSessionId();
        JavaPuppetNode javaPuppetNode = PuppetNodeSessionUtils.getJavaPuppetNode(sessionId);
        return javaPuppetNode.queryScanPortResult(taskId);
    }

    @Tool("暂停端口扫描任务（临时中止，不终止）。")
    public Map<String, Object> pauseScanPort(String taskId) throws Exception {
        String sessionId = AiToolContext.requireSessionId();
        JavaPuppetNode javaPuppetNode = PuppetNodeSessionUtils.getJavaPuppetNode(sessionId);
        return javaPuppetNode.pauseScanPort(taskId);
    }

    @Tool("恢复已暂停的端口扫描任务。")
    public Map<String, Object> resumeScanPort(String taskId) throws Exception {
        String sessionId = AiToolContext.requireSessionId();
        JavaPuppetNode javaPuppetNode = PuppetNodeSessionUtils.getJavaPuppetNode(sessionId);
        return javaPuppetNode.resumeScanPort(taskId);
    }

    @Tool("停止端口扫描任务（终止扫描释放资源）。")
    public Map<String, Object> stopScanPort(String taskId) throws Exception {
        String sessionId = AiToolContext.requireSessionId();
        JavaPuppetNode javaPuppetNode = PuppetNodeSessionUtils.getJavaPuppetNode(sessionId);
        return javaPuppetNode.stopScanPort(taskId);
    }

    @Tool("批量检测主机存活情况。scanHostsList 为 IP 列表，scanTimeout 为每个主机的超时毫秒。返回各主机是否可达。")
    public Map<String, Object> scanReachableHost(ArrayList<String> scanHostsList, int scanTimeout) throws Exception {
        String sessionId = AiToolContext.requireSessionId();
        if (scanHostsList == null || scanHostsList.isEmpty()) {
            throw new IllegalArgumentException("scanHostsList 不能为空");
        }
        JavaPuppetNode javaPuppetNode = PuppetNodeSessionUtils.getJavaPuppetNode(sessionId);
        return javaPuppetNode.scanReachableHost(scanHostsList, scanTimeout);
    }

}
