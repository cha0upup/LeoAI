package org.leo.ai.util;

import java.util.*;

/**
 * 工具结果智能压缩工具类。
 * <p>
 * 当工具返回的文本超过阈值时，用「保留头尾 + 关键行索引」的方式压缩，
 * 既节省 token 又保留 AI 做后续决策所需的上下文。
 * <p>
 * 设计原则：
 * <ul>
 *   <li>压缩是有损的，但保留足够的上下文让 AI 知道「省略了什么」以及「如何获取完整内容」</li>
 *   <li>所有方法均为纯函数，不修改输入参数</li>
 *   <li>阈值以字符数计量（不是字节），与 LangChain4j 序列化到 JSON 后的 token 近似正相关</li>
 * </ul>
 */
public final class ToolResultUtils {

    private ToolResultUtils() {}

    /** 文本文件压缩的默认阈值（字符数）。约 8K 字符 ≈ 2~3K tokens。 */
    public static final int DEFAULT_TEXT_FILE_THRESHOLD = 8000;

    /** 命令输出压缩的默认阈值（字符数）。 */
    public static final int DEFAULT_COMMAND_OUTPUT_THRESHOLD = 8000;

    // ── 文本文件压缩 ────────────────────────────────────────────────────────────

    /**
     * 压缩文本文件内容。超出阈值时保留头部和尾部各若干行，中间用摘要替代。
     * <p>
     * 压缩策略：
     * <ol>
     *   <li>如果文本长度 ≤ threshold，原样返回</li>
     *   <li>否则：保留前 headLines 行 + 尾 tailLines 行</li>
     *   <li>中间插入摘要：省略行数、总行数、以及中间区域出现的关键词行索引（错误、异常、TODO 等）</li>
     * </ol>
     *
     * @param text      原始文本内容
     * @param threshold 触发压缩的字符数阈值（≤0 使用默认值）
     * @return 压缩结果。若未压缩返回原文本；若压缩返回带标记的文本
     */
    public static String compressTextFileResult(String text, int threshold) {
        if (text == null || text.isEmpty()) return text;
        int limit = threshold > 0 ? threshold : DEFAULT_TEXT_FILE_THRESHOLD;
        if (text.length() <= limit) return text;

        String[] lines = text.split("\n", -1);
        int totalLines = lines.length;

        // 头部和尾部各保留的行数（根据阈值动态计算，但至少各 30 行）
        int headLines = Math.max(30, limit / 200);
        int tailLines = Math.max(20, limit / 300);

        // 如果总行数不超过 head+tail+10 行，不值得压缩（压缩后反而更长）
        if (totalLines <= headLines + tailLines + 10) return text;

        // ── 构建关键行索引（扫描中间被省略的区域）──
        int omitStart = headLines;
        int omitEnd = totalLines - tailLines;
        List<String> keyLineHints = scanKeyLines(lines, omitStart, omitEnd);

        // ── 拼装压缩结果 ──
        StringBuilder sb = new StringBuilder(limit + 512);

        // 头部
        for (int i = 0; i < headLines && i < totalLines; i++) {
            sb.append(lines[i]).append('\n');
        }

        // 省略标记
        int omittedCount = omitEnd - omitStart;
        sb.append("\n... [省略 ").append(omittedCount).append(" 行，共 ").append(totalLines).append(" 行]");
        sb.append(" [可用 offset 参数读取完整内容] ...\n");

        // 关键行索引
        if (!keyLineHints.isEmpty()) {
            sb.append("[省略区域关键行索引]\n");
            for (String hint : keyLineHints) {
                sb.append("  ").append(hint).append('\n');
            }
        }
        sb.append('\n');

        // 尾部
        for (int i = omitEnd; i < totalLines; i++) {
            sb.append(lines[i]);
            if (i < totalLines - 1) sb.append('\n');
        }

        return sb.toString();
    }

    /**
     * 扫描省略区域中的「关键行」，返回索引提示列表（最多 15 条）。
     * 关键行包括：错误/异常关键词、TODO/FIXME、配置项声明、SQL、端口/URL 等。
     */
    private static List<String> scanKeyLines(String[] lines, int from, int to) {
        List<String> hints = new ArrayList<>();
        int maxHints = 15;
        for (int i = from; i < to && hints.size() < maxHints; i++) {
            String line = lines[i];
            if (line == null || line.isBlank()) continue;
            String lower = line.toLowerCase().trim();

            if (isKeyLine(lower)) {
                // 截取行内容预览（最多 80 字符）
                String preview = line.trim();
                if (preview.length() > 80) preview = preview.substring(0, 80) + "...";
                hints.add("L" + (i + 1) + ": " + preview);
            }
        }
        return hints;
    }

    /** 判断是否为值得索引的关键行。 */
    private static boolean isKeyLine(String lowerTrimmed) {
        // 错误/异常/警告
        if (lowerTrimmed.contains("error") || lowerTrimmed.contains("exception")
                || lowerTrimmed.contains("fatal") || lowerTrimmed.contains("warn")) return true;
        // 开发标记
        if (lowerTrimmed.contains("todo") || lowerTrimmed.contains("fixme")
                || lowerTrimmed.contains("hack") || lowerTrimmed.contains("xxx")) return true;
        // 配置相关
        if (lowerTrimmed.contains("port") || lowerTrimmed.contains("host")
                || lowerTrimmed.contains("password") || lowerTrimmed.contains("secret")) return true;
        // 数据源/连接
        if (lowerTrimmed.contains("datasource") || lowerTrimmed.contains("jdbc:")
                || lowerTrimmed.contains("url=") || lowerTrimmed.contains("url:")) return true;
        return false;
    }

