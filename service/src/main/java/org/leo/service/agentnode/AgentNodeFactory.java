package org.leo.service.agentnode;

import org.leo.core.entity.Disguise;
import org.leo.core.entity.Agent;
import org.leo.core.entity.User;
import org.leo.core.manager.DisguiseManager;
import org.leo.core.net.Communication;
import org.leo.core.net.impl.HttpChunkedCommunication;
import org.leo.core.net.impl.HttpCommunication;
import org.leo.core.net.impl.WebSocketCommunication;
import org.leo.core.net.layer.RequestLayer;
import org.leo.core.net.layer.ResponseLayer;
import org.leo.core.net.layer.TlsFingerprintStrategy;
import org.leo.core.agent.AbstractAgentNode;
import org.leo.core.runtime.AgentNodeCreationContext;
import org.leo.core.runtime.AgentRuntime;
import org.leo.core.runtime.AgentRuntimeModule;
import org.leo.core.util.json.JsonUtil;
import org.leo.service.AgentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Runtime factory for Agent nodes.
 *
 * <p>The transport protocol (http/websocket/httpChunked) is independent from the
 * node runtime type. Java and PHP are selected through equal-status
 * {@link AgentRuntimeModule} implementations.
 */
@Service
public class AgentNodeFactory implements AgentNodeCreationContext {

    private static final Logger logger = LoggerFactory.getLogger(AgentNodeFactory.class);
    private static final int PROXY_ENABLED = 1;
    private final AgentService agentService;
    private final AgentRouteResolver routeResolver;
    private final Map<AgentRuntime, AgentRuntimeModule> runtimeModules;

    public AgentNodeFactory(AgentService agentService,
                             List<AgentRuntimeModule> runtimeModules) {
        this.agentService = agentService;
        this.routeResolver = new AgentRouteResolver(agentService);
        this.runtimeModules = indexRuntimeModules(runtimeModules);
    }

    public AbstractAgentNode createLiveNode(Agent agent, User user) throws Exception {
        if (agent == null) {
            throw new IllegalArgumentException("Agent不能为空");
        }

        AgentRuntime runtime = AgentRuntime.from(agent.getType());
        AgentRuntimeModule module = runtimeModules.get(runtime);
        if (module == null) {
            throw new IllegalArgumentException("未安装 Agent 运行时模块: " + runtime.getValue());
        }
        if (!module.isReady()) {
            throw new IllegalArgumentException("Agent 运行时模块尚未就绪: " + runtime.getValue());
        }

        AbstractAgentNode node = module.createNode(agent, user, this);
        if (node == null) {
            throw new IllegalStateException("运行时模块返回空节点: " + runtime.getValue());
        }
        if (node.getRuntime() != runtime) {
            throw new IllegalStateException("运行时模块返回了错误类型节点: expected="
                    + runtime.getValue() + ", actual=" + node.getRuntime().getValue());
        }
        return node;
    }

    private Map<AgentRuntime, AgentRuntimeModule> indexRuntimeModules(
            List<AgentRuntimeModule> modules) {
        EnumMap<AgentRuntime, AgentRuntimeModule> indexed = new EnumMap<>(AgentRuntime.class);
        if (modules == null) {
            return Collections.unmodifiableMap(indexed);
        }
        for (AgentRuntimeModule module : modules) {
            if (module == null || module.getRuntime() == null) continue;
            AgentRuntimeModule previous = indexed.putIfAbsent(module.getRuntime(), module);
            if (previous != null) {
                throw new IllegalStateException("重复的 Agent 运行时模块: "
                        + module.getRuntime().getValue());
            }
        }
        return Collections.unmodifiableMap(indexed);
    }

    public Proxy getProxy(Agent agent) {
        if (agent == null) return Proxy.NO_PROXY;
        Integer proxyEnabled = agent.getProxyEnabled();
        if (proxyEnabled == null || proxyEnabled != PROXY_ENABLED) return Proxy.NO_PROXY;

        String proxyHost = agent.getProxyHost();
        Integer proxyPort = agent.getProxyPort();
        if (proxyHost == null || proxyPort == null) return Proxy.NO_PROXY;

        String proxyType = agent.getProxyType();
        Proxy.Type type = Proxy.Type.DIRECT;
        if ("http".equals(proxyType)) type = Proxy.Type.HTTP;
        else if ("socks".equals(proxyType)) type = Proxy.Type.SOCKS;

        return new Proxy(type, new InetSocketAddress(proxyHost, proxyPort));
    }

    public Communication getCommunication(Agent agent, Proxy proxy) throws Exception {
        String protocol = agent.getProtocol();
        String connLink = agent.getConnLink();

        if ("http".equals(protocol)) {
            Map<String, String> headers = parseStringHeaders(agent.getHeaders());
            return new HttpCommunication(connLink, "POST", headers, proxy);
        }
        if ("websocket".equals(protocol)) {
            String wsUrl = appendHeaderQuery(connLink, parseStringHeaders(agent.getHeaders()));
            WebSocketCommunication webSocket = new WebSocketCommunication(wsUrl, proxy);
            webSocket.connect();
            return webSocket;
        }
        if ("httpChunked".equals(protocol)) {
            Map<String, String> headers = parseStringHeaders(agent.getHeaders());
            return new HttpChunkedCommunication(connLink, "POST", headers, proxy);
        }
        return null;
    }

