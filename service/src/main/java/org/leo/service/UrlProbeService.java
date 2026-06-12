package org.leo.service;

import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.net.ssl.*;
import java.net.URL;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * URL 路径池自动探测服务。
 * 从 Leo 平台侧外部请求目标站点，发现真实存在的路径用于 URL 随机化。
 *
 * 探测策略（低噪声，高效率）：
 * 1. 请求 /robots.txt 提取 Allow/Disallow 路径
 * 2. 请求 /sitemap.xml 解析 <loc> 标签中的路径
 * 3. 请求首页 HTML，解析所有 href/src/action 属性中的站内路径
 * 4. 从首页引用的 JS 文件中提取路由和 API path 字符串
 *
 * 总请求数通常 < 10，动静极小，发现的路径保证真实存在。
 *
 * @author LeoSpring
 */
@Service
public class UrlProbeService {

    private static final Logger logger = LoggerFactory.getLogger(UrlProbeService.class);

    private static final int CONNECT_TIMEOUT = 5;
    private static final int READ_TIMEOUT = 10;
    private static final int MAX_PATHS_RETURN = 200;
    private static final int MAX_JS_FETCH = 5; // 最多抓取的 JS 文件数

    /** sitemap 中 <loc> 标签正则 */
    private static final Pattern SITEMAP_LOC_PATTERN = Pattern.compile("<loc>\\s*(.*?)\\s*</loc>");

    /** robots.txt 中 Allow/Disallow 路径正则 */
    private static final Pattern ROBOTS_PATH_PATTERN = Pattern.compile("(?:Allow|Disallow):\\s*(.+)");

    /** HTML 中 href/src/action 属性提取 */
    private static final Pattern HTML_ATTR_PATTERN = Pattern.compile(
            "(?:href|src|action)\\s*=\\s*[\"']([^\"'#?]+)[\"']", Pattern.CASE_INSENSITIVE);

    /** JS 中路径字符串提取（/开头，2~60字符，不含空格和特殊字符） */
    private static final Pattern JS_PATH_PATTERN = Pattern.compile(
            "[\"'`]((?:/[a-zA-Z0-9_.\\-]+){1,6}(?:\\.[a-zA-Z0-9]{1,10})?)[\"'`]");

    /** JS 中 API 路径模式（/api/xxx, /v1/xxx 等） */
    private static final Pattern JS_API_PATTERN = Pattern.compile(
            "[\"'`]((?:/(?:api|v[0-9]+|rest|graphql|auth|user|admin|static|assets|public|resource)" +
            "(?:/[a-zA-Z0-9_.\\-]+){0,5}))[\"'`]");

    /** script src 属性提取 */
    private static final Pattern SCRIPT_SRC_PATTERN = Pattern.compile(
            "<script[^>]+src\\s*=\\s*[\"']([^\"']+\\.js[^\"']*)[\"']", Pattern.CASE_INSENSITIVE);

    private final OkHttpClient httpClient;

    public UrlProbeService() {
        this.httpClient = createTrustAllClient();
    }

