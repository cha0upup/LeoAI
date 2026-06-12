package org.leo.core.puppet.service;

import org.leo.core.net.Communication;
import org.leo.core.net.layer.RequestLayer;
import org.leo.core.net.layer.ResponseLayer;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ResourceService extends ComponentService {

    public ResourceService(Communication communication, List<RequestLayer> requestLayers, List<ResponseLayer> responseLayers) {
        super(communication, requestLayers, responseLayers);
    }

    public Map<String, Object> getClassBytecode(String className) throws Exception {
        String resourcePath = className.replace('.', '/') + ".class";
        HashMap<String, Object> componentParams = new HashMap<String, Object>();
        componentParams.put("resourcePath", resourcePath);
        return invokeComponent("ResourceComponent", componentParams);
    }

    public Map<String, Object> getResource(String resourcePath) throws Exception {
        HashMap<String, Object> componentParams = new HashMap<String, Object>();
        componentParams.put("resourcePath", resourcePath);
        return invokeComponent("ResourceComponent", componentParams);
    }
}
