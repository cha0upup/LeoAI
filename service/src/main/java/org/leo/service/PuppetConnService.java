package org.leo.service;

import org.leo.core.entity.Disguise;
import org.leo.core.manager.DisguiseManager;
import org.leo.core.net.Communication;
import org.leo.core.net.impl.HttpChunkedCommunication;
import org.leo.core.net.impl.HttpCommunication;
import org.leo.core.net.impl.WebSocketCommunication;
import org.leo.core.net.layer.RequestLayer;
import org.leo.core.net.layer.ResponseLayer;
import org.leo.core.net.layer.UrlStrategy;
import org.leo.core.net.layer.PaddingStrategy;
import org.leo.core.net.layer.HeaderNoiseStrategy;
import org.leo.core.net.layer.TlsFingerprintStrategy;
import org.leo.core.entity.Puppet;
import org.leo.core.puppet.impl.JavaPuppetNode;
import org.leo.core.util.json.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Puppet 连接构建与测试服务。
 *
 * <p>将原本散落在 {@code PuppetNodeController} 中的连接构建逻辑下沉到服务层，
 * 供 Web 层 Controller 和 AI 工具层共用，避免重复实现。
 */
@Service
public class PuppetConnService {

    private static final Logger logger = LoggerFactory.getLogger(PuppetConnService.class);
    private static final int MAX_PARENT_DEPTH = 100;
    private static final int PROXY_ENABLED    = 1;

    private final PuppetService puppetService;

    public PuppetConnService(PuppetService puppetService) {
        this.puppetService = puppetService;
    }

    // ── 公开 API ─────────────────────────────────────────────────────────────────

    /**
     * 仅测试连通性，不创建 Session。
     *
     * @param puppetId 目标 Puppet ID
     * @return 包含 {@code success}、{@code hostId}、{@code components}、{@code latencyMs} 的结果；
     *         失败时包含 {@code message}
     */
    public Map<String, Object> testConnection(String puppetId) {
        Puppet puppet = puppetService.findPuppetById(puppetId);
        if (puppet == null) {
            return fail("Puppet 不存在，puppetId: " + puppetId, null);
        }

        try {
            Puppet transportPuppet = resolveTransportPuppet(puppet);
            Proxy proxy            = getProxy(transportPuppet);
            Communication comm     = getCommunication(transportPuppet, proxy);

            if (comm == null) {
                return fail("无法创建通信连接，协议不支持: " + transportPuppet.getProtocol(), null);
            }

            // 应用 TLS 指纹伪装策略（需在 initService 前）
            applyTlsFingerprintStrategy(puppet, comm);

            Map<String, Object> result;
            long start;
            long latency;

            JavaPuppetNode javaPuppetNode = new JavaPuppetNode();
            javaPuppetNode.setPuppet(puppet);
            buildRequestAndResponseChain(puppet, javaPuppetNode);
            javaPuppetNode.setCommunication(comm);
            if (puppet.getMaxReqCount() != null && puppet.getMaxReqCount() > 0) {
                javaPuppetNode.setMaxReqCount(puppet.getMaxReqCount());
            }
            javaPuppetNode.initService();
            applyUrlStrategy(puppet, javaPuppetNode);
            applyPaddingStrategy(puppet, javaPuppetNode);
            applyHeaderNoiseStrategy(puppet, javaPuppetNode);

            start   = System.currentTimeMillis();
            result  = javaPuppetNode.testConnection();
            latency = System.currentTimeMillis() - start;

            if (result == null || !Integer.valueOf(200).equals(result.get("code"))) {
                Map<String, Object> data = new HashMap<>();
                data.put("success",   false);
                data.put("latencyMs", latency);
                data.put("message",   result != null ? String.valueOf(result.get("msg")) : "无响应");
                return data;
            }

            Map<String, Object> data = new HashMap<>();
            data.put("success",    true);
            data.put("hostId",     result.get("hostId"));
            data.put("components", result.get("components"));
            data.put("latencyMs",  latency);

            // 测试连接成功，记录心跳时间
            puppetService.updateLastHeartbeat(puppetId);

            return data;

        } catch (Exception e) {
            return fail(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName(), null);
        }
    }

