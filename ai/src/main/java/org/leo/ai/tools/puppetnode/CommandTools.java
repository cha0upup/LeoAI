package org.leo.ai.tools.puppetnode;

import org.leo.ai.agent.AiToolContext;
import org.leo.ai.util.PuppetNodeSessionUtils;
import org.leo.ai.util.ToolResultUtils;
import org.leo.core.puppet.impl.JavaPuppetNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.P;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 命令执行工具（精简版）
 * <p>
 * 对外仅暴露 3 个 @Tool 方法：
 * <ul>
 *   <li>{@link #exec} — 统一执行入口，自动判断同步/异步/缓存</li>
 *   <li>{@link #queryTask} — 查询异步任务输出</li>
 *   <li>{@link #stopTask} — 终止异步任务并释放资源</li>
 * </ul>
 * <p>
 * 内部自动处理：
 * <ul>
 *   <li>已知高频命令（env、java 进程参数）自动命中缓存</li>
 *   <li>timeout=0 时使用快速同步模式（适合 &lt;10s 命令）</li>
 *   <li>timeout&gt;0 时使用带超时同步模式（适合 10~120s 命令）</li>
 *   <li>检测到可能耗时的命令（根目录搜索等）自动转异步，返回 taskId</li>
 * </ul>
 */
@Component
public class CommandTools {

    private static final Logger log = LoggerFactory.getLogger(CommandTools.class);

    private static final String SIMPLE_COMMAND_CACHE_PREFIX = "simple-command:";
    private static final String OS_PLATFORM_CACHE_KEY = "os-platform";

    // ══════════════════════════════════════════════════════════════════════════════
    //  公开 @Tool 方法（仅 3 个）
    // ══════════════════════════════════════════════════════════════════════════════

    @Tool("在 puppet 侧执行系统命令。统一入口，自动选择最优执行模式。\n"
            + "• timeout=0：使用默认超时（30s），适合明确快完成的命令\n"
            + "• timeout>0：自定义超时（1~120s），到点强制终止并返回已收集的部分输出\n"
            + "• 检测到必然耗时的命令自动转异步，返回 taskId，需用 queryTask 轮询\n"
            + "已知高频命令（env、java进程参数）自动命中会话缓存。不能用于查看平台侧 VFS。")
    public Map<String, Object> exec(
            @P("要执行的命令") String cmd,
            @P("超时秒数。0=默认 30s；>0=自定义（1~120）。检测到耗时命令时忽略此参数自动转异步。") int timeout) throws Exception {
        String sessionId = AiToolContext.requireSessionId();

        // ── 1. 缓存命中检查（已知高频命令） ──
        Map<String, Object> cached = checkKnownCommandCache(sessionId, cmd);
        if (cached != null) return cached;

        // ── 2. 耗时命令检测 → 自动转异步 ──
        if (isLikelyLongRunning(cmd)) {
            return startAsync(sessionId, cmd);
        }

        // ── 3. 同步执行（无论 timeout=0 还是 >0 都走子进程，避免 PTY 回显与哨兵歧义） ──
        int timeoutSec = timeout <= 0 ? 0 : Math.min(timeout, 120);
        return execSync(sessionId, cmd, timeoutSec);
    }

    @Tool("查询异步命令的当前输出。当 exec 返回 taskId 时使用。"
            + "返回 output（当前累积输出）和 status。"
            + "如果输出已包含所需信息或为空（命令已结束），调用 stopTask 释放资源。")
    public Map<String, Object> queryTask(
            @P("exec 返回的 taskId") String taskId) throws Exception {
        String sessionId = AiToolContext.requireSessionId();
        String output = readFromTerminal(sessionId, taskId);
        HashMap<String, Object> result = new HashMap<>();
        result.put("taskId", taskId);
        result.put("output", output);
        result.put("status", "running");
        result.put("hint", "如果输出已包含所需信息或为空（命令已结束），调用 stopTask 释放资源。");
        compressOutputField(result);
        return result;
    }

    @Tool("终止异步命令并释放终端资源。返回终止前的最后一段输出。无论命令是否结束都应在不再需要时调用。")
    public Map<String, Object> stopTask(
            @P("exec 返回的 taskId") String taskId) throws Exception {
        String sessionId = AiToolContext.requireSessionId();
        String output = readFromTerminal(sessionId, taskId);
        stopTerminal(sessionId, taskId);
        HashMap<String, Object> result = new HashMap<>();
        result.put("taskId", taskId);
        result.put("output", output);
        result.put("status", "stopped");
        compressOutputField(result);
        return result;
    }

    // ══════════════════════════════════════════════════════════════════════════════
    //  内部执行模式
    // ══════════════════════════════════════════════════════════════════════════════

    /**
     * 同步执行命令：fork 子进程 → 等待退出 → 收集输出。
     * <p>无论 timeoutSeconds 是 0（用组件默认 30s）还是 &gt;0（自定义），都走同一条 fork-exec-wait 路径。
     * 不再使用交互式 PTY shell + 哨兵，避免 PTY 输入回显与第二次 sentinel 误匹配的歧义。
     * <p>结果统一规整为 {@code {cmd, output, status, exitCode, timedOut}} 形状，与异步路径一致。
     */
    private Map<String, Object> execSync(String sessionId, String cmd, int timeoutSeconds) throws Exception {
        String cacheKey = SIMPLE_COMMAND_CACHE_PREFIX + cmd;
        Object cachedResult = PuppetNodeSessionUtils.getAiContextValue(sessionId, cacheKey);
        if (cachedResult instanceof Map<?, ?> cachedMap) {
            return (Map<String, Object>) cachedMap;
        }

        JavaPuppetNode node = PuppetNodeSessionUtils.getJavaPuppetNode(sessionId);
        Map<String, Object> raw = timeoutSeconds > 0
                ? node.execSimpleCommand(cmd, timeoutSeconds)
                : node.execSimpleCommand(cmd);

        Map<String, Object> result = normalizeSimpleResult(cmd, raw, timeoutSeconds);
        compressOutputField(result);
        // 仅成功完成才缓存，避免把 timeout/exception 结果固化下来
        if (!Boolean.TRUE.equals(result.get("timedOut")) && !result.containsKey("error")) {
            PuppetNodeSessionUtils.putAiContextValue(sessionId, cacheKey, result);
        }
        return result;
    }

    /**
     * 把 ExecCommandSimpleComponent 返回的 {@code {code, data, exitCode, timedOut}} 整形为
     * AI 工具层统一形状 {@code {cmd, output, status, exitCode, timedOut, hint?}}，
     * 与异步任务路径（queryTask / stopTask）保持一致。
     */
    private static Map<String, Object> normalizeSimpleResult(String cmd, Map<String, Object> raw,
                                                              int requestedTimeoutSeconds) {
        HashMap<String, Object> out = new HashMap<>();
        out.put("cmd", cmd);
        if (raw == null) {
            out.put("output", "");
            out.put("status", "error");
            out.put("error", "no result from puppet");
            return out;
        }
        // 远端可能直接抛错；在此统一成 error 形状供 AI 识别
        if (raw.containsKey("error") || raw.containsKey("exception")) {
            out.put("output", "");
            out.put("status", "error");
            Object err = raw.getOrDefault("error", raw.get("exception"));
            if (err != null) out.put("error", err);
            return out;
        }
        Object data = raw.get("data");
        String output;
        if (data instanceof byte[] bytes) {
            output = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        } else {
            output = data == null ? "" : data.toString();
        }
        out.put("output", output);

        boolean timedOut = Boolean.TRUE.equals(raw.get("timedOut"));
        out.put("timedOut", Boolean.valueOf(timedOut));
        out.put("status", timedOut ? "timeout" : "completed");
        if (raw.get("exitCode") instanceof Number n) {
            out.put("exitCode", Integer.valueOf(n.intValue()));
        }
        if (timedOut) {
            int sec = requestedTimeoutSeconds > 0 ? requestedTimeoutSeconds : 30;
            out.put("hint", "命令执行超时（" + sec + "s），已强制终止。输出可能不完整。"
                    + "可用更大 timeout 重试，或交给系统自动判断改走异步。");
        }
        return out;
    }

    /** 异步启动（原 startCommand）。 */
    private Map<String, Object> startAsync(String sessionId, String cmd) throws Exception {
        String taskId = createTerminal(sessionId);
        writeToTerminal(sessionId, cmd, taskId);
        HashMap<String, Object> result = new HashMap<>();
        result.put("taskId", taskId);
        result.put("status", "running");
        result.put("cmd", cmd);
        result.put("hint", "命令已异步启动。使用 queryTask(taskId) 获取输出，使用 stopTask(taskId) 终止。");
        return result;
    }

    // ══════════════════════════════════════════════════════════════════════════════
    //  缓存与智能路由
    // ══════════════════════════════════════════════════════════════════════════════

    /**
     * 检查已知高频命令的缓存。
     * 覆盖：env/set（环境变量）、java 进程参数。
     * 返回 null 表示未命中。
     */
    private Map<String, Object> checkKnownCommandCache(String sessionId, String cmd) throws Exception {
        if (cmd == null) return null;
        String trimmed = cmd.trim().toLowerCase();

        // 环境变量
        if (trimmed.equals("env") || trimmed.equals("set")) {
            String cacheKey = "env-vars";
            Object cached = PuppetNodeSessionUtils.getAiContextValue(sessionId, cacheKey);
            if (cached instanceof Map<?, ?> cachedMap) {
                return (Map<String, Object>) cachedMap;
            }
            // 未缓存，执行后缓存
            Map<String, Object> results = execSync(sessionId, cmd, 0);
            if (results != null && !results.containsKey("error") && !"timeout".equals(results.get("status"))) {
                PuppetNodeSessionUtils.putAiContextValue(sessionId, cacheKey, results);
            }
            return results;
        }

        // Java 进程参数
        if (trimmed.contains("grep java") || trimmed.contains("wmic process") && trimmed.contains("java")) {
            String cacheKey = "java-process-args";
            Object cached = PuppetNodeSessionUtils.getAiContextValue(sessionId, cacheKey);
            if (cached instanceof Map<?, ?> cachedMap) {
                return (Map<String, Object>) cachedMap;
            }
            Map<String, Object> results = execSync(sessionId, cmd, 0);
            if (results != null && !results.containsKey("error") && !"timeout".equals(results.get("status"))) {
                PuppetNodeSessionUtils.putAiContextValue(sessionId, cacheKey, results);
            }
            return results;
        }

        return null;
    }

    /**
     * 判断命令是否可能耗时较长，需要自动转异步。
     * 覆盖：递归搜索大目录、全盘扫描、网络下载、包管理、持续监控等。
     */
    private boolean isLikelyLongRunning(String cmd) {
        if (cmd == null) return false;
        String lower = cmd.trim().toLowerCase();

        // ── 递归 grep / findstr 在大目录 ──
        if ((lower.contains("grep") && (lower.contains("-r") || lower.contains("--recursive"))) ||
                (lower.contains("findstr") && lower.contains("/s"))) {
            if (isLargeDirectoryTarget(lower)) {
                return true;
            }
        }

        // ── find 命令在大目录 ──
        if (lower.startsWith("find ")) {
            String afterFind = lower.substring(5).trim();
            // find / 或 find /home 等大目录（排除 /tmp /proc 等小目录）
            if (afterFind.startsWith("/") && !afterFind.startsWith("/tmp") &&
                    !afterFind.startsWith("/proc") && !afterFind.startsWith("/dev")) {
                return true;
            }
            // Windows: find /i 不算，但 dir /s 会在下面匹配
        }

        // ── Windows dir /s（递归列目录） ──
        if (lower.startsWith("dir ") && lower.contains("/s")) {
            if (isLargeDirectoryTarget(lower)) {
                return true;
            }
        }

        // ── 全盘磁盘扫描 ──
        if (lower.startsWith("du ") && (lower.contains(" /") || lower.matches(".*\\s[a-z]:\\\\.*"))) {
            if (!lower.contains("/tmp") && !lower.contains("--max-depth=0") && !lower.contains("-s")) {
                return true;
            }
        }

        // ── 网络下载/传输 ──
        if (lower.startsWith("wget ") || lower.startsWith("curl ") && lower.contains("-o") ||
                lower.startsWith("scp ") || lower.startsWith("rsync ")) {
            return true;
        }

        // ── 包管理操作 ──
        if (lower.startsWith("apt ") || lower.startsWith("apt-get ") ||
                lower.startsWith("yum ") || lower.startsWith("dnf ") ||
                lower.startsWith("pip install") || lower.startsWith("npm install")) {
            return true;
        }

        // ── 持续监控/交互式命令 ──
        if (lower.startsWith("tail -f") || lower.startsWith("watch ") ||
                lower.startsWith("top") || lower.startsWith("htop") ||
                lower.startsWith("tcpdump") || lower.startsWith("strace")) {
            return true;
        }

        // ── 编译/构建 ──
        if (lower.startsWith("make") || lower.startsWith("mvn ") ||
                lower.startsWith("gradle") || lower.startsWith("msbuild")) {
            return true;
        }

        return false;
    }

    /**
     * 判断命令目标是否为大目录（根目录、/home、/opt、/usr、/var、C:\ 等）。
     */
    private boolean isLargeDirectoryTarget(String lowerCmd) {
        return lowerCmd.contains(" / ") || lowerCmd.endsWith(" /") ||
                lowerCmd.matches(".*\\s[a-z]:\\\\?\\s*$") ||
                lowerCmd.contains(" /home") || lowerCmd.contains(" /opt") ||
                lowerCmd.contains(" /usr") || lowerCmd.contains(" /var") ||
                lowerCmd.contains(" /etc") || lowerCmd.contains(" /lib") ||
                lowerCmd.contains(" c:\\") || lowerCmd.contains(" c:/");
    }

    // ══════════════════════════════════════════════════════════════════════════════
    //  低级终端原语（内部使用）
    // ══════════════════════════════════════════════════════════════════════════════

    private String createTerminal(String sessionId) throws Exception {
        JavaPuppetNode javaPuppetNode = PuppetNodeSessionUtils.getJavaPuppetNode(sessionId);
        String processId = UUID.randomUUID().toString();
        // 首次 write 触发 puppet 端创建 shell 进程（异步）
        javaPuppetNode.execCommand("write", "\n", processId);

        // 等待 shell 就绪：轮询 read 直到收到 prompt 输出，表明 stdin 已可用
        long waitDeadline = System.currentTimeMillis() + 5000; // 最多等 5 秒
        while (System.currentTimeMillis() < waitDeadline) {
            Thread.sleep(300);
            try {
                String output = readFromTerminal(sessionId, processId);
                if (output != null && !output.isEmpty()) {
                    // 收到 prompt（如 "sh-3.2$ " 或 "C:\>"），shell 已就绪
                    log.debug("Terminal ready, processId={}, prompt={}", processId,
                            output.length() > 50 ? output.substring(0, 50) : output);
                    break;
                }
            } catch (Exception e) {
                // stdin 未就绪，继续等待
                log.debug("Waiting for terminal stdin, processId={}", processId);
            }
        }
        return processId;
    }

    private Map<String, Object> writeToTerminal(String sessionId, String cmd, String processId) throws Exception {
        JavaPuppetNode javaPuppetNode = PuppetNodeSessionUtils.getJavaPuppetNode(sessionId);
        return javaPuppetNode.execCommand("write", cmd + "\n", processId);
    }

    private String readFromTerminal(String sessionId, String processId) throws Exception {
        JavaPuppetNode javaPuppetNode = PuppetNodeSessionUtils.getJavaPuppetNode(sessionId);
        Map<String, Object> results = javaPuppetNode.execCommand("read", "", processId);
        return new String((byte[]) results.get("data"));
    }

    private Map<String, Object> stopTerminal(String sessionId, String processId) throws Exception {
        JavaPuppetNode javaPuppetNode = PuppetNodeSessionUtils.getJavaPuppetNode(sessionId);
        return javaPuppetNode.execCommand("stop", "", processId);
    }

    // ══════════════════════════════════════════════════════════════════════════════
    //  输出压缩
    // ══════════════════════════════════════════════════════════════════════════════

    /**
     * 对结果 Map 中的输出字段进行智能压缩。
     * <p>
     * 兼容两种输出格式：
     * <ul>
     *   <li>"output"（String）— execSync / queryTask / stopTask 构建</li>
     *   <li>"data"（byte[] 或 String）— execSimpleCommand 远程返回</li>
     * </ul>
     * 如果压缩生效，会额外写入 outputCompressed / originalChars / compressedChars 标记。
     */
    private void compressOutputField(Map<String, Object> result) {
        if (result == null) return;

        // 优先处理 "output" 字段（自建结果），其次处理 "data" 字段（远程返回）
        String fieldName = null;
        String raw = null;

        Object outputObj = result.get("output");
        if (outputObj instanceof String s && !s.isEmpty()) {
            fieldName = "output";
            raw = s;
        } else {
            Object dataObj = result.get("data");
            if (dataObj instanceof byte[] bytes) {
                fieldName = "data";
                raw = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
                // 同时将 byte[] 转为 String，减少序列化后的 base64 膨胀
                result.put("data", raw);
            } else if (dataObj instanceof String s && !s.isEmpty()) {
                fieldName = "data";
                raw = s;
            }
        }

        if (fieldName == null || raw == null || raw.isEmpty()) return;
        String compressed = ToolResultUtils.compressCommandOutput(raw, ToolResultUtils.DEFAULT_COMMAND_OUTPUT_THRESHOLD);
        if (compressed.length() < raw.length()) {
            result.put(fieldName, compressed);
            result.put("outputCompressed", true);
            result.put("originalChars", raw.length());
            result.put("compressedChars", compressed.length());
        }
    }

    // ══════════════════════════════════════════════════════════════════════════════
    //  OS 平台检测（供其他工具类使用）
    // ══════════════════════════════════════════════════════════════════════════════

    /**
     * 判断 puppet 目标是否为 Windows。
     * <p>
     * 优先级：
     * <ol>
     *   <li>专用缓存 key（由首次探测或预热设置）</li>
     *   <li>basic-info 文本匹配（由 getBasicInfo 工具缓存）</li>
     *   <li>惰性探测：执行 uname -s，成功则判定为 Unix，失败则默认 Unix</li>
     * </ol>
     */
    public boolean isWindows(String sessionId) {
        // 1. 专用缓存
        Object cached = PuppetNodeSessionUtils.getAiContextValue(sessionId, OS_PLATFORM_CACHE_KEY);
        if (cached instanceof String platform) {
            return "windows".equals(platform);
        }

        // 2. basic-info 文本匹配
        Object cachedBasicInfo = PuppetNodeSessionUtils.getAiContextValue(sessionId, "basic-info");
        if (cachedBasicInfo != null) {
            String text = cachedBasicInfo.toString().toLowerCase();
            if (text.contains("windows")) {
                PuppetNodeSessionUtils.putAiContextValue(sessionId, OS_PLATFORM_CACHE_KEY, "windows");
                return true;
            }
            if (text.contains("linux") || text.contains("mac") || text.contains("darwin") || text.contains("unix")) {
                PuppetNodeSessionUtils.putAiContextValue(sessionId, OS_PLATFORM_CACHE_KEY, "unix");
                return false;
            }
        }

        // 3. 惰性探测
        try {
            Map<String, Object> probe = execSync(sessionId, "uname -s", 0);
            if (probe != null) {
                // execSync 已统一规整为 {output, status, ...} 形状
                Object outputObj = probe.get("output");
                String output = outputObj == null ? "" : outputObj.toString().toLowerCase();
                if (output.contains("linux") || output.contains("darwin") || output.contains("unix")) {
                    PuppetNodeSessionUtils.putAiContextValue(sessionId, OS_PLATFORM_CACHE_KEY, "unix");
                    return false;
                }
                if (output.contains("windows")) {
                    PuppetNodeSessionUtils.putAiContextValue(sessionId, OS_PLATFORM_CACHE_KEY, "windows");
                    return true;
                }
            }
        } catch (Exception e) {
            log.debug("uname 探测失败，默认按 Unix 处理，session={}", sessionId);
        }

        // 4. 默认保守
        PuppetNodeSessionUtils.putAiContextValue(sessionId, OS_PLATFORM_CACHE_KEY, "unix");
        return false;
    }
}
