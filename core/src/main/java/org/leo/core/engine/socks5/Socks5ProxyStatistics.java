package org.leo.core.engine.socks5;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * SOCKS5代理统计信息
 * 线程安全的统计类，用于跟踪连接数、数据量、速率等
 * 
 * @author LeoSpring
 * @version 2.1
 */
public class Socks5ProxyStatistics {
    private static final Logger logger = LoggerFactory.getLogger(Socks5ProxyStatistics.class);
    
    // 当前连接数
    private final AtomicInteger activeConnections = new AtomicInteger(0);
    
    // 总连接数（包括已关闭的）
    private final AtomicInteger totalConnections = new AtomicInteger(0);
    
    // 上行数据量（客户端 -> 远程）
    private final AtomicLong uploadBytes = new AtomicLong(0);
    
    // 下行数据量（远程 -> 客户端）
    private final AtomicLong downloadBytes = new AtomicLong(0);
    
    // 上行速率（字节/秒）
    private volatile long uploadRate = 0;
    
    // 下行速率（字节/秒）
    private volatile long downloadRate = 0;
    
    // 上次更新时间
    private volatile long lastUpdateTime = System.currentTimeMillis();
    
    // 上次的上行数据量
    private volatile long lastUploadBytes = 0;
    
    // 上次的下行数据量
    private volatile long lastDownloadBytes = 0;
    
    // 连接信息映射（connId -> ConnectionInfo）
    private final ConcurrentHashMap<String, ConnectionInfo> connections = new ConcurrentHashMap<String, ConnectionInfo>();
    
    // 启动时间
    private final long startTime = System.currentTimeMillis();
    
    // 端口号
    private final int port;
    
    public Socks5ProxyStatistics(int port) {
        this.port = port;
    }
    
    /**
     * 增加连接数
     */
    public void addConnection(String connId, String targetHost, int targetPort, String clientIp) {
        activeConnections.incrementAndGet();
        totalConnections.incrementAndGet();
        
        ConnectionInfo info = new ConnectionInfo();
        info.connId = connId;
        info.targetHost = targetHost;
        info.targetPort = targetPort;
        info.clientIp = clientIp;
        info.connectTime = System.currentTimeMillis();
        info.uploadBytes = 0;
        info.downloadBytes = 0;
        
        connections.put(connId, info);
        
        logger.debug("新增连接: connId={}, target={}:{}, clientIp={}, 当前连接数={}", 
                    connId, targetHost, targetPort, clientIp, activeConnections.get());
    }
    
    /**
     * 移除连接
     */
    public void removeConnection(String connId) {
        if (connections.remove(connId) != null) {
            activeConnections.decrementAndGet();
            logger.debug("移除连接: connId={}, 当前连接数={}", connId, activeConnections.get());
        }
    }
    
    /**
     * 增加上行数据量
     */
    public void addUploadBytes(String connId, long bytes) {
        uploadBytes.addAndGet(bytes);
        ConnectionInfo info = connections.get(connId);
        if (info != null) {
            info.uploadBytes += bytes;
        }
    }
    
    /**
     * 增加下行数据量
     */
    public void addDownloadBytes(String connId, long bytes) {
        downloadBytes.addAndGet(bytes);
        ConnectionInfo info = connections.get(connId);
        if (info != null) {
            info.downloadBytes += bytes;
        }
    }
    
    /**
     * 更新速率
     */
    public void updateRates() {
        long currentTime = System.currentTimeMillis();
        long timeDiff = currentTime - lastUpdateTime;
        
        if (timeDiff >= 1000) { // 每秒更新一次
            long currentUploadBytes = uploadBytes.get();
            long currentDownloadBytes = downloadBytes.get();
            
            long uploadDiff = currentUploadBytes - lastUploadBytes;
            long downloadDiff = currentDownloadBytes - lastDownloadBytes;
            
            uploadRate = (uploadDiff * 1000) / timeDiff;
            downloadRate = (downloadDiff * 1000) / timeDiff;
            
            lastUploadBytes = currentUploadBytes;
            lastDownloadBytes = currentDownloadBytes;
            lastUpdateTime = currentTime;
        }
    }
    
    /**
     * 获取统计信息快照
     */
    public StatisticsSnapshot getSnapshot() {
        updateRates();
        
        StatisticsSnapshot snapshot = new StatisticsSnapshot();
        snapshot.port = port;
        snapshot.activeConnections = activeConnections.get();
        snapshot.totalConnections = totalConnections.get();
        snapshot.uploadBytes = uploadBytes.get();
        snapshot.downloadBytes = downloadBytes.get();
        snapshot.uploadRate = uploadRate;
        snapshot.downloadRate = downloadRate;
        snapshot.startTime = startTime;
        snapshot.uptime = System.currentTimeMillis() - startTime;
        snapshot.connections = new java.util.ArrayList<ConnectionInfo>(connections.values());
        
        return snapshot;
    }
    
    /**
     * 重置统计信息
     */
    public void reset() {
        activeConnections.set(0);
        totalConnections.set(0);
        uploadBytes.set(0);
        downloadBytes.set(0);
        uploadRate = 0;
        downloadRate = 0;
        connections.clear();
        lastUpdateTime = System.currentTimeMillis();
        lastUploadBytes = 0;
        lastDownloadBytes = 0;
    }
    
    /**
     * 连接信息
     */
    public static class ConnectionInfo {
        public String connId;
        public String targetHost;
        public int targetPort;
        public String clientIp;
        public long connectTime;
        public long uploadBytes;
        public long downloadBytes;
        
        public long getUptime() {
            return System.currentTimeMillis() - connectTime;
        }
    }
    
    /**
     * 统计信息快照
     */
    public static class StatisticsSnapshot {
        public int port;
        public int activeConnections;
        public int totalConnections;
        public long uploadBytes;
        public long downloadBytes;
        public long uploadRate;
        public long downloadRate;
        public long startTime;
        public long uptime;
        public java.util.List<ConnectionInfo> connections;
    }
}

