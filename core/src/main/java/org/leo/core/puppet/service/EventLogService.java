package org.leo.core.puppet.service;

import org.leo.core.net.Communication;
import org.leo.core.net.layer.RequestLayer;
import org.leo.core.net.layer.ResponseLayer;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 操作系统日志管理服务（服务器端解析版）。
 * 跨平台支持 Windows / macOS / Linux。
 * 支持 listSources / query / stats / clear / aggregate / meta 六种动作。
 */
public class EventLogService extends ComponentService {

    // ── OS constants ──────────────────────────────────────────────────────────
    private static final int OS_WINDOWS = 0;
    private static final int OS_MACOS   = 1;
    private static final int OS_LINUX   = 2;

    public EventLogService(Communication communication,
                           List<RequestLayer> requestLayers,
                           List<ResponseLayer> responseLayers) {
        super(communication, requestLayers, responseLayers);
    }

    // ── OS detection ──────────────────────────────────────────────────────────

    private int detectOS() throws Exception {
        String out = execFast("uname -s 2>/dev/null || echo Windows");
        if (out == null || out.trim().isEmpty() || out.toLowerCase().contains("windows")) return OS_WINDOWS;
        String s = out.trim().toLowerCase();
        if (s.contains("darwin")) return OS_MACOS;
        return OS_LINUX;
    }

    private String osName(int osType) {
        if (osType == OS_WINDOWS) return "windows";
        if (osType == OS_MACOS)   return "macos";
        return "linux";
    }

    // ── Public interface ──────────────────────────────────────────────────────

    public Map<String, Object> listSources() throws Exception {
        int osType = detectOS();
        List<Map<String, Object>> sources = new ArrayList<Map<String, Object>>();
        List<String> diagnostics = new ArrayList<String>();

        if (osType == OS_WINDOWS)      listSourcesWindows(sources, diagnostics);
        else if (osType == OS_MACOS)   listSourcesMacOS(sources, diagnostics);
        else                            listSourcesLinux(sources, diagnostics);

        Map<String, Object> result = new HashMap<String, Object>();
        result.put("code",   200);
        result.put("action", "listSources");
        result.put("total",  sources.size());
        result.put("sources", sources);
        result.put("os",     osName(osType));
        if (!diagnostics.isEmpty()) result.put("diagnostics", diagnostics);
        return result;
    }

    public Map<String, Object> stats(String source) throws Exception {
        int osType = detectOS();
        if (osType == OS_WINDOWS) return statsWindows(source);
        if (osType == OS_MACOS)   return statsMacOS(source);
        return statsLinux(source);
    }

    public Map<String, Object> clear(String source) throws Exception {
        if (source == null || source.trim().isEmpty()) return error(400, "source is required for clear action");
        int osType = detectOS();
        String output;
        if (osType == OS_WINDOWS) {
            output = execFast(winCmd("wevtutil cl \"" + escapeCmd(source) + "\""));
        } else if (source.startsWith("/")) {
            output = execFast("truncate -s 0 '" + source.replace("'", "'\\''") + "' 2>&1");
        } else if (osType == OS_MACOS) {
            output = "macOS unified log cannot be selectively cleared. "
                    + "To truncate a file: sudo truncate -s 0 " + source;
        } else {
            output = "journalctl does not support clearing by unit. "
                    + "To vacuum journal: sudo journalctl --vacuum-time=1s --vacuum-size=1M";
        }

        Map<String, Object> result = new HashMap<String, Object>();
        result.put("code",   200);
        result.put("action", "clear");
        result.put("source", source);
        result.put("output", output != null ? output.trim() : "");
        return result;
    }

    public Map<String, Object> meta(String source, String format, int lines, boolean fromTail) throws Exception {
        if (source == null || source.isEmpty() || !source.startsWith("/"))
            return error(400, "meta 需要文件路径形式的 source(以 / 开头)");
        if (!isSafeReadPath(source))
            return error(400, "source 路径不安全(禁止 .. / /proc / /sys / /dev)");

        String safePath = source.replace("'", "'\\''");
        long size = statFileSize(source);

        Map<String, Object> result = new HashMap<String, Object>();
        result.put("code",   200);
        result.put("action", "meta");
        result.put("source", source);
        if (size >= 0) {
            result.put("size",      size);
            result.put("sizeHuman", humanSize(size));
            result.put("large",     size > 100L * 1024L * 1024L);
        }

        String mtime = execFast("stat -c %Y '" + safePath + "' 2>/dev/null || stat -f %m '" + safePath + "' 2>/dev/null");
        if (mtime != null && !mtime.trim().isEmpty()) {
            try { result.put("lastModified", Long.parseLong(mtime.trim()) * 1000L); }
            catch (NumberFormatException ignored) {}
        }

        String firstLine = execFast("head -1 '" + safePath + "' 2>/dev/null");
        String lastLine  = execFast("tail -1 '" + safePath + "' 2>/dev/null");
        if (firstLine != null) {
            firstLine = stripTrailingNewline(firstLine);
            result.put("firstLine", truncate(firstLine, 1024));
            long ts = parseLogTimestamp(firstLine, format);
            if (ts > 0) result.put("firstTimestamp", ts);
        }
        if (lastLine != null) {
            lastLine = stripTrailingNewline(lastLine);
            result.put("lastLine", truncate(lastLine, 1024));
            long ts = parseLogTimestamp(lastLine, format);
            if (ts > 0) result.put("lastTimestamp", ts);
        }

        if (lines > 0) {
            if (lines > 100) lines = 100;
            String cmd = (fromTail ? "tail" : "head") + " -" + lines + " '" + safePath + "' 2>/dev/null";
            String output = execFast(cmd);
            List<String> lineList = new ArrayList<String>();
            if (output != null && !output.isEmpty()) {
                for (String l : output.split("\n")) {
                    if (lineList.size() >= lines) break;
                    if (l != null) lineList.add(l);
                }
            }
            result.put("lines", lineList);
        }
        return result;
    }

    public Map<String, Object> meta(String source, String format) throws Exception {
        return meta(source, format, 0, false);
    }

    // ── query ─────────────────────────────────────────────────────────────────

    public Map<String, Object> query(String source, int maxEntries, String keyword,
                                     String level, String since, String until,
                                     String eventId) throws Exception {
        return query(QueryOptions.of(source, maxEntries)
                .keyword(keyword).level(level).since(since).until(until).eventId(eventId));
    }

    public Map<String, Object> query(String source, int maxEntries, String keyword,
                                     String level, String since, String until,
                                     String eventId, String format) throws Exception {
        return query(QueryOptions.of(source, maxEntries)
                .keyword(keyword).level(level).since(since).until(until).eventId(eventId)
                .format(format));
    }

    public Map<String, Object> query(String source, int maxEntries, String keyword,
                                     String level, String since, String until,
                                     String eventId, String format, int maxBytes) throws Exception {
        return query(QueryOptions.of(source, maxEntries)
                .keyword(keyword).level(level).since(since).until(until).eventId(eventId)
                .format(format).maxBytes(maxBytes));
    }

    public Map<String, Object> query(String source, int maxEntries, String keyword,
                                     String level, String since, String until,
                                     String eventId, String format, int maxBytes,
                                     Long cursor, String direction,
                                     Integer minStatus, Integer maxStatus,
                                     String ipPrefix, String pathPrefix) throws Exception {
        return query(QueryOptions.of(source, maxEntries)
                .keyword(keyword).level(level).since(since).until(until).eventId(eventId)
                .format(format).maxBytes(maxBytes)
                .cursor(cursor).direction(direction)
                .statusRange(minStatus, maxStatus)
                .ipPrefix(ipPrefix).pathPrefix(pathPrefix));
    }

    public Map<String, Object> query(QueryOptions opts) throws Exception {
        int maxEntries = opts.maxEntries;
        if (maxEntries > 500) maxEntries = 500;
        if (maxEntries < 1)   maxEntries = 1;

        List<Map<String, Object>> entries = new ArrayList<Map<String, Object>>();
        List<String> diagnostics = new ArrayList<String>();
        Map<String, Object> fileMeta = new HashMap<String, Object>();

        if (opts.source != null && opts.source.startsWith("/")) {
            queryLogFile(opts.source, maxEntries, opts.keyword, opts.format,
                    opts.since, opts.direction, opts.maxBytes, opts.cursor,
                    opts.minStatus != null ? opts.minStatus.intValue() : -1,
                    opts.maxStatus != null ? opts.maxStatus.intValue() : -1,
                    opts.ipPrefix, opts.pathPrefix,
                    entries, diagnostics, fileMeta);
        } else {
            int osType = detectOS();
            if (osType == OS_WINDOWS) {
                queryWindows(opts.source, maxEntries, opts.keyword, opts.level,
                        opts.eventId, opts.since, entries, diagnostics);
            } else if (osType == OS_MACOS) {
                queryMacOS(opts.source, maxEntries, opts.keyword, opts.level,
                        opts.since, opts.until, entries, diagnostics);
            } else {
                queryLinux(opts.source, maxEntries, opts.keyword, opts.level,
                        opts.since, opts.until, opts.format,
                        entries, diagnostics, fileMeta);
            }
        }

        Map<String, Object> result = new HashMap<String, Object>();
        result.put("code",    200);
        result.put("action",  "query");
        result.put("total",   entries.size());
        result.put("entries", entries);
        if (opts.source != null) result.put("source", opts.source);
        if (!fileMeta.isEmpty()) result.put("meta", fileMeta);
        if (!diagnostics.isEmpty()) result.put("diagnostics", diagnostics);
        return result;
    }

