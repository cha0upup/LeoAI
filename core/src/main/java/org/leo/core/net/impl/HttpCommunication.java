package org.leo.core.net.impl;

import okhttp3.*;
import org.leo.core.net.Communication;
import org.leo.core.net.TransportException;
import org.leo.core.net.TransportLimits;
import org.leo.core.net.layer.TlsFingerprintStrategy;
import org.leo.core.util.request.RefererGenerator;
import org.leo.core.util.request.UserAgentGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.*;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.Proxy;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/**
 * HTTP通信实现类
 * 使用OkHttp实现HTTP/HTTPS通信，支持代理和SSL证书验证绕过
 * 
 * @author LeoSpring
 * @version 2.0
 */
public class HttpCommunication implements Communication, java.io.Closeable {

    private static final Logger logger = LoggerFactory.getLogger(HttpCommunication.class);

    // HTTP方法常量
    private static final String METHOD_GET = "GET";
    private static final String METHOD_POST = "POST";
    
    // 协议常量
    private static final String PROTOCOL_TLS = "TLS";

    // 连接池配置常量
    private static final int CONNECTION_POOL_SIZE = 10;
    private static final int CONNECTION_POOL_KEEP_ALIVE_MINUTES = 5;
    
    private volatile OkHttpClient httpClient;

    private final String url;
    private final String method;
    private final Map<String, String> headers;
    private final Set<String> explicitHeaderNames = ConcurrentHashMap.newKeySet();
    private final Proxy proxy;

    /** TLS 指纹伪装策略 */
    private TlsFingerprintStrategy tlsFingerprintStrategy;

    /** per-request URL override（线程安全：每次请求前设置，请求后清除） */
    private final ThreadLocal<String> requestUrlOverride = new ThreadLocal<>();

    /** per-request 噪声 Header（每次请求前设置，用完清除） */
    private final ThreadLocal<Map<String, String>> requestNoiseHeaders = new ThreadLocal<>();

    /** 会话画像 Header；按请求覆盖自动默认值，用完清除。 */
    private final ThreadLocal<Map<String, String>> requestProfileHeaders = new ThreadLocal<>();

    /** 从 Set-Cookie 学习到的 Cookie，按 name/domain/path 存储并按请求 URL 作用域匹配。 */
    private final Map<String, Cookie> responseCookies = new ConcurrentHashMap<>();

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
        this.headers.keySet().stream().filter(java.util.Objects::nonNull)
                .map(name -> name.toLowerCase(Locale.ROOT)).forEach(explicitHeaderNames::add);
        this.proxy = proxy;

        if (this.getHeader("User-Agent") == null) {
            this.headers.put("User-Agent", UserAgentGenerator.generateRandomUserAgent());
        }
        if (this.getHeader("Referer") == null) {
            this.headers.put("Referer", RefererGenerator.generateRandomReferer(this.getUrl()));
        }
        if (this.getHeader("Accept-Encoding") == null) {
            this.headers.put("Accept-Encoding", "gzip");
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
                        // Opaque RPC replay belongs to the operation/proxy policy, not HTTP follow-ups.
                        .retryOnConnectionFailure(false)
                        .followRedirects(false)
                        .followSslRedirects(false)
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
                builder.connectTimeout(TransportLimits.CONNECT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
                        .readTimeout(TransportLimits.READ_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
                        .writeTimeout(TransportLimits.WRITE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);

                // 响应体容错：吸收 chunked 截断 / Content-Length 不一致 / 对端提前关流
                // 等 HTTP 帧层异常，把已收到的主体字节交给应用层
                builder.addInterceptor(new TolerantBodyInterceptor(TransportLimits.MAX_MESSAGE_BYTES));

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
        TransportLimits.requireMessageSize(data);
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
            byte[] payload = data == null ? new byte[0] : data;
            body = new RequestBody() {
                public MediaType contentType() { return null; }
                public long contentLength() { return payload.length; }
                public boolean isOneShot() { return true; }
                public void writeTo(okio.BufferedSink sink) throws IOException { sink.write(payload); }
            };
        }

        HttpUrl requestUrl = HttpUrl.get(targetUrl);
        Request.Builder builder = new Request.Builder().url(requestUrl);
        List<String> configuredCookieHeaders = new ArrayList<>();

        // 设置 headers
        addHeaders(builder, headers, configuredCookieHeaders);

        // 会话画像覆盖自动生成的默认 Header，但显式配置始终优先。
        Map<String, String> profileHeaders = requestProfileHeaders.get();
        requestProfileHeaders.remove();
        if (profileHeaders != null) {
            for (Map.Entry<String, String> entry : profileHeaders.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null
                        && !hasExplicitHeader(entry.getKey())) {
                    builder.header(entry.getKey(), entry.getValue());
                }
            }
        }

        // 注入噪声 Header（一次性，用完清除）
        Map<String, String> noiseHeaders = requestNoiseHeaders.get();
        if (noiseHeaders != null && !noiseHeaders.isEmpty()) {
            addHeaders(builder, noiseHeaders, configuredCookieHeaders);
            requestNoiseHeaders.remove();
        }

        String cookieHeader = buildCookieHeader(requestUrl, configuredCookieHeaders);
        if (cookieHeader != null && !cookieHeader.isBlank()) {
            builder.addHeader("Cookie", cookieHeader);
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
            // HTTP 状态码由上层协议决定；即使是 401/404，也必须把响应体交给 disguise 解码器。
            storeResponseCookies(request.url(), response.headers());
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
            return decodeContent(rawBytes, contentEncoding, targetUrl);
        } finally {
            if (response != null) {
                response.close();
            }
        }
    }

