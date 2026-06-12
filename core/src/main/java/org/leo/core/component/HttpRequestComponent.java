package org.leo.core.component;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * HTTP 请求组件
 * <p>
 * 在 puppet 侧发起 HTTP 请求，支持 GET/POST/PUT/DELETE/HEAD 方法。
 * 用于替代通过 execOnce("curl ...") 执行 HTTP 请求的场景，
 * 不依赖目标机器是否安装 curl，且提供结构化的响应结果。
 * <p>
 * 默认忽略 HTTPS 证书验证（内网自签名证书常见）。
 * <p>
 * 兼容 Java 1.5+，仅使用 JDK 内置类。
 *
 * @author LeoSpring
 * @version 1.1
 */
@SuppressWarnings({"rawtypes", "unchecked"})
public class HttpRequestComponent implements Runnable, InvocationHandler {

    private static final String DEFAULT_CHARSET = "UTF-8";
    private static final int DEFAULT_CONNECT_TIMEOUT = 10000;
    private static final int DEFAULT_READ_TIMEOUT = 30000;
    private static final int MAX_RESPONSE_SIZE = 10 * 1024 * 1024; // 10MB

    private static volatile SSLSocketFactory trustAllSslSocketFactory;

    private HashMap params;
    private HashMap results;


    public void run() {
        java.lang.reflect.InvocationHandler h = (java.lang.reflect.InvocationHandler) Thread.currentThread().getContextClassLoader();
        try {
            params = (java.util.HashMap) h.invoke(null, null, null);
            results = new java.util.HashMap();
            invoke();
        } catch (Throwable t) {
            if (results == null) results = new java.util.HashMap();
            results.put("code", Integer.valueOf(500));
            results.put("msg", t.getMessage() != null ? t.getMessage() : t.toString());
        }
        if (results != null) {
            try { h.invoke(null, null, new Object[]{results}); } catch (Throwable ignored) {}
        }
    }


    public void invoke() throws Exception {
        String method = getStringParam("method");
        String url = getStringParam("url");

        if (url == null || url.length() == 0) {
            results.put("code", 400);
            results.put("msg", "url is required");
            return;
        }

        if (method == null || method.length() == 0) {
            method = "GET";
        }
        method = method.toUpperCase();

        int connectTimeout = getIntParam("connectTimeout", DEFAULT_CONNECT_TIMEOUT);
        int readTimeout = getIntParam("readTimeout", DEFAULT_READ_TIMEOUT);
        boolean followRedirects = getBooleanParam("followRedirects", true);

        HttpURLConnection conn = null;

        try {
            conn = (HttpURLConnection) new URL(url).openConnection();

            // HTTPS 忽略证书验证（内网自签名证书）
            if (conn instanceof HttpsURLConnection) {
                HttpsURLConnection httpsConn = (HttpsURLConnection) conn;
                httpsConn.setSSLSocketFactory(getTrustAllSslSocketFactory());
                ClassLoader cl2 = Thread.currentThread().getContextClassLoader();
                HostnameVerifier hnv = (HostnameVerifier) Proxy.newProxyInstance(cl2, new Class[]{HostnameVerifier.class}, this);
                httpsConn.setHostnameVerifier(hnv);
            }

            conn.setRequestMethod(method);
            conn.setConnectTimeout(connectTimeout);
            conn.setReadTimeout(readTimeout);
            conn.setInstanceFollowRedirects(followRedirects);

            // 设置请求头
            Map requestHeaders = (Map) params.get("headers");
            if (requestHeaders != null) {
                for (Object entry : requestHeaders.entrySet()) {
                    Map.Entry e = (Map.Entry) entry;
                    String key = String.valueOf(e.getKey());
                    String value = String.valueOf(e.getValue());
                    conn.setRequestProperty(key, value);
                }
            }

            // 设置默认 User-Agent（如果未指定）
            if (conn.getRequestProperty("User-Agent") == null) {
                conn.setRequestProperty("User-Agent", "Mozilla/5.0");
            }

            // 写入请求体（POST/PUT）
            byte[] bodyBytes = getBodyBytes();
            if (bodyBytes != null && bodyBytes.length > 0
                    && ("POST".equals(method) || "PUT".equals(method))) {
                conn.setDoOutput(true);
                if (conn.getRequestProperty("Content-Type") == null) {
                    conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                }
                OutputStream os = conn.getOutputStream();
                os.write(bodyBytes);
                os.flush();
                os.close();
            }

            // 读取响应
            int statusCode = conn.getResponseCode();
            String statusMessage = conn.getResponseMessage();

            // 收集响应头
            HashMap responseHeaders = new HashMap();
            Map headerFields = conn.getHeaderFields();
            if (headerFields != null) {
                for (Object entry : headerFields.entrySet()) {
                    Map.Entry e = (Map.Entry) entry;
                    Object key = e.getKey();
                    if (key != null) {
                        List values = (List) e.getValue();
                        if (values != null && values.size() == 1) {
                            responseHeaders.put(String.valueOf(key), String.valueOf(values.get(0)));
                        } else if (values != null) {
                            responseHeaders.put(String.valueOf(key), values);
                        }
                    }
                }
            }

            // 读取响应体
            byte[] responseBody = null;
            if (!"HEAD".equals(method)) {
                InputStream is = null;
                try {
                    is = (statusCode >= 400) ? conn.getErrorStream() : conn.getInputStream();
                } catch (Exception ignored) {
                    // 某些情况下 getInputStream 会抛异常
                    is = conn.getErrorStream();
                }

                if (is != null) {
                    responseBody = readStream(is);
                    is.close();
                }
            }

            // 构建结果
            results.put("code", 200);
            results.put("statusCode", statusCode);
            results.put("statusMessage", statusMessage);
            results.put("responseHeaders", responseHeaders);

            if (responseBody != null) {
                // 尝试判断是否为文本内容
                String contentType = conn.getContentType();
                if (isTextContent(contentType)) {
                    String charset = parseCharset(contentType);
                    results.put("body", new String(responseBody, charset));
                    results.put("bodyType", "text");
                } else {
                    results.put("body", responseBody);
                    results.put("bodyType", "binary");
                    results.put("bodySize", responseBody.length);
                }
            }

        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    // ==================== 私有辅助方法 ====================

    private SSLSocketFactory getTrustAllSslSocketFactory() throws Exception {
        SSLSocketFactory current = trustAllSslSocketFactory;
        if (current != null) {
            return current;
        }
        synchronized (HttpRequestComponent.class) {
            if (trustAllSslSocketFactory == null) {
                SSLContext ctx = SSLContext.getInstance("TLS");
                ClassLoader cl = Thread.currentThread().getContextClassLoader();
                TrustManager tm = (TrustManager) Proxy.newProxyInstance(cl, new Class[]{X509TrustManager.class}, this);
                ctx.init(null, new TrustManager[]{tm}, new SecureRandom());
                trustAllSslSocketFactory = ctx.getSocketFactory();
            }
            return trustAllSslSocketFactory;
        }
    }

    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        String name = method.getName();
        if (name.equals("verify")) return Boolean.TRUE;
        if (name.equals("getAcceptedIssuers")) return new X509Certificate[0];
        return null;
    }

    private byte[] readStream(InputStream is) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int len;
        int totalRead = 0;

        while ((len = is.read(buffer)) != -1) {
            totalRead += len;
            if (totalRead > MAX_RESPONSE_SIZE) {
                baos.write(buffer, 0, len);
                results.put("truncated", 1);
                results.put("truncateReason", "Response exceeds " + (MAX_RESPONSE_SIZE / 1024 / 1024) + "MB limit");
                break;
            }
            baos.write(buffer, 0, len);
        }

        return baos.toByteArray();
    }

