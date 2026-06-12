package org.leo.core.net.layer;

/**
 * TLS 指纹伪装策略配置（per puppet session）。
 * <p>
 * 通过自定义 cipher suites 和 TLS 协议版本来模拟真实浏览器的 TLS ClientHello，
 * 对抗 JA3/JA3S 指纹检测。
 * <p>
 * 选择 RANDOM profile 时，首次调用 {@link #getCipherSuites()} 会随机解析为一个具体浏览器，
 * 之后保持不变。如需更换指纹，需重新创建 Communication 实例（即重连）。
 *
 * @author LeoSpring
 */
public class TlsFingerprintStrategy {

    /**
     * 是否启用 TLS 指纹伪装。
     * false（默认）= 使用 OkHttp 默认 TLS 配置。
     */
    private boolean enabled = false;

    /**
     * 伪装的浏览器类型
     */
    private BrowserProfile profile = BrowserProfile.CHROME_MODERN;

    /**
     * RANDOM 解析后锁定的实际 profile（不序列化到 JSON）
     */
    private transient BrowserProfile resolvedProfile;

    /**
     * 浏览器 TLS 指纹配置
     */
    public enum BrowserProfile {
        /**
         * Chrome 120+ (Windows/Mac)
         */
        CHROME_MODERN,

        /**
         * Firefox 121+ (Windows/Mac)
         */
        FIREFOX_MODERN,

        /**
         * Safari 17+ (macOS/iOS)
         */
        SAFARI_MODERN,

        /**
         * Edge 120+ (Windows)
         */
        EDGE_MODERN,

        /**
         * 随机选择一个 profile（创建时解析，之后固定）
         */
        RANDOM
    }

    // ==================== 预置的 Cipher Suite 配置 ====================

    /**
     * Chrome 120+ cipher suites (按 Chrome 真实顺序)
     */
    public static final String[] CHROME_CIPHER_SUITES = {
            "TLS_AES_128_GCM_SHA256",
            "TLS_AES_256_GCM_SHA384",
            "TLS_CHACHA20_POLY1305_SHA256",
            "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256",
            "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
            "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384",
            "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384",
            "TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256",
            "TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256",
            "TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA",
            "TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA",
            "TLS_RSA_WITH_AES_128_GCM_SHA256",
            "TLS_RSA_WITH_AES_256_GCM_SHA384",
            "TLS_RSA_WITH_AES_128_CBC_SHA",
            "TLS_RSA_WITH_AES_256_CBC_SHA"
    };

    /**
     * Firefox 121+ cipher suites (按 Firefox 真实顺序)
     */
    public static final String[] FIREFOX_CIPHER_SUITES = {
            "TLS_AES_128_GCM_SHA256",
            "TLS_CHACHA20_POLY1305_SHA256",
            "TLS_AES_256_GCM_SHA384",
            "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256",
            "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
            "TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256",
            "TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256",
            "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384",
            "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384",
            "TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA",
            "TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA",
            "TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA",
            "TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA",
            "TLS_RSA_WITH_AES_128_GCM_SHA256",
            "TLS_RSA_WITH_AES_256_GCM_SHA384",
            "TLS_RSA_WITH_AES_128_CBC_SHA",
            "TLS_RSA_WITH_AES_256_CBC_SHA"
    };

    /**
     * Safari 17+ cipher suites (按 Safari 真实顺序)
     */
    public static final String[] SAFARI_CIPHER_SUITES = {
            "TLS_AES_128_GCM_SHA256",
            "TLS_AES_256_GCM_SHA384",
            "TLS_CHACHA20_POLY1305_SHA256",
            "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384",
            "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256",
            "TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256",
            "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384",
            "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
            "TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256",
            "TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA",
            "TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA",
            "TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA",
            "TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA",
            "TLS_RSA_WITH_AES_256_GCM_SHA384",
            "TLS_RSA_WITH_AES_128_GCM_SHA256",
            "TLS_RSA_WITH_AES_256_CBC_SHA",
            "TLS_RSA_WITH_AES_128_CBC_SHA"
    };

    /**
     * Edge 120+ cipher suites（与 Chrome 基本一致，微调顺序）
     */
    public static final String[] EDGE_CIPHER_SUITES = CHROME_CIPHER_SUITES;

    /**
     * 各 Profile 对应的 TLS 协议版本
     */
    public static final String[] MODERN_PROTOCOLS = {"TLSv1.2", "TLSv1.3"};

    // ==================== getter/setter ====================

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public BrowserProfile getProfile() {
        return profile;
    }

    public void setProfile(BrowserProfile profile) {
        this.profile = profile;
        // 重置 resolved，让下次 getCipherSuites() 重新解析
        this.resolvedProfile = null;
    }

    /**
     * 根据当前 profile 获取 cipher suites。
     * RANDOM 首次调用时解析为具体浏览器并锁定，后续调用返回相同结果。
     */
    public String[] getCipherSuites() {
        BrowserProfile p = resolveProfile();
        switch (p) {
            case FIREFOX_MODERN:
                return FIREFOX_CIPHER_SUITES;
            case SAFARI_MODERN:
                return SAFARI_CIPHER_SUITES;
            case EDGE_MODERN:
                return EDGE_CIPHER_SUITES;
            case CHROME_MODERN:
            default:
                return CHROME_CIPHER_SUITES;
        }
    }

    /**
     * 获取协议版本
     */
    public String[] getProtocols() {
        return MODERN_PROTOCOLS;
    }

    /**
     * 获取实际解析后的 profile（RANDOM 解析后的具体值）。
     * 用于日志输出。
     */
    public BrowserProfile getResolvedProfile() {
        return resolveProfile();
    }

    private BrowserProfile resolveProfile() {
        if (resolvedProfile != null) {
            return resolvedProfile;
        }
        if (this.profile == BrowserProfile.RANDOM) {
            BrowserProfile[] profiles = {BrowserProfile.CHROME_MODERN, BrowserProfile.FIREFOX_MODERN,
                    BrowserProfile.SAFARI_MODERN, BrowserProfile.EDGE_MODERN};
            resolvedProfile = profiles[new java.util.Random().nextInt(profiles.length)];
        } else {
            resolvedProfile = this.profile;
        }
        return resolvedProfile;
    }
}
