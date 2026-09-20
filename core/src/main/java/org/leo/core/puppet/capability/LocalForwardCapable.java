package org.leo.core.puppet.capability;

import org.leo.core.engine.socks5.Socks5ProxyStatistics;

import java.util.List;
import java.util.Map;

/**
 * Capability marker for nodes that can run local port forwards.
 */
public interface LocalForwardCapable {

    default Map<String, Object> startLocalForward(int localPort, String targetHost, int targetPort) throws Exception {
        return startLocalForward(localPort, null, targetHost, targetPort);
    }

    Map<String, Object> startLocalForward(int localPort, String bindAddr, String targetHost, int targetPort) throws Exception;

    Map<String, Object> stopLocalForward(int localPort);

    Map<String, Object> stopAllLocalForwards();

    List<Map<String, Object>> listLocalForwards();

    Socks5ProxyStatistics.StatisticsSnapshot getLocalForwardStatistics(int localPort);
}
