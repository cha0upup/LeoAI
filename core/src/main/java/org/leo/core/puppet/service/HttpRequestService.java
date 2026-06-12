package org.leo.core.puppet.service;

import org.leo.core.net.Communication;
import org.leo.core.net.layer.RequestLayer;
import org.leo.core.net.layer.ResponseLayer;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * HTTP 请求服务
 * <p>
 * 封装 HttpRequestComponent 的调用逻辑，在 puppet 侧发起 HTTP 请求。
 */
public class HttpRequestService extends ComponentService {

    private static final String COMPONENT_NAME = "HttpRequestComponent";

    public HttpRequestService(Communication communication, List<RequestLayer> requestLayers, List<ResponseLayer> responseLayers) {
        super(communication, requestLayers, responseLayers);
    }

    /**
     * 发起 HTTP 请求
     *
     * @param method          HTTP 方法（GET/POST/PUT/DELETE/HEAD）
     * @param url             请求 URL
     * @param headers         请求头（可为 null）
     * @param body            请求体（可为 null，仅 POST/PUT 有效）
     * @param connectTimeout  连接超时（毫秒，0 使用默认值）
     * @param readTimeout     读取超时（毫秒，0 使用默认值）
     * @param followRedirects 是否跟随重定向
     * @return 响应结果
     */
    public Map<String, Object> httpRequest(String method, String url, Map<String, String> headers,
                                           String body, int connectTimeout, int readTimeout,
                                           boolean followRedirects) throws Exception {
        HashMap<String, Object> params = new HashMap<String, Object>();
        params.put("method", method);
        params.put("url", url);

        if (headers != null && !headers.isEmpty()) {
            params.put("headers", new HashMap<String, String>(headers));
        }

        if (body != null) {
            params.put("body", body);
        }

        if (connectTimeout > 0) {
            params.put("connectTimeout", connectTimeout);
        }

        if (readTimeout > 0) {
            params.put("readTimeout", readTimeout);
        }

        params.put("followRedirects", followRedirects);

        return invokeComponent(COMPONENT_NAME, params);
    }

    /**
     * 发起 GET 请求（简化版）
     */
    public Map<String, Object> httpGet(String url, Map<String, String> headers) throws Exception {
        return httpRequest("GET", url, headers, null, 0, 0, true);
    }

    /**
     * 发起 POST 请求（简化版）
     */
    public Map<String, Object> httpPost(String url, Map<String, String> headers, String body) throws Exception {
        return httpRequest("POST", url, headers, body, 0, 0, true);
    }

    /**
     * 发起 HEAD 请求（简化版）
     */
    public Map<String, Object> httpHead(String url, Map<String, String> headers) throws Exception {
        return httpRequest("HEAD", url, headers, null, 0, 0, true);
    }
}
