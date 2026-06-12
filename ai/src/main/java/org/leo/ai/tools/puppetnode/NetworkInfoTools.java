package org.leo.ai.tools.puppetnode;

import org.leo.ai.agent.AiToolContext;
import org.leo.ai.util.PuppetNodeSessionUtils;
import org.leo.core.puppet.impl.JavaPuppetNode;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.P;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 网络工具（拓扑信息 + 实时连接）。
 * <p>
 * 原 NetworkInfoTools 与 NetworkConnectionTools 合并，统一暴露 4 个 @Tool 方法：
 * <ul>
 *   <li>{@link #collectAll} — 一键采集全部网络拓扑（网卡/ARP/路由/DNS/hosts），结果缓存</li>
 *   <li>{@link #resolveDns} — DNS 解析指定域名</li>
 *   <li>{@link #listNetworkConnections} — 列举当前活跃 TCP/UDP 连接，支持多维过滤</li>
 *   <li>{@link #networkConnectionSummary} — 网络连接聚合统计（按状态/进程/远端 IP）</li>
 * </ul>
 */
@Component
public class NetworkInfoTools {

    private static final String CACHE_KEY_ALL = "network-info:all";

    // ── 拓扑信息 ──────────────────────────────────────────────────────

    @Tool("一键采集 puppet 侧全部网络拓扑信息：所有网卡（IP/掩码/MAC/MTU）、ARP 表（存活主机）、"
            + "路由表（网段和网关）、DNS 配置、hosts 文件。自动适配 Linux 和 Windows。"
            + "返回 interfaces/arp/routes/dns/hosts 各子项。")
    public Map<String, Object> collectAll() throws Exception {
        String sessionId = AiToolContext.requireSessionId();
        Object cached = PuppetNodeSessionUtils.getAiContextValue(sessionId, CACHE_KEY_ALL);
        if (cached instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> cachedMap = (Map<String, Object>) cached;
            return cachedMap;
        }
        JavaPuppetNode node = PuppetNodeSessionUtils.getJavaPuppetNode(sessionId);
        Map<String, Object> results = node.collectNetworkInfo();
        if (results != null && isSuccess(results)) {
            PuppetNodeSessionUtils.putAiContextValue(sessionId, CACHE_KEY_ALL, results);
        }
        return results;
    }

    @Tool("在 puppet 侧执行 DNS 解析，将指定域名解析为 IP 地址列表（受 puppet 侧 DNS 配置影响）。"
            + "可用于探测内网域名是否存在、获取内部服务 IP（如 nacos.internal、eureka.local）。")
    public Map<String, Object> resolveDns(
            @P("要解析的域名") String hostname) throws Exception {
        String sessionId = AiToolContext.requireSessionId();
        JavaPuppetNode node = PuppetNodeSessionUtils.getJavaPuppetNode(sessionId);
        return node.resolveDns(hostname);
    }

    // ── 实时连接 ──────────────────────────────────────────────────────

    @Tool("列举 puppet 侧当前活跃的网络连接（TCP/UDP），自动适配 Windows / macOS / Linux。"
            + "每个连接返回 protocol、localAddr、localPort、remoteAddr、remotePort、state、pid、process。\n"
            + "支持过滤：state(LISTEN/ESTABLISHED/TIME_WAIT 等)、protocol(TCP/UDP)、port、pid、process(模糊匹配)、remoteIp(前缀)、listeningOnly。\n"
            + "建议先用 networkConnectionSummary 看全局统计，再按需过滤。")
    public Map<String, Object> listNetworkConnections(
            @P("状态过滤(LISTEN/ESTABLISHED/TIME_WAIT/CLOSE_WAIT 等)") String state,
            @P("协议过滤(TCP/UDP)") String protocol,
            @P("端口号过滤(匹配本地或远端)") String port,
            @P("PID 过滤") String pid,
            @P("进程名模糊匹配") String process,
            @P("远端 IP 前缀过滤") String remoteIp,
            @P("仅返回监听端口(默认 false)") boolean listeningOnly,
            @P("最大返回条数(默认 2000)") int maxEntries) throws Exception {
        String sessionId = AiToolContext.requireSessionId();
        JavaPuppetNode node = PuppetNodeSessionUtils.getJavaPuppetNode(sessionId);
        if (maxEntries <= 0) maxEntries = 2000;
        return node.listNetworkConnections(state, protocol, port, pid, process, remoteIp, listeningOnly, maxEntries);
    }

    @Tool("获取 puppet 侧网络连接的聚合统计信息。"
            + "返回: totalConnections(总连接数)、byState(按状态统计)、byProtocol(按协议统计)、"
            + "byProcess(按进程 Top 30)、byRemoteIp(按远端 IP Top 30)、listeningPorts(监听端口列表)。"
            + "适合快速了解网络全局态势，发现异常连接集中的进程或远端 IP。")
    public Map<String, Object> networkConnectionSummary() throws Exception {
        String sessionId = AiToolContext.requireSessionId();
        JavaPuppetNode node = PuppetNodeSessionUtils.getJavaPuppetNode(sessionId);
        return node.networkConnectionSummary();
    }

    // ── 私有辅助 ──────────────────────────────────────────────────────

    private boolean isSuccess(Map<String, Object> results) {
        if (results == null) return false;
        Object code = results.get("code");
        return Integer.valueOf(200).equals(code);
    }
}
