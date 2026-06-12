package org.leo.core.puppet.service;

import org.leo.core.net.Communication;
import org.leo.core.net.layer.RequestLayer;
import org.leo.core.net.layer.ResponseLayer;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class CommandService extends ComponentService {

    public CommandService(Communication communication, List<RequestLayer> requestLayers, List<ResponseLayer> responseLayers) {
        super(communication, requestLayers, responseLayers);
    }

    public Map<String, Object> write(String cmd, String processId) throws Exception {
        HashMap<String, Object> params = new HashMap<String, Object>();
        params.put("processId", processId.getBytes("UTF-8"));
        params.put("op", 0);
        params.put("cmd", cmd.getBytes("UTF-8"));
        return invokeComponent("ExecCommandComponent", params);
    }

    public Map<String, Object> read(String processId) throws Exception {
        HashMap<String, Object> params = new HashMap<String, Object>();
        params.put("processId", processId.getBytes("UTF-8"));
        params.put("op", 1);
        return invokeComponent("ExecCommandComponent", params);
    }

    public Map<String, Object> stop(String processId) throws Exception {
        HashMap<String, Object> params = new HashMap<String, Object>();
        params.put("processId", processId.getBytes("UTF-8"));
        params.put("op", 2);
        return invokeComponent("ExecCommandComponent", params);
    }

    public Map<String, Object> execSimpleCommand(String cmd) throws Exception {
        HashMap<String, Object> params = new HashMap<String, Object>();
        params.put("cmd", cmd.getBytes("UTF-8"));
        return invokeComponent("ExecCommandSimpleComponent", params);
    }
}
