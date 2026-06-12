package org.leo.core.net.impl;

import org.leo.core.net.Communication;
import org.leo.core.util.request.RefererGenerator;
import org.leo.core.util.request.UserAgentGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.*;
import java.io.*;
import java.lang.reflect.Field;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

public class HttpChunkedCommunication implements Communication, Runnable, java.io.Closeable {
    private static final Logger logger = LoggerFactory.getLogger(HttpChunkedCommunication.class);

    // 重连配置
    private static final int MAX_RECONNECT_ATTEMPTS = 5;
    private static final long RECONNECT_INTERVAL_MS = 3000;

    // SSL trust-all 缓存
    private static volatile SSLSocketFactory trustAllSslSocketFactory;
    private static final HostnameVerifier trustAllHostnameVerifier = new HostnameVerifier() {
        @Override
        public boolean verify(String hostname, SSLSession session) {
            return true;
        }
    };
    
    private final String url;
    private final String method;
    private final Map<String, String> headers;
    private final Proxy proxy;
    
    private DataInputStream dataInputStream;
    private DataOutputStream dataOutputStream;
    private OutputStream outputStream;
    private InputStream inputStream;
    private HttpURLConnection httpURLConnection;
    
    private final AtomicLong sendTime = new AtomicLong(System.currentTimeMillis());
    private Thread heartBeat;
    private volatile boolean isClose;
    private volatile int reconnectAttempts = 0;

    public HttpChunkedCommunication(String url, String method, Map<String, String> headers, Proxy proxy) throws Exception {
        this.url = url;
        this.method = method != null ? method : "POST";
        this.headers = headers;
        this.proxy = proxy;
        newConn();
    }

