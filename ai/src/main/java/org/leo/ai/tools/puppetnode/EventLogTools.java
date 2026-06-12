package org.leo.ai.tools.puppetnode;

import org.leo.ai.agent.AiToolContext;
import org.leo.ai.util.PuppetNodeSessionUtils;
import org.leo.core.puppet.impl.JavaPuppetNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.P;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 操作系统事件日志管理工具
 * <p>
 * 在 puppet 侧管理操作系统日志（Windows wevtutil / macOS log show / Linux journalctl）。
 */
@Component
public class EventLogTools {

    private static final Logger log = LoggerFactory.getLogger(EventLogTools.class);

    @Tool("列举 puppet 侧可用的日志源，自动适配 Windows / macOS / Linux。"
            + "返回每个 source 的 name（路径或通道名）、type、format（如 nginx-access）、size、large（>100MB）、common 标记。"
            + "建议先调用此方法确认日志源，再用 queryEventLog 查询或 aggregateEventLog 聚合统计。")
    public Map<String, Object> listEventLogSources() throws Exception {
        String sessionId = AiToolContext.requireSessionId();
        JavaPuppetNode node = PuppetNodeSessionUtils.getJavaPuppetNode(sessionId);
        return node.listEventLogSources();
    }

    @Tool("查询 puppet 侧指定日志源的日志条目，自动适配 Windows / macOS / Linux 及应用日志文件。\n"
            + "翻页（仅文件型 source）：不传 cursor → 末尾 maxEntries 行；cursor=startByte + direction=older → 更老一页；cursor=endByte + direction=newer → 更新一页。\n"
            + "时间跳转：传 since → 自动定位到首个 >= since 的位置。\n"
            + "服务端过滤：传 format=nginx-access/apache-access 时，minStatus/maxStatus/ipPrefix/pathPrefix 直接在 puppet 侧过滤。")
    public Map<String, Object> queryEventLog(
            @P("日志源(Windows 通道/macOS 子系统/Linux unit/文件路径)") String source,
            @P("最大返回条目数(默认 50,最大 500)") int maxEntries,
            @P("关键字") String keyword,
            @P("级别过滤") String level,
            @P("起始时间(epoch ms / yyyy-MM-dd HH:mm:ss / 相对 1h/30m/1d)。文件型 source 会触发二分") String since,
            @P("结束时间") String until,
            @P("Windows 事件 ID") String eventId,
            @P("format: nginx-access/apache-access/nginx-error/mysql-error/tomcat/raw") String format,
            @P("字节扫描上限(从末尾)") int maxBytes,
            @P("翻页字节 cursor(配合 direction)") Long cursor,
            @P("翻页方向: older/newer/latest") String direction,
            @P("最小状态码(awk 推下)") Integer minStatus,
            @P("最大状态码(awk 推下)") Integer maxStatus,
            @P("IP 前缀(awk 推下)") String ipPrefix,
            @P("Path 前缀(awk 推下)") String pathPrefix) throws Exception {
        String sessionId = AiToolContext.requireSessionId();
        JavaPuppetNode node = PuppetNodeSessionUtils.getJavaPuppetNode(sessionId);
        if (maxEntries <= 0) maxEntries = 50;
        if (maxBytes < 0) maxBytes = 0;
        return node.queryEventLog(source, maxEntries, keyword, level, since, until, eventId, format, maxBytes,
                cursor, direction, minStatus, maxStatus, ipPrefix, pathPrefix);
    }

    @Tool("对应用日志文件做 top-N 聚合统计。仅支持文件路径形式的 source。\n"
            + "groupBy 分组维度：ip/path/status/statusClass(2xx/3xx/4xx/5xx)/ua/method/level/hour/day。\n"
            + "可选过滤：keyword、minStatus/maxStatus、ipPrefix/pathPrefix。\n"
            + "大文件建议用 maxBytes 限制扫描范围（如 256MB）。适合定位 4xx/5xx Top IP、慢路径、爬虫 UA 等。")
    public Map<String, Object> aggregateEventLog(
            @P("日志文件路径(必填,以 / 开头,禁止 ../proc/sys/dev)") String source,
            @P("格式: nginx-access/apache-access/nginx-error/mysql-error/tomcat 等。建议先用 listEventLogSources 取到 format 再复用") String format,
            @P("分组维度: ip/path/status/statusClass/ua/method/level/hour/day(默认 ip)") String groupBy,
            @P("返回前 N 组(默认 20,最大 500)") int topN,
            @P("最多扫描的行数(默认 5 万,最大 50 万)") int maxScan,
            @P("最多扫描的字节数(从文件末尾,如 256000000=256MB),0 表示不限。大文件建议 64-256MB") int maxBytes,
            @P("关键字预过滤(grep -i)") String keyword,
            @P("最小状态码(包含)") Integer minStatus,
            @P("最大状态码(包含)") Integer maxStatus,
            @P("IP 前缀过滤") String ipPrefix,
            @P("Path 前缀过滤") String pathPrefix,
            @P("强制走 Java 慢路径(返回精确 scanned/unique/total)") Boolean slow) throws Exception {
        String sessionId = AiToolContext.requireSessionId();
        JavaPuppetNode node = PuppetNodeSessionUtils.getJavaPuppetNode(sessionId);
        if (topN <= 0) topN = 20;
        if (maxScan <= 0) maxScan = 50000;
        if (maxBytes < 0) maxBytes = 0;
        return node.aggregateEventLog(source, format, groupBy, topN, maxScan, maxBytes, keyword,
                minStatus, maxStatus, ipPrefix, pathPrefix, slow != null && slow.booleanValue());
    }

    @Tool("获取应用日志文件的元数据: 大小、修改时间、首末行、首末时间戳。"
            + "适合给翻页/时间跳转做范围提示(让 UI 知道文件时间跨度,避免跳到无效时间)。"
            + "可选:传 lines>0 时附带返回 head/tail 取样行,用于自定义路径的可读性探测和格式识别。")
    public Map<String, Object> metaEventLog(
            @P("文件路径(必填)") String source,
            @P("format 提示,辅助解析首末行时间戳") String format,
            @P("可选:返回 N 行取样(0 不返回,最大 100)") int lines,
            @P("true=tail(末尾),false=head(开头)。默认 false") Boolean fromTail) throws Exception {
        String sessionId = AiToolContext.requireSessionId();
        JavaPuppetNode node = PuppetNodeSessionUtils.getJavaPuppetNode(sessionId);
        if (lines < 0) lines = 0;
        if (lines > 100) lines = 100;
        return node.metaEventLog(source, format, lines, fromTail != null && fromTail.booleanValue());
    }

    @Tool("获取 puppet 侧日志统计信息，自动适配 Windows / macOS / Linux。"
            + "返回日志通道条目数、大小、时间范围等。不指定 source 则返回全局统计。")
    public Map<String, Object> getEventLogStats(
            @P("日志源名称（可选，不指定则返回全局统计）") String source) throws Exception {
        String sessionId = AiToolContext.requireSessionId();
        JavaPuppetNode node = PuppetNodeSessionUtils.getJavaPuppetNode(sessionId);
        return node.getEventLogStats(source);
    }

    @Tool("清除 puppet 侧指定日志，自动适配 Windows / macOS / Linux。"
            + "⚠️ 不可逆操作，清除前确认目标。")
    public Map<String, Object> clearEventLog(
            @P("日志源名称（必填）") String source) throws Exception {
        String sessionId = AiToolContext.requireSessionId();
        JavaPuppetNode node = PuppetNodeSessionUtils.getJavaPuppetNode(sessionId);
        return node.clearEventLog(source);
    }
}
