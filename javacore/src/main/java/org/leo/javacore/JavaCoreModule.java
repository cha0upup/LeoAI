package org.leo.javacore;

import org.leo.core.entity.Agent;
import org.leo.core.entity.User;
import org.leo.core.net.Communication;
import org.leo.core.net.layer.HeaderNoiseStrategy;
import org.leo.core.net.layer.PaddingStrategy;
import org.leo.core.net.layer.UrlStrategy;
import org.leo.core.agent.AbstractAgentNode;
import org.leo.core.agent.impl.JavaAgentNode;
import org.leo.core.runtime.AgentNodeCreationContext;
import org.leo.core.runtime.AgentRuntime;
import org.leo.core.runtime.AgentRuntimeModule;
import org.leo.core.util.json.JsonUtil;
import org.leo.core.util.request.ComponentClassNameStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Java runtime module implementation; selected through the shared runtime SPI. */
@Component
public final class JavaCoreModule implements AgentRuntimeModule {

    private static final Logger logger = LoggerFactory.getLogger(JavaCoreModule.class);

    @Override
    public AgentRuntime getRuntime() {
        return AgentRuntime.JAVA;
    }

    @Override
    public AbstractAgentNode createNode(Agent agent,
                                         User user,
                                         AgentNodeCreationContext context) throws Exception {
        AgentNodeCreationContext.ConnectionPlan plan = context.createConnectionPlan(agent);
        Communication communication = plan.getCommunication();
        AgentNodeCreationContext.TransportLayers layers = plan.getTransportLayers();
        if (layers.getRequestLayers().isEmpty() || layers.getResponseLayers().isEmpty()) {
            throw new IllegalArgumentException("Java Agent 必须配置请求和响应伪装");
        }

        JavaAgentNode node = new JavaAgentNode();
        node.setAgent(agent);
        node.setUser(user);
        node.setRequestLayers(layers.getRequestLayers());
        node.setResponseLayers(layers.getResponseLayers());
        node.setCommunication(communication);
        node.setMaxReqCount(Agent.requireValidMaxRequestCount(agent.getMaxReqCount()));
        node.initService();
        applyRuntimeStrategies(agent, node);
        return node;
    }

    private void applyRuntimeStrategies(Agent agent, JavaAgentNode node) {
        applyUrlStrategy(agent, node);
        applyPaddingStrategy(agent, node);
        applyHeaderNoiseStrategy(agent, node);
        applyComponentClassNameStrategy(agent, node);
    }

    private void applyUrlStrategy(Agent agent, JavaAgentNode node) {
        String json = agent.getUrlStrategy();
        if (json == null || json.isBlank()) return;
        try {
            UrlStrategy strategy = JsonUtil.fromJsonString(json, UrlStrategy.class);
            if (strategy != null) node.setUrlStrategy(strategy);
        } catch (Exception e) {
            logger.warn("解析 Java URL 随机化策略失败, agentId={}: {}",
                    agent.getAgentId(), e.getMessage());
        }
    }

    private void applyPaddingStrategy(Agent agent, JavaAgentNode node) {
        String json = agent.getPaddingStrategy();
        if (json == null || json.isBlank()) return;
        try {
            PaddingStrategy strategy = JsonUtil.fromJsonString(json, PaddingStrategy.class);
            if (strategy != null) node.setPaddingStrategy(strategy);
        } catch (Exception e) {
            logger.warn("解析 Java Padding 策略失败, agentId={}: {}",
                    agent.getAgentId(), e.getMessage());
        }
    }

    private void applyHeaderNoiseStrategy(Agent agent, JavaAgentNode node) {
        String json = agent.getHeaderNoiseStrategy();
        if (json == null || json.isBlank()) return;
        try {
            HeaderNoiseStrategy strategy = JsonUtil.fromJsonString(json, HeaderNoiseStrategy.class);
            if (strategy != null) node.setHeaderNoiseStrategy(strategy);
        } catch (Exception e) {
            logger.warn("解析 Java Header 噪声策略失败, agentId={}: {}",
                    agent.getAgentId(), e.getMessage());
        }
    }

    private void applyComponentClassNameStrategy(Agent agent, JavaAgentNode node) {
        String json = agent.getComponentClassNameStrategy();
        if (json == null || json.isBlank()) return;
        try {
            ComponentClassNameStrategy strategy = JsonUtil.fromJsonString(
                    json, ComponentClassNameStrategy.class);
            if (strategy != null) node.setComponentClassNameStrategy(strategy);
        } catch (Exception e) {
            logger.warn("解析 Java Component 类名画像失败, agentId={}: {}",
                    agent.getAgentId(), e.getMessage());
        }
    }
}
