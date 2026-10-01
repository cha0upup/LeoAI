package org.leo.core.agent.capability;

import java.util.List;
import java.util.Map;

/**
 * Capability marker for nodes that can send HTTP requests and run HTTP fuzzing tasks.
 */
public interface HttpSenderCapable {

    Map<String, Object> httpRequest(String method, String url, Map<String, String> headers,
                                    String body, int connectTimeout, int readTimeout,
                                    boolean followRedirects) throws Exception;

    Map<String, Object> sendRawHttp(String rawHttp, String targetHost, int targetPort,
                                    boolean useTls, boolean followRedirects,
                                    int connectTimeout, int readTimeout) throws Exception;

    Map<String, Object> startFuzz(String rawHttp, Map<String, List<String>> payloads,
                                  String targetHost, int targetPort, boolean useTls,
                                  int threads, int delayMs,
                                  Map<String, Object> matchRules) throws Exception;

    Map<String, Object> queryFuzz(String taskId);

    Map<String, Object> stopFuzz(String taskId);
}
