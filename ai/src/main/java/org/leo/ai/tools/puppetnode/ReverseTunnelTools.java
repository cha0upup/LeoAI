package org.leo.ai.tools.puppetnode;

import org.leo.ai.agent.AiToolContext;
import org.leo.ai.util.PuppetNodeSessionUtils;
import org.leo.core.engine.socks5.Socks5ProxyStatistics;
import org.leo.core.puppet.impl.JavaPuppetNode;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.P;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 反向隧道工具（AI 可直接调用）。
 *
 * 反向隧道：在 puppet 端开监听端口，把进入的连接经 C2 转发到 forwardHost:forwardPort。
 * 类似 ssh -R remoteListenPort:forwardHost:forwardPort，与正向代理（SOCKS5/HTTP/LocalForward）方向相反，
 * 用于把 attacker / C2 侧的服务（payload server、监听器等）"反向暴露"到 puppet 所在内网。
 */
@Component
public class ReverseTunnelTools {

    @Tool("""
            在 puppet 端开端口监听，把进入的连接转发到 C2 侧（或 C2 可达的）forwardHost:forwardPort。
            用于将 attacker/C2 侧的服务暴露到 puppet 所在内网（横向移动 / payload 投递 / 反向回连等场景）。
            参数：remoteListenPort 为 puppet 监听端口；bindAddr 默认 127.0.0.1，仅在确需被内网其他机器访问时传 0.0.0.0；
            forwardHost/forwardPort 为 C2 侧目标。
            返回 listenId，后续 stop/statistics 通过 listenId 操作。
            """)
    public Map<String, Object> startReverseTunnel(
            @P("【必填】puppet 端监听端口，1-65535") int remoteListenPort,
            @P("puppet 端监听绑定地址，默认 127.0.0.1，公开到内网才传 0.0.0.0") String bindAddr,
            @P("【必填】C2 侧（或 C2 可达的）转发目标主机") String forwardHost,
            @P("【必填】转发目标端口") int forwardPort) throws Exception {
        String sessionId = AiToolContext.requireSessionId();
        JavaPuppetNode puppet = PuppetNodeSessionUtils.getJavaPuppetNode(sessionId);
        return puppet.startReverseTunnel(remoteListenPort, bindAddr, forwardHost, forwardPort);
    }

    @Tool("停止指定反向隧道。listenId 由 startReverseTunnel 返回。")
    public Map<String, Object> stopReverseTunnel(
            @P("【必填】反向隧道 ID（startReverseTunnel 返回值）") String listenId) throws Exception {
        String sessionId = AiToolContext.requireSessionId();
        JavaPuppetNode puppet = PuppetNodeSessionUtils.getJavaPuppetNode(sessionId);
        return puppet.stopReverseTunnel(listenId);
    }

    @Tool("停止当前 puppet 上所有反向隧道。")
    public Map<String, Object> stopAllReverseTunnels() throws Exception {
        String sessionId = AiToolContext.requireSessionId();
        JavaPuppetNode puppet = PuppetNodeSessionUtils.getJavaPuppetNode(sessionId);
        return puppet.stopAllReverseTunnels();
    }

    @Tool("列出当前 puppet 上所有反向隧道：listenId、puppet 监听端口/绑定地址、C2 侧目标、运行状态。")
    public Map<String, Object> listReverseTunnels() throws Exception {
        String sessionId = AiToolContext.requireSessionId();
        JavaPuppetNode puppet = PuppetNodeSessionUtils.getJavaPuppetNode(sessionId);
        List<Map<String, Object>> rules = puppet.listReverseTunnels();
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("rules", rules != null ? rules : new ArrayList<>());
        result.put("count", rules != null ? rules.size() : 0);
        return result;
    }

    @Tool("获取指定反向隧道的运行统计：活跃/累计连接数、上下行流量、连接列表。")
    public Map<String, Object> getReverseTunnelStatistics(
            @P("【必填】反向隧道 ID") String listenId) throws Exception {
        String sessionId = AiToolContext.requireSessionId();
        JavaPuppetNode puppet = PuppetNodeSessionUtils.getJavaPuppetNode(sessionId);
        Socks5ProxyStatistics.StatisticsSnapshot snapshot = puppet.getReverseTunnelStatistics(listenId);
        if (snapshot == null) {
            throw new IllegalStateException("反向隧道未启动或不存在: " + listenId);
        }
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("port", snapshot.port);
        result.put("activeConnections", snapshot.activeConnections);
        result.put("totalConnections", snapshot.totalConnections);
        result.put("uploadBytes", snapshot.uploadBytes);
        result.put("downloadBytes", snapshot.downloadBytes);
        result.put("uploadRate", snapshot.uploadRate);
        result.put("downloadRate", snapshot.downloadRate);
        result.put("uptime", snapshot.uptime);
        List<Map<String, Object>> conns = new ArrayList<>();
        if (snapshot.connections != null) {
            for (Socks5ProxyStatistics.ConnectionInfo c : snapshot.connections) {
                Map<String, Object> m = new HashMap<>();
                m.put("connId", c.connId);
                m.put("targetHost", c.targetHost);
                m.put("targetPort", c.targetPort);
                m.put("clientIp", c.clientIp);
                m.put("uptime", c.getUptime());
                m.put("uploadBytes", c.uploadBytes);
                m.put("downloadBytes", c.downloadBytes);
                conns.add(m);
            }
        }
        result.put("connections", conns);
        return result;
    }
}
