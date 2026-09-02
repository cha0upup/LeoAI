package org.leo.core.puppet.capability;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Capability marker for nodes that can run network scan tasks.
 */
public interface ScanCapable {

    Map<String, Object> startScanPort(String scanHost, int[] scanPorts, int scanTimeout, int threadsNum) throws Exception;

    /**
     * Starts a port scan with optional lightweight service probing. The default
     * implementation keeps non-Java nodes backward compatible.
     */
    default Map<String, Object> startScanPort(String scanHost, int[] scanPorts,
                                               int scanTimeout, int threadsNum,
                                               boolean probeServices) throws Exception {
        return startScanPort(scanHost, scanPorts, scanTimeout, threadsNum);
    }

    /**
     * Starts one scan job for several targets. Implementations that have not
     * adopted the unified target model can still serve the legacy single-target
     * request through the default method.
     */
    default Map<String, Object> startScanPort(List<String> scanHosts, int[] scanPorts,
                                               int scanTimeout, int threadsNum,
                                               boolean probeServices) throws Exception {
        if (scanHosts == null || scanHosts.size() != 1) {
            throw new UnsupportedOperationException("当前节点不支持多目标端口扫描");
        }
        return startScanPort(scanHosts.get(0), scanPorts, scanTimeout, threadsNum, probeServices);
    }

    Map<String, Object> queryScanPortResult(String taskId) throws Exception;

    Map<String, Object> pauseScanPort(String taskId) throws Exception;

    Map<String, Object> resumeScanPort(String taskId) throws Exception;

    Map<String, Object> stopScanPort(String taskId) throws Exception;

    Map<String, Object> scanReachableHost(ArrayList<String> scanHostsList, int scanTimeout) throws Exception;
}