    public void newConn() throws Exception {
        try {
            // 如果心跳线程正在运行，先停止它
            if (heartBeat != null && heartBeat.isAlive()) {
                heartBeat.interrupt();
                try {
                    heartBeat.join(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            
            URL urlObj = new URL(url);
            httpURLConnection = (HttpURLConnection) (proxy == null 
                    ? urlObj.openConnection() 
                    : urlObj.openConnection(proxy));

            configureConnection(httpURLConnection);
            httpURLConnection.connect();
            
            outputStream = httpURLConnection.getOutputStream();
            manipulateOutputStreamState(outputStream);
            
            inputStream = getInputStream(httpURLConnection);
            
            initializeStreams();
            startHeartbeat();
            sendTime.set(System.currentTimeMillis());
        } catch (Exception e) {
            // 重连失败时不关闭连接，让调用者决定
            if (reconnectAttempts == 0) {
                // 首次连接失败才关闭
                close();
            }
            throw e;
        }
    }
    
    private void configureConnection(HttpURLConnection conn) throws Exception {
        // HTTPS 忽略证书验证
        if (conn instanceof HttpsURLConnection) {
            HttpsURLConnection httpsConn = (HttpsURLConnection) conn;
            httpsConn.setSSLSocketFactory(getTrustAllSslSocketFactory());
            httpsConn.setHostnameVerifier(trustAllHostnameVerifier);
        }

        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setDoInput(true);

        // 设置必需的 headers
        conn.setRequestProperty("Transfer-Encoding", "chunked");

        // 确保默认 Header 先写入 map（首次连接时也能发出去）
        if (this.getHeader("User-Agent") == null) {
            this.addHeader("User-Agent", UserAgentGenerator.generateRandomUserAgent());
        }
        if (this.getHeader("Referer") == null) {
            this.addHeader("Referer", RefererGenerator.generateRandomReferer(this.getUrl()));
        }
        if (this.getHeader("Accept-Encoding") == null) {
            this.addHeader("Accept-Encoding", "gzip, deflate");
        }

        // 添加用户自定义的 headers
        if (headers != null && !headers.isEmpty()) {
            synchronized (headers) {
                for (Map.Entry<String, String> entry : headers.entrySet()) {
                    String key = entry.getKey();
                    String value = entry.getValue();
                    if (key != null && value != null) {
                        // 避免覆盖系统必需的 headers
                        String keyLower = key.toLowerCase();
                        if (!"transfer-encoding".equals(keyLower) && !"content-type".equals(keyLower)) {
                            conn.setRequestProperty(key, value);
                            logger.debug("Setting request header: {} = {}", key, value);
                        }
                    }
                }
            }
        }

        conn.setConnectTimeout(1000);
        conn.setReadTimeout(1000);
        conn.setChunkedStreamingMode(4096 * 2);
    }
    
    private void manipulateOutputStreamState(OutputStream os) throws NoSuchFieldException, IllegalAccessException {
        Field closedField = os.getClass().getDeclaredField("closed");
        closedField.setAccessible(true);
        closedField.set(os, true);
    }
    
    private InputStream getInputStream(HttpURLConnection conn) {
        try {
            return conn.getInputStream();
        } catch (IOException e) {
            logger.debug("Failed to get input stream, trying error stream", e);
            return conn.getErrorStream();
        }
    }
    
    private void initializeStreams() throws NoSuchFieldException, IllegalAccessException, IOException {
        dataOutputStream = new DataOutputStream(outputStream);
        dataInputStream = new DataInputStream(inputStream);
        dataOutputStream.flush();
        
        // 恢复输出流状态
        Field closedField = outputStream.getClass().getDeclaredField("closed");
        closedField.setAccessible(true);
        closedField.set(outputStream, false);
        
        isClose = false;
    }
    
    private void startHeartbeat() {
        heartBeat = new Thread(this, "HttpChunkedCommunication-Heartbeat");
        heartBeat.setDaemon(true);
        heartBeat.start();
    }

    @Override
    public synchronized byte[] sendRequest(byte[] data) throws Exception {
        sendData(data);
        return receiveData();
    }

    public synchronized void sendData(byte[] data) throws IOException {
        if (isClose) {
            throw new IOException("Connection is closed");
        }
        try {
            ensureConnected();
            sendTime.set(System.currentTimeMillis());
            dataOutputStream.writeInt(data.length);
            dataOutputStream.write(data);
            dataOutputStream.flush();
        } catch (IOException e) {
            logger.warn("Send data failed, attempting reconnect", e);
            if (reconnect()) {
                // 重连成功后重试发送
                sendTime.set(System.currentTimeMillis());
                dataOutputStream.writeInt(data.length);
                dataOutputStream.write(data);
                dataOutputStream.flush();
            } else {
                throw new IOException("Failed to send data after reconnect attempts", e);
            }
        }
    }

    public synchronized void heartbeat() throws Exception {
        long currentTime = System.currentTimeMillis();
        if ((currentTime - sendTime.get()) > 5000) {
            logger.debug("Sending heartbeat");
            try {
                byte[] heartbeatBytes = "heartbeat".getBytes("UTF-8");
                sendData(heartbeatBytes);
                receiveData();
                // 心跳成功，重置重连计数
                reconnectAttempts = 0;
            } catch (Exception e) {
                logger.warn("Heartbeat failed", e);
                // 心跳失败时尝试重连
                if (!reconnect()) {
                    throw e;
                }
            }
        }
        Thread.sleep(6000);
    }

    public byte[] receiveData() throws IOException {
        if (isClose) {
            throw new IOException("Connection is closed");
        }
        
        try {
            ensureConnected();
            int dataLen = dataInputStream.readInt();
            if (dataLen < 0 || dataLen > 10 * 1024 * 1024) {
                throw new IOException("Invalid data length: " + dataLen);
            }
            byte[] data = new byte[dataLen];
            dataInputStream.readFully(data);
            return data;
        } catch (IOException e) {
            logger.warn("Receive data failed, attempting reconnect", e);
            if (reconnect()) {
                // 重连成功后重试接收
                int dataLen = dataInputStream.readInt();
                if (dataLen < 0 || dataLen > 10 * 1024 * 1024) {
                    throw new IOException("Invalid data length: " + dataLen);
                }
                byte[] data = new byte[dataLen];
                dataInputStream.readFully(data);
                return data;
            } else {
                throw new IOException("Failed to receive data after reconnect attempts", e);
            }
        }
    }
    public void addHeader(String key, String value) {
        if (key == null || value == null) {
            return;
        }
        if (this.headers != null) {
            synchronized (this.headers) {
                this.headers.put(key, value);
            }
        }
    }

    public String getHeader(String key) {
        if (key == null || this.headers == null) {
            return null;
        }
        synchronized (this.headers) {
            return this.headers.get(key);
        }
    }
    public String getUrl() {
        return url;
    }

    public String getMethod() {
        return method;
    }

    public Map<String, String> getHeaders() {
        return headers;
    }

    public Proxy getProxy() {
        return proxy;
    }

    public OutputStream getOutputStream() {
        return outputStream;
    }

    public DataInputStream getDataInputStream() {
        return dataInputStream;
    }

    public DataOutputStream getDataOutputStream() {
        return dataOutputStream;
    }

    public long getSendTime() {
        return sendTime.get();
    }

    public void setSendTime(long sendTime) {
        this.sendTime.set(sendTime);
    }

    public Thread getHeartBeat() {
        return heartBeat;
    }

    public InputStream getInputStream() {
        return inputStream;
    }

    public boolean isClose() {
        return isClose;
    }


    @Override
    public void run() {
        int count = 0;
        try {
            while (!isClose && count < 10) {
                heartbeat();
                count++;
            }
        } catch (InterruptedException e) {
            logger.debug("Heartbeat thread interrupted");
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            logger.warn("Heartbeat thread error", e);
        } finally {
            isClose = true;
        }
    }
    
    /**
     * 关闭连接并释放资源
     */
    public void close() {
        if (isClose) {
            return;
        }
        
        isClose = true;
        
        // 中断心跳线程
        if (heartBeat != null && heartBeat.isAlive()) {
            heartBeat.interrupt();
        }
        
        // 关闭流
        closeQuietly(dataInputStream);
        closeQuietly(dataOutputStream);
        closeQuietly(inputStream);
        closeQuietly(outputStream);
        
        // 断开连接
        if (httpURLConnection != null) {
            httpURLConnection.disconnect();
        }
        
        logger.debug("Connection closed");
    }
    
    private void closeQuietly(Closeable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (IOException e) {
                logger.debug("Error closing stream", e);
            }
        }
    }
    
    /**
     * 确保连接有效
     * @throws IOException 如果连接无效且无法重连
     */
    private void ensureConnected() throws IOException {
        if (dataInputStream == null || dataOutputStream == null || isClose) {
            throw new IOException("Connection is not available");
        }
    }
    
    /**
     * 尝试重连
     * @return 重连是否成功
     */
    private synchronized boolean reconnect() {
        if (isClose) {
            return false;
        }

        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            logger.error("Max reconnect attempts ({}) reached, giving up", MAX_RECONNECT_ATTEMPTS);
            return false;
        }

        reconnectAttempts++;
        logger.info("Attempting to reconnect (attempt {}/{})", reconnectAttempts, MAX_RECONNECT_ATTEMPTS);

        // 关闭旧连接
        closeQuietly(dataInputStream);
        closeQuietly(dataOutputStream);
        closeQuietly(inputStream);
        closeQuietly(outputStream);
        if (httpURLConnection != null) {
            httpURLConnection.disconnect();
        }

        // 等待一段时间后重连
        try {
            Thread.sleep(RECONNECT_INTERVAL_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warn("Reconnect sleep interrupted");
            return false;
        }

        // 尝试重新建立连接
        try {
            newConn();
            logger.info("Reconnect successful");
            reconnectAttempts = 0;
            return true;
        } catch (Exception e) {
            logger.warn("Reconnect attempt {} failed", reconnectAttempts, e);
            return false;
        }
    }

    static SSLSocketFactory getTrustAllSslSocketFactory() throws Exception {
        SSLSocketFactory current = trustAllSslSocketFactory;
        if (current != null) {
            return current;
        }
        synchronized (HttpChunkedCommunication.class) {
            if (trustAllSslSocketFactory == null) {
                TrustManager[] trustAllCerts = new TrustManager[]{
                        new X509TrustManager() {
                            @Override
                            public void checkClientTrusted(X509Certificate[] chain, String authType) {
                            }

                            @Override
                            public void checkServerTrusted(X509Certificate[] chain, String authType) {
                            }

                            @Override
                            public X509Certificate[] getAcceptedIssuers() {
                                return new X509Certificate[0];
                            }
                        }
                };
                SSLContext ctx = SSLContext.getInstance("TLS");
                ctx.init(null, trustAllCerts, new SecureRandom());
                trustAllSslSocketFactory = ctx.getSocketFactory();
            }
            return trustAllSslSocketFactory;
        }
    }
}
