package org.leo.core.engine.forward;

import org.leo.core.engine.proxy.ProxyConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LocalForwardHandleClientThread implements Runnable {
    private static final Logger log = LoggerFactory.getLogger(LocalForwardHandleClientThread.class);
    private final ProxyConnection connection;
    private final String targetHost;
    private final int targetPort;

    public LocalForwardHandleClientThread(ProxyConnection connection, String targetHost, int targetPort) {
        this.connection = connection;
        this.targetHost = targetHost;
        this.targetPort = targetPort;
    }

    @Override
    public void run() {
        try (connection) {
            if (connection.open(targetHost, targetPort)) connection.relay();
        } catch (Exception error) {
            log.debug("本地转发处理异常: {}", error.getMessage());
        }
    }
}
