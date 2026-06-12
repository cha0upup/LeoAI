package org.leo.core.puppet.service;

import org.leo.core.net.Communication;
import org.leo.core.net.layer.RequestLayer;
import org.leo.core.net.layer.ResponseLayer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ScanService extends ComponentService {

    public ScanService(Communication communication, List<RequestLayer> requestLayers, List<ResponseLayer> responseLayers) {
        super(communication, requestLayers, responseLayers);
    }

    public Map<String, Object> startScanPort(String scanHost, int[] scanPorts, int scanTimeout, int threadsNum) throws Exception {
        HashMap<String, Object> componentParams = new HashMap<String, Object>();
        componentParams.put("methodName", "startScan");
        componentParams.put("scanHost", scanHost);
        componentParams.put("scanPorts", scanPorts);
        componentParams.put("scanTimeout", scanTimeout);
        componentParams.put("threadsNum", threadsNum);
        return invokeComponent("PortScanComponent", componentParams);
    }

    public Map<String, Object> queryScanPortResult(String taskId) throws Exception {
        HashMap<String, Object> componentParams = new HashMap<String, Object>();
        componentParams.put("methodName", "queryResult");
        componentParams.put("taskId", taskId);
        return invokeComponent("PortScanComponent", componentParams);
    }

    public Map<String, Object> pauseScanPort(String taskId) throws Exception {
        HashMap<String, Object> componentParams = new HashMap<String, Object>();
        componentParams.put("methodName", "pauseScan");
        componentParams.put("taskId", taskId);
        return invokeComponent("PortScanComponent", componentParams);
    }

    public Map<String, Object> resumeScanPort(String taskId) throws Exception {
        HashMap<String, Object> componentParams = new HashMap<String, Object>();
        componentParams.put("methodName", "resumeScan");
        componentParams.put("taskId", taskId);
        return invokeComponent("PortScanComponent", componentParams);
    }

    public Map<String, Object> stopScanPort(String taskId) throws Exception {
        HashMap<String, Object> componentParams = new HashMap<String, Object>();
        componentParams.put("methodName", "stopScan");
        componentParams.put("taskId", taskId);
        return invokeComponent("PortScanComponent", componentParams);
    }

    public Map<String, Object> scanReachableHost(ArrayList scanHostsList, int scanTimeout) throws Exception {
        HashMap<String, Object> componentParams = new HashMap<String, Object>();
        componentParams.put("scanHosts", scanHostsList);
        componentParams.put("scanTimeout", scanTimeout);
        return invokeComponent("HostIsReachableComponent", componentParams);
    }
}