    private byte[] decodeContent(byte[] rawBytes, String contentEncoding, String targetUrl)
            throws IOException {
        if (rawBytes.length == 0 || contentEncoding == null || contentEncoding.isBlank()
                || "identity".equalsIgnoreCase(contentEncoding)) {
            return rawBytes;
        }
        InputStream decoder;
        if ("gzip".equalsIgnoreCase(contentEncoding)) {
            decoder = new GZIPInputStream(new ByteArrayInputStream(rawBytes));
        } else if ("deflate".equalsIgnoreCase(contentEncoding)) {
            decoder = new InflaterInputStream(new ByteArrayInputStream(rawBytes));
        } else {
            throw new TransportException(TransportException.Reason.FRAME_INVALID,
                    "不支持的 Content-Encoding: " + contentEncoding);
        }

        ByteArrayOutputStream output = new ByteArrayOutputStream(
                Math.min(rawBytes.length * 2, TransportLimits.MAX_MESSAGE_BYTES));
        byte[] buffer = new byte[8192];
        try (InputStream input = decoder) {
            int length;
            while ((length = input.read(buffer)) != -1) {
                if (length > TransportLimits.MAX_MESSAGE_BYTES - output.size()) {
                    throw new TransportException(TransportException.Reason.MESSAGE_TOO_LARGE,
                            "HTTP 解压响应超过限制: " + TransportLimits.MAX_MESSAGE_BYTES);
                }
                output.write(buffer, 0, length);
            }
        } catch (EOFException eof) {
            logger.warn("[HttpCommunication] 压缩响应尾部截断，保留已解压 {} 字节 url={}",
                    output.size(), targetUrl);
        }
        return output.toByteArray();
    }

    // -----------------------
    //  工具方法
    // -----------------------

    /**
     * 添加HTTP请求头
     */
    public void addHeader(String key, String value) {
        if (key != null && value != null) {
            this.headers.keySet().removeIf(existing -> existing != null
                    && existing.equalsIgnoreCase(key) && !existing.equals(key));
            this.headers.put(key, value);
            this.explicitHeaderNames.add(key.toLowerCase(Locale.ROOT));
        }
    }

