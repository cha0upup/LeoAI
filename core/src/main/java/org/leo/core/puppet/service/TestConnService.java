package org.leo.core.puppet.service;

import org.leo.core.net.Communication;
import org.leo.core.net.layer.RequestLayer;
import org.leo.core.net.layer.ResponseLayer;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class TestConnService extends ComponentService {

    public TestConnService(Communication communication, List<RequestLayer> requestLayers, List<ResponseLayer> responseLayers) {
        super(communication, requestLayers, responseLayers);
    }

    public Map<String, Object> testConn() {
        Map<String, Object> params = new HashMap<String, Object>();
        params.put("M", 0);
        return run(params);
    }
}
