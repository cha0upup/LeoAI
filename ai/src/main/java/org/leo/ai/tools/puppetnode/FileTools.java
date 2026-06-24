package org.leo.ai.tools.puppetnode;

import org.leo.ai.agent.AiToolContext;
import org.leo.ai.util.PuppetNodeSessionUtils;
import org.leo.ai.util.ToolResultUtils;
import org.leo.core.entity.User;
import org.leo.core.puppet.impl.JavaPuppetNode;
import org.leo.core.session.PuppetNodeSession;
import org.leo.service.DownloadEngineService;
import org.leo.service.UploadEngineService;
import org.leo.service.user.UserService;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.P;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 文件传输工具。
 *
 * <p>方法：
 * <ul>
 *   <li>{@code startDownloadTask} / {@code startUploadTask} — 分块二进制传输</li>
 *   <li>{@code readTextFile} — 读取小体积文本文件，会话级缓存（替代 exec cat）</li>
 *   <li>{@code searchFileContent} — grep 封装，结构化返回匹配结果</li>
 * </ul>
 */
@Component
public class FileTools {

    private static final long DEFAULT_TEXT_READ_SIZE = 64 * 1024;

    private final DownloadEngineService downloadEngineService;
    private final UploadEngineService uploadEngineService;
    private final UserService userService;

    public FileTools(DownloadEngineService downloadEngineService, UploadEngineService uploadEngineService, UserService userService) {
        this.downloadEngineService = downloadEngineService;
        this.uploadEngineService = uploadEngineService;
        this.userService = userService;
    }

    // ── 分块传输 ─────────────────────────────────────────────────────────────

