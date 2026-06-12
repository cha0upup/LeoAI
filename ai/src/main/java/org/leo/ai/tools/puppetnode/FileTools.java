package org.leo.ai.tools.puppetnode;

import org.leo.ai.agent.AiToolContext;
import org.leo.ai.util.PuppetNodeSessionUtils;
import org.leo.core.entity.User;
import org.leo.core.puppet.impl.JavaPuppetNode;
import org.leo.core.session.PuppetNodeSession;
import org.leo.service.DownloadEngineService;
import org.leo.service.UploadEngineService;
import org.leo.service.user.UserService;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

import org.leo.ai.util.ToolResultUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

    /**
     * 从 puppet 侧目标服务器下载文件分块数据（内部实现，不作为 AI 工具暴露）。
     * 上层工具 readTextFile 已封装此方法，Agent 应优先使用 readTextFile。
     */
    Map<String, Object> fileDownloadChunk(String sessionId, String path, long size, long offset) throws Exception {
        JavaPuppetNode node = PuppetNodeSessionUtils.getJavaPuppetNode(sessionId);
        return node.fileDownloadChunk(path, size, offset);
    }

    @Tool("在 puppet 侧创建文件并写入内容。")
    public Map<String, Object> createFile(String path, String content) throws Exception {
        String sessionId = AiToolContext.requireSessionId();
        JavaPuppetNode node = PuppetNodeSessionUtils.getJavaPuppetNode(sessionId);
        Map<String, Object> result = node.createFile(path, content);
        invalidateParentCache(sessionId, path);
        return result;
    }

    /**
     * 向 puppet 侧目标服务器按分块写入文件数据（内部实现，不作为 AI 工具暴露）。
     * 上层工具 startUploadTask 已封装完整的上传流程，Agent 应优先使用 startUploadTask。
     */
    Map<String, Object> fileUploadChunk(String sessionId, String path, long offset, byte[] data) throws Exception {
        JavaPuppetNode node = PuppetNodeSessionUtils.getJavaPuppetNode(sessionId);
        return node.fileUploadChunk(path, offset, data);
    }

    @Tool("获取 puppet 侧指定路径下的文件详细信息。不能用于查看平台侧 VFS。maxEntries 限制返回条目数（0=不限制；大目录建议 200 避免上下文过长）。")
    @SuppressWarnings("unchecked")
    public Map<String, Object> getFileList(String path, int maxEntries) throws Exception {
        String sessionId = AiToolContext.requireSessionId();
        String cacheKey = "file-list:" + path;
        Object cached = PuppetNodeSessionUtils.getAiContextValue(sessionId, cacheKey);
        Map<String, Object> results;
        if (cached instanceof Map<?, ?> cachedMap) {
            results = (Map<String, Object>) cachedMap;
        } else {
            JavaPuppetNode node = PuppetNodeSessionUtils.getJavaPuppetNode(sessionId);
            results = node.getFileList(path);
            if (results != null) {
                PuppetNodeSessionUtils.putAiContextValue(sessionId, cacheKey, results);
            }
        }
        return truncateFileList(results, maxEntries);
    }

    /**
     * 如果 maxEntries > 0 且 fileList 超出限制，返回截断后的副本并附加 truncated/totalCount 标记。
     * maxEntries <= 0 时直接返回原始结果，不做任何拷贝。
     */
    private Map<String, Object> truncateFileList(Map<String, Object> results, int maxEntries) {
        if (results == null || maxEntries <= 0) return results;
        Object fileListObj = results.get("fileList");
        if (!(fileListObj instanceof List<?> fileList) || fileList.size() <= maxEntries) return results;
        HashMap<String, Object> truncated = new HashMap<>(results);
        truncated.put("fileList", fileList.subList(0, maxEntries));
        truncated.put("truncated", true);
        truncated.put("totalCount", fileList.size());
        truncated.put("returnedCount", maxEntries);
        return truncated;
    }

    @Tool("压缩文件或文件夹为 zip，excludePattern 是不打包进去的文件名称正则。")
    public Map<String, Object> compressFile(String src, String des, String excludePattern) throws Exception {
        String sessionId = AiToolContext.requireSessionId();
        JavaPuppetNode node = PuppetNodeSessionUtils.getJavaPuppetNode(sessionId);
        return node.compressFile(src, des, excludePattern);
    }

    @Tool("解压 zip 文件。")
    public Map<String, Object> decompressFile(String src, String des) throws Exception {
        String sessionId = AiToolContext.requireSessionId();
        JavaPuppetNode node = PuppetNodeSessionUtils.getJavaPuppetNode(sessionId);
        return node.decompressFile(src, des);
    }

    @Tool("获取指定文件的 md5 值。")
    public Map<String, Object> getFileMd5(String path) throws Exception {
        String sessionId = AiToolContext.requireSessionId();
        JavaPuppetNode node = PuppetNodeSessionUtils.getJavaPuppetNode(sessionId);
        return node.getFileMD5(path);
    }

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

    @Tool("读取 puppet 侧小体积文本文件并返回 UTF-8 内容。适用于配置文件（yml、properties、xml、env）。不能用于查看平台侧 VFS。相同路径按会话缓存。")
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

    @Tool("获取 puppet 侧根目录或根驱动器列表。适用于 Windows 多盘符（C:\\、D:\\ 等）或 Linux 根路径探测。结果按会话缓存。")
    @SuppressWarnings("unchecked")
    public Map<String, Object> getRootList() throws Exception {
        String sessionId = AiToolContext.requireSessionId();
        String cacheKey = "root-list";
        Object cached = PuppetNodeSessionUtils.getAiContextValue(sessionId, cacheKey);
        if (cached instanceof Map<?, ?> cachedMap) {
            return (Map<String, Object>) cachedMap;
        }
        JavaPuppetNode javaPuppetNode = PuppetNodeSessionUtils.getJavaPuppetNode(sessionId);
        Map<String, Object> results = javaPuppetNode.getRootList();
        if (results != null) {
            PuppetNodeSessionUtils.putAiContextValue(sessionId, cacheKey, results);
        }
        return results;
    }

    @Tool("编辑已有文件内容（全量覆盖）。⚠️ 操作不可逆。建议先用 readTextFile 确认原始内容并用 copyFile 备份。不能创建新文件（用 createFile）。")
    public Map<String, Object> editFile(String path, String content) throws Exception {
        String sessionId = AiToolContext.requireSessionId();
        JavaPuppetNode javaPuppetNode = PuppetNodeSessionUtils.getJavaPuppetNode(sessionId);
        Map<String, Object> result = javaPuppetNode.editFile(path, content);
        invalidateFileCache(sessionId, path);
        return result;
    }

    @Tool("在 puppet 侧创建目录。若目录已存在通常不报错。")
    public Map<String, Object> createDir(String dirName) throws Exception {
        String sessionId = AiToolContext.requireSessionId();
        JavaPuppetNode javaPuppetNode = PuppetNodeSessionUtils.getJavaPuppetNode(sessionId);
        Map<String, Object> result = javaPuppetNode.createDir(dirName);
        invalidateParentCache(sessionId, dirName);
        return result;
    }

    @Tool("在 puppet 侧复制文件或目录。srcPath 源路径，destPath 目标路径。")
    public Map<String, Object> copyFile(String srcPath, String destPath) throws Exception {
        String sessionId = AiToolContext.requireSessionId();
        JavaPuppetNode javaPuppetNode = PuppetNodeSessionUtils.getJavaPuppetNode(sessionId);
        Map<String, Object> result = javaPuppetNode.copyFile(srcPath, destPath);
        invalidateParentCache(sessionId, destPath);
        return result;
    }

    @Tool("在 puppet 侧移动或重命名文件。srcPath 原路径，newPath 新路径。操作会改变文件位置，调用前请确认。")
    public Map<String, Object> moveFile(String srcPath, String newPath) throws Exception {
        String sessionId = AiToolContext.requireSessionId();
        JavaPuppetNode javaPuppetNode = PuppetNodeSessionUtils.getJavaPuppetNode(sessionId);
        Map<String, Object> result = javaPuppetNode.moveFile(srcPath, newPath);
        invalidateFileCache(sessionId, srcPath);
        invalidateParentCache(sessionId, newPath);
        return result;
    }

    @Tool("扫描 puppet 侧指定目录下常见的配置文件（application.yml、bootstrap.yml、context.xml、server.xml、.env 等）。depth 控制递归深度（默认 1，最大 3）。maxDirs 限制最多扫描的子目录数（默认 20，最大 50）。不能用于查找平台侧 skills/uploads。")
    @SuppressWarnings("unchecked")
    public Map<String, Object> findConfigCandidates(String directoryPath, int depth, int maxDirs) throws Exception {
        String sessionId = AiToolContext.requireSessionId();
        int resolvedDepth = depth <= 0 ? 1 : Math.min(depth, 3);
        int resolvedMaxDirs = maxDirs <= 0 ? 20 : Math.min(maxDirs, 50);
        String cacheKey = "config-candidates:" + directoryPath + ":" + resolvedDepth + ":" + resolvedMaxDirs;
        Object cached = PuppetNodeSessionUtils.getAiContextValue(sessionId, cacheKey);
        if (cached instanceof Map<?, ?> cachedMap) {
            return (Map<String, Object>) cachedMap;
        }

        List<Map<String, Object>> matches = new ArrayList<>();
        int[] dirCount = {0};
        collectConfigCandidates(sessionId, directoryPath, resolvedDepth, resolvedMaxDirs, dirCount, matches);

        HashMap<String, Object> result = new HashMap<>();
        result.put("directoryPath", directoryPath);
        result.put("depth", resolvedDepth);
        result.put("maxDirs", resolvedMaxDirs);
        result.put("dirsScanned", dirCount[0]);
        result.put("matches", matches);
        result.put("count", matches.size());
        if (dirCount[0] >= resolvedMaxDirs) {
            result.put("truncated", true);
            result.put("truncatedReason", "已达到最大子目录扫描数量 " + resolvedMaxDirs + "，部分子目录未扫描；可增大 maxDirs 或缩小 directoryPath 范围");
        }
        PuppetNodeSessionUtils.putAiContextValue(sessionId, cacheKey, result);
        return result;
    }

    /**
     * 递归收集配置文件候选。
     *
     * @param remainingDepth 剩余递归层数
     * @param maxDirs        最多扫描的目录总数（含根目录）
     * @param dirCount       已扫描目录计数器（int[1] 用于跨递归层共享可变状态）
     */
    private void collectConfigCandidates(String sessionId, String directoryPath,
                                          int remainingDepth, int maxDirs, int[] dirCount,
                                          List<Map<String, Object>> matches) throws Exception {
        if (dirCount[0] >= maxDirs) return;
        dirCount[0]++;

        Map<String, Object> listResult = getFileList(directoryPath, 0);
        Object fileListObj = listResult == null ? null : listResult.get("fileList");
        if (!(fileListObj instanceof List<?> fileList)) return;

        for (Object item : fileList) {
            if (!(item instanceof Map<?, ?> rawItem)) continue;
            String fileName = toStringValue(rawItem.get("name"));
            boolean isDirectory = Boolean.TRUE.equals(rawItem.get("isDirectory"));

            if (!isDirectory && isInterestingConfigName(fileName)) {
                HashMap<String, Object> matched = new HashMap<>();
                matched.put("name", fileName);
                matched.put("absolutePath", rawItem.get("absolutePath"));
                matched.put("size", rawItem.get("size"));
                matched.put("lastModified", rawItem.get("lastModified"));
                matches.add(matched);
            } else if (isDirectory && remainingDepth > 1 && dirCount[0] < maxDirs) {
                String subPath = toStringValue(rawItem.get("absolutePath"));
                if (subPath != null) {
                    collectConfigCandidates(sessionId, subPath, remainingDepth - 1, maxDirs, dirCount, matches);
                }
            }
        }
    }

    private boolean isInterestingConfigName(String fileName) {
        if (fileName == null || fileName.isEmpty()) {
            return false;
        }
        String normalized = fileName.toLowerCase();
        return normalized.equals("application.yml")
                || normalized.equals("application.yaml")
                || normalized.equals("application.properties")
                || normalized.equals("bootstrap.yml")
                || normalized.equals("bootstrap.yaml")
                || normalized.equals("bootstrap.properties")
                || normalized.equals("context.xml")
                || normalized.equals("server.xml")
                || normalized.equals(".env")
                || normalized.endsWith(".properties")
                || normalized.endsWith(".yml")
                || normalized.endsWith(".yaml")
                || normalized.endsWith(".xml")
                || normalized.contains("datasource")
                || normalized.contains("jdbc");
    }

    private String toStringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    @Tool("在 puppet 侧删除文件或目录。⚠️ 操作不可逆，删除后无法恢复，请确认路径。")
    public Map<String, Object> deleteFile(String path) throws Exception {
        String sessionId = AiToolContext.requireSessionId();
        JavaPuppetNode javaPuppetNode = PuppetNodeSessionUtils.getJavaPuppetNode(sessionId);
        Map<String, Object> result = javaPuppetNode.deleteFile(path);
        invalidateFileCache(sessionId, path);
        return result;
    }

    // ── 缓存失效辅助方法 ──────────────────────────────────────────────────────

    /**
     * 文件内容变更后失效：清除该路径的 readTextFile 缓存（所有 maxBytes 变体）和父目录的列表缓存。
     * 适用于 editFile、deleteFile、moveFile（源路径）。
     */
    private void invalidateFileCache(String sessionId, String path) {
        if (path == null) return;
        // readTextFile 缓存 key 格式：text-file:{path}:{readSize}，前缀匹配清除所有 readSize 变体
        PuppetNodeSessionUtils.removeAiContextByPrefix(sessionId, "text-file:" + path + ":");
        invalidateParentCache(sessionId, path);
    }

    /**
     * 目录内容变更后失效：清除父目录的 getFileList 和 findConfigCandidates 缓存。
     * 适用于 createFile、createDir、copyFile（目标路径）、moveFile（目标路径）。
     */
    private void invalidateParentCache(String sessionId, String path) {
        if (path == null) return;
        String parent = getParentPath(path);
        if (parent != null) {
            PuppetNodeSessionUtils.removeAiContextValue(sessionId, "file-list:" + parent);
            // config-candidates 缓存 key 格式：config-candidates:{path}:{depth}:{maxDirs}，前缀匹配清除所有变体
            PuppetNodeSessionUtils.removeAiContextByPrefix(sessionId, "config-candidates:" + parent + ":");
        }
    }

    /**
     * 提取路径的父目录，兼容 Linux（/）和 Windows（\）分隔符。
     */
    private String getParentPath(String path) {
        if (path == null || path.isEmpty()) return null;
        // 统一处理正反斜杠，去掉末尾分隔符后取最后一个分隔符前的部分
        String normalized = path.replace('\\', '/').replaceAll("/+$", "");
        int idx = normalized.lastIndexOf('/');
        if (idx <= 0) return null;
        return path.substring(0, idx);
    }
}
