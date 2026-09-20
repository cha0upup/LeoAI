package org.leo.core.engine.forward;

import org.leo.core.engine.proxy.LocalProxyServer;
import org.leo.core.engine.proxy.ProxyConnection;
import org.leo.core.puppet.capability.ComponentInvokeCapable;

/** Local listener forwarding to a fixed target reached from the node. */
public class LocalForwardServer extends LocalProxyServer {
    private final String targetHost;
    private final int targetPort;

    public LocalForwardServer(ComponentInvokeCapable node, int localPort, String targetHost, int targetPort) {
        this(node, localPort, null, targetHost, targetPort);
    }

    public LocalForwardServer(ComponentInvokeCapable node, int localPort, String bindAddr,
                              String targetHost, int targetPort) {
        super(node, localPort, bindAddr, 0);
        this.targetHost = targetHost;
        this.targetPort = targetPort;
    }

    public int getLocalPort() { return getListenPort(); }
    public String getTargetHost() { return targetHost; }
    public int getTargetPort() { return targetPort; }

    @Override
    protected void handle(ProxyConnection connection) {
        new LocalForwardHandleClientThread(connection, targetHost, targetPort).run();
    }
}
