package org.leo.core.net.layer;

import org.leo.core.entity.Disguise;

public class ResponseLayer {
    private Disguise disguise;
    private String payloadKey;

    public ResponseLayer(Disguise disguise) {
        this(disguise, null);
    }

    public ResponseLayer(Disguise disguise, String payloadKey) {
        this.disguise = disguise;
        this.payloadKey = normalizePayloadKey(payloadKey);
    }

    public Disguise getDisguise() {
        return disguise;
    }

    public void setDisguise(Disguise disguise) {
        this.disguise = disguise;
    }

    /** PayloadCodec key for this hop; null keeps legacy single-layer fallback behavior. */
    public String getPayloadKey() {
        return payloadKey;
    }

    public void setPayloadKey(String payloadKey) {
        this.payloadKey = normalizePayloadKey(payloadKey);
    }

    /** Removes the traffic wrapper and returns the opaque payload bytes. */
    public byte[] decodeTraffic(byte[] body) throws Exception {
        if (disguise == null || !disguise.isTrafficOnly()) {
            throw new IllegalStateException("响应伪装必须配置 traffic 编解码");
        }
        return disguise.decodeTraffic(body);
    }

    private static String normalizePayloadKey(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