    // ── 命令输出压缩 ────────────────────────────────────────────────────────────

    /**
     * 压缩命令输出。超出阈值时进行去重压缩 + 头尾保留。
     * <p>
     * 压缩策略（按优先级）：
     * <ol>
     *   <li>如果长度 ≤ threshold，原样返回</li>
     *   <li>检测连续重复行/模式 → 折叠为 "[重复 N 次]"</li>
     *   <li>如果去重后仍超阈值 → 头尾截断</li>
     * </ol>
     *
     * @param output    原始命令输出
     * @param threshold 触发压缩的字符数阈值（≤0 使用默认值）
     * @return 压缩后的输出
     */
    public static String compressCommandOutput(String output, int threshold) {
        if (output == null || output.isEmpty()) return output;
        int limit = threshold > 0 ? threshold : DEFAULT_COMMAND_OUTPUT_THRESHOLD;
        if (output.length() <= limit) return output;

        // 第一步：连续重复行折叠
        String deduped = deduplicateConsecutiveLines(output);
        if (deduped.length() <= limit) return deduped;

        // 第二步：仍然超长，头尾截断
        String[] lines = deduped.split("\n", -1);
        int totalLines = lines.length;
        int headLines = Math.max(40, limit / 160);
        int tailLines = Math.max(20, limit / 240);

        if (totalLines <= headLines + tailLines + 5) {
            // 行数不多但单行很长，按字符截断
            return charTruncate(deduped, limit);
        }

        StringBuilder sb = new StringBuilder(limit + 256);
        for (int i = 0; i < headLines && i < totalLines; i++) {
            sb.append(lines[i]).append('\n');
        }
        int omitted = totalLines - headLines - tailLines;
        sb.append("\n... [省略 ").append(omitted).append(" 行，共 ").append(totalLines).append(" 行] ...\n\n");
        for (int i = totalLines - tailLines; i < totalLines; i++) {
            sb.append(lines[i]);
            if (i < totalLines - 1) sb.append('\n');
        }
        return sb.toString();
    }

    /**
     * 折叠连续重复行。如果同一行连续出现 ≥ 3 次，折叠为一行 + "[... 重复 N 次]"。
     */
    static String deduplicateConsecutiveLines(String text) {
        String[] lines = text.split("\n", -1);
        if (lines.length <= 3) return text;

        StringBuilder sb = new StringBuilder(text.length());
        int i = 0;
        while (i < lines.length) {
            String current = lines[i].trim();
            int j = i + 1;
            // 统计连续相同行（忽略前后空白）
            while (j < lines.length && lines[j].trim().equals(current)) {
                j++;
            }
            int count = j - i;
            if (count >= 3 && !current.isEmpty()) {
                sb.append(lines[i]).append('\n');
                sb.append("[... 以上行重复 ").append(count).append(" 次，已折叠]\n");
            } else {
                for (int k = i; k < j; k++) {
                    sb.append(lines[k]).append('\n');
                }
            }
            i = j;
        }
        // 移除末尾多余换行
        if (sb.length() > 0 && sb.charAt(sb.length() - 1) == '\n') {
            sb.setLength(sb.length() - 1);
        }
        return sb.toString();
    }

    /** 按字符截断：保留前 60% 和后 30%，中间标注省略。 */
    private static String charTruncate(String text, int limit) {
        int headChars = (int) (limit * 0.6);
        int tailChars = (int) (limit * 0.3);
        int omitted = text.length() - headChars - tailChars;
        return text.substring(0, headChars)
                + "\n\n... [省略 " + omitted + " 字符] ...\n\n"
                + text.substring(text.length() - tailChars);
    }

    // ── 通用 Map 字段压缩 ──────────────────────────────────────────────────────

    /**
     * 对 Map 中指定 String 字段做命令输出压缩（去重 + 头尾截断）。
     * 如果压缩生效，额外写入 {fieldName}Compressed / originalChars / compressedChars。
     * <p>
     * 也兼容字段值为 byte[] 的情况（先转 UTF-8 String，再压缩，回写 String）。
     *
     * @param result    工具返回的 Map
     * @param fieldName 要压缩的字段名（如 "output"、"body"、"result"）
     * @param threshold 压缩阈值（字符数），≤0 使用默认值
     */
    public static void compressMapField(Map<String, Object> result, String fieldName, int threshold) {
        if (result == null || fieldName == null) return;
        Object value = result.get(fieldName);
        String raw;
        if (value instanceof String s) {
            raw = s;
        } else if (value instanceof byte[] bytes) {
            raw = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
            result.put(fieldName, raw); // byte[] → String 减少 JSON base64 膨胀
        } else {
            return;
        }
        if (raw.isEmpty()) return;
        String compressed = compressCommandOutput(raw, threshold);
        if (compressed.length() < raw.length()) {
            result.put(fieldName, compressed);
            result.put(fieldName + "Compressed", true);
            result.put("originalChars", raw.length());
            result.put("compressedChars", compressed.length());
        }
    }
}
