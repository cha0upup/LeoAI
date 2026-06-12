package org.leo.core.puppet.service;

import org.leo.core.net.Communication;
import org.leo.core.net.layer.RequestLayer;
import org.leo.core.net.layer.ResponseLayer;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ExecScriptService extends ComponentService {

    public ExecScriptService(Communication communication, List<RequestLayer> requestLayers, List<ResponseLayer> responseLayers) {
        super(communication, requestLayers, responseLayers);
    }

    public Map<String, Object> execScript(String language, String script) throws Exception {
        HashMap<String, Object> payload = new HashMap<String, Object>();
        payload.put("language", language);
        payload.put("script", script);
        return invokeComponent("ExecScriptComponent", payload);
    }
}
