package org.leo.core.util.request;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.Random;

/**
 * Referer生成器工具类
 * 生成随机的HTTP Referer头，用于模拟真实的浏览器行为
 * 
 * @author LeoSpring
 * @version 2.0
 */
public class RefererGenerator {

    // 常见的路径
    private static final String[] PATHS = {
            "/home", "/products", "/about", "/contact", "/blog", "/article", "/search",
            "/products/category1", "/products/category2", "/user/profile", "/user/settings",
            "/shop/item/123", "/news/article/456", "/forum/topic/789"
    };

    // 常见的查询参数
    private static final String[] QUERY_PARAMS = {
            "?search=java", "?page=1", "?sort=asc", "?id=123", "?category=tech", "?filter=new", "?date=2024-08-30",
            "?user=guest", "?action=login", "?ref=homepage"
    };

    // 协议常量
    private static final String PROTOCOL_HTTP = "http";
    private static final String PROTOCOL_HTTPS = "https";
    
    // URL格式常量
    private static final String URL_FORMAT = "%s://%s%s%s%s";
    
    // 字符集常量
    private static final String CHARACTERS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    
    // 端口范围常量
    private static final int MAX_PORT = 65535;
    private static final int MIN_PORT = 1;
    private static final int DEFAULT_PORT = -1;
    
    // 查询参数长度范围
    private static final int MIN_QUERY_KEY_LENGTH = 1;
    private static final int MAX_QUERY_KEY_LENGTH = 5;
    private static final int MIN_QUERY_VALUE_LENGTH = 1;
    private static final int MAX_QUERY_VALUE_LENGTH = 10;
    
    private static final Random RANDOM = new Random();

    /**
     * 生成随机Referer URL
     * 
     * @param baseUrl 基础URL
     * @return 随机生成的Referer URL
     * @throws IllegalArgumentException 如果baseUrl为空或无效
     */
    public static String generateRandomReferer(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("baseUrl不能为空");
        }

        String path = generateRandomPath();
        String query = generateRandomQuery();
        String protocol = RANDOM.nextBoolean() ? PROTOCOL_HTTP : PROTOCOL_HTTPS;
        String port = RANDOM.nextBoolean() ? "" : ":" + (RANDOM.nextInt(MAX_PORT) + MIN_PORT);

        try {
            URL url = new URL(baseUrl);
            String host = url.getHost();
            if (host == null || host.isEmpty()) {
                throw new IllegalArgumentException("无效的URL: " + baseUrl);
            }
            String portString = (url.getPort() != DEFAULT_PORT) ? ":" + url.getPort() : port;

            // 生成完整的 Referer URL
            return String.format(URL_FORMAT, protocol, host, portString, path, query);
        } catch (MalformedURLException e) {
            throw new IllegalArgumentException("无效的URL格式: " + baseUrl, e);
        }
    }

    private static String generateRandomPath() {
        return PATHS[RANDOM.nextInt(PATHS.length)];
    }

    private static String generateRandomQuery() {
        // 选择查询参数数组中的一个或生成一个随机查询参数
        if (RANDOM.nextBoolean()) {
            return QUERY_PARAMS[RANDOM.nextInt(QUERY_PARAMS.length)];
        } else {
            // 随机生成查询参数
            int keyLength = RANDOM.nextInt(MAX_QUERY_KEY_LENGTH - MIN_QUERY_KEY_LENGTH + 1) + MIN_QUERY_KEY_LENGTH;
            int valueLength = RANDOM.nextInt(MAX_QUERY_VALUE_LENGTH - MIN_QUERY_VALUE_LENGTH + 1) + MIN_QUERY_VALUE_LENGTH;
            return "?" + generateRandomString(keyLength) + "=" + generateRandomString(valueLength);
        }
    }

    /**
     * 生成随机字符串
     */
    private static String generateRandomString(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            int index = RANDOM.nextInt(CHARACTERS.length());
            sb.append(CHARACTERS.charAt(index));
        }
        return sb.toString();
    }


}