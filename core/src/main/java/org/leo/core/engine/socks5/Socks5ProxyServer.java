package org.leo.core.engine.socks5;

import org.leo.core.engine.proxy.LocalProxyServer;
import org.leo.core.engine.proxy.ProxyConnection;
import org.leo.core.puppet.capability.ComponentInvokeCapable;

public class Socks5ProxyServer extends LocalProxyServer {
    public Socks5ProxyServer(ComponentInvokeCapable node, int listenPort) {
        this(node, listenPort, null);
    }

    public Socks5ProxyServer(ComponentInvokeCapable node, int listenPort, String bindAddr) {
        super(node, listenPort, bindAddr, 15000);
    }

    @Override
    protected void handle(ProxyConnection connection) {
        new HandleClientThread(connection).run();
    }
}
