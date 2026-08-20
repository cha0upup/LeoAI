package org.leo.core.net.layer;

/**
 * 请求体 Padding 策略配置（per puppet session）。
 * <p>
 * Padding 是伪装链的内部能力，填充数据由外层伪装统一加密保护。
 * 填充字段会在 PayloadCodec 序列化前写入业务 Map，外层 traffic profile 只处理编码后的不透明字节。
 * 支持预设模板和多种填充长度分布。
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

    /** 是否优先把编码后的请求补齐到固定长度桶。 */
    private boolean bucketed = true;

    /** 编码请求使用的长度桶，单位为字节。 */
    private int[] bucketSizes = {1024, 2048, 4096, 8192};

    /** Padding 参与计算时允许的最大总请求大小。 */
    private int maxTotalBytes = 8192;

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

    public boolean isBucketed() {
        return bucketed;
    }

    public PaddingStrategy setBucketed(boolean bucketed) {
        this.bucketed = bucketed;
        return this;
    }

    public int[] getBucketSizes() {
        return bucketSizes == null ? new int[0] : bucketSizes.clone();
    }

    public PaddingStrategy setBucketSizes(int[] bucketSizes) {
        if (bucketSizes == null) {
            this.bucketSizes = new int[0];
            return this;
        }
        this.bucketSizes = java.util.Arrays.stream(bucketSizes)
                .filter(value -> value > 0)
                .distinct()
                .sorted()
                .toArray();
        return this;
    }

    public int getMaxTotalBytes() {
        return maxTotalBytes;
    }

    public PaddingStrategy setMaxTotalBytes(int maxTotalBytes) {
        this.maxTotalBytes = Math.max(0, maxTotalBytes);
        return this;
    }
}
