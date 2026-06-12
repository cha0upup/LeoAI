package org.leo.core.engine.socks5;

import org.leo.core.puppet.impl.JavaPuppetNode;
import org.leo.core.util.md5.MD5Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.util.HashMap;
import java.util.Map;

public class HandleClientThread implements Runnable{

    private static final Logger logger = LoggerFactory.getLogger(HandleClientThread.class);

    private Socket clientSocket;
    private JavaPuppetNode javaPuppetNode;
    private Socks5ProxyStatistics statistics;
    private String connId;
    private String targetHost;
    private int targetPort;

    public HandleClientThread(Socket clientSocket, JavaPuppetNode javaPuppetNode, Socks5ProxyStatistics statistics) {
        this.clientSocket = clientSocket;
        this.javaPuppetNode = javaPuppetNode;
        this.statistics = statistics;
    }

    @Override
    public void run() {
        OutputStream outputStream;
        InputStream inputStream;
        logger.debug("处理新的客户端连接: {}", clientSocket);
        try {
             outputStream = clientSocket.getOutputStream();
             inputStream = clientSocket.getInputStream();
        } catch (Exception e) {
            logger.error("获取客户端流失败", e);
            return;
        }
        if (parseSocks5(outputStream,inputStream)){
            // 握手完成后移除读超时，避免数据中继阶段无谓的 SocketTimeoutException
            try { clientSocket.setSoTimeout(0); } catch (Exception ignored) {}
            // 记录连接建立
            if (statistics != null && connId != null) {
                String clientIp = clientSocket.getInetAddress().getHostAddress();
                statistics.addConnection(connId, targetHost, targetPort, clientIp);
            }
            try {
                Thread readDataThread=new Thread(new ReadDataThread(clientSocket, connId, javaPuppetNode, statistics));
                Thread writeDataThread=new Thread(new WriteDataThread(clientSocket, connId, javaPuppetNode, statistics));
                readDataThread.start();
                writeDataThread.start();
                readDataThread.join();
                writeDataThread.join();
            } catch (InterruptedException e) {
                //closeRemoteSocket(connId);
            } finally {
                // 记录连接关闭
                if (statistics != null && connId != null) {
                    statistics.removeConnection(connId);
                }
                try { clientSocket.close(); } catch (IOException ignored) {}
            }
        }else {
            try {
                this.clientSocket.close();
            } catch (IOException ignored) {}
        }

    }
    private boolean parseSocks5(OutputStream outputStream,InputStream inputStream){

        try {
            // SOCKS5 greeting
            int ver = inputStream.read();
            if (ver != 0x05) { return false; }
            int nMethods = inputStream.read();
            for (int i = 0; i < nMethods; i++) inputStream.read();
            // no auth
            outputStream.write(new byte[]{0x05, 0x00});
            outputStream.flush();

            // request
            ver = inputStream.read();
            if (ver != 0x05) { return false; }
            int cmd = inputStream.read(); // only CONNECT
            int rsv = inputStream.read();
            int atyp = inputStream.read();
            if (atyp == 0x01) { // IPv4
                byte[] addr = new byte[4];
                readFully(inputStream, addr);
                this.targetHost = (addr[0] & 0xFF) + "." + (addr[1] & 0xFF) + "." + (addr[2] & 0xFF) + "." + (addr[3] & 0xFF);
            } else if (atyp == 0x03) { // DOMAIN
                int len = inputStream.read();
                byte[] addr = new byte[len];
                readFully(inputStream, addr);
                this.targetHost = new String(addr, "UTF-8");
            } else if (atyp == 0x04) { // IPv6
                byte[] addr = new byte[16];
                readFully(inputStream, addr);
                this.targetHost = InetAddress.getByAddress(addr).getHostAddress();
            } else {
                return false;
            }
            this.targetPort = ((inputStream.read() & 0xFF) << 8) | (inputStream.read() & 0xFF);
            this.connId= MD5Utils.getMD5Hash("" + clientSocket.getInetAddress() + clientSocket.getPort() + this.targetHost + this.targetPort + System.nanoTime() + "");


            if (cmd != 0x01) { // only CONNECT
                // reply: command not supported
                outputStream.write(new byte[]{0x05, 0x07, 0x00, 0x01,0,0,0,0,0,0});
                outputStream.flush();
                return false;
            }
            // open remote connection via component
            if (openRemoteSocket(this.targetHost,this.targetPort,this.connId)){
                outputStream.write(new byte[]{0x05, 0x00, 0x00, 0x01,0,0,0,0,0,0});
                outputStream.flush();
            }else {
                outputStream.write(new byte[]{0x05, 0x01, 0x00, 0x01,0,0,0,0,0,0});
                outputStream.flush();
                return false;
            }

        } catch (Exception e) {
            return false;
        }
        return true;
    }
    private void readFully(InputStream in, byte[] buf) throws Exception {
        int off = 0;
        int n;
        while (off < buf.length && (n = in.read(buf, off, buf.length - off)) != -1) {
            off += n;
        }
        if (off < buf.length) throw new Exception("unexpected EOF");
    }

    private boolean openRemoteSocket(String targetHost, int targetPort, String connId) {
         try {
             logger.info("连接到远程主机: {}:{}, connId: {}", targetHost, targetPort, connId);
             Map<String, Object> params = new HashMap<String, Object>();
             params.put("op", 0);
             params.put("targetHost", targetHost);
             params.put("targetPort", targetPort);
             params.put("connId", connId);
             Map<String, Object> res = javaPuppetNode.invokeComponent("ProxyForwardComponent", params);
             int code= (int) res.get("code");
             if (code==200){
                 return true;
             }
        } catch (Exception e) {
            logger.error("打开远程Socket失败: {}:{}", targetHost, targetPort);
            return false;
        }
        logger.error("打开远程Socket失败: {}:{}", targetHost, targetPort);
        return false;
    }
}
