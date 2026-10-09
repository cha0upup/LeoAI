package org.leo.core.engine.socks5;

import org.leo.core.engine.proxy.LocalProxyServer;
import org.leo.core.engine.proxy.ProxyConnection;
import org.leo.core.engine.proxy.ProxySessionResources;
import org.leo.core.agent.capability.ComponentInvokeCapable;

public class Socks5ProxyServer extends LocalProxyServer {
    public Socks5ProxyServer(ComponentInvokeCapable node, int listenPort) {
        this(node, listenPort, null);
    }

    public Socks5ProxyServer(ComponentInvokeCapable node, int listenPort, String bindAddr) {
        this(node, listenPort, bindAddr, null);
    }

    public Socks5ProxyServer(ComponentInvokeCapable node, int listenPort, String bindAddr,
                            ProxySessionResources resources) {
        super(node, listenPort, bindAddr, 15000, 200, resources);
    }

    @Override
    protected void handle(ProxyConnection connection) {
        new HandleClientThread(connection).run();
    }
}