    // ── aggregate ─────────────────────────────────────────────────────────────

    public Map<String, Object> aggregate(String source, String format, String groupBy,
                                         int topN, int maxScan, String keyword,
                                         Integer minStatus, Integer maxStatus,
                                         String ipPrefix, String pathPrefix) throws Exception {
        return aggregate(AggregateOptions.of(source, groupBy)
                .format(format).topN(topN).maxScan(maxScan).keyword(keyword)
                .statusRange(minStatus, maxStatus).ipPrefix(ipPrefix).pathPrefix(pathPrefix));
    }

    public Map<String, Object> aggregate(String source, String format, String groupBy,
                                         int topN, int maxScan, int maxBytes, String keyword,
                                         Integer minStatus, Integer maxStatus,
                                         String ipPrefix, String pathPrefix, boolean slow) throws Exception {
        return aggregate(AggregateOptions.of(source, groupBy)
                .format(format).topN(topN).maxScan(maxScan).maxBytes(maxBytes).keyword(keyword)
                .statusRange(minStatus, maxStatus).ipPrefix(ipPrefix).pathPrefix(pathPrefix)
                .slow(slow));
    }

    public Map<String, Object> aggregate(AggregateOptions opts) throws Exception {
        String source  = opts.source;
        String format  = opts.format;
        String groupBy = opts.groupBy != null && !opts.groupBy.isEmpty() ? opts.groupBy : "ip";
        int topN       = opts.topN > 0 ? Math.min(opts.topN, 500) : 20;
        int maxScan    = opts.maxScan > 0 ? Math.min(opts.maxScan, 500000) : 50000;
        if (maxScan < 100) maxScan = 100;

        if (source == null || source.isEmpty() || !source.startsWith("/"))
            return error(400, "aggregate 需要文件路径形式的 source(以 / 开头)");
        if (!isSafeReadPath(source))
            return error(400, "source 路径不安全(禁止 .. / /proc / /sys / /dev)");

        return doAggregate(source, format, groupBy, topN, maxScan,
                opts.maxBytes, opts.keyword,
                opts.minStatus != null ? opts.minStatus.intValue() : -1,
                opts.maxStatus != null ? opts.maxStatus.intValue() : -1,
                opts.ipPrefix, opts.pathPrefix, opts.slow);
    }

    // ── listSources implementations ───────────────────────────────────────────

    private void listSourcesWindows(List<Map<String, Object>> sources, List<String> diagnostics) throws Exception {
        String output = execFast(winCmd("wevtutil el"));
        if (output == null || output.trim().isEmpty()) {
            diagnostics.add("wevtutil el returned empty");
            return;
        }
        diagnostics.add("source=wevtutil el");

        String[] commonChannels = {
            "Application", "Security", "System", "Setup",
            "Microsoft-Windows-Sysmon/Operational",
            "Microsoft-Windows-PowerShell/Operational",
            "Microsoft-Windows-TaskScheduler/Operational",
            "Microsoft-Windows-Windows Defender/Operational"
        };
        Map<String, String> commonMap = new HashMap<String, String>();
        for (String ch : commonChannels) commonMap.put(ch.toLowerCase(), ch);

        int totalChannels = 0;
        for (String line : output.split("\n")) {
            String channel = line.trim();
            if (channel.isEmpty()) continue;
            totalChannels++;
            boolean isCommon = commonMap.containsKey(channel.toLowerCase());
            if (isCommon || sources.size() < 200) {
                Map<String, Object> src = new HashMap<String, Object>();
                src.put("name", channel);
                src.put("type", "eventlog");
                if (isCommon) src.put("common", Boolean.TRUE);
                sources.add(src);
            }
        }
        diagnostics.add("totalChannels=" + totalChannels);
    }

    private void listSourcesMacOS(List<Map<String, Object>> sources, List<String> diagnostics) throws Exception {
        diagnostics.add("source=log(1) unified logging");
        String[] commonSubsystems = {
            "com.apple.securityd", "com.apple.authd", "com.apple.opendirectoryd",
            "com.apple.launchd", "com.apple.ftp", "com.apple.sshd",
            "com.apple.SystemConfiguration", "com.apple.CrashReporter"
        };
        for (String ss : commonSubsystems) {
            Map<String, Object> src = new HashMap<String, Object>();
            src.put("name", ss);
            src.put("type", "unified-log");
            src.put("common", Boolean.TRUE);
            sources.add(src);
        }

        String lsOutput = execFast("ls -1 /var/log/ 2>/dev/null");
        if (lsOutput != null) {
            for (String fname : lsOutput.split("\n")) {
                fname = fname.trim();
                if (fname.isEmpty()) continue;
                if (sources.size() >= 100) break;
                if (fname.endsWith(".log") || fname.equals("system.log")
                        || fname.equals("install.log") || fname.equals("wifi.log")
                        || fname.equals("asl")) {
                    Map<String, Object> src = new HashMap<String, Object>();
                    src.put("name", "/var/log/" + fname);
                    src.put("type", "file");
                    sources.add(src);
                }
            }
        }
        listAppLogsMacOS(sources, diagnostics);
    }

    private void listSourcesLinux(List<Map<String, Object>> sources, List<String> diagnostics) throws Exception {
        String output = execFast("journalctl --field=_SYSTEMD_UNIT 2>/dev/null | head -100");
        if (output != null && !output.trim().isEmpty()) {
            diagnostics.add("source=journalctl units");
            for (String unit : output.split("\n")) {
                unit = unit.trim();
                if (unit.isEmpty()) continue;
                Map<String, Object> src = new HashMap<String, Object>();
                src.put("name", unit);
                src.put("type", "journald");
                sources.add(src);
            }
        }

        String lsOutput = execFast(
                "find /var/log -maxdepth 2 -name '*.log' -o -name 'syslog' "
                + "-o -name 'messages' -o -name 'auth.log' -o -name 'secure' "
                + "-o -name 'kern.log' -o -name 'dmesg' -o -name 'boot.log' "
                + "-o -name 'cron.log' -o -name 'maillog' -o -name 'wtmp' "
                + "-o -name 'btmp' -o -name 'lastlog' -o -name 'faillog' 2>/dev/null | head -80");
        if (lsOutput != null && !lsOutput.trim().isEmpty()) {
            diagnostics.add("source=/var/log files");
            for (String path : lsOutput.split("\n")) {
                path = path.trim();
                if (path.isEmpty()) continue;
                Map<String, Object> src = new HashMap<String, Object>();
                src.put("name", path);
                src.put("type", "file");
                if (path.endsWith("/auth.log") || path.endsWith("/secure")
                        || path.endsWith("/syslog") || path.endsWith("/messages")) {
                    src.put("common", Boolean.TRUE);
                }
                sources.add(src);
            }
        }

        if (sources.isEmpty()) {
            diagnostics.add("no log sources found");
        }
        listAppLogsLinux(sources, diagnostics);
    }

    // ── query implementations ─────────────────────────────────────────────────

    private void queryWindows(String source, int maxEntries, String keyword,
                              String level, String eventId, String since,
                              List<Map<String, Object>> entries,
                              List<String> diagnostics) throws Exception {
        if (source == null || source.isEmpty()) source = "System";

        StringBuilder cmd = new StringBuilder();
        cmd.append("wevtutil qe \"").append(escapeCmd(source)).append("\"");
        cmd.append(" /c:").append(maxEntries);
        cmd.append(" /rd:true /f:text");

        StringBuilder xpath = new StringBuilder();
        boolean hasFilter = false;
        if (eventId != null && !eventId.isEmpty()) {
            xpath.append("EventID=").append(escapeShell(eventId));
            hasFilter = true;
        }
        if (level != null && !level.isEmpty()) {
            int levelNum = windowsLevelNum(level);
            if (levelNum >= 0) {
                if (hasFilter) xpath.append(" and ");
                xpath.append("Level=").append(levelNum);
                hasFilter = true;
            }
        }
        if (since != null && !since.isEmpty()) {
            long ms = parseRelativeTimeMs(since);
            if (ms > 0) {
                if (hasFilter) xpath.append(" and ");
                xpath.append("TimeCreated[timediff(@SystemTime) <= ").append(ms).append("]");
                hasFilter = true;
            }
        }
        if (hasFilter) cmd.append(" /q:\"*[System[").append(xpath).append("]]\"");

        diagnostics.add("cmd=wevtutil qe ...");
        String output = execWithTimeout(winCmd(cmd.toString()), 30);
        if (output == null || output.trim().isEmpty()) {
            diagnostics.add("wevtutil returned empty");
            return;
        }
        parseWevtutilTextOutput(output, entries, keyword, maxEntries);
    }