    @Override
    public Communication createCommunication(Agent agent) throws Exception {
        return createCommunication(routeResolver.resolve(agent));
    }

    private Communication createCommunication(AgentRouteResolver.Route route) throws Exception {
        Agent transportAgent = route.transport();
        Communication communication = getCommunication(transportAgent, getProxy(transportAgent));
        if (communication == null) {
            throw new IllegalArgumentException("无法创建通信连接，协议不支持: "
                    + transportAgent.getProtocol());
        }
        applyTlsFingerprintStrategy(route.requested(), communication);
        return communication;
    }

    @Override
    public TransportLayers createTransportLayers(Agent agent) throws Exception {
        return createTransportLayers(routeResolver.resolve(agent));
    }

    @Override
    public ConnectionPlan createConnectionPlan(Agent agent) throws Exception {
        AgentRouteResolver.Route route = routeResolver.resolve(agent);
        return new ConnectionPlan(createCommunication(route), createTransportLayers(route));
    }

    private TransportLayers createTransportLayers(AgentRouteResolver.Route route) {
        List<RequestLayer> requestLayers = new ArrayList<>();
        List<ResponseLayer> responseLayers = new ArrayList<>();
        List<Agent> chain = route.chain();
        for (Agent tempAgent : chain) {
            String reqDisguiseId = tempAgent.getReqDisguiseId();
            if (reqDisguiseId != null && !reqDisguiseId.trim().isEmpty()) {
                Disguise reqDisguise = DisguiseManager.getInstance().getDisguiseById(reqDisguiseId);
                if (reqDisguise == null) {
                    throw new IllegalArgumentException("请求伪装不存在: " + reqDisguiseId
                            + "（agentId=" + tempAgent.getAgentId() + "）");
                }
                RequestLayer requestLayer = new RequestLayer(
                        tempAgent.getConnLink(),
                        parseStringHeaders(tempAgent.getHeaders()),
                        reqDisguise,
                        requirePayloadKey(tempAgent));
                requestLayers.add(requestLayer);
            }
            String respDisguiseId = tempAgent.getRespDisguiseId();
            if (respDisguiseId != null && !respDisguiseId.trim().isEmpty()) {
                Disguise respDisguise = DisguiseManager.getInstance().getDisguiseById(respDisguiseId);
                if (respDisguise == null) {
                    throw new IllegalArgumentException("响应伪装不存在: " + respDisguiseId
                            + "（agentId=" + tempAgent.getAgentId() + "）");
                }
                responseLayers.add(0, new ResponseLayer(respDisguise,
                        requirePayloadKey(tempAgent)));
            }
        }
        return new TransportLayers(requestLayers, responseLayers);
    }

    private String requirePayloadKey(Agent agent) {
        String payloadKey = agent.getPayloadKey();
        if (payloadKey == null || payloadKey.trim().isEmpty()) {
            throw new IllegalArgumentException("Agent payloadKey 不能为空（agentId="
                    + agent.getAgentId() + "）");
        }
        return payloadKey.trim();
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> parseStringHeaders(String headersJson) {
        Object parsed = JsonUtil.fromJsonString(headersJson, Map.class);
        return parsed instanceof Map<?, ?> ? (Map<String, String>) parsed : new HashMap<>();
    }

    /** 将 headers 拼成 query string 追加到 URL，供 WebSocket 门禁（ws://host/path?name=value） */
    static String appendHeaderQuery(String url, Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) return url;
        StringBuilder qs = new StringBuilder();
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (qs.length() > 0) qs.append('&');
            qs.append(encodeQueryComponent(e.getKey()))
                    .append('=')
                    .append(encodeQueryComponent(e.getValue()));
        }
        return url + (url.contains("?") ? "&" : "?") + qs;
    }

    private static String encodeQueryComponent(String value) {
        if (value == null) {
            throw new IllegalArgumentException("WebSocket 门禁参数不能为 null");
        }
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private void applyTlsFingerprintStrategy(Agent agent, Communication comm) {
        String tlsJson = agent.getTlsFingerprintStrategy();
        if (tlsJson == null || tlsJson.isBlank()) {
            return;
        }
        if (!(comm instanceof HttpCommunication)) {
            return;
        }
        try {
            TlsFingerprintStrategy strategy = (TlsFingerprintStrategy) JsonUtil.fromJsonString(tlsJson, TlsFingerprintStrategy.class);
            if (strategy != null && strategy.isEnabled()) {
                ((HttpCommunication) comm).setTlsFingerprintStrategy(strategy);
            }
        } catch (Exception e) {
            logger.warn("解析 TLS 指纹策略失败, agentId={}: {}", agent.getAgentId(), e.getMessage());
        }
    }

}
