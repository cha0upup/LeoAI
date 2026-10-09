package org.leo.core.engine.proxy;

import org.leo.core.engine.forward.LocalForwardServer;
import org.leo.core.engine.http.HttpProxyServer;
import org.leo.core.engine.reverse.ReverseTunnelServer;
import org.leo.core.engine.socks5.Socks5ProxyServer;
import org.leo.core.engine.socks5.Socks5ProxyStatistics;
import org.leo.core.agent.capability.ComponentInvokeCapable;
import org.leo.core.agent.capability.HttpProxyCapable;
import org.leo.core.agent.capability.LocalForwardCapable;
import org.leo.core.agent.capability.ReverseTunnelCapable;
import org.leo.core.agent.capability.Socks5ProxyCapable;

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

    private final ComponentInvokeCapable agentNode;
    private final Map<Integer, LocalForwardServer> localForwards = new ConcurrentHashMap<>();
    private final Map<String, ReverseTunnelServer> reverseTunnels = new ConcurrentHashMap<>();
    // Ports stay reserved through START/STOP, including slow or cancelled RPCs.
    private final Map<Integer, ReverseTunnelServer> reversePorts = new ConcurrentHashMap<>();
    private final ProxySessionResources resources;
    private final int maxListeners;
    private volatile Socks5ProxyServer socks5Proxy;
    private volatile HttpProxyServer httpProxy;
    private boolean closed;

    public NetworkProxyManager(ComponentInvokeCapable agentNode) {
        this(agentNode, 64, 512, 16);
    }

    public NetworkProxyManager(ComponentInvokeCapable agentNode, int maxListeners,
                               int maxConnections, int maxDialers) {
        if (agentNode == null) throw new IllegalArgumentException("agentNode不能为空");
        if (maxListeners < 1) throw new IllegalArgumentException("maxListeners must be positive");
        this.maxListeners = maxListeners;
        this.resources = new ProxySessionResources(maxConnections, maxDialers);
        this.agentNode = agentNode;
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
        requireListenerSlot();
        Socks5ProxyServer server = new Socks5ProxyServer(agentNode, port, address, resources);
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
    public Map<String, Object> getSocks5ProxyStatus() {
        Socks5ProxyServer server = socks5Proxy;
        Map<String, Object> status = new LinkedHashMap<>();
        boolean enabled = server != null && server.isRunning();
        status.put("enabled", enabled);
        status.put("port", enabled ? server.getListenPort() : null);
        status.put("bindAddr", enabled ? server.getBindAddr() : null);
        return status;
    }

    @Override
    public Socks5ProxyStatistics.StatisticsSnapshot getSocks5ProxyStatistics() {
        Socks5ProxyServer server = socks5Proxy;
        return snapshot(server == null ? null : server.getStatistics());
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
        requireListenerSlot();
        HttpProxyServer server = new HttpProxyServer(agentNode, port, address, resources);
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
    public Map<String, Object> getHttpProxyStatus() {
        HttpProxyServer server = httpProxy;
        Map<String, Object> status = new LinkedHashMap<>();
        boolean running = server != null && server.isRunning();
        status.put("running", running);
        if (running) {
            status.put("port", server.getListenPort());
            status.put("bindAddr", server.getBindAddr());
        }
        return status;
    }

    @Override
    public Socks5ProxyStatistics.StatisticsSnapshot getHttpProxyStatistics() {
        HttpProxyServer server = httpProxy;
        return snapshot(server == null ? null : server.getStatistics());
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
        requireListenerSlot();
        LocalForwardServer server = new LocalForwardServer(agentNode, localPort, address, targetHost.trim(), targetPort, resources);
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
    public List<Map<String, Object>> listLocalForwards() {
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
    public Socks5ProxyStatistics.StatisticsSnapshot getLocalForwardStatistics(int localPort) {
        LocalForwardServer server = localForwards.get(localPort);
        return snapshot(server == null ? null : server.getStatistics());
    }

    @Override
    public Map<String, Object> startReverseTunnel(
            int remoteListenPort, String bindAddr, String forwardHost, int forwardPort) throws Exception {
        requirePort(remoteListenPort, "remoteListenPort");
        requireTarget(forwardHost, forwardPort, "forwardHost", "forwardPort");
        ReverseTunnelServer server;
        synchronized (this) {
            if (reversePorts.containsKey(remoteListenPort)) {
                return result(409, "reverse tunnel already exists", "remoteListenPort", remoteListenPort);
            }
            requireListenerSlot();
            server = new ReverseTunnelServer(agentNode, remoteListenPort,
                    normalizeBindAddress(bindAddr), forwardHost.trim(), forwardPort, resources);
            server.setOnDead(() -> releaseReverse(server));
            reversePorts.put(remoteListenPort, server);
            reverseTunnels.put(server.getListenId(), server);
        }
        try {
            server.start(() -> reverseTunnels.get(server.getListenId()) == server);
            if (reverseTunnels.get(server.getListenId()) != server) {
                stopReverse(server);
                return result(409, "reverse tunnel start cancelled", "listenId", server.getListenId());
            }
        } catch (Exception error) {
            boolean cancelled = reverseTunnels.get(server.getListenId()) != server;
            stopReverse(server);
            if (cancelled) return result(409, "reverse tunnel start cancelled", "listenId", server.getListenId());
            throw error;
        }
        Map<String, Object> response = result(200, "started", "listenId", server.getListenId());
        response.put("remoteListenPort", remoteListenPort);
        response.put("bindAddr", server.getBindAddr());
        response.put("forwardHost", forwardHost.trim());
        response.put("forwardPort", forwardPort);
        return response;
    }

    @Override
    public Map<String, Object> stopReverseTunnel(String listenId) {
        ReverseTunnelServer server = reverseTunnels.remove(listenId);
        if (server == null) return result(200, "not running", "listenId", listenId);
        stopReverse(server);
        return result(200, "stopped", "listenId", listenId);
    }

    @Override
    public Map<String, Object> stopAllReverseTunnels() {
        List<ReverseTunnelServer> servers = drainReverseTunnels();
        servers.forEach(this::stopReverse);
        return result(200, "stopped " + servers.size() + " reverse tunnel(s)");
    }

    private synchronized List<ReverseTunnelServer> drainReverseTunnels() {
        List<ReverseTunnelServer> servers = new ArrayList<>(reverseTunnels.values());
        reverseTunnels.clear();
        return servers;
    }

    private void stopReverse(ReverseTunnelServer server) {
        try {
            server.stop();
        } finally {
            releaseReverse(server);
        }
    }

    private void releaseReverse(ReverseTunnelServer server) {
        reverseTunnels.remove(server.getListenId(), server);
        reversePorts.remove(server.getRemoteListenPort(), server);
    }

    @Override
    public List<Map<String, Object>> listReverseTunnels() {
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
    public Socks5ProxyStatistics.StatisticsSnapshot getReverseTunnelStatistics(String listenId) {
        ReverseTunnelServer server = reverseTunnels.get(listenId);
        return snapshot(server == null ? null : server.getStatistics());
    }

    @Override
    public void close() {
        List<ReverseTunnelServer> servers;
        synchronized (this) {
            if (closed) return;
            closed = true;
            stopSocks5Proxy();
            stopHttpProxy();
            stopAllLocalForwards();
            servers = drainReverseTunnels();
        }
        try {
            servers.forEach(this::stopReverse);
        } finally {
            resources.close();
        }
    }

    private void requireListenerSlot() {
        if (closed) throw new IllegalStateException("proxy session closed");
        int count = localForwards.size() + reversePorts.size()
                + (socks5Proxy != null && socks5Proxy.isRunning() ? 1 : 0)
                + (httpProxy != null && httpProxy.isRunning() ? 1 : 0);
        if (count >= maxListeners) throw new IllegalStateException("proxy session listener limit reached: " + maxListeners);
    }

    private Socks5ProxyStatistics.StatisticsSnapshot snapshot(Socks5ProxyStatistics statistics) {
        return statistics == null ? null : statistics.getSnapshot();
    }

    private void requirePort(int port, String name) {
        if (port < 1 || port > 65535) throw new IllegalArgumentException(name + "必须在1到65535之间");
    }

    private void requireTarget(String host, int port, String hostParameterName, String portParameterName) {
        if (host == null || host.isBlank() || host.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(hostParameterName + "不能为空");
        }
        requirePort(port, portParameterName);
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
