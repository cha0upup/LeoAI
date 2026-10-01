package org.leo.ai.tools.agentnode;

import org.leo.ai.agent.AiToolContext;
import org.leo.ai.agent.AiToolException;
import org.leo.ai.util.AgentNodeSessionUtils;
import org.leo.core.engine.socks5.Socks5ProxyStatistics;
import org.leo.core.agent.capability.ReverseTunnelCapable;
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
 * 反向隧道：在 agent 端开监听端口，把进入的连接经 C2 转发到 forwardHost:forwardPort。
 * 类似 ssh -R remoteListenPort:forwardHost:forwardPort，与正向代理（SOCKS5/HTTP/LocalForward）方向相反，
 * 用于把 attacker / C2 侧的服务（payload server、监听器等）"反向暴露"到 agent 所在内网。
 */
@Component
@org.leo.ai.agent.AiToolPolicy(kind = org.leo.ai.agent.AiToolKind.COMMAND,
        operation = org.leo.ai.agent.AiToolOperation.WRITE)
public class ReverseTunnelTools {

    @Tool("""
            在 agent 端开端口监听，把进入的连接转发到 C2 侧（或 C2 可达的）forwardHost:forwardPort。
            用于将 attacker/C2 侧的服务暴露到 agent 所在内网（横向移动 / payload 投递 / 反向回连等场景）。
            参数：remoteListenPort 为 agent 监听端口；bindAddr 默认 127.0.0.1，仅在确需被内网其他机器访问时传 0.0.0.0；
            forwardHost/forwardPort 为 C2 侧目标。
            返回 listenId，后续 stop/statistics 通过 listenId 操作。
            """)
    public Map<String, Object> startReverseTunnel(
            @P("【必填】agent 端监听端口，1-65535") int remoteListenPort,
            @P(value = "agent 端监听绑定地址，默认 127.0.0.1，公开到内网才传 0.0.0.0",
                    required = false, defaultValue = "127.0.0.1") String bindAddr,
            @P("【必填】C2 侧（或 C2 可达的）转发目标主机") String forwardHost,
            @P("【必填】转发目标端口") int forwardPort) throws Exception {
        String sessionId = AiToolContext.requireSessionId();
        ReverseTunnelCapable agent = AgentNodeSessionUtils.requireCapability(sessionId, ReverseTunnelCapable.class);
        return agent.startReverseTunnel(remoteListenPort, bindAddr, forwardHost, forwardPort);
    }

    @org.leo.ai.agent.AiToolPolicy(kind = org.leo.ai.agent.AiToolKind.COMMAND,
            operation = org.leo.ai.agent.AiToolOperation.DESTRUCTIVE, exclusive = true)
    @Tool("停止反向隧道。停止单个时传 listenId；停止当前 Agent 全部隧道时设置 all=true。两者必须且只能选择一种。")
    public Map<String, Object> stopReverseTunnels(
            @P(value = "单个反向隧道 ID，与 all=true 二选一", required = false)
            String listenId,
            @P(value = "是否停止全部反向隧道", required = false)
            Boolean all) throws Exception {
        String sessionId = AiToolContext.requireSessionId();
        ReverseTunnelCapable agent = AgentNodeSessionUtils.requireCapability(sessionId, ReverseTunnelCapable.class);
        boolean stopAll = Boolean.TRUE.equals(all);
        boolean hasId = listenId != null && !listenId.isBlank();
        if (stopAll == hasId) {
            throw new IllegalArgumentException("listenId 与 all=true 必须且只能选择一种");
        }
        return stopAll ? agent.stopAllReverseTunnels()
                : agent.stopReverseTunnel(listenId.trim());
    }

    @Tool("查看反向隧道。listenId 为空时列出全部隧道；传入 listenId 时返回该隧道的连接和流量统计。")
    @org.leo.ai.agent.AiToolPolicy(kind = org.leo.ai.agent.AiToolKind.QUERY,
            operation = org.leo.ai.agent.AiToolOperation.READ_ONLY, parallelizable = true)
    public Map<String, Object> inspectReverseTunnels(
            @P(value = "可选反向隧道 ID", required = false) String listenId) throws Exception {
        String sessionId = AiToolContext.requireSessionId();
        ReverseTunnelCapable agent = AgentNodeSessionUtils.requireCapability(sessionId, ReverseTunnelCapable.class);
        if (listenId != null && !listenId.isBlank()) {
            return tunnelStatistics(agent, listenId.trim());
        }
        List<Map<String, Object>> rules = agent.listReverseTunnels();
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("rules", rules != null ? rules : new ArrayList<>());
        result.put("count", rules != null ? rules.size() : 0);
        return result;
    }

    private Map<String, Object> tunnelStatistics(ReverseTunnelCapable agent,
                                                 String listenId) throws Exception {
        Socks5ProxyStatistics.StatisticsSnapshot snapshot = agent.getReverseTunnelStatistics(listenId);
        if (snapshot == null) {
            throw AiToolException.modelCorrectable(
                    "RESOURCE_NOT_FOUND",
                    "反向隧道未启动或不存在: " + listenId,
                    "先调用 inspectReverseTunnels 获取当前有效 listenId。");
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
