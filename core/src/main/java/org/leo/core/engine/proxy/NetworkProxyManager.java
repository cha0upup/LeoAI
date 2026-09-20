package org.leo.core.engine.proxy;

import org.leo.core.engine.forward.LocalForwardServer;
import org.leo.core.engine.http.HttpProxyServer;
import org.leo.core.engine.reverse.ReverseTunnelServer;
import org.leo.core.engine.socks5.Socks5ProxyServer;
import org.leo.core.engine.socks5.Socks5ProxyStatistics;
import org.leo.core.puppet.capability.ComponentInvokeCapable;
import org.leo.core.puppet.capability.HttpProxyCapable;
import org.leo.core.puppet.capability.LocalForwardCapable;
import org.leo.core.puppet.capability.ReverseTunnelCapable;
import org.leo.core.puppet.capability.Socks5ProxyCapable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runtime-neutral lifecycle manager for all socket proxy modes.
 * The transport-specific node only needs to expose component invocation.
 */
public final class NetworkProxyManager implements Socks5ProxyCapable, HttpProxyCapable,
        LocalForwardCapable, ReverseTunnelCapable, AutoCloseable {

    private final ComponentInvokeCapable puppetNode;
    private final Map<Integer, LocalForwardServer> localForwards = new ConcurrentHashMap<>();
    private final Map<String, ReverseTunnelServer> reverseTunnels = new ConcurrentHashMap<>();
    private Socks5ProxyServer socks5Proxy;
    private HttpProxyServer httpProxy;

    public NetworkProxyManager(ComponentInvokeCapable puppetNode) {
        if (puppetNode == null) throw new IllegalArgumentException("puppetNode不能为空");
        this.puppetNode = puppetNode;
    }

    @Override
    public synchronized Map<String, Object> startSocks5Proxy(int port, String bindAddr) throws Exception {
        requirePort(port, "port");
        String address = ProxyBindAddress.normalize(bindAddr);
        if (socks5Proxy != null && socks5Proxy.isRunning()) {
            if (socks5Proxy.getListenPort() != port || !socks5Proxy.getBindAddr().equals(address)) {
                return result(409, "代理已启动，请先停止再修改监听地址或端口");
            }
            return listenerResult(socks5Proxy, "already running");
        }
        Socks5ProxyServer server = new Socks5ProxyServer(puppetNode, port, address);
        server.start();
        socks5Proxy = server;
        return listenerResult(server, "started");
    }

    @Override
    public synchronized Map<String, Object> stopSocks5Proxy() {
        if (socks5Proxy == null) return result(200, "not running");
        socks5Proxy.stop();
        socks5Proxy = null;
        return result(200, "stopped");
    }

    @Override
    public synchronized Map<String, Object> getSocks5ProxyStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        boolean enabled = socks5Proxy != null && socks5Proxy.isRunning();
        status.put("enabled", enabled);
        status.put("port", enabled ? socks5Proxy.getListenPort() : null);
        status.put("bindAddr", enabled ? socks5Proxy.getBindAddr() : null);
        return status;
    }

    @Override
    public synchronized Socks5ProxyStatistics.StatisticsSnapshot getSocks5ProxyStatistics() {
        return snapshot(socks5Proxy == null ? null : socks5Proxy.getStatistics());
    }

    @Override
    public synchronized Map<String, Object> startHttpProxy(int port, String bindAddr) throws Exception {
        requirePort(port, "port");
        String address = ProxyBindAddress.normalize(bindAddr);
        if (httpProxy != null && httpProxy.isRunning()) {
            if (httpProxy.getListenPort() != port || !httpProxy.getBindAddr().equals(address)) {
                return result(409, "代理已启动，请先停止再修改监听地址或端口");
            }
            return listenerResult(httpProxy, "already running");
        }
        HttpProxyServer server = new HttpProxyServer(puppetNode, port, address);
        server.start();
        httpProxy = server;
        return listenerResult(server, "started");
    }

    @Override
    public synchronized Map<String, Object> stopHttpProxy() {
        if (httpProxy == null) return result(200, "not running");
        httpProxy.stop();
        httpProxy = null;
        return result(200, "stopped");
    }

    @Override
    public synchronized Map<String, Object> getHttpProxyStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        boolean running = httpProxy != null && httpProxy.isRunning();
        status.put("running", running);
        if (running) {
            status.put("port", httpProxy.getListenPort());
            status.put("bindAddr", httpProxy.getBindAddr());
        }
        return status;
    }

    @Override
    public synchronized Socks5ProxyStatistics.StatisticsSnapshot getHttpProxyStatistics() {
        return snapshot(httpProxy == null ? null : httpProxy.getStatistics());
    }

    @Override
    public synchronized Map<String, Object> startLocalForward(
            int localPort, String bindAddr, String targetHost, int targetPort) throws Exception {
        requirePort(localPort, "localPort");
        String address = ProxyBindAddress.normalize(bindAddr);
        requireTarget(targetHost, targetPort, "targetHost", "targetPort");
        if (localForwards.containsKey(localPort)) {
            return result(409, "forward already exists", "localPort", localPort);
        }
        LocalForwardServer server = new LocalForwardServer(puppetNode, localPort, address, targetHost.trim(), targetPort);
        server.start();
        localForwards.put(localPort, server);
        Map<String, Object> response = result(200, "started", "localPort", localPort);
        response.put("bindAddr", server.getBindAddr());
        response.put("targetHost", targetHost.trim());
        response.put("targetPort", targetPort);
        return response;
    }

    @Override
    public synchronized Map<String, Object> stopLocalForward(int localPort) {
        LocalForwardServer server = localForwards.remove(localPort);
        if (server == null) return result(200, "not running", "localPort", localPort);
        server.stop();
        return result(200, "stopped", "localPort", localPort);
    }

    @Override
    public synchronized Map<String, Object> stopAllLocalForwards() {
        int count = localForwards.size();
        localForwards.values().forEach(LocalForwardServer::stop);
        localForwards.clear();
        return result(200, "stopped " + count + " forward(s)");
    }

    @Override
    public synchronized List<Map<String, Object>> listLocalForwards() {
        List<Map<String, Object>> result = new ArrayList<>();
        localForwards.values().stream()
                .sorted((left, right) -> Integer.compare(left.getLocalPort(), right.getLocalPort()))
                .forEach(server -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("localPort", server.getLocalPort());
                    item.put("bindAddr", server.getBindAddr());
                    item.put("targetHost", server.getTargetHost());
                    item.put("targetPort", server.getTargetPort());
                    item.put("running", server.isRunning());
                    result.add(item);
                });
        return result;
    }

    @Override
    public synchronized Socks5ProxyStatistics.StatisticsSnapshot getLocalForwardStatistics(int localPort) {
        LocalForwardServer server = localForwards.get(localPort);
        return snapshot(server == null ? null : server.getStatistics());
    }

    @Override
    public synchronized Map<String, Object> startReverseTunnel(
            int remoteListenPort, String bindAddr, String forwardHost, int forwardPort) throws Exception {
        requirePort(remoteListenPort, "remoteListenPort");
        requireTarget(forwardHost, forwardPort, "forwardHost", "forwardPort");
        for (ReverseTunnelServer existing : reverseTunnels.values()) {
            if (existing.isRunning() && existing.getRemoteListenPort() == remoteListenPort) {
                return result(409, "reverse tunnel already exists", "remoteListenPort", remoteListenPort);
            }
        }
        ReverseTunnelServer server = new ReverseTunnelServer(puppetNode, remoteListenPort,
                normalizeBindAddress(bindAddr), forwardHost.trim(), forwardPort);
        String listenId = server.getListenId();
        server.setOnDead(() -> reverseTunnels.remove(listenId, server));
        reverseTunnels.put(listenId, server);
        try {
            server.start();
        } catch (Exception error) {
            reverseTunnels.remove(listenId, server);
            server.stop();
            throw error;
        }
        Map<String, Object> response = result(200, "started", "listenId", listenId);
        response.put("remoteListenPort", remoteListenPort);
        response.put("bindAddr", server.getBindAddr());
        response.put("forwardHost", forwardHost.trim());
        response.put("forwardPort", forwardPort);
        return response;
    }

    @Override
    public synchronized Map<String, Object> stopReverseTunnel(String listenId) {
        ReverseTunnelServer server = reverseTunnels.remove(listenId);
        if (server == null) return result(200, "not running", "listenId", listenId);
        server.stop();
        return result(200, "stopped", "listenId", listenId);
    }

    @Override
    public synchronized Map<String, Object> stopAllReverseTunnels() {
        int count = reverseTunnels.size();
        reverseTunnels.values().forEach(ReverseTunnelServer::stop);
        reverseTunnels.clear();
        return result(200, "stopped " + count + " reverse tunnel(s)");
    }

    @Override
    public synchronized List<Map<String, Object>> listReverseTunnels() {
        List<Map<String, Object>> result = new ArrayList<>();
        reverseTunnels.values().stream()
                .sorted((left, right) -> Integer.compare(left.getRemoteListenPort(), right.getRemoteListenPort()))
                .forEach(server -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("listenId", server.getListenId());
                    item.put("remoteListenPort", server.getRemoteListenPort());
                    item.put("bindAddr", server.getBindAddr());
                    item.put("forwardHost", server.getForwardHost());
                    item.put("forwardPort", server.getForwardPort());
                    item.put("running", server.isRunning());
                    item.put("startTime", server.getStartTime());
                    result.add(item);
                });
        return result;
    }

    @Override
    public synchronized Socks5ProxyStatistics.StatisticsSnapshot getReverseTunnelStatistics(String listenId) {
        ReverseTunnelServer server = reverseTunnels.get(listenId);
        return snapshot(server == null ? null : server.getStatistics());
    }

    @Override
    public synchronized void close() {
        stopSocks5Proxy();
        stopHttpProxy();
        stopAllLocalForwards();
        stopAllReverseTunnels();
    }

    private Socks5ProxyStatistics.StatisticsSnapshot snapshot(Socks5ProxyStatistics statistics) {
        return statistics == null ? null : statistics.getSnapshot();
    }

    private void requirePort(int port, String name) {
        if (port < 1 || port > 65535) throw new IllegalArgumentException(name + "必须在1到65535之间");
    }

    private void requireTarget(String host, int port, String hostName, String portName) {
        if (host == null || host.isBlank() || host.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(hostName + "不能为空");
        }
        requirePort(port, portName);
    }

    private String normalizeBindAddress(String bindAddr) {
        return bindAddr == null || bindAddr.isBlank() ? "127.0.0.1" : bindAddr.trim();
    }

    private Map<String, Object> listenerResult(LocalProxyServer server, String message) {
        Map<String, Object> response = result(200, message, "port", server.getListenPort());
        response.put("bindAddr", server.getBindAddr());
        return response;
    }

    private Map<String, Object> result(int code, String message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("code", code);
        result.put("msg", message);
        return result;
    }

    private Map<String, Object> result(int code, String message, String key, Object value) {
        Map<String, Object> result = result(code, message);
        result.put(key, value);
        return result;
    }
}
