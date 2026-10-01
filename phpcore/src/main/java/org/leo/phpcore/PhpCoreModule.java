package org.leo.phpcore;

import org.leo.core.runtime.AgentRuntime;
import org.leo.core.runtime.AgentRuntimeModule;
import org.leo.core.runtime.AgentNodeCreationContext;
import org.leo.core.entity.Agent;
import org.leo.core.entity.User;
import org.leo.core.agent.AbstractAgentNode;
import org.springframework.stereotype.Component;
import org.leo.core.net.layer.HeaderNoiseStrategy;
import org.leo.core.net.layer.PaddingStrategy;
import org.leo.core.net.layer.UrlStrategy;
import org.leo.core.util.json.JsonUtil;
import org.leo.phpcore.agent.PhpAgentNode;
import org.leo.phpcore.rpc.PhpRpcClient;
import org.leo.phpcore.component.PhpComponentArtifactRegistry;

/**
 * PHP runtime integration module.
 *
 * <p>The module contains the platform-side PHP node adapter, protocol client,
 * generator, disguise validator and target-side PHP template. Keeping it separate from
 * {@code core} prevents runtime-specific source generation from leaking into
 * the shared protocol and capability contracts.
 */
@Component
public final class PhpCoreModule implements AgentRuntimeModule {

    public static final AgentRuntime RUNTIME = AgentRuntime.PHP;

    private final PhpComponentArtifactRegistry componentRegistry;

    public PhpCoreModule(PhpComponentArtifactRegistry componentRegistry) {
        this.componentRegistry = componentRegistry;
    }

    @Override
    public AgentRuntime getRuntime() {
        return RUNTIME;
    }

    @Override
    public boolean isReady() {
        return true;
    }

    @Override
    public AbstractAgentNode createNode(Agent agent,
                                         User user,
                                         AgentNodeCreationContext context) throws Exception {
        AgentNodeCreationContext.ConnectionPlan plan = context.createConnectionPlan(agent);
        AgentNodeCreationContext.TransportLayers layers = plan.getTransportLayers();
        if (layers.getRequestLayers().isEmpty() || layers.getResponseLayers().isEmpty()) {
            throw new IllegalArgumentException("PHP Agent 必须配置请求和响应伪装");
        }
        if (!layers.getRequestLayers().get(0).getDisguise().supportsRuntime("php")) {
            throw new IllegalArgumentException("请求伪装不支持 PHP: "
                    + layers.getRequestLayers().get(0).getDisguise().getDisguiseId());
        }
        if (!layers.getResponseLayers().get(layers.getResponseLayers().size() - 1)
                .getDisguise().supportsRuntime("php")) {
            throw new IllegalArgumentException("响应伪装不支持 PHP");
        }
        PhpRpcClient client = new PhpRpcClient(
                plan.getCommunication(), layers.getRequestLayers(), layers.getResponseLayers());
        client.setMaxReqCount(agent.getMaxReqCount());
        applyStrategies(agent, client);

        PhpAgentNode node = new PhpAgentNode(client, componentRegistry);
        node.setAgent(agent);
        node.setUser(user);
        return node;
    }

    private void applyStrategies(Agent agent, PhpRpcClient client) {
        try {
            if (agent.getUrlStrategy() != null && !agent.getUrlStrategy().isBlank()) {
                client.setUrlStrategy((UrlStrategy) JsonUtil.fromJsonString(
                        agent.getUrlStrategy(), UrlStrategy.class));
            }
        } catch (Exception ignored) { }
        try {
            if (agent.getPaddingStrategy() != null && !agent.getPaddingStrategy().isBlank()) {
                client.setPaddingStrategy((PaddingStrategy) JsonUtil.fromJsonString(
                        agent.getPaddingStrategy(), PaddingStrategy.class));
            }
        } catch (Exception ignored) { }
        try {
            if (agent.getHeaderNoiseStrategy() != null && !agent.getHeaderNoiseStrategy().isBlank()) {
                client.setHeaderNoiseStrategy((HeaderNoiseStrategy) JsonUtil.fromJsonString(
                        agent.getHeaderNoiseStrategy(), HeaderNoiseStrategy.class));
            }
        } catch (Exception ignored) { }
    }
}
