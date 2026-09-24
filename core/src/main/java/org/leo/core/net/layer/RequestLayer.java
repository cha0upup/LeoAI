package org.leo.core.net.layer;

import org.leo.core.entity.Disguise;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public class RequestLayer {
    private String url;
    private Map<String, String> headers;
    private Disguise disguise;
    private final String payloadKey;

    public RequestLayer(String url, Map<String, String> headers, Disguise disguise,
                        String payloadKey) {
        this.url = url;
        this.headers = headers;
        this.disguise = disguise;
        this.payloadKey = normalizePayloadKey(payloadKey);
    }

    public String getUrl() {
        return url;
    }

    public Map<String, String> getHeaders() {
        return headers;
    }

    public Disguise getDisguise() {
        return disguise;
    }

    /** Explicit PayloadCodec key for this hop. */
    public String getPayloadKey() {
        return payloadKey;
    }

    /** Wraps already encoded payload bytes with the traffic-only disguise. */
    public byte[] encodeTraffic(byte[] payload) throws Exception {
        if (disguise == null) throw new IllegalStateException("请求伪装不能为空");
        if (!disguise.isTrafficOnly()) {
            throw new IllegalStateException("请求伪装必须配置 traffic 编解码");
        }
        return disguise.encodeTraffic(payload);
    }

    /**
     * 返回当前层最终生效的请求头。伪装默认值先写入，节点自定义值按大小写不敏感覆盖。
     */
    public Map<String, String> getMergedHeaders() {
        Map<String, String> merged = new LinkedHashMap<>();
        Map<String, String> namesByLowerCase = new LinkedHashMap<>();
        if (disguise != null) {
            mergeHeaders(merged, namesByLowerCase, disguise.getHeaders());
        }
        mergeHeaders(merged, namesByLowerCase, headers);
        return merged;
    }

    /**
     * 中转请求不会把下游 Content-Encoding 带回平台，因此固定请求 identity 响应；同时为
     * 二进制伪装体补齐通用 Content-Type，避免容器按表单请求提前消费或改写请求体。
     */
    public Map<String, String> getRelayHeaders() {
        Map<String, String> relayHeaders = new LinkedHashMap<>(getMergedHeaders());
        removeIgnoreCase(relayHeaders, "Accept-Encoding");
        relayHeaders.put("Accept-Encoding", "identity");
        if (!containsIgnoreCase(relayHeaders, "Content-Type")) {
            relayHeaders.put("Content-Type", "application/octet-stream");
        }
        return relayHeaders;
    }

    private static void mergeHeaders(Map<String, String> target,
                                     Map<String, String> namesByLowerCase,
                                     Map<? extends String, ? extends String> source) {
        if (source == null) return;
        for (Map.Entry<? extends String, ? extends String> entry : source.entrySet()) {
            String name = canonicalHeaderName(entry.getKey());
            String value = entry.getValue();
            if (name == null || value == null) continue;
            String lowerCaseName = name.toLowerCase(Locale.ROOT);
            String previousName = namesByLowerCase.put(lowerCaseName, name);
            if (previousName != null && !previousName.equals(name)) target.remove(previousName);
            target.put(name, value);
        }
    }

    private static String canonicalHeaderName(String name) {
        if (name == null || name.isBlank()) return null;
        return name.trim();
    }

    private static String normalizePayloadKey(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("传输层 payloadKey 不能为空");
        }
        return value.trim();
    }

    private static boolean containsIgnoreCase(Map<String, String> headers, String name) {
        for (String existing : headers.keySet()) {
            if (existing.equalsIgnoreCase(name)) return true;
        }
        return false;
    }

    private static void removeIgnoreCase(Map<String, String> headers, String name) {
        headers.keySet().removeIf(existing -> existing.equalsIgnoreCase(name));
    }
}
