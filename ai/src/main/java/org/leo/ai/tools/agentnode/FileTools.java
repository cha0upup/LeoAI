package org.leo.ai.tools.agentnode;

import org.leo.ai.agent.AiToolContext;
import org.leo.ai.agent.AiToolException;
import org.leo.ai.service.workspace.AiWorkspaceService;
import org.leo.ai.util.AgentNodeSessionUtils;
import org.leo.ai.util.ToolResultUtils;
import org.leo.core.entity.User;
import org.leo.core.agent.AbstractAgentNode;
import org.leo.core.agent.capability.CommandCapable;
import org.leo.core.agent.capability.FileCapable;
import org.leo.core.session.AgentNodeSession;
import org.leo.service.DownloadEngineService;
import org.leo.service.UploadEngineService;
import org.leo.service.audit.AgentAuditService;
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
 *   <li>{@code stageRemoteFileToWorkspace} / {@code queryRemoteFileStage} — 远端文件采集到 AI 工作空间</li>
 *   <li>{@code readTextFile} — 读取小体积文本文件，会话级缓存（替代 exec cat）</li>
 *   <li>{@code searchFileContent} — grep 封装，结构化返回匹配结果</li>
 * </ul>
 */
@Component
@org.leo.ai.agent.AiToolPolicy(kind = org.leo.ai.agent.AiToolKind.COMMAND,
        operation = org.leo.ai.agent.AiToolOperation.WRITE)
public class FileTools {

    private static final long DEFAULT_TEXT_READ_SIZE = 64 * 1024;

    private final DownloadEngineService downloadEngineService;
    private final UploadEngineService uploadEngineService;
    private final UserService userService;
    private final AgentAuditService auditService;
    private final AiWorkspaceService workspaceService;

    public FileTools(DownloadEngineService downloadEngineService,
                     UploadEngineService uploadEngineService,
                     UserService userService,
                     AgentAuditService auditService,
                     AiWorkspaceService workspaceService) {
        this.downloadEngineService = downloadEngineService;
        this.uploadEngineService = uploadEngineService;
        this.userService = userService;
        this.auditService = auditService;
        this.workspaceService = workspaceService;
    }

    // ── 分块传输 ─────────────────────────────────────────────────────────────

    @Tool("启动或续传文件下载任务（agent 侧→平台侧）。threads 默认 4（最大 16），chunkSize 默认 1048576。")
    @org.leo.ai.agent.AiToolPolicy(kind = org.leo.ai.agent.AiToolKind.ARTIFACT,
            operation = org.leo.ai.agent.AiToolOperation.WRITE, business = false)
    public Map<String, Object> startDownloadTask(
            @P("agent 侧待下载文件的绝对路径") String filePath,
            @P(value = "下载线程数，默认4，最大16", required = false, defaultValue = "4") Integer threads,
            @P(value = "分块大小，默认1048576", required = false, defaultValue = "1048576") Integer chunkSize) throws Exception {
        String sessionId = AiToolContext.requireSessionId();
        AgentNodeSession session = AgentNodeSessionUtils.getSession(sessionId);
        String userId = session.getCreateByUser();
        if (userId == null || userId.isBlank()) {
            throw missingSessionUser("下载");
        }
        FileCapable fileNode = AgentNodeSessionUtils.requireCapability(sessionId, FileCapable.class);
        AbstractAgentNode auditNode = AgentNodeSessionUtils.getAgentNode(sessionId);
        int resolvedThreads = threads == null ? 4 : threads;
        int resolvedChunkSize = chunkSize == null ? (1024 * 1024) : chunkSize;
        Map<String, Object> auditParams = fileAuditParams(sessionId, filePath, null, null);
        auditParams.put("threads", resolvedThreads);
        auditParams.put("chunkSize", resolvedChunkSize);
        try {
            Map<String, Object> result = downloadEngineService.startOrResume(
                    fileNode,
                    userId,
                    sessionId,
                    filePath,
                    resolvedThreads,
                    resolvedChunkSize
            );
            auditService.logSuccess(sessionId, auditNode, "FILE_DOWNLOAD", "AI下载文件", filePath,
                    auditParams, "AI下载任务已启动");
            return result;
        } catch (Exception e) {
            auditService.logFailure(sessionId, auditNode, "FILE_DOWNLOAD", "AI下载文件", filePath,
                    auditParams, e.getMessage());
            throw e;
        }
    }

