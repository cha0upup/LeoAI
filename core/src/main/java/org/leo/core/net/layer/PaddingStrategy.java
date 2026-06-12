package org.leo.core.net.layer;

/**
 * 请求体 Padding 策略配置（per puppet session）。
 * <p>
 * 新版设计：Padding 作为伪装链的内部能力，填充数据由外层伪装统一加密保护。
 * <ul>
 *   <li>不再需要独立的加密信封 — 外层 Disguise 会加密一切</li>
 *   <li>协议格式极简：[4字节数据长度][真实数据][随机填充]</li>
 *   <li>支持预设模板，一行代码切换</li>
 *   <li>填充长度支持多种随机分布</li>
 * </ul>
 *
 * @author LeoSpring
 */
public class PaddingStrategy {

    // ==================== 预设模板 ====================

    /**
     * 高隐蔽模式：大范围随机填充 + 高斯分布
     * 适用于对抗 DPI 和高级流量分析
     */
    public static PaddingStrategy stealth() {
        PaddingStrategy s = new PaddingStrategy();
        s.enabled = true;
        s.minBytes = 128;
        s.maxBytes = 2048;
        s.lengthDistribution = LengthDistribution.GAUSSIAN;
        return s;
    }

    /**
     * 常规模式：中等填充 + 均匀分布
     * 平衡隐蔽性和带宽开销
     */
    public static PaddingStrategy normal() {
        PaddingStrategy s = new PaddingStrategy();
        s.enabled = true;
        s.minBytes = 64;
        s.maxBytes = 512;
        s.lengthDistribution = LengthDistribution.UNIFORM;
        return s;
    }

    /**
     * 轻量模式：小填充
     * 适用于带宽敏感或低安全要求场景
     */
    public static PaddingStrategy light() {
        PaddingStrategy s = new PaddingStrategy();
        s.enabled = true;
        s.minBytes = 16;
        s.maxBytes = 128;
        s.lengthDistribution = LengthDistribution.UNIFORM;
        return s;
    }

    // ==================== 配置字段 ====================

    /** 是否启用 Padding */
    private boolean enabled = false;

    /** 最小填充字节数 */
    private int minBytes = 64;

    /** 最大填充字节数 */
    private int maxBytes = 512;

    /**
     * 填充长度的随机分布策略
     */
    private LengthDistribution lengthDistribution = LengthDistribution.UNIFORM;

    // ==================== 枚举定义 ====================

    /**
     * 填充长度随机分布
     */
    public enum LengthDistribution {
        /** 均匀分布：[min, max] 等概率 */
        UNIFORM,

        /** 高斯分布：以中位数为均值，减少极端值出现频率，长期观察难以聚类 */
        GAUSSIAN,

        /** 指数分布：大多数请求填充较少，偶尔出现大填充，模拟真实流量特征 */
        EXPONENTIAL
    }

    // ==================== getter/setter（链式） ====================

    public boolean isEnabled() {
        return enabled;
    }

    public PaddingStrategy setEnabled(boolean enabled) {
        this.enabled = enabled;
        return this;
    }

    public int getMinBytes() {
        return minBytes;
    }

    public PaddingStrategy setMinBytes(int minBytes) {
        this.minBytes = Math.max(0, minBytes);
        return this;
    }

    public int getMaxBytes() {
        return maxBytes;
    }

    public PaddingStrategy setMaxBytes(int maxBytes) {
        this.maxBytes = Math.max(this.minBytes, maxBytes);
        return this;
    }

    public LengthDistribution getLengthDistribution() {
        return lengthDistribution;
    }

    public PaddingStrategy setLengthDistribution(LengthDistribution lengthDistribution) {
        this.lengthDistribution = lengthDistribution;
        return this;
    }
}
