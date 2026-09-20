package org.leo.core.puppet.capability;

import org.leo.core.engine.socks5.Socks5ProxyStatistics;

import java.util.Map;

/**
 * Capability marker for nodes that can run an HTTP proxy.
 */
public interface HttpProxyCapable {

    default Map<String, Object> startHttpProxy(int port) throws Exception {
        return startHttpProxy(port, null);
    }

    Map<String, Object> startHttpProxy(int port, String bindAddr) throws Exception;

    Map<String, Object> stopHttpProxy();

    Map<String, Object> getHttpProxyStatus();

    Socks5ProxyStatistics.StatisticsSnapshot getHttpProxyStatistics();
}
