package org.leo.core.engine.socks5;

import org.leo.core.engine.proxy.ProxyConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;

public class WriteDataThread implements Runnable {
    private static final Logger log = LoggerFactory.getLogger(WriteDataThread.class);
    private final ProxyConnection connection;

    public WriteDataThread(ProxyConnection connection) {
        this.connection = connection;
    }

    @Override
    public void run() {
        byte[] buffer = new byte[65536];
        try {
            while (!connection.isClosed()) {
                int length = connection.socket().getInputStream().read(buffer);
                if (length < 0) break;
                if (length > 0) connection.write(Arrays.copyOf(buffer, length));
            }
        } catch (Exception error) {
            if (!connection.isClosed()) log.debug("代理写入结束: {}", error.getMessage());
        } finally {
            connection.closeLocal();
        }
    }
}