    private byte[] getBodyBytes() {
        Object body = params.get("body");
        if (body == null) {
            return null;
        }
        if (body instanceof byte[]) {
            return (byte[]) body;
        }
        try {
            return String.valueOf(body).getBytes(DEFAULT_CHARSET);
        } catch (UnsupportedEncodingException e) {
            return String.valueOf(body).getBytes();
        }
    }

    private boolean isTextContent(String contentType) {
        if (contentType == null) {
            return true; // 默认当文本处理
        }
        String lower = contentType.toLowerCase();
        return lower.contains("text/")
                || lower.contains("application/json")
                || lower.contains("application/xml")
                || lower.contains("application/javascript")
                || lower.contains("application/x-www-form-urlencoded")
                || lower.contains("application/yaml")
                || lower.contains("application/toml");
    }

    private String parseCharset(String contentType) {
        if (contentType != null) {
            String lower = contentType.toLowerCase();
            int idx = lower.indexOf("charset=");
            if (idx >= 0) {
                String charset = contentType.substring(idx + 8).trim();
                // 移除可能的分号和后续参数
                int semi = charset.indexOf(';');
                if (semi >= 0) {
                    charset = charset.substring(0, semi);
                }
                // 移除引号
                charset = charset.replace("\"", "").replace("'", "").trim();
                if (charset.length() > 0) {
                    return charset;
                }
            }
        }
        return DEFAULT_CHARSET;
    }

    private String getStringParam(String key) {
        if (key == null) {
            return null;
        }
        Object value = params.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof byte[]) {
            try {
                return new String((byte[]) value, DEFAULT_CHARSET);
            } catch (UnsupportedEncodingException e) {
                return new String((byte[]) value);
            }
        }
        return String.valueOf(value);
    }

    private int getIntParam(String key, int defaultValue) {
        Object value = params.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private boolean getBooleanParam(String key, boolean defaultValue) {
        Object value = params.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue() != 0;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }
}
