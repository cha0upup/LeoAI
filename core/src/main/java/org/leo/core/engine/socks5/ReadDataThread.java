package org.leo.core.engine.socks5;

import org.leo.core.engine.proxy.ProxyConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ReadDataThread implements Runnable {
    private static final Logger log = LoggerFactory.getLogger(ReadDataThread.class);
    private final ProxyConnection connection;

    public ReadDataThread(ProxyConnection connection) {
        this.connection = connection;
    }

    @Override
    public void run() {
        long idleDelay = 100L;
        try {
            while (!connection.isClosed()) {
                byte[] data = connection.read();
                if (data == null || data.length == 0) {
                    Thread.sleep(idleDelay);
                    idleDelay = Math.min(idleDelay * 2, 800L);
                    continue;
                }
                connection.socket().getOutputStream().write(data);
                connection.socket().getOutputStream().flush();
                connection.recordDownload(data.length);
                idleDelay = 100L;
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        } catch (Exception error) {
            if (!connection.isClosed()) log.debug("代理读取结束: {}", error.getMessage());
        } finally {
            // Wake the upload direction; its handler owns remote cleanup.
            connection.closeLocal();
        }
    }
}
