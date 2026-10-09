package org.leo.core.agent.capability;

import org.leo.core.engine.proxy.ProxyStatisticsQuery;
import org.leo.core.engine.socks5.Socks5ProxyStatistics;

import java.util.List;
import java.util.Map;

/**
 * Capability marker for nodes that can run reverse tunnels from the agent side.
 */
public interface ReverseTunnelCapable {

    Map<String, Object> startReverseTunnel(int remoteListenPort, String bindAddr,
                                           String forwardHost, int forwardPort) throws Exception;

    Map<String, Object> stopReverseTunnel(String listenId);

    Map<String, Object> stopAllReverseTunnels();

    List<Map<String, Object>> listReverseTunnels();

    Socks5ProxyStatistics.StatisticsSnapshot getReverseTunnelStatistics(String listenId);

    default Socks5ProxyStatistics.StatisticsSnapshot getReverseTunnelStatistics(String listenId, ProxyStatisticsQuery query) {
        return query.applyTo(getReverseTunnelStatistics(listenId));
    }
}
