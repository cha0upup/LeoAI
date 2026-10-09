package org.leo.core.agent.capability;

import org.leo.core.engine.proxy.ProxyStatisticsQuery;
import org.leo.core.engine.socks5.Socks5ProxyStatistics;

import java.util.Map;

/**
 * Capability marker for nodes that can run a SOCKS5 proxy.
 */
public interface Socks5ProxyCapable {

    default Map<String, Object> startSocks5Proxy(int port) throws Exception {
        return startSocks5Proxy(port, null);
    }

    Map<String, Object> startSocks5Proxy(int port, String bindAddr) throws Exception;

    Map<String, Object> stopSocks5Proxy();

    Map<String, Object> getSocks5ProxyStatus();

    Socks5ProxyStatistics.StatisticsSnapshot getSocks5ProxyStatistics();

    default Socks5ProxyStatistics.StatisticsSnapshot getSocks5ProxyStatistics(ProxyStatisticsQuery query) {
        return query.applyTo(getSocks5ProxyStatistics());
    }
}