    /**
     * 构建 RequestLayer / ResponseLayer 链（供 init 流程使用）。
     */
    public void buildRequestAndResponseChain(Puppet puppet, JavaPuppetNode javaPuppetNode) throws Exception {
        List<RequestLayer> requestLayers  = new ArrayList<>();
        List<ResponseLayer> responseLayers = new ArrayList<>();

        Puppet tempPuppet = puppet;
        int depth = 0;

        while (tempPuppet != null && depth < MAX_PARENT_DEPTH) {
            depth++;
            String reqDisguiseId = tempPuppet.getReqDisguiseId();
            if (reqDisguiseId != null) {
                Disguise reqDisguise = DisguiseManager.getInstance().getDisguiseById(reqDisguiseId);
                if (reqDisguise != null) {
                    RequestLayer requestLayer = new RequestLayer(
                            tempPuppet.getConnLink(),
                            parseStringHeaders(tempPuppet.getHeaders()),
                            reqDisguise);
                    requestLayers.add(0, requestLayer);
                }
            }
            String respDisguiseId = tempPuppet.getRespDisguiseId();
            if (respDisguiseId != null) {
                Disguise respDisguise = DisguiseManager.getInstance().getDisguiseById(respDisguiseId);
                if (respDisguise != null) {
                    responseLayers.add(new ResponseLayer(respDisguise));
                }
            }
            String parentId = tempPuppet.getParentPuppetId();
            if (parentId == null || "root".equals(parentId)) {
                break;
            }
            tempPuppet = puppetService.findPuppetById(parentId);
        }
        Collections.reverse(requestLayers);
        Collections.reverse(responseLayers);

        javaPuppetNode.setRequestLayers(requestLayers);
        javaPuppetNode.setResponseLayers(responseLayers);
    }

    /**
     * 解析实际建立底层连接的最外层 Puppet。
     */
    public Puppet resolveTransportPuppet(Puppet puppet) {
        Puppet current = puppet;
        int depth = 0;
        while (current != null && depth < MAX_PARENT_DEPTH) {
            String parentId = current.getParentPuppetId();
            if (parentId == null || parentId.isBlank() || "root".equals(parentId)) {
                return current;
            }
            current = puppetService.findPuppetById(parentId);
            depth++;
        }
        return puppet;
    }

    /**
     * 获取代理配置。
     */
    public Proxy getProxy(Puppet puppet) {
        if (puppet == null) return Proxy.NO_PROXY;
        Integer proxyEnabled = puppet.getProxyEnabled();
        if (proxyEnabled == null || proxyEnabled != PROXY_ENABLED) return Proxy.NO_PROXY;

        String  proxyHost = puppet.getProxyHost();
        Integer proxyPort = puppet.getProxyPort();
        if (proxyHost == null || proxyPort == null) return Proxy.NO_PROXY;

        String proxyType = puppet.getProxyType();
        Proxy.Type type = Proxy.Type.DIRECT;
        if ("http".equals(proxyType))   type = Proxy.Type.HTTP;
        else if ("socks".equals(proxyType)) type = Proxy.Type.SOCKS;

        return new Proxy(type, new InetSocketAddress(proxyHost, proxyPort));
    }

    /**
     * 根据协议类型创建对应的 Communication 实例。
     */
    public Communication getCommunication(Puppet puppet, Proxy proxy) throws Exception {
        String protocol = puppet.getProtocol();
        String connLink = puppet.getConnLink();

        if ("http".equals(protocol)) {
            Map<String, String> headers = parseStringHeaders(puppet.getHeaders());
            return new HttpCommunication(connLink, "POST", headers, proxy);
        }
        if ("websocket".equals(protocol)) {
            WebSocketCommunication webSocket = new WebSocketCommunication(connLink, proxy);
            webSocket.connect();
            return webSocket;
        }
        if ("httpChunked".equals(protocol)) {
            Map<String, String> headers = parseStringHeaders(puppet.getHeaders());
            return new HttpChunkedCommunication(connLink, "POST", headers, proxy);
        }
        return null;
    }

