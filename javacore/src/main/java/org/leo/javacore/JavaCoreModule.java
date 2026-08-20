package org.leo.javacore;

import org.leo.core.entity.Puppet;
import org.leo.core.entity.User;
import org.leo.core.net.Communication;
import org.leo.core.net.layer.HeaderNoiseStrategy;
import org.leo.core.net.layer.PaddingStrategy;
import org.leo.core.net.layer.UrlStrategy;
import org.leo.core.payload.PayloadCodec;
import org.leo.core.puppet.AbstractPuppetNode;
import org.leo.core.puppet.impl.JavaPuppetNode;
import org.leo.core.runtime.PuppetNodeCreationContext;
import org.leo.core.runtime.PuppetRuntime;
import org.leo.core.runtime.PuppetRuntimeModule;
import org.leo.core.util.json.JsonUtil;
import org.leo.core.util.request.ComponentClassNameStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Java runtime module implementation; selected through the shared runtime SPI. */
@Component
public final class JavaCoreModule implements PuppetRuntimeModule {

    private static final Logger logger = LoggerFactory.getLogger(JavaCoreModule.class);

    @Override
    public PuppetRuntime getRuntime() {
        return PuppetRuntime.JAVA;
    }

    @Override
    public AbstractPuppetNode createNode(Puppet puppet,
                                         User user,
                                         PuppetNodeCreationContext context) throws Exception {
        PuppetNodeCreationContext.ConnectionPlan plan = context.createConnectionPlan(puppet);
        Communication communication = plan.getCommunication();
        PuppetNodeCreationContext.TransportLayers layers = plan.getTransportLayers();
        if (layers.getRequestLayers().isEmpty() || layers.getResponseLayers().isEmpty()) {
            throw new IllegalArgumentException("Java Puppet 必须配置请求和响应伪装");
        }

        JavaPuppetNode node = new JavaPuppetNode();
        node.setPuppet(puppet);
        node.setUser(user);
        node.setRequestLayers(layers.getRequestLayers());
        node.setResponseLayers(layers.getResponseLayers());
        node.setCommunication(communication);
        String payloadKey = puppet.getPayloadKey();
        if (payloadKey == null || payloadKey.trim().isEmpty()) {
            throw new IllegalArgumentException("Java Puppet AES 密钥不能为空");
        }
        node.setPayloadCodec(new PayloadCodec(payloadKey.trim()));
        node.setMaxReqCount(Puppet.requireValidMaxRequestCount(puppet.getMaxReqCount()));
        node.initService();
        applyRuntimeStrategies(puppet, node);
        return node;
    }

    private void applyRuntimeStrategies(Puppet puppet, JavaPuppetNode node) {
        applyUrlStrategy(puppet, node);
        applyPaddingStrategy(puppet, node);
        applyHeaderNoiseStrategy(puppet, node);
        applyComponentClassNameStrategy(puppet, node);
    }

    private void applyUrlStrategy(Puppet puppet, JavaPuppetNode node) {
        String json = puppet.getUrlStrategy();
        if (json == null || json.isBlank()) return;
        try {
            UrlStrategy strategy = (UrlStrategy) JsonUtil.fromJsonString(json, UrlStrategy.class);
            if (strategy != null) node.setUrlStrategy(strategy);
        } catch (Exception e) {
            logger.warn("解析 Java URL 随机化策略失败, puppetId={}: {}",
                    puppet.getPuppetId(), e.getMessage());
        }
    }

    private void applyPaddingStrategy(Puppet puppet, JavaPuppetNode node) {
        String json = puppet.getPaddingStrategy();
        if (json == null || json.isBlank()) return;
        try {
            PaddingStrategy strategy = (PaddingStrategy) JsonUtil.fromJsonString(json, PaddingStrategy.class);
            if (strategy != null) node.setPaddingStrategy(strategy);
        } catch (Exception e) {
            logger.warn("解析 Java Padding 策略失败, puppetId={}: {}",
                    puppet.getPuppetId(), e.getMessage());
        }
    }

    private void applyHeaderNoiseStrategy(Puppet puppet, JavaPuppetNode node) {
        String json = puppet.getHeaderNoiseStrategy();
        if (json == null || json.isBlank()) return;
        try {
            HeaderNoiseStrategy strategy = (HeaderNoiseStrategy) JsonUtil.fromJsonString(json, HeaderNoiseStrategy.class);
            if (strategy != null) node.setHeaderNoiseStrategy(strategy);
        } catch (Exception e) {
            logger.warn("解析 Java Header 噪声策略失败, puppetId={}: {}",
                    puppet.getPuppetId(), e.getMessage());
        }
    }

    private void applyComponentClassNameStrategy(Puppet puppet, JavaPuppetNode node) {
        String json = puppet.getComponentClassNameStrategy();
        if (json == null || json.isBlank()) return;
        try {
            ComponentClassNameStrategy strategy = (ComponentClassNameStrategy) JsonUtil.fromJsonString(
                    json, ComponentClassNameStrategy.class);
            if (strategy != null) node.setComponentClassNameStrategy(strategy);
        } catch (Exception e) {
            logger.warn("解析 Java Component 类名画像失败, puppetId={}: {}",
                    puppet.getPuppetId(), e.getMessage());
        }
    }
}