    private void parseWevtutilTextOutput(String output,
                                         List<Map<String, Object>> entries,
                                         String keyword, int maxEntries) {
        String[] lines = output.split("\n");
        Map<String, Object> current = null;
        String lastKey = null;

        for (String line : lines) {
            String trimmed = line.trim();

            if (trimmed.startsWith("Event[") || (trimmed.isEmpty() && current != null)) {
                if (current != null && !current.isEmpty()) {
                    if (matchesKeyword(current, keyword)) {
                        entries.add(current);
                        if (entries.size() >= maxEntries) return;
                    }
                }
                if (trimmed.startsWith("Event[")) {
                    current = new HashMap<String, Object>();
                    lastKey = null;
                }
                continue;
            }

            if (current == null) {
                if (trimmed.contains(":")) { current = new HashMap<String, Object>(); lastKey = null; }
                else continue;
            }

            int colonIdx = trimmed.indexOf(':');
            if (colonIdx > 0 && colonIdx < trimmed.length() - 1 && !trimmed.startsWith("  ")) {
                String key = trimmed.substring(0, colonIdx).trim();
                String val = trimmed.substring(colonIdx + 1).trim();
                current.put(key, val);
                lastKey = key;
            } else if (lastKey != null && !trimmed.isEmpty()) {
                Object prev = current.get(lastKey);
                if (prev != null) current.put(lastKey, prev.toString() + " " + trimmed);
            }
        }
        if (current != null && !current.isEmpty() && matchesKeyword(current, keyword)) {
            entries.add(current);
        }
    }

    private int windowsLevelNum(String level) {
        if (level == null) return -1;
        String l = level.toLowerCase();
        if ("critical".equals(l))                      return 1;
        if ("error".equals(l))                         return 2;
        if ("warning".equals(l))                       return 3;
        if ("information".equals(l) || "info".equals(l)) return 4;
        if ("verbose".equals(l) || "debug".equals(l)) return 5;
        return -1;
    }

    private void queryMacOS(String source, int maxEntries, String keyword,
                            String level, String since, String until,
                            List<Map<String, Object>> entries,
                            List<String> diagnostics) throws Exception {
        StringBuilder cmd = new StringBuilder();
        cmd.append("log show");

        if (since != null && !since.isEmpty()) {
            String period = convertToPeriod(since);
            if (period != null) cmd.append(" --last ").append(period);
        } else {
            cmd.append(" --last 1h");
        }

        if (source != null && !source.isEmpty()) {
            cmd.append(" --predicate 'subsystem == \"").append(escapeShell(source)).append("\"'");
        }
        if (level != null && !level.isEmpty()) {
            String macLevel = macOSLogLevel(level);
            if (macLevel != null) cmd.append(" --predicate 'messageType == ").append(macLevel).append("'");
        }
        cmd.append(" --style compact 2>/dev/null | head -").append(maxEntries + 1);

        diagnostics.add("cmd=log show ...");
        String output = execWithTimeout(cmd.toString(), 30);
        if (output == null || output.trim().isEmpty()) {
            diagnostics.add("log show returned empty");
            return;
        }

        for (String line : output.split("\n")) {
            if (entries.size() >= maxEntries) break;
            line = line.trim();
            if (line.isEmpty() || line.startsWith("Filtering") || line.startsWith("Timestamp")) continue;
            if (keyword != null && !keyword.isEmpty()
                    && !line.toLowerCase().contains(keyword.toLowerCase())) continue;

            Map<String, Object> entry = new HashMap<String, Object>();
            entry.put("raw", truncate(line, 2048));
            if (line.length() > 20) {
                entry.put("timestamp", line.substring(0, 19).trim());
                entry.put("message",   truncate(line.substring(19).trim(), 1024));
            }
            entries.add(entry);
        }
    }

    private String macOSLogLevel(String level) {
        if (level == null) return null;
        String l = level.toLowerCase();
        if ("error".equals(l) || "critical".equals(l)) return "16";
        if ("warning".equals(l))  return "17";
        if ("info".equals(l))     return "1";
        if ("debug".equals(l))    return "2";
        return null;
    }

    private void queryLinux(String source, int maxEntries, String keyword,
                            String level, String since, String until, String format,
                            List<Map<String, Object>> entries,
                            List<String> diagnostics,
                            Map<String, Object> fileMeta) throws Exception {
        StringBuilder cmd = new StringBuilder();
        cmd.append("journalctl");
        if (source != null && !source.isEmpty()) cmd.append(" -u ").append(escapeShell(source));
        if (since != null && !since.isEmpty())
            cmd.append(" --since '").append(escapeJournalTime(since)).append("'");
        if (until != null && !until.isEmpty())
            cmd.append(" --until '").append(escapeJournalTime(until)).append("'");
        if (level != null && !level.isEmpty()) {
            String prio = journalctlPriority(level);
            if (prio != null) cmd.append(" -p ").append(prio);
        }
        if (keyword != null && !keyword.isEmpty())
            cmd.append(" -g '").append(escapeShell(keyword)).append("'");
        cmd.append(" --no-pager -r -n ").append(maxEntries).append(" -o short-iso 2>/dev/null");

        diagnostics.add("cmd=journalctl ...");
        String output = execFast(cmd.toString());

        if (output != null && !output.trim().isEmpty()
                && !output.contains("command not found")
                && !output.contains("No journal files")) {
            diagnostics.add("source=journalctl");
            parseJournalctlOutput(output, entries, maxEntries);
            return;
        }

        diagnostics.add("journalctl unavailable, trying /var/log/syslog or /var/log/messages");
        String fallbackPath = null;
        String check = execFast("test -f /var/log/syslog && echo YES 2>/dev/null");
        if (check != null && check.trim().contains("YES")) {
            fallbackPath = "/var/log/syslog";
        } else {
            check = execFast("test -f /var/log/messages && echo YES 2>/dev/null");
            if (check != null && check.trim().contains("YES")) fallbackPath = "/var/log/messages";
        }

        if (fallbackPath != null) {
            queryLogFile(fallbackPath, maxEntries, keyword, format, null, "latest", 0, null,
                    -1, -1, null, null, entries, diagnostics, fileMeta);
        } else {
            diagnostics.add("no readable log file found");
        }
    }

    private void parseJournalctlOutput(String output,
                                       List<Map<String, Object>> entries,
                                       int maxEntries) {
        for (String line : output.split("\n")) {
            if (entries.size() >= maxEntries) break;
            line = line.trim();
            if (line.isEmpty() || line.startsWith("-- ")) continue;

            Map<String, Object> entry = new HashMap<String, Object>();
            entry.put("raw", truncate(line, 2048));

            int firstSpace = line.indexOf(' ');
            if (firstSpace > 0) {
                entry.put("timestamp", line.substring(0, firstSpace));
                String rest = line.substring(firstSpace + 1).trim();
                int secondSpace = rest.indexOf(' ');
                if (secondSpace > 0) {
                    entry.put("hostname", rest.substring(0, secondSpace));
                    String msg = rest.substring(secondSpace + 1);
                    int colonIdx = msg.indexOf(": ");
                    if (colonIdx > 0) {
                        entry.put("source",  msg.substring(0, colonIdx));
                        entry.put("message", truncate(msg.substring(colonIdx + 2), 1024));
                    } else {
                        entry.put("message", truncate(msg, 1024));
                    }
                }
            }
            entries.add(entry);
        }
    }

    private String journalctlPriority(String level) {
        if (level == null) return null;
        String l = level.toLowerCase();
        if ("critical".equals(l) || "crit".equals(l)) return "crit";
        if ("error".equals(l)    || "err".equals(l))  return "err";
        if ("warning".equals(l)  || "warn".equals(l)) return "warning";
        if ("info".equals(l))    return "info";
        if ("debug".equals(l))   return "debug";
        return null;
    }

    // ── queryLogFile (shell-based, byte-cursor pagination) ────────────────────

