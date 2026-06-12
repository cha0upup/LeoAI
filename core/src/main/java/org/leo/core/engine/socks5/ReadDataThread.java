package org.leo.core.engine.socks5;


import org.leo.core.puppet.impl.JavaPuppetNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.Socket;
import java.util.HashMap;
import java.util.Map;

public class ReadDataThread implements Runnable {
    private static final Logger logger = LoggerFactory.getLogger(ReadDataThread.class);

    private final Socket clientSocket;
    private final String connId;
    private final JavaPuppetNode javaPuppetNode;
    private final Socks5ProxyStatistics statistics;

    public ReadDataThread(Socket clientSocket, String connId, JavaPuppetNode javaPuppetNode, Socks5ProxyStatistics statistics) {
        this.clientSocket = clientSocket;
        this.connId = connId;
        this.javaPuppetNode = javaPuppetNode;
        this.statistics = statistics;
    }

    @Override
    public void run() {
        long idleDelay = 100L;
        while (true) {
            if (this.clientSocket == null) {
                return;
            }
            if (this.clientSocket.isClosed()) {
                return;
            }
            try {
                Map<String, Object> params = new HashMap<String, Object>();
                params.put("op", 2);
                params.put("connId", this.connId);
                Map<String, Object> res = javaPuppetNode.invokeComponent("ProxyForwardComponent", params);
                int code = (int) res.get("code");
                if (code == 200) {
                    byte[] data = (byte[]) res.get("data");
                    logger.debug("读取数据: connId={}, size={}", this.connId, data.length);
                    if (data.length > 0) {
                        this.clientSocket.getOutputStream().write(data);
                        this.clientSocket.getOutputStream().flush();
                        // 记录下载数据量
                        if (statistics != null && connId != null) {
                            statistics.addDownloadBytes(connId, data.length);
                        }
                    }
                    idleDelay = 100L; // 有数据，重置退避
                    continue;
                }
                if (code == 204) {
                    Thread.sleep(idleDelay);
                    if (idleDelay < 800L) idleDelay = Math.min(idleDelay * 2, 800L);
                    continue;
                }
                if (code == 500) {
                    logger.debug((String) res.get("msg"));
                    break;
                }
                if (code == 404) {
                    //404 对端关闭退出线程
                    logger.debug("对端关闭连接: connId={}", this.connId);
                    this.clientSocket.close();
                    break;
                }
            } catch (Exception e) {
                logger.error("读取数据异常: connId={}", this.connId, e);
                return;
            }
        }

    }
}