    @Tool("启动或续传文件采集任务，将 agent 侧普通文件下载后导入当前 Agent 任务工作空间。"
            + "workspacePath 必须是当前工作空间中的新相对路径。返回 taskId；未完成时使用 queryRemoteFileStage 查询。")
    @org.leo.ai.agent.AiToolPolicy(kind = org.leo.ai.agent.AiToolKind.ARTIFACT,
            operation = org.leo.ai.agent.AiToolOperation.WRITE, business = false)
    public Map<String, Object> stageRemoteFileToWorkspace(
            @P("agent 侧文件绝对路径") String filePath,
            @P("当前 AI 工作空间中的目标相对路径，如 input/browser/history.sqlite") String workspacePath,
            @P(value = "下载线程数，默认4，最大16", required = false, defaultValue = "4") Integer threads,
            @P(value = "分块大小，默认1048576", required = false, defaultValue = "1048576") Integer chunkSize) throws Exception {
        String sessionId = AiToolContext.requireSessionId();
        AgentNodeSession session = AgentNodeSessionUtils.getSession(sessionId);
        String userId = requireSessionUser(session, "采集");
        FileCapable fileNode = AgentNodeSessionUtils.requireCapability(sessionId, FileCapable.class);
        AbstractAgentNode auditNode = AgentNodeSessionUtils.getAgentNode(sessionId);
        var workspace = workspaceService.workspaceFromContext();
        workspaceService.validateGeneratedFileTarget(workspace, workspacePath, 0L);
        int resolvedThreads = threads == null ? 4 : threads;
        int resolvedChunkSize = chunkSize == null ? (1024 * 1024) : chunkSize;
        Map<String, Object> auditParams = fileAuditParams(sessionId, filePath, null, null);
        auditParams.put("workspacePath", workspacePath);
        auditParams.put("threads", resolvedThreads);
        auditParams.put("chunkSize", resolvedChunkSize);
        try {
            Map<String, Object> task = downloadEngineService.startOrResume(
                    fileNode, userId, sessionId, filePath,
                    resolvedThreads, resolvedChunkSize);
            long expectedLength = numberValue(task.get("expectedLength"));
            workspaceService.validateGeneratedFileTarget(
                    workspace, workspacePath, expectedLength);
            Map<String, Object> result = finalizeWorkspaceStage(
                    task, userId, sessionId, workspacePath);
            auditParams.put("taskId", result.get("taskId"));
            auditService.logSuccess(sessionId, auditNode, "FILE_DOWNLOAD",
                    "AI采集文件到任务工作空间", filePath, auditParams,
                    Boolean.TRUE.equals(result.get("staged"))
                            ? "文件已导入任务工作空间" : "文件采集任务已启动");
            return result;
        } catch (Exception e) {
            auditService.logFailure(sessionId, auditNode, "FILE_DOWNLOAD",
                    "AI采集文件到任务工作空间", filePath, auditParams, e.getMessage());
            throw e;
        }
    }

    @Tool("查询远端文件采集任务；下载完成时自动把文件原子导入当前 AI 工作空间。"
            + "workspacePath 是当前工作空间中的新目标相对路径。")
    @org.leo.ai.agent.AiToolPolicy(kind = org.leo.ai.agent.AiToolKind.ARTIFACT,
            operation = org.leo.ai.agent.AiToolOperation.WRITE, business = false)
    public Map<String, Object> queryRemoteFileStage(
            @P("stageRemoteFileToWorkspace 返回的 taskId") String taskId,
            @P("启动采集时使用的工作空间目标相对路径") String workspacePath) throws Exception {
        String sessionId = AiToolContext.requireSessionId();
        AgentNodeSession session = AgentNodeSessionUtils.getSession(sessionId);
        String userId = requireSessionUser(session, "查询采集任务");
        AbstractAgentNode auditNode = AgentNodeSessionUtils.getAgentNode(sessionId);
        Map<String, Object> auditParams = fileAuditParams(sessionId, null, null, null);
        auditParams.put("taskId", taskId);
        auditParams.put("workspacePath", workspacePath);
        try {
            Map<String, Object> task = downloadEngineService.progress(userId, taskId);
            Map<String, Object> result = finalizeWorkspaceStage(
                    task, userId, sessionId, workspacePath);
            if (Boolean.TRUE.equals(result.get("staged"))) {
                auditService.logSuccess(sessionId, auditNode, "FILE_DOWNLOAD",
                        "AI完成文件采集", String.valueOf(result.get("filePath")),
                        auditParams, "文件已导入任务工作空间");
            }
            return result;
        } catch (Exception e) {
            auditService.logFailure(sessionId, auditNode, "FILE_DOWNLOAD",
                    "AI查询文件采集任务", taskId, auditParams, e.getMessage());
            throw e;
        }
    }

