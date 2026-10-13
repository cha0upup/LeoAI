package org.leo.core.agent.service;

import org.leo.core.net.Communication;
import org.leo.core.net.layer.RequestLayer;
import org.leo.core.net.layer.ResponseLayer;

import java.util.List;
import java.util.Map;

/** Platform-side transport adapter for the node's NetworkProbeComponent. */
public class NetworkProbeService extends ComponentService {

    private static final String COMPONENT = "NetworkProbeComponent";

    public NetworkProbeService(Communication communication, List<RequestLayer> requestLayers,
                               List<ResponseLayer> responseLayers) {
        super(communication, requestLayers, responseLayers);
    }

    public Map<String, Object> startNetworkProbe(Map<String, Object> plan) throws Exception {
        return call(COMPONENT, "startTask", "plan", plan);
    }

    public Map<String, Object> queryNetworkProbe(String taskId, long cursor, long ackCursor,
                                                 int maxItems, int maxBytes,
                                                 boolean includeEvidence) throws Exception {
        return call(COMPONENT, "queryTask", "taskId", taskId, "cursor", cursor,
                "ackCursor", ackCursor, "maxItems", maxItems, "maxBytes", maxBytes,
                "includeEvidence", includeEvidence);
    }

    public Map<String, Object> ackNetworkProbe(String taskId, long cursor) throws Exception {
        return call(COMPONENT, "ackTask", "taskId", taskId, "cursor", cursor);
    }

    public Map<String, Object> pauseNetworkProbe(String taskId) throws Exception {
        return call(COMPONENT, "pauseTask", "taskId", taskId);
    }

    public Map<String, Object> resumeNetworkProbe(String taskId) throws Exception {
        return call(COMPONENT, "resumeTask", "taskId", taskId);
    }

    public Map<String, Object> stopNetworkProbe(String taskId) throws Exception {
        return call(COMPONENT, "stopTask", "taskId", taskId);
    }

    public Map<String, Object> releaseNetworkProbe(String taskId) throws Exception {
        return call(COMPONENT, "releaseTask", "taskId", taskId);
    }
}