    private void queryLogFile(String path,
                              int maxEntries, String keyword, String format,
                              String since, String direction, int maxBytes, Long cursor,
                              int minStatus, int maxStatus,
                              String ipPrefix, String pathPrefix,
                              List<Map<String, Object>> entries,
                              List<String> diagnostics,
                              Map<String, Object> fileMeta) throws Exception {
        if (!isSafeReadPath(path)) {
            diagnostics.add("rejected unsafe path: " + path);
            return;
        }
        long fileSize = statFileSize(path);
        if (fileSize < 0) {
            diagnostics.add("cannot stat file: " + path);
            return;
        }
        fileMeta.put("fileSize", fileSize);
        if (fileSize == 0) {
            fileMeta.put("startByte", 0L);
            fileMeta.put("endByte",   0L);
            fileMeta.put("reachedStart", Boolean.TRUE);
            fileMeta.put("reachedEnd",   Boolean.TRUE);
            return;
        }

        String safePath = path.replace("'", "'\\''");
        long startByte = -1, endByte = -1;
        boolean usedTimeSeek = false;
        boolean useTailLatest = false;

        // Time-seek (binary search via shell probes)
        if (since != null && !since.isEmpty()
                && !"older".equals(direction) && !"newer".equals(direction)) {
            long targetMs = parseUserTimestamp(since);
            if (targetMs > 0) {
                long pos = binarySearchByTimestampShell(safePath, fileSize, targetMs, format);
                if (pos >= 0 && pos < fileSize) {
                    startByte = pos;
                    endByte   = Math.min(fileSize, pos + estimateChunkBytes(maxEntries));
                    usedTimeSeek = true;
                    diagnostics.add("seekedByTime=" + since + " -> byte=" + pos);
                } else {
                    diagnostics.add("time-seek miss: " + since + " (falling back to tail)");
                }
            } else {
                diagnostics.add("invalid since: " + since);
            }
        }

        if (startByte < 0) {
            if (cursor == null || direction == null || "latest".equals(direction)) {
                useTailLatest = true;
            } else if ("older".equals(direction)) {
                long c = cursor.longValue();
                if (c <= 0) {
                    fileMeta.put("startByte", 0L); fileMeta.put("endByte", 0L);
                    fileMeta.put("reachedStart", Boolean.TRUE);
                    return;
                }
                startByte = Math.max(0, c - estimateChunkBytes(maxEntries));
                endByte   = c;
            } else if ("newer".equals(direction)) {
                long c = cursor.longValue();
                if (c >= fileSize) {
                    fileMeta.put("startByte", fileSize); fileMeta.put("endByte", fileSize);
                    fileMeta.put("reachedEnd", Boolean.TRUE);
                    return;
                }
                startByte = c;
                endByte   = Math.min(fileSize, c + estimateChunkBytes(maxEntries));
            } else {
                useTailLatest = true;
            }
        }

        diagnostics.add("source=file:" + path
                + (format != null && !format.isEmpty() ? " format=" + format : "")
                + (useTailLatest ? " mode=latest" : " mode=range[" + startByte + "," + endByte + ")"));

        boolean hasStructuralFilter = (minStatus >= 0 || maxStatus >= 0
                || (ipPrefix != null && !ipPrefix.isEmpty())
                || (pathPrefix != null && !pathPrefix.isEmpty()));
        boolean awkPushable = hasStructuralFilter && isAccessFormat(format)
                && useTailLatest && (keyword == null || keyword.isEmpty());

        String[] rawLines;
        if (useTailLatest) {
            StringBuilder cmd = new StringBuilder();
            if (maxBytes > 0) {
                cmd.append("tail -c ").append(maxBytes).append(" '").append(safePath).append("' 2>/dev/null");
                if (awkPushable) {
                    cmd.append(" | ").append(buildAwkCommand(minStatus, maxStatus, ipPrefix, pathPrefix, "print"));
                }
                if (keyword != null && !keyword.isEmpty()) {
                    cmd.append(" | grep -i '").append(escapeShell(keyword)).append("'");
                }
                cmd.append(" | tail -").append(maxEntries);
            } else if (awkPushable) {
                cmd.append(buildAwkCommand(minStatus, maxStatus, ipPrefix, pathPrefix, "print"))
                   .append(" '").append(safePath).append("' 2>/dev/null | tail -").append(maxEntries);
                fileMeta.put("awkPushdown", Boolean.TRUE);
            } else if (keyword != null && !keyword.isEmpty()) {
                cmd.append("grep -i '").append(escapeShell(keyword)).append("' '").append(safePath)
                   .append("' 2>/dev/null | tail -").append(maxEntries);
            } else {
                cmd.append("tail -").append(maxEntries).append(" '").append(safePath).append("' 2>/dev/null");
            }
            String output = execFast(cmd.toString());
            rawLines = (output == null || output.isEmpty()) ? new String[0] : output.split("\n");
            int outBytes = output == null ? 0 : output.length();
            endByte   = fileSize;
            startByte = Math.max(0, fileSize - outBytes);
            fileMeta.put("mode", "latest");
        } else {
            // Shell-based byte range read
            String[] chunk = readByteRangeShell(safePath, startByte, endByte, fileSize);
            if (chunk == null) {
                diagnostics.add("read chunk failed");
                return;
            }
            rawLines = chunk;
            // Re-align byte cursors: startByte / endByte stay as requested
            // (shell tail -c+ handles partial first line alignment automatically)
            if (keyword != null && !keyword.isEmpty()) {
                rawLines = filterByKeyword(rawLines, keyword);
            }
            if (rawLines.length > maxEntries) {
                String[] trimmed = new String[maxEntries];
                if ("older".equals(direction)) {
                    System.arraycopy(rawLines, rawLines.length - maxEntries, trimmed, 0, maxEntries);
                } else {
                    System.arraycopy(rawLines, 0, trimmed, 0, maxEntries);
                }
                rawLines = trimmed;
            }
            if (usedTimeSeek) fileMeta.put("seekedByTime", Boolean.TRUE);
            fileMeta.put("mode", usedTimeSeek ? "time-seek" : direction);
        }

        fileMeta.put("startByte",    startByte);
        fileMeta.put("endByte",      endByte);
        fileMeta.put("reachedStart", startByte <= 0);
        fileMeta.put("reachedEnd",   endByte >= fileSize);

        if ("tomcat".equals(format)) {
            List<String> lineList = new ArrayList<String>();
            for (String l : rawLines) lineList.add(l);
            parseTomcatBatch(lineList, entries, maxEntries, path);
            return;
        }

        for (String line : rawLines) {
            if (entries.size() >= maxEntries) break;
            if (line == null || line.isEmpty()) continue;
            Map<String, Object> entry = parseLine(line, format);
            entry.put("source", path);
            entries.add(entry);
        }
    }

    /** Shell-based byte-range read: tail -c +${startByte+1} | head -c ${length} */
    private String[] readByteRangeShell(String safePath, long startByte, long endByte,
                                        long fileSize) throws Exception {
        if (startByte < 0)        startByte = 0;
        if (endByte > fileSize)   endByte   = fileSize;
        if (startByte >= endByte) return new String[0];
        long len = endByte - startByte;
        if (len > 8L * 1024L * 1024L) len = 8L * 1024L * 1024L;
        // tail -c +N is 1-indexed
        long tailFrom = startByte + 1;
        String cmd = "tail -c +" + tailFrom + " '" + safePath + "' 2>/dev/null | head -c " + len;
        String output = execFast(cmd);
        if (output == null || output.isEmpty()) return new String[0];
        // Skip first partial line if startByte > 0
        if (startByte > 0) {
            int firstNl = output.indexOf('\n');
            if (firstNl >= 0) output = output.substring(firstNl + 1);
            else return new String[0];
        }
        // Strip trailing newline
        if (output.endsWith("\n")) output = output.substring(0, output.length() - 1);
        if (output.isEmpty()) return new String[0];
        return output.split("\n", -1);
    }

    /** Shell-based binary search for first line with timestamp >= targetMs */
    private long binarySearchByTimestampShell(String safePath, long fileSize,
                                               long targetMs, String format) throws Exception {
        long lo = 0, hi = fileSize, result = fileSize;
        for (int iter = 0; iter < 30 && lo < hi; iter++) {
            long mid = lo + (hi - lo) / 2;
            // Read line starting after mid
            long tailFrom = mid + 1;
            String cmd = "tail -c +" + tailFrom + " '" + safePath + "' 2>/dev/null | head -c 4096 | head -1";
            String line = execFast(cmd);
            if (line == null || line.trim().isEmpty()) {
                hi = mid;
                continue;
            }
            line = line.trim();
            long ts = parseLogTimestamp(line, format);
            if (ts <= 0) {
                lo = mid + 1;
                continue;
            }
            if (ts < targetMs) {
                lo = mid + 1;
            } else {
                result = mid + 1; // approximate line start
                hi = mid;
            }
        }
        return result >= fileSize ? -1 : result;
    }

    private boolean isAccessFormat(String format) {
        return "nginx-access".equals(format) || "apache-access".equals(format) || "combined".equals(format);
    }

    private long estimateChunkBytes(int lines) {
        long c = (long) lines * 384L;
        if (c < 4096L) c = 4096L;
        if (c > 4L * 1024L * 1024L) c = 4L * 1024L * 1024L;
        return c;
    }