    // ── 私有工具 ─────────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private Map<String, String> parseStringHeaders(String headersJson) {
        Object parsed = JsonUtil.fromJsonString(headersJson, Map.class);
        return parsed instanceof Map<?, ?> ? (Map<String, String>) parsed : new HashMap<>();
    }

    private static Map<String, Object> fail(String message, Long latencyMs) {
        HashMap<String, Object> data = new HashMap<>();
        data.put("success", false);
        data.put("message", message);
        if (latencyMs != null) data.put("latencyMs", latencyMs);
        return data;
    }

    /**
     * 从 Puppet 配置中解析并应用 URL 随机化策略到 JavaPuppetNode
     */
    private void applyUrlStrategy(Puppet puppet, JavaPuppetNode javaPuppetNode) {
        String urlStrategyJson = puppet.getUrlStrategy();
        if (urlStrategyJson == null || urlStrategyJson.isBlank()) {
            return;
        }
        try {
            UrlStrategy strategy = (UrlStrategy) JsonUtil.fromJsonString(urlStrategyJson, UrlStrategy.class);
            if (strategy != null) {
                javaPuppetNode.setUrlStrategy(strategy);
            }
        } catch (Exception e) {
            logger.warn("解析 URL 随机化策略失败, puppetId={}: {}", puppet.getPuppetId(), e.getMessage());
        }
    }

    /**
     * 从 Puppet 配置中解析并应用请求体 Padding 策略到 JavaPuppetNode
     */
    private void applyPaddingStrategy(Puppet puppet, JavaPuppetNode javaPuppetNode) {
        String paddingJson = puppet.getPaddingStrategy();
        if (paddingJson == null || paddingJson.isBlank()) {
            return;
        }
        try {
            PaddingStrategy strategy = (PaddingStrategy) JsonUtil.fromJsonString(paddingJson, PaddingStrategy.class);
            if (strategy != null) {
                javaPuppetNode.setPaddingStrategy(strategy);
            }
        } catch (Exception e) {
            logger.warn("解析 Padding 策略失败, puppetId={}: {}", puppet.getPuppetId(), e.getMessage());
        }
    }

    /**
     * 从 Puppet 配置中解析并应用 Header 噪声注入策略到 JavaPuppetNode
     */
    private void applyHeaderNoiseStrategy(Puppet puppet, JavaPuppetNode javaPuppetNode) {
        String noiseJson = puppet.getHeaderNoiseStrategy();
        if (noiseJson == null || noiseJson.isBlank()) {
            return;
        }
        try {
            HeaderNoiseStrategy strategy = (HeaderNoiseStrategy) JsonUtil.fromJsonString(noiseJson, HeaderNoiseStrategy.class);
            if (strategy != null) {
                javaPuppetNode.setHeaderNoiseStrategy(strategy);
            }
        } catch (Exception e) {
            logger.warn("解析 Header 噪声策略失败, puppetId={}: {}", puppet.getPuppetId(), e.getMessage());
        }
    }

    /**
     * 从 Puppet 配置中解析并应用 TLS 指纹伪装策略到 Communication。
     * 必须在 initService 之前调用，因为会重建 OkHttpClient。
     */
    private void applyTlsFingerprintStrategy(Puppet puppet, Communication comm) {
        String tlsJson = puppet.getTlsFingerprintStrategy();
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
            logger.warn("解析 TLS 指纹策略失败, puppetId={}: {}", puppet.getPuppetId(), e.getMessage());
        }
    }

}
