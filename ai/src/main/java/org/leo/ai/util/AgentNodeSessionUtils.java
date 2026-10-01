package org.leo.ai.util;

import org.leo.ai.agent.AiToolException;
import org.leo.core.entity.User;
import org.leo.core.agent.AbstractAgentNode;
import org.leo.core.agent.capability.AgentNodeCapabilityRegistry;
import org.leo.core.runtime.CapabilityStatus;
import org.leo.core.session.AgentNodeSession;
import org.leo.core.session.AgentNodeSessionContainer;

public final class AgentNodeSessionUtils {

    private AgentNodeSessionUtils() {}

    public static AgentNodeSession getSession(String sessionId) {
        AgentNodeSession session = AgentNodeSessionContainer.getSession(sessionId);
        if (session == null) {
            throw AiToolException.userActionRequired(
                    "SESSION_EXPIRED",
                    "当前 Agent 会话不存在或已过期。",
                    "不要重复调用；请向用户说明需要重新连接 Agent。");
        }
        return session;
    }

    public static AbstractAgentNode getAgentNode(String sessionId) {
        return getSession(sessionId).getAgentNode();
    }

    public static String requireAgentId(String sessionId) {
        String agentId = getSession(sessionId).resolveAgentId();
        if (agentId == null || agentId.isBlank()) {
            throw AiToolException.userActionRequired(
                    "AGENT_ID_MISSING",
                    "当前会话未绑定可持久化的 Agent。",
                    "请重新连接或选择已保存的 Agent 后再调用数据库配置工具。");
        }
        return agentId;
    }

    public static String requireUserId(String sessionId) {
        AgentNodeSession session = getSession(sessionId);
        AbstractAgentNode node = session.getAgentNode();
        User user = node != null ? node.getUser() : null;
        String userId = user != null ? user.getUserId() : session.getCreateByUser();
        if (userId == null || userId.isBlank()) {
            throw AiToolException.userActionRequired(
                    "USER_CONTEXT_MISSING",
                    "当前 AI 会话缺少用户身份上下文。",
                    "请重新进入节点会话后再调用数据库配置工具。");
        }
        return userId;
    }

    public static <T> T requireCapability(String sessionId, Class<T> capabilityType) {
        if (capabilityType == null) {
            throw AiToolException.fatal(
                    "CAPABILITY_TYPE_MISSING",
                    "工具没有声明所需的 Agent 能力类型。",
                    null);
        }
        AbstractAgentNode node = getAgentNode(sessionId);
        if (node == null) {
            throw AiToolException.userActionRequired(
                    "AGENT_UNAVAILABLE",
                    "当前会话没有可用的 Agent 实体。",
                    "不要重复调用；请向用户说明需要重新连接或选择 Agent。");
        }
        if (AgentNodeCapabilityRegistry.supports(node, capabilityType)) {
            return capabilityType.cast(node);
        }
        String nodeType = node.getAgent() != null ? node.getAgent().getType() : node.getClass().getSimpleName();
        String capabilityName = AgentNodeCapabilityRegistry.capabilityName(capabilityType);
        CapabilityStatus status = capabilityName != null
                ? AgentNodeCapabilityRegistry.getStatus(node, capabilityName) : null;
        String reason = status != null && status.getReason() != null && !status.getReason().isBlank()
                ? ", reason=" + status.getReason() : "";
        throw AiToolException.modelCorrectable(
                "UNSUPPORTED_CAPABILITY",
                "当前 Agent 类型不支持能力: "
                        + capabilityType.getSimpleName()
                        + " (type=" + nodeType + reason + ")",
                "查询当前 Agent capabilities，选择已支持的工具或向用户说明能力限制。");
    }

    public static Object getAiContextValue(String sessionId, String key) {
        return getSession(sessionId).getAiContextValue(key);
    }

    public static void putAiContextValue(String sessionId, String key, Object value) {
        getSession(sessionId).putAiContextValue(key, value);
    }

}
