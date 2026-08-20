package org.leo.core.net.layer;

import org.leo.core.entity.Disguise;

public class ResponseLayer {
    private Disguise disguise;

    public ResponseLayer(Disguise disguise) {
        this.disguise = disguise;
    }

    public Disguise getDisguise() {
        return disguise;
    }

    public void setDisguise(Disguise disguise) {
        this.disguise = disguise;
    }

    /** Removes the traffic wrapper and returns the opaque payload bytes. */
    public byte[] decodeTraffic(byte[] body) throws Exception {
        if (disguise == null || !disguise.isTrafficOnly()) {
            throw new IllegalStateException("响应伪装必须配置 traffic 编解码");
        }
        return disguise.decodeTraffic(body);
    }
}
