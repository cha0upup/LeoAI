package org.leo.core.engine.http;

import org.leo.core.engine.proxy.LocalProxyServer;
import org.leo.core.engine.proxy.ProxyConnection;
import org.leo.core.puppet.capability.ComponentInvokeCapable;

/** Local HTTP/CONNECT listener; target sockets are opened through the node component. */
public class HttpProxyServer extends LocalProxyServer {
    public HttpProxyServer(ComponentInvokeCapable node, int listenPort) {
        this(node, listenPort, null);
    }

    public HttpProxyServer(ComponentInvokeCapable node, int listenPort, String bindAddr) {
        super(node, listenPort, bindAddr, 30000);
    }

    @Override
    protected void handle(ProxyConnection connection) {
        new HttpProxyHandleClientThread(connection).run();
    }
}
