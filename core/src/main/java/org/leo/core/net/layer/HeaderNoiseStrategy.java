package org.leo.core.net.layer;

/**
 * HTTP Header 噪声注入策略配置（per puppet session）。
 * <p>
 * 每次请求前随机附加若干无意义 HTTP 头，改变请求指纹，
 * 对抗基于 Header 数量/名称/顺序的流量特征分析。
 *
 * @author LeoSpring
 */
public class HeaderNoiseStrategy {

    /**
     * 是否启用 Header 噪声注入。
     * false（默认）= 不注入额外 Header，向后兼容。
     */
    private boolean enabled = false;

    /**
     * 每次请求最少注入的噪声 Header 数量
     */
    private int minHeaders = 1;

    /**
     * 每次请求最多注入的噪声 Header 数量
     */
    private int maxHeaders = 5;

    /**
     * Header 名称前缀池（用于生成类似真实应用的 Header 名）
     */
    private String[] prefixes;

    /**
     * 噪声 Header 值模式
     */
    private HeaderValueMode valueMode = HeaderValueMode.RANDOM_ALPHANUM;

    public enum HeaderValueMode {
        /**
         * 纯随机字母数字串
         */
        RANDOM_ALPHANUM,

        /**
         * 模拟 UUID 格式
         */
        UUID_LIKE,

        /**
         * 模拟时间戳/数字
         */
        NUMERIC
    }

    // ==================== 默认前缀池 ====================

    private static final String[] DEFAULT_PREFIXES = {
            "X-Request-Id", "X-Trace-Id", "X-Correlation-Id", "X-Session-Token",
            "X-Client-Version", "X-App-Instance", "X-Forwarded-Tag", "X-Cache-Key",
            "X-Transaction-Id", "X-Flow-Id", "X-Span-Id", "X-Device-Id",
            "X-Nonce", "X-Signature-V", "X-Build-Hash", "X-Region"
    };

    // ==================== getter/setter ====================

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getMinHeaders() {
        return minHeaders;
    }

    public void setMinHeaders(int minHeaders) {
        this.minHeaders = Math.max(0, minHeaders);
    }

    public int getMaxHeaders() {
        return maxHeaders;
    }

    public void setMaxHeaders(int maxHeaders) {
        this.maxHeaders = Math.max(this.minHeaders, maxHeaders);
    }

    public String[] getPrefixes() {
        return prefixes != null && prefixes.length > 0 ? prefixes : DEFAULT_PREFIXES;
    }

    public void setPrefixes(String[] prefixes) {
        this.prefixes = prefixes;
    }

    public HeaderValueMode getValueMode() {
        return valueMode;
    }

    public void setValueMode(HeaderValueMode valueMode) {
        this.valueMode = valueMode;
    }
}