    private long statFileSize(String path) throws Exception {
        String safePath = path.replace("'", "'\\''");
        String out = execFast("stat -c %s '" + safePath + "' 2>/dev/null || stat -f %z '" + safePath + "' 2>/dev/null");
        if (out == null) return -1;
        String s = out.trim();
        if (s.isEmpty()) return -1;
        try { return Long.parseLong(s); } catch (NumberFormatException ex) { return -1; }
    }

    private String[] filterByKeyword(String[] lines, String keyword) {
        String kw = keyword.toLowerCase();
        List<String> kept = new ArrayList<String>();
        for (String line : lines) {
            if (line != null && line.toLowerCase().contains(kw)) kept.add(line);
        }
        return kept.toArray(new String[0]);
    }

    // ── stats ─────────────────────────────────────────────────────────────────

    private Map<String, Object> statsWindows(String source) throws Exception {
        if (source == null || source.isEmpty()) source = "System";
        String output = execFast(winCmd("wevtutil gli \"" + escapeCmd(source) + "\""));

        Map<String, Object> detail = new HashMap<String, Object>();
        if (output != null && !output.trim().isEmpty()) {
            for (String line : output.split("\n")) {
                line = line.trim();
                int colonIdx = line.indexOf(':');
                if (colonIdx > 0 && colonIdx < line.length() - 1) {
                    detail.put(line.substring(0, colonIdx).trim(), line.substring(colonIdx + 1).trim());
                }
            }
        }

        Map<String, Object> result = new HashMap<String, Object>();
        result.put("code",   200);
        result.put("action", "stats");
        result.put("source", source);
        result.put("detail", detail);
        if (output != null) result.put("rawOutput", truncate(output, 4096));
        return result;
    }

    private Map<String, Object> statsMacOS(String source) throws Exception {
        Map<String, Object> detail = new HashMap<String, Object>();
        String output = execFast("log stats 2>/dev/null");
        if (output != null && !output.trim().isEmpty()) detail.put("logStats", truncate(output.trim(), 4096));
        if (source != null && source.startsWith("/")) {
            String safePath = source.replace("'", "'\\''");
            String sizeOut = execFast("ls -lh '" + safePath + "' 2>/dev/null");
            if (sizeOut != null) detail.put("fileInfo", sizeOut.trim());
            String wcOut = execFast("wc -l < '" + safePath + "' 2>/dev/null");
            if (wcOut != null) detail.put("lineCount", wcOut.trim());
        }

        Map<String, Object> result = new HashMap<String, Object>();
        result.put("code",   200);
        result.put("action", "stats");
        result.put("source", source != null ? source : "unified-log");
        result.put("detail", detail);
        return result;
    }

    private Map<String, Object> statsLinux(String source) throws Exception {
        Map<String, Object> detail = new HashMap<String, Object>();
        String diskUsage = execFast("journalctl --disk-usage 2>/dev/null");
        if (diskUsage != null && !diskUsage.trim().isEmpty()) detail.put("journalDiskUsage", diskUsage.trim());

        if (source != null && !source.isEmpty() && !source.startsWith("/")) {
            String countOut = execFast("journalctl -u " + escapeShell(source)
                    + " --no-pager -q 2>/dev/null | wc -l");
            if (countOut != null) detail.put("unitLogLines", countOut.trim());
        }
        if (source != null && source.startsWith("/")) {
            String safePath = source.replace("'", "'\\''");
            String sizeOut = execFast("ls -lh '" + safePath + "' 2>/dev/null");
            if (sizeOut != null) detail.put("fileInfo", sizeOut.trim());
            String wcOut = execFast("wc -l < '" + safePath + "' 2>/dev/null");
            if (wcOut != null) detail.put("lineCount", wcOut.trim());
        }

        Map<String, Object> result = new HashMap<String, Object>();
        result.put("code",   200);
        result.put("action", "stats");
        result.put("source", source != null ? source : "journald");
        result.put("detail", detail);
        return result;
    }

    // ── aggregate ─────────────────────────────────────────────────────────────

    private Map<String, Object> doAggregate(String source, String format, String groupBy,
                                            int topN, int maxScan, int maxBytes, String keyword,
                                            int minStatus, int maxStatus,
                                            String ipPrefix, String pathPrefix,
                                            boolean slow) throws Exception {
        String safePath = source.replace("'", "'\\''");

        // awk fast path
        if (!slow) {
            Map<String, Object> fast = tryAwkFastPathMap(safePath, source, format, groupBy,
                    topN, maxScan, maxBytes, keyword, minStatus, maxStatus, ipPrefix, pathPrefix);
            if (fast != null) return fast;
        }

        // Java slow path
        StringBuilder cmd = new StringBuilder();
        if (maxBytes > 0) cmd.append("tail -c ").append(maxBytes).append(" '").append(safePath).append("' 2>/dev/null");
        else              cmd.append("cat '").append(safePath).append("' 2>/dev/null");
        if (keyword != null && !keyword.isEmpty())
            cmd.append(" | grep -i '").append(escapeShell(keyword)).append("'");
        cmd.append(" | tail -").append(maxScan);

        String output = execFast(cmd.toString());
        if (output == null || output.trim().isEmpty()) {
            Map<String, Object> r = new HashMap<String, Object>();
            r.put("code", 200); r.put("action", "aggregate");
            r.put("fastPath", Boolean.FALSE); r.put("groupBy", groupBy);
            r.put("source", source); r.put("scanned", 0); r.put("unique", 0);
            r.put("groups", new ArrayList<Map<String, Object>>());
            return r;
        }

        Map<String, Long> counts = new HashMap<String, Long>();
        int scanned = 0;
        for (String line : output.split("\n")) {
            if (line == null || line.isEmpty()) continue;
            Map<String, Object> parsed = parseLine(line, format);

            if (minStatus >= 0 || maxStatus >= 0) {
                String s = (String) parsed.get("status");
                if (s == null) continue;
                int code;
                try { code = Integer.parseInt(s); } catch (NumberFormatException ex) { continue; }
                if (minStatus >= 0 && code < minStatus) continue;
                if (maxStatus >= 0 && code > maxStatus) continue;
            }
            if (ipPrefix != null && !ipPrefix.isEmpty()) {
                String ip = (String) parsed.get("remoteAddr");
                if (ip == null || !ip.startsWith(ipPrefix)) continue;
            }
            if (pathPrefix != null && !pathPrefix.isEmpty()) {
                String pa = (String) parsed.get("path");
                if (pa == null || !pa.startsWith(pathPrefix)) continue;
            }

            String key = extractGroupKey(parsed, groupBy);
            if (key == null || key.isEmpty()) continue;
            Long c = counts.get(key);
            counts.put(key, c == null ? 1L : c + 1L);
            scanned++;
        }

        // Top-N via insertion sort
        long total = 0;
        String[] topKeys = new String[topN];
        long[]   topVals = new long[topN];
        int topSize = 0;

        Iterator<Map.Entry<String, Long>> it = counts.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Long> e = it.next();
            String k = e.getKey();
            long v = e.getValue();
            total += v;
            if (topSize < topN) {
                int pos = topSize;
                while (pos > 0 && topVals[pos - 1] < v) {
                    topKeys[pos] = topKeys[pos - 1]; topVals[pos] = topVals[pos - 1]; pos--;
                }
                topKeys[pos] = k; topVals[pos] = v; topSize++;
            } else if (v > topVals[topN - 1]) {
                int pos = topN - 1;
                while (pos > 0 && topVals[pos - 1] < v) {
                    topKeys[pos] = topKeys[pos - 1]; topVals[pos] = topVals[pos - 1]; pos--;
                }
                topKeys[pos] = k; topVals[pos] = v;
            }
        }

