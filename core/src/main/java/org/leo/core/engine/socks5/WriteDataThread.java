package org.leo.core.engine.socks5;


import org.leo.core.puppet.impl.JavaPuppetNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

public class WriteDataThread implements Runnable{

    private static final Logger logger = LoggerFactory.getLogger(WriteDataThread.class);

    private Socket clientSocket;
    private String connId;
    private JavaPuppetNode javaPuppetNode;
    private Socks5ProxyStatistics statistics;

    public WriteDataThread(Socket clientSocket, String connId, JavaPuppetNode javaPuppetNode, Socks5ProxyStatistics statistics) {
        this.clientSocket = clientSocket;
        this.connId = connId;
        this.javaPuppetNode = javaPuppetNode;
        this.statistics = statistics;
    }

    @Override
    public void run() {
        while (true){
            if (this.clientSocket==null){
                closeRemoteSocket(connId);
                return;
            }
            if (this.clientSocket.isClosed()){
                closeRemoteSocket(connId);
                return;
            }
            byte[] data = new byte[65535];
                try {
                    int length = this.clientSocket.getInputStream().read(data);
                    if (length == -1) {
                        // 流结束，退出循环
                        closeRemoteSocket(connId);
                        this.clientSocket.close();
                        break;
                    }
                    data = Arrays.copyOfRange(data, 0, length);
                    logger.debug("写入数据: connId={}, size={}", connId, data.length);
                    // 记录上传数据量
                    if (statistics != null && connId != null) {
                        statistics.addUploadBytes(connId, data.length);
                    }
                    Map<String, Object> params = new HashMap<String, Object>();
                    params.put("op", 1);
                    params.put("connId", connId);
                    params.put("data", data);
                    Map<String, Object> res = javaPuppetNode.invokeComponent("ProxyForwardComponent", params);
                    int code= (int) res.get("code");
                    if (code==404){
                        logger.debug("对端关闭连接: connId={}", this.connId);
                        closeRemoteSocket(connId);
                        break;
                    }
                    if (code==500){
                        logger.debug("puppet 端写超时，连接已关闭: connId={}", this.connId);
                        break;
                    }
                } catch (SocketTimeoutException e) {
                    // HTTP 隧道暂时没数据
                    continue;
                } catch (SocketException socketException){
                    break;

                }catch (Exception e) {
                    logger.error("写入数据异常: connId={}", connId,e);
                    break;
                }
            }
    }
    private boolean closeRemoteSocket(String connId) {
        try {
            logger.debug("关闭远程Socket: connId={}", connId);
            Map<String, Object> params = new HashMap<String, Object>();
            params.put("op", 3);
            params.put("connId", connId);
            javaPuppetNode.invokeComponent("ProxyForwardComponent", params);
        } catch (Exception e) {
            logger.debug("关闭远程Socket失败: connId={}", connId, e);
            return false;
        }
        return true;
    }
}
