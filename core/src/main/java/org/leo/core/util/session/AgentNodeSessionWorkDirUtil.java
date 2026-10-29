package org.leo.core.util.session;

import org.leo.core.config.LeoConfig;
import org.leo.core.session.AgentNodeSession;
import org.leo.core.session.AgentNodeSessionContainer;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.regex.Pattern;
import java.util.stream.Stream;

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
        File userDir = new File(new File(getRootDir(), USERS_SUBDIR), userId.trim());
        File workspaceDir = new File(userDir, WORKSPACE_SUBDIR);
        if (!workspaceDir.exists()) {
            workspaceDir.mkdirs();
        }
        return workspaceDir;
    }

    // ── session 级目录 ────────────────────────────────────────────────────────

    private static File getSessionRootDirByUser(String userId) {
        File root = getRootDir();
        if (userId == null || userId.isBlank()) {
            return new File(root, SESSIONS_SUBDIR);
        }
        File userDir = new File(new File(root, USERS_SUBDIR), userId.trim());
        return new File(userDir, SESSIONS_SUBDIR);
    }

    /**
     * 获取会话工作目录：root/users/{userId}/sessions/{sessionId}。
     * 若不存在则创建。
     */
    private static File getSessionWorkDir(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId 不能为空");
        }
        String userId = resolveSessionOwnerUserId(sessionId);
        File sessionsRoot = getSessionRootDirByUser(userId);
        File sessionDir = new File(sessionsRoot, sessionId.trim());
        if (!sessionDir.exists()) {
            sessionDir.mkdirs();
        }
        return sessionDir;
    }

    /**
     * 获取会话文件存储目录：root/users/{userId}/sessions/{sessionId}/file。
     * 若不存在则创建。
     */
    public static File getSessionFileDir(String sessionId) {
        File sessionDir = getSessionWorkDir(sessionId);
        File fileDir = new File(sessionDir, FILE_SUBDIR);
        if (!fileDir.exists()) {
            fileDir.mkdirs();
        }
        return fileDir;
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
        File root = getRootDir();
        File agentsRoot;
        if (userId != null && !userId.isBlank()) {
            agentsRoot = new File(new File(new File(root, USERS_SUBDIR), userId.trim()), AGENTS_SUBDIR);
        } else {
            agentsRoot = new File(root, AGENTS_SUBDIR);
        }
        File agentDir = new File(agentsRoot, agentId.trim());
        if (!agentDir.exists()) {
            agentDir.mkdirs();
        }
        return agentDir;
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
            File root = getRootDir();
            Path agentsRoot;
            if (userId != null && !userId.isBlank()) {
                agentsRoot = new File(new File(new File(root, USERS_SUBDIR), userId.trim()), AGENTS_SUBDIR).toPath();
            } else {
                agentsRoot = new File(root, AGENTS_SUBDIR).toPath();
            }
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

    private static String resolveSessionOwnerUserId(String sessionId) {
        AgentNodeSession session = AgentNodeSessionContainer.getSession(sessionId);
        return session != null ? session.getCreateByUser() : null;
    }

    /**
     * 从 session 解析 agentId。缓存模式和正常连接模式都支持。
     */
    public static String resolveAgentId(AgentNodeSession session) {
        if (session == null) return null;
        return session.resolveAgentId();
    }

    private static boolean deleteDir(Path path) {
        try {
            Path normalized = path.toAbsolutePath().normalize();
            if (!Files.exists(normalized)) return true;
            try (Stream<Path> walk = Files.walk(normalized)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (IOException e) { throw new RuntimeException(e); }
                });
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

}
