package org.leo.service.agentnode;

import org.leo.core.entity.Agent;
import org.leo.core.agent.AbstractAgentNode;
import org.leo.core.rpc.AgentRpcEnvelopeMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * Agent 连接构建与测试服务。
 *
 * <p>将原本散落在 {@code AgentNodeController} 中的连接构建逻辑下沉到服务层，
 * 供 Web 层 Controller 和 AI 工具层共用，避免重复实现。
 */
@Service
public class AgentConnectionService {

    private static final Logger logger = LoggerFactory.getLogger(AgentConnectionService.class);

    private final AgentService agentService;
    private final AgentNodeFactory agentNodeFactory;

    public AgentConnectionService(AgentService agentService,
                             AgentNodeFactory agentNodeFactory) {
        this.agentService = agentService;
        this.agentNodeFactory = agentNodeFactory;
    }

    /**
     * 仅测试连通性，不创建 Session。
     *
     * @param agentId 目标 Agent ID
     * @return 包含 {@code success}、{@code hostId}、{@code components}、{@code latencyMs} 的结果；
     *         失败时包含 {@code message}
     */
    public Map<String, Object> testConnection(String agentId) {
        Agent agent = agentService.findAgentById(agentId);
        if (agent == null) {
            return fail("Agent 不存在，agentId: " + agentId, null);
        }

        return testConnection(agent, true);
    }

    /**
     * 使用尚未保存的表单配置测试连接，适用于新增和编辑页面的配置预检。
     * 此流程不创建 Session，也不写入主机心跳。
     */
    public Map<String, Object> testConnection(Agent agent) {
        if (agent == null) return fail("Agent 配置为空", null);
        return testConnection(agent, false);
    }

    private Map<String, Object> testConnection(Agent agent, boolean updateHeartbeat) {
        AbstractAgentNode node = null;
        try {
            node = agentNodeFactory.createLiveNode(agent, null);

            long start = System.currentTimeMillis();
            Map<String, Object> result = node.testConnection();
            long latency = System.currentTimeMillis() - start;

            if (!AgentRpcEnvelopeMapper.isCurrentPingResult(result)) {
                return fail(result == null ? "无响应" :
                        result.get("msg") != null ? String.valueOf(result.get("msg"))
                                : "节点响应格式不符合当前协议", latency);
            }

            Map<String, Object> data = new HashMap<>();
            data.put("success", true);
            data.put("hostId", result.get("hostId"));
            data.put("components", result.get("components"));
            data.put("latencyMs", latency);

            // 测试连接成功，记录心跳时间
            if (updateHeartbeat && agent.getAgentId() != null) {
                agentService.updateLastConnectedAt(agent.getAgentId());
            }

            return data;

        } catch (Exception e) {
            return fail(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName(), null);
        } finally {
            // A probe never transfers ownership to a live Session. Cleanup failures must
            // not replace the ping result or hide the original connection failure.
            if (node != null) {
                try {
                    node.close();
                } catch (Exception e) {
                    logger.warn("关闭临时连接测试节点失败, agentId={}", agent.getAgentId(), e);
                }
            }
        }
    }

    private static Map<String, Object> fail(String message, Long latencyMs) {
        HashMap<String, Object> data = new HashMap<>();
        data.put("success", false);
        data.put("message", message);
        if (latencyMs != null) data.put("latencyMs", latencyMs);
        return data;
    }

}
