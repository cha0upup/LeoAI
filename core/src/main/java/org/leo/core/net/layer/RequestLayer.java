package org.leo.core.net.layer;

import org.leo.core.entity.Disguise;

import java.util.Map;

public class RequestLayer {
    private String rUrl;
    private Map<String, String> headers;
    private Disguise disguise;

    public RequestLayer(String rUrl, Map<String, String> headers, Disguise disguise) {
        this.rUrl = rUrl;
        this.headers = headers;
        this.disguise = disguise;
    }

    public String getRUrl() {
        return rUrl;
    }

    public Map<String, String> getHeaders() {
        return headers;
    }

    public Disguise getDisguise() {
        return disguise;
    }
}
