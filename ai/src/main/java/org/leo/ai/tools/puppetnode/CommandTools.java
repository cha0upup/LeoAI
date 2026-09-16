package org.leo.ai.tools.puppetnode;

import org.leo.ai.agent.AiToolContext;
import org.leo.ai.util.PuppetNodeSessionUtils;
import org.leo.ai.util.ToolResultUtils;
import org.leo.core.puppet.AbstractPuppetNode;
import org.leo.core.puppet.capability.CommandCapable;
import org.leo.service.audit.PuppetAuditService;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.P;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 命令执行工具（精简版）
 * <p>
 * 对外仅暴露 5 个 @Tool 方法：
 * <ul>
 *   <li>{@link #exec} — 统一执行入口，自动判断同步/异步/缓存</li>
 *   <li>{@link #queryTask} — 查询异步任务输出</li>
 *   <li>{@link #writeTask} — 向异步终端继续写入输入</li>
 *   <li>{@link #resizeTask} — 调整异步终端窗口尺寸</li>
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
@org.leo.ai.agent.AiToolPolicy(kind = org.leo.ai.agent.AiToolKind.COMMAND,
        operation = org.leo.ai.agent.AiToolOperation.WRITE)
public class CommandTools {

    private final PuppetAuditService auditService;

    public CommandTools(PuppetAuditService auditService) {
        this.auditService = auditService;
    }

    // ══════════════════════════════════════════════════════════════════════════════
    //  公开 @Tool 方法（仅 5 个）
    // ══════════════════════════════════════════════════════════════════════════════

    @Tool("在 puppet 侧执行系统命令。统一入口，自动选择最优执行模式。\n"
            + "• timeout=0：使用默认超时（30s），适合明确快完成的命令\n"
            + "• timeout>0：自定义超时（1~120s），到点强制终止并返回已收集的部分输出\n"
            + "• 检测到必然耗时的命令自动转异步，返回 taskId，需用 queryTask 轮询\n"
            + "已知高频命令（env、java进程参数）自动命中会话缓存。不能用于查看平台侧 VFS。")
    public Map<String, Object> exec(
            @P("要执行的命令") String cmd,
            @P(value = "超时秒数。0=默认 30s；>0=自定义（1~120）。检测到耗时命令时忽略此参数自动转异步。",
                    required = false, defaultValue = "0") int timeout) throws Exception {
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

    @Tool("查询异步命令的新输出。当 exec 返回 taskId 时使用；每次读取会消费当前输出缓冲区。"
            + "返回 output、status、alive，命令结束时还会返回 exitCode。"
            + "如果输出已包含所需信息或为空（命令已结束），调用 stopTask 释放资源；"
            + "该资源回收动作直接执行，不需要另行确认。")
    @org.leo.ai.agent.AiToolPolicy(kind = org.leo.ai.agent.AiToolKind.QUERY,
            operation = org.leo.ai.agent.AiToolOperation.WRITE, business = false, exclusive = true)
    public Map<String, Object> queryTask(
            @P("exec 返回的 taskId") String taskId) throws Exception {
        String sessionId = AiToolContext.requireSessionId();
        Map<String, Object> terminal = readTerminalState(sessionId, taskId);
        HashMap<String, Object> result = new HashMap<>();
        result.put("taskId", taskId);
        result.put("output", terminalOutput(terminal));
        boolean alive = terminal != null && Boolean.TRUE.equals(terminal.get("alive"));
        result.put("alive", alive);
        result.put("status", alive ? "running" : "completed");
        if (terminal != null && terminal.get("exitCode") != null) {
            result.put("exitCode", terminal.get("exitCode"));
        }
        if (terminal != null && terminal.get("error") != null) {
            result.put("error", terminal.get("error"));
        }
        result.put("hint", alive
                ? "继续轮询；获得所需信息后调用 stopTask 回收资源。"
                : "命令已结束，直接调用 stopTask 清理异步终端。");
        compressOutputField(result);
        return result;
    }

    @org.leo.ai.agent.AiToolPolicy(kind = org.leo.ai.agent.AiToolKind.CONTROL,
            operation = org.leo.ai.agent.AiToolOperation.WRITE, business = false, exclusive = true)
    @Tool("向当前 AI 通过 exec 创建的异步终端写入后续输入。用于回答交互提示、发送控制字符"
            + "或继续操作终端程序；普通文本输入通常设置 appendNewline=true，Ctrl+C 使用 input=\\u0003"
            + "且 appendNewline=false。该终端交互动作直接执行，不需要用户确认。")
    public Map<String, Object> writeTask(
            @P("exec 返回的 taskId") String taskId,
            @P(value = "写入终端的原始文本或控制字符；允许空字符串与 appendNewline=true 组合表示单独按回车")
                    String input,
            @P(value = "是否在输入末尾追加换行；普通命令或提示回答设为 true，控制字符设为 false",
                    required = false, defaultValue = "false") boolean appendNewline) throws Exception {
        String sessionId = AiToolContext.requireSessionId();
        String rawInput = input != null ? input : "";
        if (rawInput.isEmpty() && !appendNewline) {
            throw new IllegalArgumentException("input 为空时 appendNewline 必须为 true");
        }
        String payload = appendNewline ? rawInput + "\n" : rawInput;
        Map<String, Object> terminal = writeRawToTerminal(sessionId, payload, taskId);
        HashMap<String, Object> result = new HashMap<>(copyStringKeyMap(terminal));
        result.put("taskId", taskId);
        result.put("status", "written");
        result.put("writtenChars", payload.length());
        result.put("appendNewline", appendNewline);
        result.put("hint", "使用 queryTask(taskId) 读取交互后的新输出。");
        return result;
    }

    @org.leo.ai.agent.AiToolPolicy(kind = org.leo.ai.agent.AiToolKind.CONTROL,
            operation = org.leo.ai.agent.AiToolOperation.WRITE, business = false, exclusive = true)
    @Tool("调整当前 AI 异步终端的窗口尺寸。适用于依赖 TTY 行列数的交互程序；"
            + "PTY 后端会实时调整，PIPE/FIXED 后端会返回 resizable=false。该动作直接执行，不需要用户确认。")
    public Map<String, Object> resizeTask(
            @P("exec 返回的 taskId") String taskId,
            @P("终端列数，范围 20-500") int cols,
            @P("终端行数，范围 5-200") int rows) throws Exception {
        String sessionId = AiToolContext.requireSessionId();
        int normalizedCols = Math.max(20, Math.min(500, cols));
        int normalizedRows = Math.max(5, Math.min(200, rows));
        Map<String, Object> terminal = getCommandNode(sessionId).execCommand(
                "resize", normalizedCols + "," + normalizedRows, taskId);
        HashMap<String, Object> result = new HashMap<>(copyStringKeyMap(terminal));
        result.put("taskId", taskId);
        result.put("cols", normalizedCols);
        result.put("rows", normalizedRows);
        result.put("status", Boolean.FALSE.equals(result.get("resizable")) ? "fixed" : "resized");
        return result;
    }

    @org.leo.ai.agent.AiToolPolicy(kind = org.leo.ai.agent.AiToolKind.CONTROL,
            operation = org.leo.ai.agent.AiToolOperation.WRITE, business = false, exclusive = true)
    @Tool("关闭当前 AI 通过 exec 创建的异步终端并释放资源。若命令仍在运行会同步结束对应进程；"
            + "若命令已经结束则只做清理。该资源回收动作直接执行，不需要用户确认。返回清理前的最后一段输出。")
    public Map<String, Object> stopTask(
            @P("exec 返回的 taskId") String taskId) throws Exception {
        String sessionId = AiToolContext.requireSessionId();
        AbstractPuppetNode auditNode = PuppetNodeSessionUtils.getPuppetNode(sessionId);
        try {
            String output = terminalOutput(readTerminalState(sessionId, taskId));
            stopTerminal(sessionId, taskId);
            auditService.logSuccess(sessionId, auditNode, "COMMAND_STOP", "AI停止命令进程", taskId,
                    commandAuditParams(sessionId, null, "async-stop", taskId, null), "停止命令进程成功");
            HashMap<String, Object> result = new HashMap<>();
            result.put("taskId", taskId);
            result.put("output", output);
            result.put("status", "stopped");
            compressOutputField(result);
            return result;
        } catch (Exception e) {
            auditService.logFailure(sessionId, auditNode, "COMMAND_STOP", "AI停止命令进程", taskId,
                    commandAuditParams(sessionId, null, "async-stop", taskId, null), e.getMessage());
            throw e;
        }
    }

    // ══════════════════════════════════════════════════════════════════════════════
    //  内部执行模式
    // ══════════════════════════════════════════════════════════════════════════════

    /**
     * 同步执行命令：fork 子进程 → 等待退出 → 收集输出。
     * <p>无论 timeoutSeconds 是 0（用组件默认 30s）还是 &gt;0（自定义），都走同一条 fork-exec-wait 路径。
     * <p>结果统一规整为 {@code {cmd, output, status, exitCode, timedOut}} 形状，与异步路径一致。
     */
    private Map<String, Object> execSync(String sessionId, String cmd, int timeoutSeconds) throws Exception {
        CommandCapable commandNode = getCommandNode(sessionId);
        AbstractPuppetNode auditNode = PuppetNodeSessionUtils.getPuppetNode(sessionId);
        Map<String, Object> auditParams = commandAuditParams(sessionId, cmd, "sync", null, timeoutSeconds);
        try {
            Map<String, Object> raw = timeoutSeconds > 0
                    ? commandNode.execSimpleCommand(cmd, timeoutSeconds)
                    : commandNode.execSimpleCommand(cmd);

            Map<String, Object> result = normalizeSimpleResult(cmd, raw, timeoutSeconds);
            compressOutputField(result);
            if (result.containsKey("error")) {
                auditService.logFailure(sessionId, auditNode, "COMMAND_EXEC", "AI执行命令", cmd, auditParams,
                        String.valueOf(result.get("error")));
            } else {
                auditService.logSuccess(sessionId, auditNode, "COMMAND_EXEC", "AI执行命令", cmd, auditParams,
                        "AI命令执行完成");
            }
            return result;
        } catch (Exception e) {
            auditService.logFailure(sessionId, auditNode, "COMMAND_EXEC", "AI执行命令", cmd, auditParams, e.getMessage());
            throw e;
        }
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
        AbstractPuppetNode auditNode = PuppetNodeSessionUtils.getPuppetNode(sessionId);
        Map<String, Object> auditParams = commandAuditParams(sessionId, cmd, "async", taskId, null);
        try {
            writeToTerminal(sessionId, cmd, taskId);
            auditService.logSuccess(sessionId, auditNode, "COMMAND_EXEC", "AI执行命令", cmd, auditParams,
                    "AI异步命令已启动");
        } catch (Exception e) {
            auditService.logFailure(sessionId, auditNode, "COMMAND_EXEC", "AI执行命令", cmd, auditParams, e.getMessage());
            try {
                stopTerminal(sessionId, taskId);
            } catch (Exception ignored) {
                // 保留原始写入异常；终端的空闲回收仍会处理极端清理失败。
            }
            throw e;
        }
        HashMap<String, Object> result = new HashMap<>();
        result.put("taskId", taskId);
        result.put("status", "running");
        result.put("cmd", cmd);
        result.put("hint", "命令已异步启动。使用 queryTask(taskId) 获取输出；遇到交互提示时使用 "
                + "writeTask(taskId, input, appendNewline)，需要调整终端尺寸时使用 resizeTask；"
                + "完成后直接使用 stopTask(taskId) 回收资源。");
        return result;
    }

    private Map<String, Object> commandAuditParams(String sessionId,
                                                   String cmd,
                                                   String mode,
                                                   String taskId,
                                                   Integer timeoutSeconds) {
        HashMap<String, Object> params = new HashMap<>();
        params.put("sessionId", sessionId);
        params.put("cmd", cmd);
        params.put("mode", mode);
        params.put("taskId", taskId);
        params.put("timeoutSeconds", timeoutSeconds);
        return params;
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
                return normalizeCachedCommandResult(cmd, cachedMap);
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
                return normalizeCachedCommandResult(cmd, cachedMap);
            }
            Map<String, Object> results = execSync(sessionId, cmd, 0);
            if (results != null && !results.containsKey("error") && !"timeout".equals(results.get("status"))) {
                PuppetNodeSessionUtils.putAiContextValue(sessionId, cacheKey, results);
            }
            return results;
        }

        return null;
    }

    private static Map<String, Object> normalizeCachedCommandResult(String cmd, Map<?, ?> cachedMap) {
        Map<String, Object> cached = copyStringKeyMap(cachedMap);
        if (cached.containsKey("output") || cached.containsKey("status")) return cached;
        return normalizeSimpleResult(cmd, cached, 0);
    }

    private static Map<String, Object> copyStringKeyMap(Map<?, ?> source) {
        HashMap<String, Object> copy = new HashMap<>();
        source.forEach((key, value) -> {
            if (key instanceof String stringKey) copy.put(stringKey, value);
        });
        return copy;
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
        CommandCapable commandNode = getCommandNode(sessionId);
        String processId = UUID.randomUUID().toString();
        try {
            // Java 与 PHP 的 init 响应都在输入通道可用后返回，无需额外消费一次终端输出。
            Map<String, Object> initialized = commandNode.execCommand("init", "", processId);
            if (initialized == null) {
                throw new IllegalStateException("terminal initialization returned no state");
            }
            if (Boolean.FALSE.equals(initialized.get("alive"))) {
                throw new IllegalStateException("terminal initialization returned inactive state");
            }
            return processId;
        } catch (Exception error) {
            try {
                commandNode.execCommand("stop", "", processId);
            } catch (Exception ignored) {
                // 保留初始化阶段的原始异常。
            }
            throw error;
        }
    }

    private Map<String, Object> writeToTerminal(String sessionId, String cmd, String processId) throws Exception {
        return writeRawToTerminal(sessionId, cmd + "\n", processId);
    }

    private Map<String, Object> writeRawToTerminal(String sessionId, String input,
                                                   String processId) throws Exception {
        return getCommandNode(sessionId).execCommand("write", input, processId);
    }

    private Map<String, Object> readTerminalState(String sessionId, String processId) throws Exception {
        return getCommandNode(sessionId).execCommand("read", "", processId);
    }

    private static String terminalOutput(Map<String, Object> terminal) {
        if (terminal == null) return "";
        Object data = terminal.get("data");
        if (data instanceof byte[] bytes) {
            return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        }
        return data == null ? "" : String.valueOf(data);
    }

    private Map<String, Object> stopTerminal(String sessionId, String processId) throws Exception {
        return getCommandNode(sessionId).execCommand("stop", "", processId);
    }

    private CommandCapable getCommandNode(String sessionId) {
        return PuppetNodeSessionUtils.requireCapability(sessionId, CommandCapable.class);
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

}