    /**
     * 获取HTTP请求头
     */
    public String getHeader(String key) {
        if (key == null) return null;
        String direct = this.headers.get(key);
        if (direct != null) return direct;
        for (Map.Entry<String, String> entry : this.headers.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(key)) {
                return entry.getValue();
            }
        }
        return null;
    }

    /**
     * 获取请求URL
     */
    public String getUrl() {
        return url;
    }

    public String getMethod() {
        return method;
    }

    public boolean hasExplicitHeader(String name) {
        return name != null && explicitHeaderNames.contains(name.toLowerCase(Locale.ROOT));
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
            requestNoiseHeaders.set(new LinkedHashMap<>(noiseHeaders));
        } else {
            requestNoiseHeaders.remove();
        }
    }

    /** 设置下一次请求的会话画像 Header；调用者显式配置的同名 Header 保持最高优先级。 */
    public void setRequestProfileHeaders(Map<String, String> profileHeaders) {
        if (profileHeaders != null && !profileHeaders.isEmpty()) {
            requestProfileHeaders.set(new LinkedHashMap<>(profileHeaders));
        } else {
            requestProfileHeaders.remove();
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
     * 清除由连接复用和 Set-Cookie 建立的后端亲和，供 HostId 切换或错配重试重新抽取
     * 负载均衡后端。显式配置的请求头和 Cookie 保持不变。
     */
    public void resetSessionAffinity() {
        responseCookies.clear();
        requestUrlOverride.remove();
        requestNoiseHeaders.remove();
        requestProfileHeaders.remove();
        OkHttpClient client = httpClient;
        if (client != null) client.connectionPool().evictAll();
    }

    @Override
    public void close() {
        responseCookies.clear();
        requestUrlOverride.remove();
        requestNoiseHeaders.remove();
        requestProfileHeaders.remove();
        OkHttpClient client = httpClient;
        httpClient = null;
        if (client == null) return;
        client.dispatcher().cancelAll();
        client.connectionPool().evictAll();
        client.dispatcher().executorService().shutdown();
    }

    /**
     * 获取代理设置
     */
    public Proxy getProxy() {
        return proxy;
    }

    private void addHeaders(Request.Builder builder,
                            Map<String, String> sourceHeaders,
                            List<String> configuredCookieHeaders) {
        if (sourceHeaders == null || sourceHeaders.isEmpty()) {
            return;
        }
        for (Map.Entry<String, String> entry : sourceHeaders.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (key == null || value == null) {
                continue;
            }
            if ("Cookie".equalsIgnoreCase(key)) {
                if (!value.isBlank()) {
                    configuredCookieHeaders.add(value);
                }
                continue;
            }
            builder.addHeader(key, value);
        }
    }

    private void storeResponseCookies(HttpUrl requestUrl, Headers responseHeaders) {
        List<Cookie> parsedCookies = Cookie.parseAll(requestUrl, responseHeaders);
        if (parsedCookies.isEmpty()) {
            return;
        }

        long now = System.currentTimeMillis();
        for (Cookie cookie : parsedCookies) {
            String key = cookieStoreKey(cookie);
            if (cookie.expiresAt() <= now) {
                responseCookies.remove(key);
            } else {
                responseCookies.put(key, cookie);
            }
        }
    }

    private String buildCookieHeader(HttpUrl requestUrl, List<String> configuredCookieHeaders) {
        List<String> cookieParts = new ArrayList<>();
        Set<String> configuredCookieNames = new HashSet<>();
        long now = System.currentTimeMillis();

        if (configuredCookieHeaders != null) {
            for (String configured : configuredCookieHeaders) {
                if (configured == null || configured.isBlank()) {
                    continue;
                }
                cookieParts.add(configured.trim());
                collectCookieNames(configured, configuredCookieNames);
            }
        }

        List<Cookie> matchedCookies = new ArrayList<>();
        for (Cookie cookie : responseCookies.values()) {
            if (cookie.expiresAt() <= now) {
                responseCookies.remove(cookieStoreKey(cookie));
                continue;
            }
            if (configuredCookieNames.contains(cookie.name())) {
                continue;
            }
            if (cookie.matches(requestUrl)) {
                matchedCookies.add(cookie);
            }
        }
        matchedCookies.sort(Comparator
                .comparingInt((Cookie cookie) -> cookie.path().length())
                .reversed());

        for (Cookie cookie : matchedCookies) {
            cookieParts.add(cookie.name() + "=" + cookie.value());
        }

        return cookieParts.isEmpty() ? null : String.join("; ", cookieParts);
    }

    private void collectCookieNames(String cookieHeader, Set<String> names) {
        String[] parts = cookieHeader.split(";");
        for (String part : parts) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int eq = trimmed.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String name = trimmed.substring(0, eq).trim();
            if (!name.isEmpty()) {
                names.add(name);
            }
        }
    }

    private String cookieStoreKey(Cookie cookie) {
        return cookie.name() + "\n" + cookie.domain() + "\n" + cookie.path();
    }
}