    @Tool("启动或续传文件下载任务（puppet 侧→平台侧）。threads 默认 4（最大 16），chunkSize 默认 1048576。")
    public Map<String, Object> startDownloadTask(String filePath, Integer threads, Integer chunkSize) throws Exception {
        String sessionId = AiToolContext.requireSessionId();
        PuppetNodeSession session = PuppetNodeSessionUtils.getSession(sessionId);
        String userId = session.getCreateByUser();
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("当前会话缺少用户信息，无法启动下载任务");
        }
        JavaPuppetNode javaPuppetNode = session.getJavaPuppetNode();
        int resolvedThreads = threads == null ? 4 : threads;
        int resolvedChunkSize = chunkSize == null ? (1024 * 1024) : chunkSize;
        return downloadEngineService.startOrResume(
                javaPuppetNode,
                userId,
                sessionId,
                filePath,
                resolvedThreads,
                resolvedChunkSize
        );
    }

    @Tool("启动文件上传任务（平台侧 VFS→puppet 侧）。vfsPath 相对 VFS 根目录（users/<userId>/uploads/... 或 skills/...）。filePath 为目标服务器落地路径。chunkSize 默认 1048576。")
    public Map<String, Object> startUploadTask(String vfsPath, String filePath, Integer chunkSize) throws Exception {
        String sessionId = AiToolContext.requireSessionId();
        PuppetNodeSession session = PuppetNodeSessionUtils.getSession(sessionId);
        String userId = session.getCreateByUser();
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("当前会话缺少用户信息，无法启动上传任务");
        }
        JavaPuppetNode javaPuppetNode = session.getJavaPuppetNode();
        User user = userService.getUserById(userId);
        String privilege = user == null ? null : user.getPrivilege();
        Path sourceFile = uploadEngineService.resolveVfsFilePath(vfsPath);
        uploadEngineService.validateReadPermission(userId, privilege, sourceFile);
        if (!Files.exists(sourceFile) || !Files.isRegularFile(sourceFile)) {
            throw new IllegalArgumentException("平台侧文件不存在: " + vfsPath);
        }
        int resolvedChunkSize = chunkSize == null ? (1024 * 1024) : chunkSize;
        return uploadEngineService.start(
                javaPuppetNode,
                userId,
                sessionId,
                filePath,
                sourceFile.toFile(),
                sourceFile.getFileName().toString(),
                resolvedChunkSize
        );
    }

    // ── 文本文件读取（带会话缓存）───────────────────────────────────────────

    @Tool("读取 puppet 侧小体积文本文件并返回 UTF-8 内容。适用于配置文件（yml、properties、xml、env）。不能用于查看平台侧 VFS。相同路径按会话缓存，多次读取不重复通信。")
    @SuppressWarnings("unchecked")
    public Map<String, Object> readTextFile(
            String path,
            @P(value = "最多读取字节数。可省略，默认 65536。", required = false, defaultValue = "65536")
            long maxBytes) throws Exception {
        String sessionId = AiToolContext.requireSessionId();
        long readSize = maxBytes > 0 ? maxBytes : DEFAULT_TEXT_READ_SIZE;
        String cacheKey = "text-file:" + path + ":" + readSize;
        Object cached = PuppetNodeSessionUtils.getAiContextValue(sessionId, cacheKey);
        if (cached instanceof Map<?, ?> cachedMap) {
            return (Map<String, Object>) cachedMap;
        }

        Map<String, Object> chunk = fileDownloadChunk(sessionId, path, readSize, 0);
        HashMap<String, Object> result = new HashMap<>();
        result.put("path", path);
        result.put("maxBytes", readSize);
        result.put("download", chunk);
        boolean readSuccess = false;
        if (chunk != null) {
            Object data = chunk.get("data");
            if (data instanceof byte[] bytes) {
                String rawText = new String(bytes, StandardCharsets.UTF_8);
                String compressed = ToolResultUtils.compressTextFileResult(rawText, ToolResultUtils.DEFAULT_TEXT_FILE_THRESHOLD);
                result.put("text", compressed);
                if (compressed.length() < rawText.length()) {
                    result.put("textCompressed", true);
                    result.put("originalChars", rawText.length());
                    result.put("compressedChars", compressed.length());
                }
                readSuccess = true;
            }
            result.put("isTruncated", Boolean.FALSE.equals(chunk.get("isComplete")) || Integer.valueOf(100).equals(chunk.get("code")));
        }
        // 只缓存成功读取的结果，避免将文件不存在或权限错误永久固化
        if (readSuccess) {
            PuppetNodeSessionUtils.putAiContextValue(sessionId, cacheKey, result);
        }
        return result;
    }

    // ── 文件内容搜索 ─────────────────────────────────────────────────────────

    @Tool("在 puppet 侧指定目录下递归搜索文件内容。" +
            "pattern 支持 grep 正则表达式（默认忽略大小写）。fileGlob 限制文件范围，" +
            "逗号分隔多个 pattern（如 yml,yaml,properties,xml）。" +
            "返回结构化匹配列表 [{file, lineNumber, content}]，去重后最多 200 条。" +
            "自动适配 Linux（grep）和 Windows（findstr）。")
    public Map<String, Object> searchFileContent(
            @P("搜索目录的绝对路径") String directory,
            @P("搜索模式（关键词或正则表达式）") String pattern,
            @P("文件扩展名过滤，逗号分隔（如 yml,yaml,properties,xml）。为空则搜所有文件。") String fileGlob,
            @P("最大返回条数（默认 200）") int maxResults) throws Exception {
        String sessionId = AiToolContext.requireSessionId();
        if (directory == null || directory.isBlank()) {
            throw new IllegalArgumentException("directory 不能为空");
        }
        if (pattern == null || pattern.isBlank()) {
            throw new IllegalArgumentException("pattern 不能为空");
        }
        int limit = maxResults > 0 ? Math.min(maxResults, 500) : 200;

        JavaPuppetNode node = PuppetNodeSessionUtils.getJavaPuppetNode(sessionId);

        // 检测 OS 并构建命令
        boolean isWin = isWindowsPuppet(sessionId, node);
        String cmd = buildSearchCommand(directory, pattern, fileGlob, limit, isWin);

        Map<String, Object> raw = node.execSimpleCommand(cmd, 30);
        String output = extractCommandOutput(raw);

        List<Map<String, Object>> matches = parseSearchResults(output, limit);

        HashMap<String, Object> result = new HashMap<>();
        result.put("directory", directory);
        result.put("pattern", pattern);
        result.put("fileGlob", fileGlob);
        result.put("totalMatches", matches.size());
        result.put("matches", matches);
        result.put("truncated", matches.size() >= limit);
        if (output.length() > 12000) {
            result.put("rawOutputTruncated", true);
        }
        return result;
    }

    // ── 内部辅助方法 ─────────────────────────────────────────────────────────

    /**
     * 从 puppet 侧下载文件分块数据（内部实现，不对 AI 暴露）。
     */
    Map<String, Object> fileDownloadChunk(String sessionId, String path, long size, long offset) throws Exception {
        JavaPuppetNode node = PuppetNodeSessionUtils.getJavaPuppetNode(sessionId);
        return node.fileDownloadChunk(path, size, offset);
    }

    /** 构造跨平台搜索命令。 */
    private String buildSearchCommand(String directory, String pattern, String fileGlob, int limit, boolean isWin) {
        if (isWin) {
            return buildWindowsSearchCmd(directory, pattern, fileGlob, limit);
        }
        return buildLinuxSearchCmd(directory, pattern, fileGlob, limit);
    }

    private String buildLinuxSearchCmd(String directory, String pattern, String fileGlob, int limit) {
        StringBuilder sb = new StringBuilder("grep -rin -m ").append(limit);
        sb.append(" '").append(escapeShell(pattern)).append("' ");
        sb.append("'").append(escapeShell(directory)).append("'");
        if (fileGlob != null && !fileGlob.isBlank()) {
            String[] globs = fileGlob.split(",");
            for (String g : globs) {
                String ext = g.trim();
                if (!ext.isEmpty()) {
                    sb.append(" --include='*.").append(escapeShell(ext)).append("'");
                }
            }
        }
        sb.append(" 2>/dev/null | head -").append(limit);
        return sb.toString();
    }

    private String buildWindowsSearchCmd(String directory, String pattern, String fileGlob, int limit) {
        StringBuilder sb = new StringBuilder("findstr /s /i /n /c:\"");
        sb.append(escapeCmd(pattern)).append("\" ");
        String normalizedDir = directory.replace("/", "\\").replaceAll("\\\\$", "");
        if (fileGlob != null && !fileGlob.isBlank()) {
            String[] globs = fileGlob.split(",");
            for (String g : globs) {
                String ext = g.trim();
                if (!ext.isEmpty()) {
                    sb.append(normalizedDir).append("\\*.").append(ext).append(" ");
                }
            }
        } else {
            sb.append(normalizedDir).append("\\* ");
        }
        sb.append("2>nul");
        return "cmd /c \"" + sb + "\"";
    }

    /** 解析 grep -rn 输出为结构化列表。格式：file:lineNumber:content */
    private List<Map<String, Object>> parseSearchResults(String output, int limit) {
        List<Map<String, Object>> matches = new ArrayList<>();
        if (output == null || output.isBlank()) return matches;

        String[] lines = output.split("\n");
        for (int i = 0; i < lines.length && matches.size() < limit; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) continue;
            // grep -rn: path:lineno:content
            int firstColon = line.indexOf(':');
            if (firstColon < 0) continue;
            int secondColon = line.indexOf(':', firstColon + 1);
            if (secondColon < 0) continue;

            String file = line.substring(0, firstColon);
            String lineNumStr = line.substring(firstColon + 1, secondColon);
            String content = line.substring(secondColon + 1);

            int lineNumber;
            try {
                lineNumber = Integer.parseInt(lineNumStr);
            } catch (NumberFormatException e) {
                continue;
            }

            Map<String, Object> match = new HashMap<>();
            match.put("file", file);
            match.put("lineNumber", lineNumber);
            match.put("content", content.trim());
            matches.add(match);
        }
        return matches;
    }

    /** 从 execSimpleCommand 返回的 Map 中提取字符串输出。 */
    private String extractCommandOutput(Map<String, Object> raw) {
        if (raw == null) return "";
        Object data = raw.get("data");
        if (data instanceof byte[] bytes) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
        if (data instanceof String s) return s;
        Object output = raw.get("output");
        if (output instanceof String s) return s;
        return "";
    }

    /** 简单的 Windows puppet 检测（首次探测后缓存）。 */
    private boolean isWindowsPuppet(String sessionId, JavaPuppetNode node) {
        String cacheKey = "os-platform";
        Object cached = PuppetNodeSessionUtils.getAiContextValue(sessionId, cacheKey);
        if (cached instanceof String platform) {
            return "windows".equals(platform);
        }
        // 惰性探测一次
        try {
            Map<String, Object> probe = node.execSimpleCommand("uname -s 2>/dev/null || echo Windows");
            String out = extractCommandOutput(probe).trim().toLowerCase();
            boolean isWin = out.isEmpty() || out.contains("windows");
            PuppetNodeSessionUtils.putAiContextValue(sessionId, cacheKey, isWin ? "windows" : "unix");
            return isWin;
        } catch (Exception e) {
            return false;
        }
    }

    private String escapeShell(String s) {
        if (s == null) return "";
        return s.replace("'", "'\\''");
    }

    private String escapeCmd(String s) {
        if (s == null) return "";
        return s.replace("\"", "\\\"");
    }
}
