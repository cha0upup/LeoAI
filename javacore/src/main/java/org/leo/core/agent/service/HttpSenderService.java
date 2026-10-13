package org.leo.core.agent.service;

import org.leo.core.net.Communication;
import org.leo.core.net.layer.RequestLayer;
import org.leo.core.net.layer.ResponseLayer;
import org.leo.core.agent.http.HttpSenderEngine;

import java.util.List;
import java.util.Map;

/** Java runtime adapter for the shared Repeater/Fuzzer engine. */
public class HttpSenderService extends HttpRequestService {

    private final HttpSenderEngine engine = new HttpSenderEngine() {
        @Override
        protected Map<String, Object> executeRequest(
                String method, String url, Map<String, String> headers, String body,
                int connectTimeout, int readTimeout, boolean followRedirects) throws Exception {
            return HttpSenderService.this.httpRequest(method, url, headers, body,
                    connectTimeout, readTimeout, followRedirects);
        }
    };

    public HttpSenderService(Communication communication,
                             List<RequestLayer> requestLayers,
                             List<ResponseLayer> responseLayers) {
        super(communication, requestLayers, responseLayers);
    }

    public Map<String, Object> sendRawHttp(String rawHttp, String targetHost, int targetPort,
                                           boolean useTls, boolean followRedirects,
                                           int connectTimeout, int readTimeout) throws Exception {
        return engine.sendRawHttp(rawHttp, targetHost, targetPort, useTls, followRedirects,
                connectTimeout, readTimeout);
    }

    public Map<String, Object> startFuzz(String rawHttp, Map<String, List<String>> payloads,
                                         String targetHost, int targetPort, boolean useTls,
                                         int threads, int delayMs,
                                         Map<String, Object> matchRules) throws Exception {
        return engine.startFuzz(rawHttp, payloads, targetHost, targetPort, useTls,
                threads, delayMs, matchRules);
    }

    public Map<String, Object> queryFuzz(String taskId) {
        return engine.queryFuzz(taskId);
    }

    public Map<String, Object> stopFuzz(String taskId) {
        return engine.stopFuzz(taskId);
    }

    public void close() {
        engine.close();
    }
}
