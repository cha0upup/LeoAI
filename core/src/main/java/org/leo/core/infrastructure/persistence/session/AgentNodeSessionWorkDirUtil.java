package org.leo.core.infrastructure.persistence.session;

import org.leo.core.config.LeoConfig;
import org.leo.core.session.AgentNodeSession;
import org.leo.core.session.AgentNodeSessionContainer;
import org.springframework.util.FileSystemUtils;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Pattern;

/**
 * 会话 / Agent 工作目录工具类。
 *
 * <p>目录结构：
 * <pre>
 * root/users/{userId}/
 * ├── workspace/                   ← 用户个人工作区，可由文件模块读写
 * ├── agents/{agentId}/          ← agent 级，跨 session 共享
 * │   ├── basic-info/{hostId}.json ← 按 HostId 隔离的 OS/硬件/中间件快照
 * │   ├── web-runtime/{hostId}.snapshot.json ← 按 HostId 隔离的 Web Runtime 快照
 * │   └── recon-summary/{hostId}.md ← 按 HostId 隔离的侦察摘要（文件名采用 URL-safe Base64）
 * │
 * └── sessions/{sessionId}/        ← session 级，本次操作专属
 *     └── file/{server-path}/
 *         └── fileinfo.json        ← 文件列表缓存
 * </pre>
 */
public final class AgentNodeSessionWorkDirUtil {

    private static final String SESSIONS_SUBDIR    = "sessions";
    private static final String AGENTS_SUBDIR     = "agents";
    private static final String USERS_SUBDIR       = "users";
    private static final String WORKSPACE_SUBDIR   = "workspace";
    private static final String FILE_SUBDIR        = "file";
    private static final Pattern PATH_SEPARATORS   = Pattern.compile("[\\\\/]+");

    private AgentNodeSessionWorkDirUtil() {}

    // ── root ─────────────────────────────────────────────────────────────────

    /** 获取 VFS 根目录。 */
    public static File getRootDir() {
        String vfsPath = LeoConfig.getVfsPath();
        if (vfsPath == null || vfsPath.isBlank()) {
            vfsPath = "root";
        }
        return new File(vfsPath);
    }

    /**
     * 获取用户个人工作区：root/users/{userId}/workspace。
     *
     * <p>该目录与 sessions、agents 等系统运行目录隔离，供用户文件模块读写。
     * 若不存在则创建。
     */
    public static File getUserWorkspaceDir(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId 不能为空");
        }
        return ensureDirectory(new File(getUserRootDir(userId), WORKSPACE_SUBDIR));
    }

    // ── session 级目录 ────────────────────────────────────────────────────────

    private static File getSessionRootDirByUser(String userId) {
        return new File(getUserRootDir(userId), SESSIONS_SUBDIR);
    }

    /**
     * 获取会话文件存储目录：root/users/{userId}/sessions/{sessionId}/file。
     * 若不存在则创建。
     */
    public static File getSessionFileDir(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId 不能为空");
        }
        String userId = resolveSessionOwnerUserId(sessionId);
        File sessionsRoot = getSessionRootDirByUser(userId);
        File sessionDir = new File(sessionsRoot, sessionId.trim());
        return ensureDirectory(new File(sessionDir, FILE_SUBDIR));
    }

    // ── agent 级目录 ─────────────────────────────────────────────────────────

    /**
     * 获取 agent 工作目录：root/users/{userId}/agents/{agentId}。
     * 若不存在则创建。
     */
    public static File getAgentWorkDir(String userId, String agentId) {
        if (agentId == null || agentId.isBlank()) {
            throw new IllegalArgumentException("agentId 不能为空");
        }
        File agentsRoot = new File(getUserRootDir(userId), AGENTS_SUBDIR);
        return ensureDirectory(new File(agentsRoot, agentId.trim()));
    }

    // ── 删除方法 ──────────────────────────────────────────────────────────────

    /**
     * 删除会话工作目录 root/users/{userId}/sessions/{sessionId}。
     *
     * @return 删除成功（或目录不存在）返回 true；失败返回 false
     */
    public static boolean deleteSessionWorkDir(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId 不能为空");
        }
        try {
            String userId = resolveSessionOwnerUserId(sessionId);
            return deleteDir(getSessionRootDirByUser(userId).toPath().resolve(sessionId.trim()));
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 删除 agent 工作目录 root/users/{userId}/agents/{agentId}。
     * 应在删除 agent 记录时调用。
     *
     * @param userId   agent 所属用户 ID
     * @param agentId agent ID
     * @return 删除成功（或目录不存在）返回 true；失败返回 false
     */
    public static boolean deleteAgentWorkDir(String userId, String agentId) {
        if (agentId == null || agentId.isBlank()) {
            throw new IllegalArgumentException("agentId 不能为空");
        }
        try {
            Path agentsRoot = new File(getUserRootDir(userId), AGENTS_SUBDIR).toPath();
            return deleteDir(agentsRoot.resolve(agentId.trim()));
        } catch (Exception e) {
            return false;
        }
    }

    // ── 路径工具 ──────────────────────────────────────────────────────────────

    /**
     * 将远程文件路径转换为相对于 file 目录的相对路径，防止路径穿越。
     */
    public static String toRelativePathUnderFile(String filePath) {
        if (filePath == null || filePath.isBlank() || "root".equalsIgnoreCase(filePath.trim())) {
            return "root";
        }
        String normalized = PATH_SEPARATORS.matcher(filePath.trim()).replaceAll("/");
        if (normalized.startsWith("/")) normalized = normalized.substring(1);
        if (normalized.isEmpty()) return "root";
        try {
            Path p = Paths.get(normalized).normalize();
            String rel = p.toString().replace(File.separatorChar, '/');
            if (rel.contains("..") || rel.startsWith("/")) return "root";
            return rel;
        } catch (Exception e) {
            return "root";
        }
    }

    // ── 私有辅助 ──────────────────────────────────────────────────────────────

    private static File ensureDirectory(File directory) {
        if (!directory.exists()) directory.mkdirs();
        return directory;
    }

    private static File getUserRootDir(String userId) {
        File root = getRootDir();
        return userId == null || userId.isBlank()
                ? root : new File(new File(root, USERS_SUBDIR), userId.trim());
    }

    private static String resolveSessionOwnerUserId(String sessionId) {
        AgentNodeSession session = AgentNodeSessionContainer.getSession(sessionId.trim());
        return session != null ? session.getCreateByUser() : null;
    }

    private static boolean deleteDir(Path path) {
        try {
            Path normalized = path.toAbsolutePath().normalize();
            // 根路径可能是悬空链接；目录内的链接由遍历器按文件删除，不跟随目标。
            if (Files.isSymbolicLink(normalized)) Files.deleteIfExists(normalized);
            else FileSystemUtils.deleteRecursively(normalized);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

}