    @Tool("启动文件上传任务（平台侧 VFS→agent 侧）。vfsPath 相对 VFS 根目录（users/<userId>/uploads/... 或 skills/...）。filePath 为目标服务器落地路径。chunkSize 默认 1048576。")
    public Map<String, Object> startUploadTask(
            @P("平台侧 VFS 相对路径") String vfsPath,
            @P("agent 侧目标文件绝对路径") String filePath,
            @P(value = "分块大小，默认1048576", required = false, defaultValue = "1048576") Integer chunkSize) throws Exception {
        String sessionId = AiToolContext.requireSessionId();
        AgentNodeSession session = AgentNodeSessionUtils.getSession(sessionId);
        String userId = session.getCreateByUser();
        if (userId == null || userId.isBlank()) {
            throw missingSessionUser("上传");
        }
        FileCapable fileNode = AgentNodeSessionUtils.requireCapability(sessionId, FileCapable.class);
        AbstractAgentNode auditNode = AgentNodeSessionUtils.getAgentNode(sessionId);
        User user = userService.getUserById(userId);
        String privilege = user == null ? null : user.getPrivilege();
        Path sourceFile = uploadEngineService.resolveVfsFilePath(vfsPath);
        uploadEngineService.validateReadPermission(userId, privilege, sourceFile);
        if (!Files.exists(sourceFile) || !Files.isRegularFile(sourceFile)) {
            throw AiToolException.modelCorrectable(
                    "RESOURCE_NOT_FOUND",
                    "平台侧文件不存在: " + vfsPath,
                    "先查询平台侧 VFS 文件列表，使用存在且有读取权限的路径。");
        }
        int resolvedChunkSize = chunkSize == null ? (1024 * 1024) : chunkSize;
        Map<String, Object> auditParams = fileAuditParams(sessionId, filePath, vfsPath, null);
        auditParams.put("chunkSize", resolvedChunkSize);
        try {
            Map<String, Object> result = uploadEngineService.start(
                    fileNode,
                    userId,
                    sessionId,
                    filePath,
                    sourceFile.toFile(),
                    sourceFile.getFileName().toString(),
                    resolvedChunkSize
            );
            auditService.logSuccess(sessionId, auditNode, "FILE_UPLOAD", "AI上传文件",
                    vfsPath + " -> " + filePath, auditParams, "AI上传任务已启动");
            return result;
        } catch (Exception e) {
            auditService.logFailure(sessionId, auditNode, "FILE_UPLOAD", "AI上传文件",
                    vfsPath + " -> " + filePath, auditParams, e.getMessage());
            throw e;
        }
    }

    // ── 文本文件读取（带会话缓存）───────────────────────────────────────────