    /**
     * 探测目标站点的有效路径。
     *
     * @param baseUrl  目标站点基础 URL，如 https://target.com
     * @param timeout  单个请求超时秒数，0 使用默认值
     * @return 探测结果
     */
    public Map<String, Object> probe(String baseUrl, int timeout) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("baseUrl 不能为空");
        }

        baseUrl = normalizeBaseUrl(baseUrl.trim());
        int effectiveTimeout = timeout > 0 ? timeout : READ_TIMEOUT;
        long startTime = System.currentTimeMillis();

        Set<String> discoveredPaths = new LinkedHashSet<>();
        List<String> probeSources = new ArrayList<>();
        int totalRequests = 0;

        // 1. robots.txt（1 次请求）
        try {
            String robotsBody = fetchUrl(baseUrl + "/robots.txt", effectiveTimeout);
            totalRequests++;
            if (robotsBody != null) {
                List<String> robotsPaths = parseRobotsTxt(robotsBody);
                if (!robotsPaths.isEmpty()) {
                    discoveredPaths.addAll(robotsPaths);
                    probeSources.add("robots.txt (" + robotsPaths.size() + ")");
                }
            }
        } catch (Exception e) {
            logger.debug("robots.txt 探测失败: {}", e.getMessage());
        }

        // 2. sitemap.xml（1 次请求）
        try {
            String sitemapBody = fetchUrl(baseUrl + "/sitemap.xml", effectiveTimeout);
            totalRequests++;
            if (sitemapBody != null) {
                List<String> sitemapPaths = parseSitemap(sitemapBody, baseUrl);
                if (!sitemapPaths.isEmpty()) {
                    discoveredPaths.addAll(sitemapPaths);
                    probeSources.add("sitemap.xml (" + sitemapPaths.size() + ")");
                }
            }
        } catch (Exception e) {
            logger.debug("sitemap.xml 探测失败: {}", e.getMessage());
        }

        // 3. 首页 HTML 解析（1 次请求）
        String homepageHtml = null;
        try {
            homepageHtml = fetchUrl(baseUrl + "/", effectiveTimeout);
            totalRequests++;
            if (homepageHtml != null) {
                List<String> htmlPaths = parseHtmlPaths(homepageHtml, baseUrl);
                if (!htmlPaths.isEmpty()) {
                    discoveredPaths.addAll(htmlPaths);
                    probeSources.add("html-parse (" + htmlPaths.size() + ")");
                }
            }
        } catch (Exception e) {
            logger.debug("首页 HTML 解析失败: {}", e.getMessage());
        }

        // 4. 从首页引用的 JS 文件中提取路由/API 路径（最多 MAX_JS_FETCH 次请求）
        if (homepageHtml != null) {
            try {
                List<String> scriptUrls = extractScriptUrls(homepageHtml, baseUrl);
                int jsFetched = 0;
                Set<String> jsDiscovered = new LinkedHashSet<>();

                for (String scriptUrl : scriptUrls) {
                    if (jsFetched >= MAX_JS_FETCH) break;
                    try {
                        String jsBody = fetchUrl(scriptUrl, effectiveTimeout);
                        totalRequests++;
                        jsFetched++;
                        if (jsBody != null) {
                            jsDiscovered.addAll(parseJsPaths(jsBody));
                        }
                    } catch (Exception ignored) {
                    }
                }

                if (!jsDiscovered.isEmpty()) {
                    discoveredPaths.addAll(jsDiscovered);
                    probeSources.add("js-parse (" + jsDiscovered.size() + " from " + jsFetched + " files)");
                }
            } catch (Exception e) {
                logger.debug("JS 路径提取失败: {}", e.getMessage());
            }
        }

        long elapsed = System.currentTimeMillis() - startTime;

        // 构建结果
        List<String> resultPaths = new ArrayList<>(discoveredPaths);
        if (resultPaths.size() > MAX_PATHS_RETURN) {
            resultPaths = resultPaths.subList(0, MAX_PATHS_RETURN);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("baseUrl", baseUrl);
        result.put("paths", resultPaths);
        result.put("total", resultPaths.size());
        result.put("sources", probeSources);
        result.put("totalRequests", totalRequests);
        result.put("elapsedMs", elapsed);
        return result;
    }

    // ==================== Sitemap 解析 ====================

    private List<String> parseSitemap(String body, String baseUrl) {
        List<String> paths = new ArrayList<>();
        Matcher matcher = SITEMAP_LOC_PATTERN.matcher(body);
        while (matcher.find()) {
            String loc = matcher.group(1).trim();
            String path = extractPath(loc, baseUrl);
            if (isValidPath(path)) {
                paths.add(path);
            }
        }
        return paths;
    }

    // ==================== robots.txt 解析 ====================

    private List<String> parseRobotsTxt(String body) {
        List<String> paths = new ArrayList<>();
        Matcher matcher = ROBOTS_PATH_PATTERN.matcher(body);
        while (matcher.find()) {
            String path = matcher.group(1).trim();
            if (!path.contains("*") && !path.equals("/") && isValidPath(path)) {
                paths.add(path);
            }
        }
        return paths;
    }

    // ==================== HTML 解析 ====================

    private List<String> parseHtmlPaths(String html, String baseUrl) {
        Set<String> paths = new LinkedHashSet<>();
        Matcher matcher = HTML_ATTR_PATTERN.matcher(html);
        while (matcher.find()) {
            String raw = matcher.group(1).trim();
            String path = resolveToPath(raw, baseUrl);
            if (isValidPath(path)) {
                paths.add(path);
            }
        }
        return new ArrayList<>(paths);
    }

    // ==================== JS 路径提取 ====================

    private List<String> extractScriptUrls(String html, String baseUrl) {
        List<String> urls = new ArrayList<>();
        Matcher matcher = SCRIPT_SRC_PATTERN.matcher(html);
        while (matcher.find()) {
            String src = matcher.group(1).trim();
            String fullUrl = resolveUrl(src, baseUrl);
            if (fullUrl != null) {
                urls.add(fullUrl);
            }
        }
        return urls;
    }

    private Set<String> parseJsPaths(String jsBody) {
        Set<String> paths = new LinkedHashSet<>();

        // 提取 API 风格路径
        Matcher apiMatcher = JS_API_PATTERN.matcher(jsBody);
        while (apiMatcher.find()) {
            String path = apiMatcher.group(1);
            if (isValidPath(path)) {
                paths.add(path);
            }
        }

        // 提取一般路径字符串
        Matcher pathMatcher = JS_PATH_PATTERN.matcher(jsBody);
        while (pathMatcher.find()) {
            String path = pathMatcher.group(1);
            if (isValidPath(path) && looksLikeRoutePath(path)) {
                paths.add(path);
            }
        }

        return paths;
    }

    /**
     * 判断路径是否像一个路由路径（过滤掉明显不是 URL 的字符串）
     */
    private boolean looksLikeRoutePath(String path) {
        // 排除纯文件版本号如 /1.2.3
        if (path.matches("/[0-9.]+")) return false;
        // 排除过短的无意义路径
        if (path.length() < 2) return false;
        // 排除 node_modules 类路径
        if (path.contains("node_modules") || path.contains("__")) return false;
        return true;
    }

    // ==================== HTTP 工具 ====================

    private String fetchUrl(String url, int timeout) throws Exception {
        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .get()
                .build();

        OkHttpClient client = httpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT, TimeUnit.SECONDS)
                .readTimeout(timeout, TimeUnit.SECONDS)
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (response.isSuccessful() && response.body() != null) {
                return response.body().string();
            }
        }
        return null;
    }

    // ==================== 路径工具方法 ====================

    /**
     * 将原始 href/src 值解析为站内路径
     */
    private String resolveToPath(String raw, String baseUrl) {
        if (raw == null || raw.isEmpty()) return null;
        // 跳过外部链接、javascript:、data:、mailto:
        if (raw.startsWith("javascript:") || raw.startsWith("data:") ||
            raw.startsWith("mailto:") || raw.startsWith("#")) {
            return null;
        }
        // 绝对 URL，提取路径
        if (raw.startsWith("http://") || raw.startsWith("https://")) {
            return extractPathIfSameHost(raw, baseUrl);
        }
        // 协议相对 URL
        if (raw.startsWith("//")) {
            return extractPathIfSameHost("https:" + raw, baseUrl);
        }
        // 站内相对路径
        if (raw.startsWith("/")) {
            return raw;
        }
        // 其他相对路径，补 /
        return "/" + raw;
    }

    private String extractPathIfSameHost(String fullUrl, String baseUrl) {
        try {
            URL target = new URL(fullUrl);
            URL base = new URL(baseUrl);
            if (target.getHost().equalsIgnoreCase(base.getHost())) {
                String path = target.getPath();
                return (path != null && !path.isEmpty()) ? path : "/";
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private String resolveUrl(String raw, String baseUrl) {
        if (raw == null || raw.isEmpty()) return null;
        if (raw.startsWith("http://") || raw.startsWith("https://")) {
            return raw;
        }
        if (raw.startsWith("//")) {
            return "https:" + raw;
        }
        if (raw.startsWith("/")) {
            return baseUrl + raw;
        }
        return baseUrl + "/" + raw;
    }

    private String extractPath(String fullUrl, String baseUrl) {
        try {
            if (fullUrl.startsWith("http://") || fullUrl.startsWith("https://")) {
                URL parsed = new URL(fullUrl);
                String path = parsed.getPath();
                return (path != null && !path.isEmpty()) ? path : "/";
            }
            return fullUrl.startsWith("/") ? fullUrl : "/" + fullUrl;
        } catch (Exception e) {
            return null;
        }
    }

    private boolean isValidPath(String path) {
        if (path == null || path.isEmpty() || path.equals("/")) return false;
        if (path.length() > 200) return false;
        // 跳过明显的外部资源
        if (path.contains("://")) return false;
        return path.startsWith("/");
    }

    private String normalizeBaseUrl(String baseUrl) {
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        if (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) {
            baseUrl = "https://" + baseUrl;
        }
        return baseUrl;
    }

    private OkHttpClient createTrustAllClient() {
        try {
            TrustManager[] trustAllCerts = new TrustManager[]{
                    new X509TrustManager() {
                        public void checkClientTrusted(X509Certificate[] xcs, String s) { }
                        public void checkServerTrusted(X509Certificate[] xcs, String s) { }
                        public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[]{}; }
                    }
            };

            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, trustAllCerts, new java.security.SecureRandom());

            return new OkHttpClient.Builder()
                    .sslSocketFactory(sslContext.getSocketFactory(), (X509TrustManager) trustAllCerts[0])
                    .hostnameVerifier((hostname, session) -> true)
                    .connectTimeout(CONNECT_TIMEOUT, TimeUnit.SECONDS)
                    .readTimeout(READ_TIMEOUT, TimeUnit.SECONDS)
                    .followRedirects(true)
                    .build();
        } catch (Exception e) {
            logger.error("创建 OkHttpClient 失败", e);
            return new OkHttpClient();
        }
    }
}