        List<Map<String, Object>> groups = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < topSize; i++) {
            Map<String, Object> g = new HashMap<String, Object>();
            g.put("key",   topKeys[i]);
            g.put("count", topVals[i]);
            if (total > 0) g.put("ratio", (double) topVals[i] / (double) total);
            groups.add(g);
        }

        Map<String, Object> result = new HashMap<String, Object>();
        result.put("code", 200); result.put("action", "aggregate");
        result.put("fastPath", Boolean.FALSE); result.put("groupBy", groupBy);
        result.put("source", source); result.put("scanned", scanned);
        result.put("unique", counts.size()); result.put("total", total);
        result.put("groups", groups);
        return result;
    }

    private Map<String, Object> tryAwkFastPathMap(String safePath, String source,
                                                   String format, String groupBy,
                                                   int topN, int maxScan, int maxBytes,
                                                   String keyword,
                                                   int minStatus, int maxStatus,
                                                   String ipPrefix, String pathPrefix) throws Exception {
        boolean isAccess = isAccessFormat(format);
        if (!isAccess) return null;

        String awkExpr;
        if ("ip".equals(groupBy) || "remoteAddr".equals(groupBy)) awkExpr = "$1";
        else if ("status".equals(groupBy))      awkExpr = "$9";
        else if ("statusClass".equals(groupBy)) awkExpr = "substr($9,1,1) \"xx\"";
        else if ("method".equals(groupBy))      awkExpr = "substr($6,2)";
        else if ("path".equals(groupBy))        awkExpr = "$7";
        else return null;

        StringBuilder cmd = new StringBuilder();
        if (maxBytes > 0) cmd.append("tail -c ").append(maxBytes).append(" '").append(safePath).append("' 2>/dev/null");
        else              cmd.append("tail -").append(maxScan).append(" '").append(safePath).append("' 2>/dev/null");
        if (keyword != null && !keyword.isEmpty())
            cmd.append(" | grep -i '").append(escapeShell(keyword)).append("'");
        if (maxBytes > 0) cmd.append(" | tail -").append(maxScan);
        cmd.append(" | ").append(buildAwkCommand(minStatus, maxStatus, ipPrefix, pathPrefix, "print " + awkExpr));
        cmd.append(" | sort | uniq -c | sort -rn | head -").append(topN);

        String output = execFast(cmd.toString());
        List<Map<String, Object>> groups = new ArrayList<Map<String, Object>>();
        long topSum = 0;
        if (output != null) {
            for (String line : output.split("\n")) {
                line = line.trim();
                if (line.isEmpty()) continue;
                int sp = line.indexOf(' ');
                if (sp <= 0) continue;
                long cnt;
                try { cnt = Long.parseLong(line.substring(0, sp)); } catch (NumberFormatException ex) { continue; }
                Map<String, Object> g = new HashMap<String, Object>();
                g.put("key", line.substring(sp + 1).trim());
                g.put("count", cnt);
                groups.add(g);
                topSum += cnt;
            }
        }

        Map<String, Object> result = new HashMap<String, Object>();
        result.put("code", 200); result.put("action", "aggregate");
        result.put("fastPath", Boolean.TRUE); result.put("groupBy", groupBy);
        result.put("source", source); result.put("groups", groups);
        result.put("topSum", topSum);
        result.put("note", "fast path: scanned & unique 未精确统计,可加 slow=true 走 Java 路径获得完整信息");
        return result;
    }

    private String buildAwkCondition(int minStatus, int maxStatus, String ipPrefix, String pathPrefix) {
        StringBuilder cond = new StringBuilder();
        cond.append("NF >= 9");
        if (minStatus >= 0) cond.append(" && $9+0 >= ").append(minStatus);
        if (maxStatus >= 0) cond.append(" && $9+0 <= ").append(maxStatus);
        if (ipPrefix != null && !ipPrefix.isEmpty())
            cond.append(" && index($1, \"").append(ipPrefix.replace("\"", "")).append("\") == 1");
        if (pathPrefix != null && !pathPrefix.isEmpty())
            cond.append(" && index($7, \"").append(pathPrefix.replace("\"", "")).append("\") == 1");
        return cond.toString();
    }

    private String buildAwkCommand(int minStatus, int maxStatus,
                                    String ipPrefix, String pathPrefix, String printExpr) {
        return "awk '" + buildAwkCondition(minStatus, maxStatus, ipPrefix, pathPrefix)
                + " {" + printExpr + "}'";
    }

    // ── App log discovery ─────────────────────────────────────────────────────

    private static final String[][] LINUX_APP_LOG_PATHS = {
        {"/var/log/nginx",         "*.log*",    "nginx",      ""},
        {"/var/log/apache2",       "*.log*",    "apache",     ""},
        {"/var/log/httpd",         "*.log*",    "apache",     ""},
        {"/var/log/mysql",         "*.log*",    "mysql",      ""},
        {"/var/log/mariadb",       "*.log*",    "mysql",      ""},
        {"/var/log/redis",         "*.log*",    "redis",      ""},
        {"/var/log/postgresql",    "*.log*",    "postgres",   ""},
        {"/var/log/tomcat*/logs",  "*",         "tomcat",     "tomcat"},
        {"/var/log/tomcat*",       "catalina*", "tomcat",     "tomcat"},
        {"/opt/tomcat*/logs",      "*",         "tomcat",     "tomcat"},
        {"/usr/local/tomcat/logs", "*",         "tomcat",     "tomcat"},
        {"/var/log/elasticsearch", "*.log*",    "elastic",    ""},
        {"/var/log/kafka",         "*.log*",    "kafka",      ""},
        {"/var/log/zookeeper",     "*.log*",    "zookeeper",  ""},
        {"/var/log/mongodb",       "*.log*",    "mongo",      ""},
        {"/var/log/rabbitmq",      "*.log*",    "rabbitmq",   ""},
        {"/var/log/clickhouse-server", "*.log*","clickhouse", ""},
    };

    private static final String[][] MACOS_APP_LOG_PATHS = {
        {"/usr/local/var/log/nginx",       "*.log*", "nginx",    ""},
        {"/opt/homebrew/var/log/nginx",    "*.log*", "nginx",    ""},
        {"/usr/local/var/mysql",           "*.err",  "mysql",    "mysql-error"},
        {"/opt/homebrew/var/mysql",        "*.err",  "mysql",    "mysql-error"},
        {"/usr/local/var/log/redis.log",   "",       "redis",    ""},
        {"/opt/homebrew/var/log/redis.log","",       "redis",    ""},
        {"/usr/local/var/log/postgres",    "*.log*", "postgres", ""},
        {"/opt/homebrew/var/log/postgres", "*.log*", "postgres", ""},
        {"/usr/local/var/log",             "*.log*", "app",      ""},
        {"/opt/homebrew/var/log",          "*.log*", "app",      ""},
    };

    private void listAppLogsLinux(List<Map<String, Object>> sources, List<String> diagnostics) throws Exception {
        scanAppLogs(LINUX_APP_LOG_PATHS, sources, diagnostics);
        String home = execFast("echo $HOME 2>/dev/null");
        if (home != null && !home.trim().isEmpty()) {
            String[][] pm2 = { { home.trim() + "/.pm2/logs", "*.log*", "nodejs", "pm2" } };
            scanAppLogs(pm2, sources, diagnostics);
        }
    }

    private void listAppLogsMacOS(List<Map<String, Object>> sources, List<String> diagnostics) throws Exception {
        scanAppLogs(MACOS_APP_LOG_PATHS, sources, diagnostics);
    }

    private void scanAppLogs(String[][] templates,
                             List<Map<String, Object>> sources,
                             List<String> diagnostics) throws Exception {
        int before = sources.size();
        for (String[] tpl : templates) {
            String dirGlob      = tpl[0];
            String filePattern  = tpl[1];
            String type         = tpl[2];
            String explicitFmt  = tpl[3];

            StringBuilder cmd = new StringBuilder();
            cmd.append("for d in ").append(dirGlob).append("; do ")
               .append("[ -e \"$d\" ] || continue; ")
               .append("if [ -d \"$d\" ]; then find \"$d\" -maxdepth 2");
            if (filePattern != null && !filePattern.isEmpty())
                cmd.append(" -name '").append(filePattern).append("'");
            cmd.append(" -type f 2>/dev/null; else echo \"$d\"; fi; done")
               .append(" | head -100 | while read f; do ")
               .append("[ -n \"$f\" ] || continue; ")
               .append("sz=$(stat -c %s \"$f\" 2>/dev/null || stat -f %z \"$f\" 2>/dev/null || echo 0); ")
               .append("echo \"$sz|$f\"; done");

            String out = execFast(cmd.toString());
            if (out == null || out.trim().isEmpty()) continue;
            for (String line : out.split("\n")) {
                line = line.trim();
                if (line.isEmpty()) continue;
                int sep = line.indexOf('|');
                if (sep <= 0) continue;
                long size = 0;
                try { size = Long.parseLong(line.substring(0, sep)); } catch (NumberFormatException ex) {}
                String p = line.substring(sep + 1);
                if (p.isEmpty()) continue;
                if (p.endsWith(".pid") || p.endsWith(".sock") || p.endsWith(".lock")) continue;
                if (p.endsWith(".gz") || p.endsWith(".bz2") || p.endsWith(".xz") || p.endsWith(".zip")) continue;

                Map<String, Object> src = new HashMap<String, Object>();
                src.put("name", p);
                src.put("type", type);
                src.put("size", size);
                src.put("sizeHuman", humanSize(size));
                if (size > 100L * 1024L * 1024L) src.put("large", Boolean.TRUE);
                String fmt = (explicitFmt != null && !explicitFmt.isEmpty()) ? explicitFmt : guessFormat(p, type);
                if (fmt != null && !fmt.isEmpty()) src.put("format", fmt);
                sources.add(src);
            }
        }
        if (sources.size() > before) diagnostics.add("source=app-logs(" + (sources.size() - before) + ")");
    }

    private String guessFormat(String path, String type) {
        String n = path.toLowerCase();
        if ("nginx".equals(type))  { return n.contains("access") ? "nginx-access" : n.contains("error") ? "nginx-error" : ""; }
        if ("apache".equals(type)) { return n.contains("access") ? "apache-access" : n.contains("error") ? "apache-error" : ""; }
        if ("tomcat".equals(type)) return "tomcat";
        if ("mysql".equals(type))  { return n.contains("slow") ? "mysql-slow" : (n.contains("error") || n.endsWith(".err")) ? "mysql-error" : ""; }
        return "";
    }

    // ── Format-aware parsers ──────────────────────────────────────────────────

    private static final Pattern COMBINED_LOG_PATTERN = Pattern.compile(
        "^(\\S+) (\\S+) (\\S+) \\[([^\\]]+)\\] \"([^\"]*)\" (\\d+) (\\S+)(?: \"([^\"]*)\" \"([^\"]*)\")?"
    );
    private static final Pattern NGINX_ERROR_PATTERN = Pattern.compile(
        "^(\\d{4}/\\d{2}/\\d{2} \\d{2}:\\d{2}:\\d{2}) \\[([a-z]+)\\] (\\d+)#(\\d+): (.*)$"
    );
    private static final Pattern MYSQL_ERROR_PATTERN = Pattern.compile(
        "^(\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}:\\d{2})\\S* +(\\d+) +\\[([A-Za-z]+)\\](?: \\[([^\\]]+)\\])? +(.*)$"
    );
    private static final Pattern TOMCAT_TS_PATTERN = Pattern.compile(
        "^(\\d{2}-[A-Z][a-z]+-\\d{4} \\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?|\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?)"
        + "(?:\\s+([A-Z]+))?(?:\\s+\\[([^\\]]+)\\])?(?:\\s+(\\S+))?\\s*(.*)$"
    );

    private Map<String, Object> parseLine(String line, String format) {
        if (format == null || format.isEmpty() || "raw".equals(format)) return rawEntry(line);
        if (isAccessFormat(format))          return parseCombined(line, format);
        if ("nginx-error".equals(format))    return parseNginxError(line);
        if ("mysql-error".equals(format))    return parseMysqlError(line);
        return rawEntry(line);
    }

    private Map<String, Object> rawEntry(String line) {
        Map<String, Object> m = new HashMap<String, Object>();
        m.put("raw", truncate(line, 4096));
        return m;
    }

    private Map<String, Object> parseCombined(String line, String format) {
        Map<String, Object> m = new HashMap<String, Object>();
        m.put("raw",    truncate(line, 4096));
        m.put("format", format);
        Matcher mm = COMBINED_LOG_PATTERN.matcher(line);
        if (mm.find()) {
            m.put("remoteAddr", mm.group(1));
            m.put("ident",      mm.group(2));
            m.put("user",       mm.group(3));
            m.put("time",       mm.group(4));
            String req = mm.group(5);
            m.put("request", req);
            m.put("status",  mm.group(6));
            m.put("bytes",   mm.group(7));
            if (mm.group(8) != null) m.put("referer",   mm.group(8));
            if (mm.group(9) != null) m.put("userAgent", mm.group(9));
            if (req != null) {
                int sp1 = req.indexOf(' ');
                int sp2 = sp1 >= 0 ? req.indexOf(' ', sp1 + 1) : -1;
                if (sp1 > 0) {
                    m.put("method", req.substring(0, sp1));
                    if (sp2 > sp1) { m.put("path", req.substring(sp1 + 1, sp2)); m.put("proto", req.substring(sp2 + 1)); }
                    else             m.put("path", req.substring(sp1 + 1));
                }
            }
        }
        return m;
    }

    private Map<String, Object> parseNginxError(String line) {
        Map<String, Object> m = new HashMap<String, Object>();
        m.put("raw", truncate(line, 4096)); m.put("format", "nginx-error");
        Matcher mm = NGINX_ERROR_PATTERN.matcher(line);
        if (mm.find()) {
            m.put("time", mm.group(1)); m.put("level", mm.group(2));
            m.put("pid",  mm.group(3)); m.put("tid",   mm.group(4));
            m.put("message", mm.group(5));
        }
        return m;
    }

    private Map<String, Object> parseMysqlError(String line) {
        Map<String, Object> m = new HashMap<String, Object>();
        m.put("raw", truncate(line, 4096)); m.put("format", "mysql-error");
        Matcher mm = MYSQL_ERROR_PATTERN.matcher(line);
        if (mm.find()) {
            m.put("time",    mm.group(1)); m.put("thread", mm.group(2));
            m.put("level",   mm.group(3));
            if (mm.group(4) != null) m.put("code", mm.group(4));
            m.put("message", mm.group(5));
        }
        return m;
    }

    private void parseTomcatBatch(List<String> lines, List<Map<String, Object>> entries,
                                  int maxEntries, String path) {
        Map<String, Object> current = null;
        StringBuilder body = null;
        for (String line : lines) {
            if (line == null) continue;
            Matcher mm = TOMCAT_TS_PATTERN.matcher(line);
            if (mm.find()) {
                if (current != null) {
                    if (body != null) current.put("message", truncate(body.toString(), 8192));
                    entries.add(current);
                    if (entries.size() >= maxEntries) return;
                }
                current = new HashMap<String, Object>();
                current.put("format", "tomcat");
                current.put("time", mm.group(1));
                if (mm.group(2) != null) current.put("level",  mm.group(2));
                if (mm.group(3) != null) current.put("thread", mm.group(3));
                if (mm.group(4) != null) current.put("logger", mm.group(4));
                current.put("source", path);
                body = new StringBuilder();
                String rest = mm.group(5);
                if (rest != null && !rest.isEmpty()) body.append(rest);
            } else if (current != null && body != null) {
                if (body.length() > 0) body.append('\n');
                body.append(line);
            }
        }
        if (current != null && entries.size() < maxEntries) {
            if (body != null) current.put("message", truncate(body.toString(), 8192));
            entries.add(current);
        }
    }

    // ── Timestamp utilities ───────────────────────────────────────────────────

    private long parseLogTimestamp(String line, String format) {
        if (line == null || line.length() < 8) return -1;
        if (isAccessFormat(format)) {
            int lb = line.indexOf('['), rb = lb >= 0 ? line.indexOf(']', lb) : -1;
            if (lb < 0 || rb <= lb) return -1;
            try { return new SimpleDateFormat("dd/MMM/yyyy:HH:mm:ss Z", Locale.ENGLISH).parse(line.substring(lb + 1, rb)).getTime(); }
            catch (ParseException e) { return -1; }
        }
        if ("nginx-error".equals(format)) {
            if (line.length() < 19) return -1;
            try { return new SimpleDateFormat("yyyy/MM/dd HH:mm:ss").parse(line.substring(0, 19)).getTime(); }
            catch (ParseException e) { return -1; }
        }
        if ("mysql-error".equals(format) || "mysql-slow".equals(format)) {
            if (line.length() < 19) return -1;
            char sep = line.charAt(10);
            if (sep != 'T' && sep != ' ') return -1;
            try { return new SimpleDateFormat(sep == 'T' ? "yyyy-MM-dd'T'HH:mm:ss" : "yyyy-MM-dd HH:mm:ss").parse(line.substring(0, 19)).getTime(); }
            catch (ParseException e) { return -1; }
        }
        if ("tomcat".equals(format)) {
            if (line.length() >= 20 && line.charAt(2) == '-') {
                try { return new SimpleDateFormat("dd-MMM-yyyy HH:mm:ss", Locale.ENGLISH).parse(line.substring(0, 20)).getTime(); }
                catch (ParseException e) { /* try next */ }
            }
        }
        if (line.length() >= 19 && line.charAt(4) == '-' && (line.charAt(10) == ' ' || line.charAt(10) == 'T')) {
            try { return new SimpleDateFormat(line.charAt(10) == 'T' ? "yyyy-MM-dd'T'HH:mm:ss" : "yyyy-MM-dd HH:mm:ss").parse(line.substring(0, 19)).getTime(); }
            catch (ParseException e) { return -1; }
        }
        return -1;
    }

    private long parseUserTimestamp(String s) {
        if (s == null || s.isEmpty()) return -1;
        String t = s.trim();
        try {
            long v = Long.parseLong(t);
            if (v < 100000000000L) v *= 1000L;
            return v;
        } catch (NumberFormatException ex) {}
        long rel = parseRelativeTimeMs(t);
        if (rel > 0) return System.currentTimeMillis() - rel;
        String[] patterns = {
            "yyyy-MM-dd'T'HH:mm:ss.SSS", "yyyy-MM-dd'T'HH:mm:ss",
            "yyyy-MM-dd HH:mm:ss.SSS",   "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd HH:mm",          "yyyy-MM-dd"
        };
        for (String p : patterns) {
            try { return new SimpleDateFormat(p).parse(t).getTime(); }
            catch (ParseException e) {}
        }
        return -1;
    }

    private long parseRelativeTimeMs(String timeStr) {
        if (timeStr == null || timeStr.isEmpty()) return 0;
        try {
            long total = 0;
            StringBuilder numBuf = new StringBuilder();
            for (int i = 0; i < timeStr.length(); i++) {
                char c = timeStr.charAt(i);
                if (c >= '0' && c <= '9') { numBuf.append(c); }
                else {
                    if (numBuf.length() == 0) continue;
                    long num = Long.parseLong(numBuf.toString()); numBuf = new StringBuilder();
                    if (c == 'd' || c == 'D')      total += num * 86400000L;
                    else if (c == 'h' || c == 'H') total += num * 3600000L;
                    else if (c == 'm' || c == 'M') total += num * 60000L;
                    else if (c == 's' || c == 'S') total += num * 1000L;
                }
            }
            if (numBuf.length() > 0) total += Long.parseLong(numBuf.toString()) * 1000L;
            return total;
        } catch (Exception e) { return 0; }
    }

    private String convertToPeriod(String timeStr) {
        if (timeStr == null || timeStr.isEmpty()) return null;
        if (timeStr.matches("\\d+[hHmMdDsS]")) {
            String unit = timeStr.substring(timeStr.length() - 1).toLowerCase();
            String num  = timeStr.substring(0, timeStr.length() - 1);
            if ("d".equals(unit)) return (Integer.parseInt(num) * 24) + "h";
            return num + unit;
        }
        return "1h";
    }

    private String escapeJournalTime(String timeStr) {
        if (timeStr == null) return "";
        if (timeStr.matches("\\d+[hH]")) return timeStr.replaceAll("[hH]", "") + " hours ago";
        if (timeStr.matches("\\d+[mM]")) return timeStr.replaceAll("[mM]", "") + " minutes ago";
        if (timeStr.matches("\\d+[dD]")) return timeStr.replaceAll("[dD]", "") + " days ago";
        return escapeShell(timeStr);
    }

    // ── General utilities ─────────────────────────────────────────────────────

    private boolean isSafeReadPath(String path) {
        if (path == null || path.isEmpty() || !path.startsWith("/")) return false;
        if (path.contains("/../") || path.endsWith("/.."))          return false;
        if (path.startsWith("/proc/") || "/proc".equals(path))      return false;
        if (path.startsWith("/sys/")  || "/sys".equals(path))       return false;
        if (path.startsWith("/dev/")  || "/dev".equals(path))       return false;
        return true;
    }

    private boolean matchesKeyword(Map<String, Object> entry, String keyword) {
        if (keyword == null || keyword.isEmpty()) return true;
        String kw = keyword.toLowerCase();
        for (Object val : entry.values()) {
            if (val != null && val.toString().toLowerCase().contains(kw)) return true;
        }
        return false;
    }

    private String extractGroupKey(Map<String, Object> entry, String groupBy) {
        if (entry == null) return null;
        if ("ip".equals(groupBy) || "remoteAddr".equals(groupBy)) return (String) entry.get("remoteAddr");
        if ("path".equals(groupBy))    return (String) entry.get("path");
        if ("status".equals(groupBy))  return (String) entry.get("status");
        if ("statusClass".equals(groupBy)) {
            String s = (String) entry.get("status");
            return (s != null && s.length() >= 1) ? s.substring(0, 1) + "xx" : null;
        }
        if ("ua".equals(groupBy) || "userAgent".equals(groupBy)) return (String) entry.get("userAgent");
        if ("method".equals(groupBy)) return (String) entry.get("method");
        if ("level".equals(groupBy))  return (String) entry.get("level");
        if ("hour".equals(groupBy) || "day".equals(groupBy)) {
            String t = (String) entry.get("time");
            if (t == null) return null;
            if ("day".equals(groupBy)) {
                int spaceIdx = t.indexOf(' '), firstColon = t.indexOf(':');
                if (t.indexOf('/') > 0 && firstColon > 0 && firstColon < (spaceIdx < 0 ? t.length() : spaceIdx))
                    return t.substring(0, firstColon);
                if (spaceIdx > 0) return t.substring(0, spaceIdx);
                return t.length() >= 10 ? t.substring(0, 10) : t;
            }
            int firstColon = t.indexOf(':'), spaceIdx = t.indexOf(' ');
            if (t.indexOf('/') > 0 && firstColon > 0 && firstColon < (spaceIdx < 0 ? t.length() : spaceIdx)) {
                int endHour = firstColon + 3;
                if (endHour <= t.length()) return t.substring(0, endHour);
            }
            if (spaceIdx > 0 && spaceIdx + 3 <= t.length()) return t.substring(0, spaceIdx + 3);
            return t.length() >= 13 ? t.substring(0, 13) : t;
        }
        return null;
    }

    private String humanSize(long bytes) {
        if (bytes < 1024) return bytes + "B";
        double v = bytes / 1024.0;
        if (v < 1024) return Math.round(v * 10.0) / 10.0 + "K";
        v = v / 1024.0;
        if (v < 1024) return Math.round(v * 10.0) / 10.0 + "M";
        return Math.round(v / 1024.0 * 10.0) / 10.0 + "G";
    }

    private String stripTrailingNewline(String s) {
        if (s == null) return null;
        int len = s.length();
        while (len > 0 && (s.charAt(len - 1) == '\n' || s.charAt(len - 1) == '\r')) len--;
        return s.substring(0, len);
    }

    private boolean isWindows(String osOutput) {
        return osOutput == null || osOutput.trim().isEmpty()
                || osOutput.toLowerCase().contains("windows");
    }

    private String escapeCmd(String s) {
        if (s == null) return "";
        return s.replace("\"", "\\\"");
    }

    private String escapeShell(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '_' || c == '.' || c == '-' || c == '@' || c == '/'
                    || c == '*' || c == '+' || c == ':' || c == '=' || c == ',') {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return null;
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...(truncated)";
    }

    private Map<String, Object> error(int code, String msg) {
        Map<String, Object> result = new HashMap<String, Object>();
        result.put("code", code);
        result.put("msg",  msg);
        return result;
    }

    // ── Options Builders ──────────────────────────────────────────────────────

    public static final class QueryOptions {
        public String source; public int maxEntries;
        public String keyword; public String level; public String since; public String until;
        public String eventId; public String format; public int maxBytes;
        public Long cursor; public String direction;
        public Integer minStatus; public Integer maxStatus;
        public String ipPrefix; public String pathPrefix;

        private QueryOptions() {}
        public static QueryOptions of(String source, int maxEntries) {
            QueryOptions o = new QueryOptions(); o.source = source; o.maxEntries = maxEntries; return o;
        }
        public QueryOptions keyword(String v)    { this.keyword = v; return this; }
        public QueryOptions level(String v)      { this.level = v; return this; }
        public QueryOptions since(String v)      { this.since = v; return this; }
        public QueryOptions until(String v)      { this.until = v; return this; }
        public QueryOptions eventId(String v)    { this.eventId = v; return this; }
        public QueryOptions format(String v)     { this.format = v; return this; }
        public QueryOptions maxBytes(int v)      { this.maxBytes = v; return this; }
        public QueryOptions cursor(Long v)       { this.cursor = v; return this; }
        public QueryOptions direction(String v)  { this.direction = v; return this; }
        public QueryOptions ipPrefix(String v)   { this.ipPrefix = v; return this; }
        public QueryOptions pathPrefix(String v) { this.pathPrefix = v; return this; }
        public QueryOptions statusRange(Integer min, Integer max) { this.minStatus = min; this.maxStatus = max; return this; }
    }

    public static final class AggregateOptions {
        public String source; public String groupBy; public String format;
        public int topN; public int maxScan; public int maxBytes;
        public String keyword; public Integer minStatus; public Integer maxStatus;
        public String ipPrefix; public String pathPrefix; public boolean slow;

        private AggregateOptions() {}
        public static AggregateOptions of(String source, String groupBy) {
            AggregateOptions o = new AggregateOptions(); o.source = source; o.groupBy = groupBy; return o;
        }
        public AggregateOptions format(String v)     { this.format = v; return this; }
        public AggregateOptions topN(int v)          { this.topN = v; return this; }
        public AggregateOptions maxScan(int v)       { this.maxScan = v; return this; }
        public AggregateOptions maxBytes(int v)      { this.maxBytes = v; return this; }
        public AggregateOptions keyword(String v)    { this.keyword = v; return this; }
        public AggregateOptions ipPrefix(String v)   { this.ipPrefix = v; return this; }
        public AggregateOptions pathPrefix(String v) { this.pathPrefix = v; return this; }
        public AggregateOptions slow(boolean v)      { this.slow = v; return this; }
        public AggregateOptions statusRange(Integer min, Integer max) { this.minStatus = min; this.maxStatus = max; return this; }
    }
}
