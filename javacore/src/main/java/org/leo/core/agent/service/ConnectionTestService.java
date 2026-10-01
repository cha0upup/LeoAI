package org.leo.core.agent.service;

import org.leo.core.net.Communication;
import org.leo.core.net.layer.RequestLayer;
import org.leo.core.net.layer.ResponseLayer;
import org.leo.core.rpc.AgentOperation;

import java.util.Collections;
import java.util.List;
import java.util.Map;

public class ConnectionTestService extends ComponentService {

    public ConnectionTestService(Communication communication, List<RequestLayer> requestLayers, List<ResponseLayer> responseLayers) {
        super(communication, requestLayers, responseLayers);
    }

    public Map<String, Object> testConnection() {
        return run(AgentOperation.PING, null, null, Collections.emptyMap());
    }
}