    @Tool("读取 agent 侧小体积文本文件并返回 UTF-8 内容。适用于配置文件（yml、properties、xml、env）。不能用于查看平台侧 VFS。相同路径按会话缓存，多次读取不重复通信。")
    @SuppressWarnings("unchecked")
    @org.leo.ai.agent.AiToolPolicy(kind = org.leo.ai.agent.AiToolKind.QUERY,
            operation = org.leo.ai.agent.AiToolOperation.READ_ONLY, parallelizable = true)
    public Map<String, Object> readTextFile(
            String path,
            @P(value = "最多读取字节数。可省略，默认 65536。", required = false, defaultValue = "65536")
            long maxBytes) throws Exception {
        String sessionId = AiToolContext.requireSessionId();
        long readSize = maxBytes > 0 ? maxBytes : DEFAULT_TEXT_READ_SIZE;
        String cacheKey = "text-file:" + path + ":" + readSize;
        Object cached = AgentNodeSessionUtils.getAiContextValue(sessionId, cacheKey);
        if (cached instanceof Map<?, ?> cachedMap) {
            return (Map<String, Object>) cachedMap;
        }

        FileCapable node = AgentNodeSessionUtils.requireCapability(sessionId, FileCapable.class);
        AbstractAgentNode auditNode = AgentNodeSessionUtils.getAgentNode(sessionId);
        Map<String, Object> auditParams = fileAuditParams(sessionId, path, null, readSize);
        Map<String, Object> chunk;
        try {
            chunk = node.fileDownloadChunk(path, readSize, 0);
            auditService.logSuccess(sessionId, auditNode, "FILE_READ", "AI读取文件", path, auditParams,
                    "AI读取文件成功");
        } catch (Exception e) {
            auditService.logFailure(sessionId, auditNode, "FILE_READ", "AI读取文件", path, auditParams, e.getMessage());
            throw e;
        }
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
            AgentNodeSessionUtils.putAiContextValue(sessionId, cacheKey, result);
        }
        return result;
    }

    // ── 文件内容搜索 ─────────────────────────────────────────────────────────

    @Tool("在 agent 侧指定目录下递归搜索文件内容。" +
            "pattern 支持 grep 正则表达式（默认忽略大小写）。fileGlob 限制文件范围，" +
            "逗号分隔多个 pattern（如 yml,yaml,properties,xml）。" +
            "返回结构化匹配列表 [{file, lineNumber, content}]，去重后最多 200 条。" +
            "自动适配 Linux（grep）和 Windows（findstr）。")
    @org.leo.ai.agent.AiToolPolicy(kind = org.leo.ai.agent.AiToolKind.QUERY,
            operation = org.leo.ai.agent.AiToolOperation.READ_ONLY, parallelizable = true)
    public Map<String, Object> searchFileContent(
            @P("搜索目录的绝对路径") String directory,
            @P("搜索模式（关键词或正则表达式）") String pattern,
            @P(value = "文件扩展名过滤，逗号分隔（如 yml,yaml,properties,xml）；省略时搜索所有文件",
                    required = false) String fileGlob,
            @P(value = "最大返回条数，默认 200，最大 500",
                    required = false, defaultValue = "200") int maxResults) throws Exception {
        String sessionId = AiToolContext.requireSessionId();
        if (directory == null || directory.isBlank()) {
            throw new IllegalArgumentException("directory 不能为空");
        }
        if (pattern == null || pattern.isBlank()) {
            throw new IllegalArgumentException("pattern 不能为空");
        }
        int limit = maxResults > 0 ? Math.min(maxResults, 500) : 200;

        CommandCapable commandNode = AgentNodeSessionUtils.requireCapability(sessionId, CommandCapable.class);
        AbstractAgentNode auditNode = AgentNodeSessionUtils.getAgentNode(sessionId);

        // 检测 OS 并构建命令
        boolean isWin = isWindowsHost(sessionId, commandNode);
        String cmd = buildSearchCommand(directory, pattern, fileGlob, limit, isWin);

        Map<String, Object> auditParams = fileAuditParams(sessionId, directory, null, null);
        auditParams.put("pattern", pattern);
        auditParams.put("fileGlob", fileGlob);
        auditParams.put("limit", limit);
        try {
            Map<String, Object> raw = commandNode.execSimpleCommand(cmd, 30);
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
            auditService.logSuccess(sessionId, auditNode, "FILE_SEARCH", "AI搜索文件内容", directory,
                    auditParams, "AI搜索文件内容成功");
            return result;
        } catch (Exception e) {
            auditService.logFailure(sessionId, auditNode, "FILE_SEARCH", "AI搜索文件内容", directory,
                    auditParams, e.getMessage());
            throw e;
        }
    }

    // ── 内部辅助方法 ─────────────────────────────────────────────────────────

    /**
     * 从 agent 侧下载文件分块数据（内部实现，不对 AI 暴露）。
     */
    Map<String, Object> fileDownloadChunk(String sessionId, String path, long size, long offset) throws Exception {
        FileCapable node = AgentNodeSessionUtils.requireCapability(sessionId, FileCapable.class);
        return node.fileDownloadChunk(path, size, offset);
    }

    private Map<String, Object> fileAuditParams(String sessionId, String path, String sourcePath, Long maxBytes) {
        HashMap<String, Object> params = new HashMap<>();
        params.put("sessionId", sessionId);
        params.put("path", path);
        params.put("sourcePath", sourcePath);
        params.put("maxBytes", maxBytes);
        return params;
    }

    private String requireSessionUser(AgentNodeSession session, String operation) {
        String userId = session == null ? null : session.getCreateByUser();
        if (userId == null || userId.isBlank()) {
            throw missingSessionUser(operation);
        }
        return userId;
    }

    private Map<String, Object> finalizeWorkspaceStage(Map<String, Object> task,
                                                       String userId,
                                                       String sessionId,
                                                       String workspacePath) {
        Map<String, Object> result = new HashMap<>(task == null ? Map.of() : task);
        String taskSessionId = stringValue(result.get("sessionId"));
        if (taskSessionId == null || !sessionId.equals(taskSessionId)) {
            throw new SecurityException("采集任务不属于当前会话");
        }
        result.put("workspacePath", workspacePath);
        String state = stringValue(result.get("state"));
        if (!"COMPLETED".equalsIgnoreCase(state)) {
            result.put("staged", false);
            if ("FAILED".equalsIgnoreCase(state) || "CANCELLED".equalsIgnoreCase(state)) {
                result.put("nextAction", "查看 lastError/errorStage；需要重试时重新调用 stageRemoteFileToWorkspace");
            } else if ("PAUSED".equalsIgnoreCase(state)) {
                result.put("nextAction", "重新调用 stageRemoteFileToWorkspace 恢复采集，随后查询任务状态");
            } else {
                result.put("nextAction", "继续调用 queryRemoteFileStage，直到 state=COMPLETED 或任务进入终态");
            }
            return result;
        }
        String downloadPath = stringValue(result.get("downloadPath"));
        if (downloadPath == null || downloadPath.isBlank()) {
            throw new IllegalStateException("已完成下载缺少平台侧文件路径");
        }
        User user = userService.getUserById(userId);
        String privilege = user == null ? null : user.getPrivilege();
        Path source = uploadEngineService.resolveVfsFilePath(
                "users/" + userId + "/" + downloadPath);
        uploadEngineService.validateReadPermission(userId, privilege, source);
        Map<String, Object> workspaceFile = workspaceService.writeGeneratedFile(
                workspaceService.workspaceFromContext(), workspacePath, source);
        result.put("workspaceFile", workspaceFile);
        result.put("staged", true);
        result.put("nextAction", "使用 workspaceList、workspaceReadText、workspaceSearch 或 workspaceExec 分析该文件");
        return result;
    }

    private String stringValue(Object value) {
        if (value == null) return null;
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private long numberValue(Object value) {
        if (value instanceof Number number) return number.longValue();
        if (value == null) return 0L;
        try { return Long.parseLong(String.valueOf(value)); }
        catch (NumberFormatException ignored) { return 0L; }
    }

    /** 构造跨平台搜索命令。 */
    private String buildSearchCommand(String directory, String pattern, String fileGlob, int limit, boolean isWin) {
        if (isWin) {
            return buildWindowsSearchCmd(directory, pattern, fileGlob);
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

    private String buildWindowsSearchCmd(String directory, String pattern, String fileGlob) {
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
        return "";
    }

    /** 简单的 Windows agent 检测（首次探测后缓存）。 */
    private boolean isWindowsHost(String sessionId, CommandCapable commandNode) {
        String cacheKey = "os-platform";
        Object cached = AgentNodeSessionUtils.getAiContextValue(sessionId, cacheKey);
        if (cached instanceof String platform) {
            return "windows".equals(platform);
        }
        // 惰性探测一次
        try {
            Map<String, Object> probe = commandNode.execSimpleCommand("uname -s 2>/dev/null || echo Windows");
            String out = extractCommandOutput(probe).trim().toLowerCase();
            boolean isWin = out.isEmpty() || out.contains("windows");
            AgentNodeSessionUtils.putAiContextValue(sessionId, cacheKey, isWin ? "windows" : "unix");
            return isWin;
        } catch (Exception e) {
            return false;
        }
    }

    private AiToolException missingSessionUser(String action) {
        return AiToolException.userActionRequired(
                "SESSION_USER_MISSING",
                "当前会话缺少用户信息，无法启动文件" + action + "任务。",
                "不要重复调用；请向用户说明需要重新建立带有效用户身份的会话。");
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
