package org.leo.core.net.layer;

import java.util.List;

/**
 * URL 随机化策略配置（per puppet session）
 * <p>
 * 控制正向通道请求 URL 是否随机化，以及随机化方式。
 * 仅在 WebShell 以 Filter/* 或 Interceptor/** 方式部署时启用，
 * 精确路径映射的 WebShell 必须关闭。
 *
 * @author LeoSpring
 */
public class UrlStrategy {

    /**
     * 是否启用 URL 随机化。
     * false（默认）= 始终使用 RequestLayer.rUrl，向后兼容。
     */
    private boolean enabled = false;

    /**
     * 随机化模式
     */
    private Mode mode = Mode.POOL;

    /**
     * 路径池（mode=POOL 时使用）。
     * 来源于目标站点真实存在的路径，由用户手动配置或自动探测填充。
     * 每次请求从中随机选取一个。
     */
    private List<String> urlPool;

    /**
     * 路径模板（mode=TEMPLATE 时使用）。
     * 支持占位符：
     * <ul>
     *   <li>{rand}  — 8 位随机字母数字</li>
     *   <li>{uuid}  — 短 UUID（前 8 位）</li>
     *   <li>{ts}    — 13 位毫秒时间戳</li>
     *   <li>{ext}   — 随机扩展名（从 extensions 池或默认池）</li>
     *   <li>{word}  — 随机常见路径词</li>
     *   <li>{dir}   — 随机目录名（js/css/img/fonts 等）</li>
     *   <li>{hex}   — 6 位十六进制</li>
     * </ul>
     */
    private String urlTemplate;

    /**
     * 路径前缀（所有模式通用，可选）。
     * 如 "/assets"、"/api/v2"、"/static"
     */
    private String prefix;

    /**
     * 扩展名池（mode=STATIC_ASSET 或模板中 {ext} 使用）。
     * 为空时使用默认池 [.js, .css, .png, .woff2, .svg, .json]
     */
    private List<String> extensions;

    public enum Mode {
        /**
         * 从目标站点真实路径池中随机选取（最隐蔽）
         */
        POOL,

        /**
         * 按模板动态生成路径（通用性强）
         */
        TEMPLATE,

        /**
         * 模拟前端静态资源路径请求（最简单，适合 /* 通配场景）
         */
        STATIC_ASSET
    }

    // ==================== getter/setter ====================

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Mode getMode() {
        return mode;
    }

    public void setMode(Mode mode) {
        this.mode = mode;
    }

    public List<String> getUrlPool() {
        return urlPool;
    }

    public void setUrlPool(List<String> urlPool) {
        this.urlPool = urlPool;
    }

    public String getUrlTemplate() {
        return urlTemplate;
    }

    public void setUrlTemplate(String urlTemplate) {
        this.urlTemplate = urlTemplate;
    }

    public String getPrefix() {
        return prefix;
    }

    public void setPrefix(String prefix) {
        this.prefix = prefix;
    }

    public List<String> getExtensions() {
        return extensions;
    }

    public void setExtensions(List<String> extensions) {
        this.extensions = extensions;
    }
}
