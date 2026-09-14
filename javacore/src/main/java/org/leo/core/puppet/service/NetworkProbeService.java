package org.leo.core.puppet.service;

import org.leo.core.net.Communication;
import org.leo.core.net.layer.RequestLayer;
import org.leo.core.net.layer.ResponseLayer;

import java.util.HashMap;
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
        HashMap<String, Object> params = params("startTask");
        params.put("plan", plan);
        return invokeComponent(COMPONENT, params);
    }

    public Map<String, Object> queryNetworkProbe(String taskId, long cursor,
                                                 int maxItems, int maxBytes,
                                                 boolean includeEvidence) throws Exception {
        HashMap<String, Object> params = params("queryTask");
        params.put("taskId", taskId);
        params.put("cursor", Long.valueOf(cursor));
        params.put("maxItems", Integer.valueOf(maxItems));
        params.put("maxBytes", Integer.valueOf(maxBytes));
        params.put("includeEvidence", Boolean.valueOf(includeEvidence));
        return invokeComponent(COMPONENT, params);
    }

    public Map<String, Object> ackNetworkProbe(String taskId, long cursor) throws Exception {
        HashMap<String, Object> params = params("ackTask");
        params.put("taskId", taskId);
        params.put("cursor", Long.valueOf(cursor));
        return invokeComponent(COMPONENT, params);
    }

    public Map<String, Object> pauseNetworkProbe(String taskId) throws Exception {
        return invokeTask("pauseTask", taskId);
    }

    public Map<String, Object> resumeNetworkProbe(String taskId) throws Exception {
        return invokeTask("resumeTask", taskId);
    }

    public Map<String, Object> stopNetworkProbe(String taskId) throws Exception {
        return invokeTask("stopTask", taskId);
    }

    public Map<String, Object> releaseNetworkProbe(String taskId) throws Exception {
        return invokeTask("releaseTask", taskId);
    }

    private Map<String, Object> invokeTask(String method, String taskId) throws Exception {
        HashMap<String, Object> params = params(method);
        params.put("taskId", taskId);
        return invokeComponent(COMPONENT, params);
    }

    private HashMap<String, Object> params(String method) {
        HashMap<String, Object> params = new HashMap<>();
        params.put("methodName", method);
        return params;
    }
}
