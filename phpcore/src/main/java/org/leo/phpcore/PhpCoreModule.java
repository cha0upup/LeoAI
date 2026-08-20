package org.leo.phpcore;

import org.leo.core.runtime.PuppetRuntime;
import org.leo.core.runtime.PuppetRuntimeModule;
import org.leo.core.runtime.PuppetNodeCreationContext;
import org.leo.core.entity.Puppet;
import org.leo.core.entity.User;
import org.leo.core.puppet.AbstractPuppetNode;
import org.springframework.stereotype.Component;
import org.leo.core.net.layer.HeaderNoiseStrategy;
import org.leo.core.net.layer.PaddingStrategy;
import org.leo.core.net.layer.UrlStrategy;
import org.leo.core.util.json.JsonUtil;
import org.leo.phpcore.puppet.PhpPuppetNode;
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
public final class PhpCoreModule implements PuppetRuntimeModule {

    public static final PuppetRuntime RUNTIME = PuppetRuntime.PHP;

    private final PhpComponentArtifactRegistry componentRegistry;

    public PhpCoreModule(PhpComponentArtifactRegistry componentRegistry) {
        this.componentRegistry = componentRegistry;
    }

    @Override
    public PuppetRuntime getRuntime() {
        return RUNTIME;
    }

    @Override
    public boolean isReady() {
        return true;
    }

    @Override
    public AbstractPuppetNode createNode(Puppet puppet,
                                         User user,
                                         PuppetNodeCreationContext context) throws Exception {
        PuppetNodeCreationContext.ConnectionPlan plan = context.createConnectionPlan(puppet);
        PuppetNodeCreationContext.TransportLayers layers = plan.getTransportLayers();
        if (layers.getRequestLayers().isEmpty() || layers.getResponseLayers().isEmpty()) {
            throw new IllegalArgumentException("PHP Puppet 必须配置请求和响应伪装");
        }
        if (!layers.getRequestLayers().get(0).getDisguise().supportsRuntime("php")) {
            throw new IllegalArgumentException("请求伪装不支持 PHP: "
                    + layers.getRequestLayers().get(0).getDisguise().getDisguiseId());
        }
        if (!layers.getResponseLayers().get(layers.getResponseLayers().size() - 1)
                .getDisguise().supportsRuntime("php")) {
            throw new IllegalArgumentException("响应伪装不支持 PHP");
        }
        String payloadKey = puppet.getPayloadKey();
        if (payloadKey == null || payloadKey.trim().isEmpty()) {
            throw new IllegalArgumentException("PHP Puppet AES 密钥不能为空");
        }

        PhpRpcClient client = new PhpRpcClient(
                plan.getCommunication(), layers.getRequestLayers(), layers.getResponseLayers(),
                payloadKey.trim());
        client.setMaxReqCount(puppet.getMaxReqCount());
        applyStrategies(puppet, client);

        PhpPuppetNode node = new PhpPuppetNode(client, componentRegistry);
        node.setPuppet(puppet);
        node.setUser(user);
        return node;
    }

    private void applyStrategies(Puppet puppet, PhpRpcClient client) {
        try {
            if (puppet.getUrlStrategy() != null && !puppet.getUrlStrategy().isBlank()) {
                client.setUrlStrategy((UrlStrategy) JsonUtil.fromJsonString(
                        puppet.getUrlStrategy(), UrlStrategy.class));
            }
        } catch (Exception ignored) { }
        try {
            if (puppet.getPaddingStrategy() != null && !puppet.getPaddingStrategy().isBlank()) {
                client.setPaddingStrategy((PaddingStrategy) JsonUtil.fromJsonString(
                        puppet.getPaddingStrategy(), PaddingStrategy.class));
            }
        } catch (Exception ignored) { }
        try {
            if (puppet.getHeaderNoiseStrategy() != null && !puppet.getHeaderNoiseStrategy().isBlank()) {
                client.setHeaderNoiseStrategy((HeaderNoiseStrategy) JsonUtil.fromJsonString(
                        puppet.getHeaderNoiseStrategy(), HeaderNoiseStrategy.class));
            }
        } catch (Exception ignored) { }
    }
}
