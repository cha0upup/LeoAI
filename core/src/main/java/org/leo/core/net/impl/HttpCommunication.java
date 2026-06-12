package org.leo.core.net.impl;

import okhttp3.*;
import org.leo.core.net.Communication;
import org.leo.core.net.layer.TlsFingerprintStrategy;
import org.leo.core.util.request.RefererGenerator;
import org.leo.core.util.request.UserAgentGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.*;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.Proxy;
import java.security.cert.X509Certificate;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;

/**
 * HTTP通信实现类
 * 使用OkHttp实现HTTP/HTTPS通信，支持代理和SSL证书验证绕过
 * 
 * @author LeoSpring
 * @version 2.0
 */
public class HttpCommunication implements Communication {

    private static final Logger logger = LoggerFactory.getLogger(HttpCommunication.class);

    // HTTP方法常量
    private static final String METHOD_GET = "GET";
    private static final String METHOD_POST = "POST";
    private static final String METHOD_PUT = "PUT";
    private static final String METHOD_DELETE = "DELETE";
    
    // 协议常量
    private static final String PROTOCOL_TLS = "TLS";

    // 连接池配置常量
    private static final int CONNECTION_POOL_SIZE = 10;
    private static final int CONNECTION_POOL_KEEP_ALIVE_MINUTES = 5;
    
    // 超时配置常量（秒）
    private static final int CONNECT_TIMEOUT_SECONDS = 10;
    private static final int READ_TIMEOUT_SECONDS = 30;
    private static final int WRITE_TIMEOUT_SECONDS = 30;

    private volatile OkHttpClient httpClient;

    private final String url;
    private final String method;
    private final Map<String, String> headers;
    private final Proxy proxy;

    /** TLS 指纹伪装策略 */
    private TlsFingerprintStrategy tlsFingerprintStrategy;

    /** per-request URL override（线程安全：每次请求前设置，请求后清除） */
    private final ThreadLocal<String> requestUrlOverride = new ThreadLocal<>();

    /** per-request 噪声 Header（每次请求前设置，用完清除） */
    private final ThreadLocal<Map<String, String>> requestNoiseHeaders = new ThreadLocal<>();

    /**
     * 构造函数
     *
     * @param url 请求URL
     * @param method HTTP方法，如果为空则默认为POST
     * @param headers HTTP请求头
     * @param proxy 代理设置，可以为null
     */
    public HttpCommunication(String url, String method, Map<String, String> headers, Proxy proxy) {
        this.url = url;
        this.method = (method == null || method.equals("")) ? METHOD_POST : method.toUpperCase();
        this.headers = headers != null ? new ConcurrentHashMap<>(headers) : new ConcurrentHashMap<>();
        this.proxy = proxy;

        if (this.getHeader("User-Agent") == null) {
            this.addHeader("User-Agent", UserAgentGenerator.generateRandomUserAgent());
        }
        if (this.getHeader("Referer") == null) {
            this.addHeader("Referer", RefererGenerator.generateRandomReferer(this.getUrl()));
        }
        if (this.getHeader("Accept-Encoding") == null) {
            this.addHeader("Accept-Encoding", "gzip, deflate");
        }
        // httpClient 延迟初始化：等 TLS 策略设置完毕后，首次 sendRequest 时构建
    }

