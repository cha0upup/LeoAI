package org.leo.core.engine.proxy;

import org.leo.core.engine.socks5.Socks5ProxyStatistics.ConnectionInfo;
import org.leo.core.engine.socks5.Socks5ProxyStatistics.StatisticsSnapshot;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/** Summary queries skip connection allocation; paged details have a stable ordering. */
public record ProxyStatisticsQuery(boolean includeConnections, int page, int pageSize) {
    public static final ProxyStatisticsQuery ALL = new ProxyStatisticsQuery(true, 1, Integer.MAX_VALUE);

    public ProxyStatisticsQuery {
        if (page < 1 || pageSize < 1) throw new IllegalArgumentException("page和pageSize必须为正整数");
    }

    public StatisticsSnapshot applyTo(StatisticsSnapshot snapshot) {
        if (snapshot == null) return null;
        StatisticsSnapshot selected = new StatisticsSnapshot();
        selected.port = snapshot.port;
        selected.activeConnections = snapshot.activeConnections;
        selected.totalConnections = snapshot.totalConnections;
        selected.uploadBytes = snapshot.uploadBytes;
        selected.downloadBytes = snapshot.downloadBytes;
        selected.uploadRate = snapshot.uploadRate;
        selected.downloadRate = snapshot.downloadRate;
        selected.startTime = snapshot.startTime;
        selected.uptime = snapshot.uptime;
        return applyTo(selected, snapshot.connections);
    }

    public StatisticsSnapshot applyTo(StatisticsSnapshot snapshot, Collection<ConnectionInfo> connections) {
        Collection<ConnectionInfo> source = connections == null ? List.of() : connections;
        snapshot.connectionCount = source.size();
        snapshot.connectionPage = page;
        snapshot.connectionPageSize = equals(ALL) ? 0 : pageSize;
        snapshot.connectionsIncluded = includeConnections;
        snapshot.connections = !includeConnections ? List.of() : equals(ALL) ? new ArrayList<>(source)
                : source.stream().sorted(Comparator.comparingLong((ConnectionInfo row) -> row.connectTime)
                        .reversed().thenComparing(row -> row.connId))
                        .skip((long) (page - 1) * pageSize).limit(pageSize).toList();
        return snapshot;
    }
}