    private void initClient() {
        if (httpClient != null) return;

        synchronized (this) {
            if (httpClient != null) return;
            try {
                // 忽略所有 HTTPS 证书
                TrustManager[] trustAllCerts = new TrustManager[]{
                        new X509TrustManager() {

                            public void checkClientTrusted(X509Certificate[] xcs, String s) { }


                            public void checkServerTrusted(X509Certificate[] xcs, String s) { }


                            public X509Certificate[] getAcceptedIssuers() {
                                return new X509Certificate[]{};
                            }
                        }
                };

                SSLContext sslContext = SSLContext.getInstance(PROTOCOL_TLS);
                sslContext.init(null, trustAllCerts, new java.security.SecureRandom());
                SSLSocketFactory sslSocketFactory = sslContext.getSocketFactory();

                OkHttpClient.Builder builder = new OkHttpClient.Builder()
                        .sslSocketFactory(sslSocketFactory, (X509TrustManager) trustAllCerts[0])
                        .hostnameVerifier(new HostnameVerifier() {
                            @Override
                            public boolean verify(String hostname, SSLSession session) {
                                return true;
                            }
                        });

                // TLS 指纹伪装：自定义 cipher suites 和协议版本
                if (tlsFingerprintStrategy != null && tlsFingerprintStrategy.isEnabled()) {
                    String[] cipherSuites = tlsFingerprintStrategy.getCipherSuites();
                    String[] protocols = tlsFingerprintStrategy.getProtocols();
                    ConnectionSpec customSpec = new ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
                            .cipherSuites(cipherSuites)
                            .tlsVersions(protocols)
                            .build();
                    builder.connectionSpecs(java.util.Arrays.asList(customSpec, ConnectionSpec.CLEARTEXT));
                }

                // 支持代理
                if (proxy != null) {
                    builder.proxy(proxy);
                }

                // 连接池配置
                builder.connectionPool(new ConnectionPool(CONNECTION_POOL_SIZE, CONNECTION_POOL_KEEP_ALIVE_MINUTES, TimeUnit.MINUTES));

                // 超时设置
                builder.connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                        .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS);

                // 响应体容错：吸收 chunked 截断 / Content-Length 不一致 / 对端提前关流
                // 等 HTTP 帧层异常，把已收到的主体字节交给应用层
                builder.addInterceptor(new TolerantBodyInterceptor());

                httpClient = builder.build();

            } catch (Exception e) {
                logger.error("初始化OkHttpClient失败", e);
                throw new RuntimeException("Failed to init OkHttpClient", e);
            }
        }
    }

    // -----------------------
    //   Communication 接口实现
    // -----------------------

    @Override
    public byte[] sendRequest(byte[] data) throws Exception {
        // 确保 httpClient 已初始化
        if (httpClient == null) {
            initClient();
        }
        if (httpClient == null) {
            throw new IllegalStateException("HttpClient is not initialized");
        }

        // 使用 override URL（如果设置了），否则使用默认 URL
        String targetUrl = requestUrlOverride.get();
        if (targetUrl == null || targetUrl.isEmpty()) {
            targetUrl = url;
        } else {
            requestUrlOverride.remove(); // 用完即清
        }

        RequestBody body = null;

        if (!"GET".equalsIgnoreCase(method)) {
            if (data == null) data = new byte[0];
            body = RequestBody.create(data);
        }

        Request.Builder builder = new Request.Builder().url(targetUrl);

        // 设置 headers
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            builder.addHeader(entry.getKey(), entry.getValue());
        }

        // 注入噪声 Header（一次性，用完清除）
        Map<String, String> noiseHeaders = requestNoiseHeaders.get();
        if (noiseHeaders != null && !noiseHeaders.isEmpty()) {
            for (Map.Entry<String, String> entry : noiseHeaders.entrySet()) {
                builder.addHeader(entry.getKey(), entry.getValue());
            }
            requestNoiseHeaders.remove();
        }

        // 设置 method
        if (METHOD_GET.equalsIgnoreCase(method)) {
            builder.get();
        } else if (METHOD_POST.equalsIgnoreCase(method)) {
            builder.post(body);
        } else {
            builder.method(method, body);
        }

        Request request = builder.build();

        // 执行请求
        Response response = null;
        try {
            response = httpClient.newCall(request).execute();
            ResponseBody responseBody = response.body();
            if (responseBody == null) {
                logger.warn("[HttpCommunication] 响应体为 null url={} status={}", url, response.code());
                return new byte[0];
            }
            // TolerantBodyInterceptor 已在前置阶段吸收 chunked 截断 / Content-Length 不一致 /
            // 对端提前关流等 HTTP 帧层异常，此处 bytes() 调用读到的是干净的内存 Buffer，不会再
            // 触发 chunked 解析路径，因此不会抛 EOFException。
            byte[] rawBytes = responseBody.bytes();
            String contentEncoding = response.header("Content-Encoding");
            logger.debug("[HttpCommunication] 响应 status={} Content-Encoding={} rawLen={}",
                    response.code(), contentEncoding, rawBytes.length);
            if ("gzip".equalsIgnoreCase(contentEncoding)) {
                if (rawBytes.length == 0) {
                    logger.warn("[HttpCommunication] Content-Encoding:gzip 但响应体为空 url={}", url);
                    return rawBytes;
                }
                try (ByteArrayInputStream bais = new ByteArrayInputStream(rawBytes);
                     GZIPInputStream gzip = new GZIPInputStream(bais);
                     ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[8192];
                    int len;
                    while ((len = gzip.read(buffer)) != -1) {
                        baos.write(buffer, 0, len);
                    }
                    rawBytes = baos.toByteArray();
                } catch (java.io.EOFException eof) {
                    // gzip trailer 截断：极少数情况，TolerantBodyInterceptor 之后仍可能因
                    // 原始 gzip 字节流缺末尾 CRC32/ISIZE 而抛 EOF，记录后兜底返回已解压字节
                    logger.warn("[HttpCommunication] gzip trailer 截断，已忽略 url={}", url, eof);
                }
            }
            return rawBytes;
        } finally {
            if (response != null) {
                response.close();
            }
        }
    }

    // -----------------------
    //  工具方法
    // -----------------------

    /**
     * 添加HTTP请求头
     */
    public void addHeader(String key, String value) {
        if (key != null && value != null) {
            this.headers.put(key, value);
        }
    }

    /**
     * 获取HTTP请求头
     */
    public String getHeader(String key) {
        return this.headers.get(key);
    }

    /**
     * 获取请求URL
     */
    public String getUrl() {
        return url;
    }

    /**
     * 设置下一次请求使用的 URL（一次性，用完自动清除）。
     * 用于 URL 随机化场景，调用后下一次 sendRequest 使用此 URL。
     */
    public void setRequestUrl(String overrideUrl) {
        if (overrideUrl != null && !overrideUrl.isEmpty()) {
            requestUrlOverride.set(overrideUrl);
        }
    }

    /**
     * 设置下一次请求附加的噪声 Header（一次性，用完自动清除）。
     * 用于 Header 噪声注入场景。
     */
    public void setRequestNoiseHeaders(Map<String, String> noiseHeaders) {
        if (noiseHeaders != null && !noiseHeaders.isEmpty()) {
            requestNoiseHeaders.set(noiseHeaders);
        }
    }

    /**
     * 设置 TLS 指纹伪装策略。
     * httpClient 采用延迟初始化，设置策略后下次 sendRequest 会使用新配置构建 client。
     */
    public void setTlsFingerprintStrategy(TlsFingerprintStrategy strategy) {
        this.tlsFingerprintStrategy = strategy;
        // 清空现有 client，下次 sendRequest 时按新策略重建
        this.httpClient = null;
    }

    public TlsFingerprintStrategy getTlsFingerprintStrategy() {
        return tlsFingerprintStrategy;
    }

    /**
     * 获取代理设置
     */
    public Proxy getProxy() {
        return proxy;
    }
}
