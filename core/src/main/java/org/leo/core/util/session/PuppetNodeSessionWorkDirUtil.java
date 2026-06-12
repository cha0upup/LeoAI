package org.leo.core.util.session;

import org.leo.core.config.LeoConfig;
import org.leo.core.entity.Puppet;
import org.leo.core.session.PuppetNodeSession;
import org.leo.core.session.PuppetNodeSessionContainer;
import org.leo.core.util.json.JsonUtil;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 会话 / Puppet 工作目录工具类。
 *
 * <p>目录结构：
 * <pre>
 * root/users/{userId}/
 * ├── puppets/{puppetId}/          ← puppet 级，跨 session 共享
 * │   ├── basic-info.json          ← OS/硬件/中间件快照（last-write-wins）
 * │   ├── catalina-info.json       ← Tomcat 容器信息快照（last-write-wins）
 * │   ├── recon-summary.md         ← 侦察摘要（覆盖/追加写，跨 session 共享）
 * │   ├── recon-summary.json       ← 结构化侦察摘要（last-write-wins，跨 session 共享）
 * │   ├── ai-threads/              ← Spring AI Graph checkpoint
 * │
 * └── sessions/{sessionId}/        ← session 级，本次操作专属
 *     └── file/{server-path}/
 *         └── fileinfo.json        ← 文件列表缓存
 * </pre>
 */
public final class PuppetNodeSessionWorkDirUtil {

    private static final String SESSIONS_SUBDIR    = "sessions";
    private static final String PUPPETS_SUBDIR     = "puppets";
    private static final String USERS_SUBDIR       = "users";
    private static final String FILE_SUBDIR        = "file";
    private static final String AI_THREADS_SUBDIR  = "ai-threads";
    private static final String AI_THREAD_CHECKPOINTS_SUBDIR = "checkpoints";
    private static final String FILEINFO_JSON      = "fileinfo.json";
    private static final String BASIC_INFO_JSON    = "basic-info.json";
    private static final String CATALINA_INFO_JSON = "catalina-info.json";
    private static final String SAVE_TIME_KEY      = "saveTime";
    private static final Pattern PATH_SEPARATORS   = Pattern.compile("[\\\\/]+");
    private static final ConcurrentHashMap<String, ReentrantLock> AI_LOCKS = new ConcurrentHashMap<>();

    private PuppetNodeSessionWorkDirUtil() {}

    // ── root ─────────────────────────────────────────────────────────────────

    /** 获取 VFS 根目录。 */
    public static File getRootDir() {
        String vfsPath = LeoConfig.getVfsPath();
        if (vfsPath == null || vfsPath.isBlank()) {
            vfsPath = "root";
        }
        return new File(vfsPath);
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
    public static File getSessionWorkDir(String sessionId) {
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

    // ── puppet 级目录 ─────────────────────────────────────────────────────────

    /**
     * 获取 puppet 工作目录：root/users/{userId}/puppets/{puppetId}。
     * 若不存在则创建。
     */
    public static File getPuppetWorkDir(String userId, String puppetId) {
        if (puppetId == null || puppetId.isBlank()) {
            throw new IllegalArgumentException("puppetId 不能为空");
        }
        File root = getRootDir();
        File puppetsRoot;
        if (userId != null && !userId.isBlank()) {
            puppetsRoot = new File(new File(new File(root, USERS_SUBDIR), userId.trim()), PUPPETS_SUBDIR);
        } else {
            puppetsRoot = new File(root, PUPPETS_SUBDIR);
        }
        File puppetDir = new File(puppetsRoot, puppetId.trim());
        if (!puppetDir.exists()) {
            puppetDir.mkdirs();
        }
        return puppetDir;
    }

    // ── 写入方法 ──────────────────────────────────────────────────────────────

    /**
     * 将主机基础信息写入 puppet 工作目录（root/users/{userId}/puppets/{puppetId}/basic-info.json）。
     *
     * <p>采用 last-write-wins 策略直接覆盖，不再 read-merge-write，避免并发写损坏文件。
     * 若无法从 session 解析 puppetId，则降级写入 session 目录（兼容旧行为）。
     *
     * @param sessionId 会话 ID
     * @param hostId    主机 ID（仅用于 JSON 中的 hostId 字段记录，不再作为 key 合并）
     * @param basicInfo 主机基础信息 Map
     * @return 写入的文件，失败时返回 null
     */
    public static File saveBasicInfo(String sessionId, String hostId, Map<String, Object> basicInfo) {
        if (sessionId == null || sessionId.isBlank() || basicInfo == null) {
            return null;
        }
        try {
            File targetDir = resolvePuppetDirFromSession(sessionId);
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("hostId", hostId != null ? hostId : "_default");
            root.put(SAVE_TIME_KEY, Instant.now().toString());
            root.putAll(basicInfo);
            return writeJson(new File(targetDir, BASIC_INFO_JSON), root);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 将容器管理信息写入 puppet 工作目录（root/users/{userId}/puppets/{puppetId}/catalina-info.json）。
     *
     * <p>采用 last-write-wins 策略直接覆盖。
     *
     * @param sessionId 会话 ID
     * @param results   get-all 接口返回的 Map，需包含 code=200
     * @return 写入的文件，失败时返回 null
     */
    public static File saveCatalinaInfo(String sessionId, Map<String, Object> results) {
        if (sessionId == null || sessionId.isBlank() || results == null) {
            return null;
        }
        Object codeObj = results.get("code");
        if (!(codeObj instanceof Number) || ((Number) codeObj).intValue() != 200) {
            return null;
        }
        try {
            File targetDir = resolvePuppetDirFromSession(sessionId);
            Map<String, Object> structured = new LinkedHashMap<>();
            structured.put(SAVE_TIME_KEY, Instant.now().toString());
            if (results.containsKey("catalinaInfo"))  structured.put("catalinaInfo",  results.get("catalinaInfo"));
            if (results.containsKey("frameworkInfo")) structured.put("frameworkInfo", results.get("frameworkInfo"));
            return writeJson(new File(targetDir, CATALINA_INFO_JSON), structured);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 将文件列表结果写入 session 工作目录（session 级，不跨 session 共享）。
     *
     * @param sessionId     会话 ID
     * @param requestedPath 本次列表请求的路径
     * @param results       列表接口返回的 Map
     * @return 写入的 fileinfo.json，失败时返回 null
     */
    public static File saveFileList(String sessionId, String requestedPath, Map<String, Object> results) {
        if (results == null) return null;
        Object codeObj = results.get("code");
        if (!(codeObj instanceof Number) || ((Number) codeObj).intValue() != 200) return null;

        Object pathObj = results.get("absolutePath");
        String serverPath = pathObj != null ? pathObj.toString().trim() : null;
        if (serverPath == null || serverPath.isEmpty()) {
            serverPath = requestedPath != null ? requestedPath : "root";
        }
        try {
            File fileDir = getSessionFileDir(sessionId);
            Path basePath = fileDir.toPath().toAbsolutePath().normalize();
            String relativeDir = "/".equals(serverPath) ? "" : toRelativePathUnderFile(serverPath);
            Path dirPath = relativeDir.isEmpty() ? basePath : basePath.resolve(relativeDir).normalize();
            if (!dirPath.startsWith(basePath)) return null;

            File dir = dirPath.toFile();
            if (!dir.exists()) dir.mkdirs();

            Map<String, Object> structured = new LinkedHashMap<>();
            structured.put("path",         requestedPath != null ? requestedPath : "root");
            structured.put("absolutePath", results.get("absolutePath"));
            structured.put("listTime",     Instant.now().toString());
            structured.put("count",        results.get("count"));
            structured.put("fileList",     results.get("fileList"));
            return writeJson(new File(dir, FILEINFO_JSON), structured);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 将下载数据块追加到 session 工作目录（session 级）。
     *
     * @param sessionId 会话 ID
     * @param filePath  远程文件路径
     * @param offset    当前块偏移（0 表示新建/覆盖）
     * @param data      本块数据
     * @return 写入后的本地文件，失败时返回 null
     */
    public static File appendDownloadChunk(String sessionId, String filePath, long offset, byte[] data) {
        if (data == null || data.length == 0) return null;
        try {
            File fileDir = getSessionFileDir(sessionId);
            Path basePath = fileDir.toPath().toAbsolutePath().normalize();
            String relative = toRelativePathUnderFile(filePath);
            Path localPath = basePath.resolve(relative).normalize();
            if (!localPath.startsWith(basePath)) return null;

            File localFile = localPath.toFile();
            File parent = localFile.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();

            if (offset == 0) {
                Files.write(localPath, data, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            } else {
                Files.write(localPath, data, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
            return localFile;
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * 从 puppet 工作目录读取基础信息（basic-info.json）。
     *
     * @param userId   puppet 所属用户 ID
     * @param puppetId puppet ID
     * @return 基础信息 Map；文件不存在或解析失败时返回 null
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> loadBasicInfo(String userId, String puppetId) {
        if (puppetId == null || puppetId.isBlank()) return null;
        try {
            File puppetDir = getPuppetWorkDir(userId, puppetId);
            File file = new File(puppetDir, BASIC_INFO_JSON);
            if (!file.exists() || file.length() == 0) return null;
            String json = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            return (Map<String, Object>) JsonUtil.fromJsonString(json, LinkedHashMap.class);
        } catch (Exception e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> loadBasicInfo(String sessionId) {
        try {
            File dir = resolvePuppetDirFromSession(sessionId);
            File file = new File(dir, BASIC_INFO_JSON);
            if (!file.exists() || file.length() == 0) return null;
            String json = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            return (Map<String, Object>) JsonUtil.fromJsonString(json, LinkedHashMap.class);
        } catch (Exception e) {
            return null;
        }
    }

    // ── 缓存检查 ──────────────────────────────────────────────────────────────

    /**
     * 检查指定 puppet 是否存在本地缓存（basic-info、catalina-info 或 recon-summary 任一存在即视为有缓存）。
     */
    public static boolean hasPuppetCache(String userId, String puppetId) {
        if (puppetId == null || puppetId.isBlank()) return false;
        try {
            File puppetDir = getPuppetWorkDir(userId, puppetId);
            return new File(puppetDir, BASIC_INFO_JSON).exists()
                    || new File(puppetDir, CATALINA_INFO_JSON).exists()
                    || new File(puppetDir, RECON_SUMMARY_MD).exists()
                    || new File(puppetDir, RECON_SUMMARY_JSON).exists();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 获取 puppet 缓存的最后保存时间（取 basic-info.json 中的 saveTime 字段）。
     * 若无法读取则返回 null。
     */
    @SuppressWarnings("unchecked")
    public static String getPuppetCacheSaveTime(String userId, String puppetId) {
        if (puppetId == null || puppetId.isBlank()) return null;
        try {
            File puppetDir = getPuppetWorkDir(userId, puppetId);
            // 优先读 basic-info，其次 catalina-info
            for (String name : new String[]{BASIC_INFO_JSON, CATALINA_INFO_JSON}) {
                File f = new File(puppetDir, name);
                if (f.exists() && f.length() > 0) {
                    String json = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
                    Map<String, Object> map = (Map<String, Object>) JsonUtil.fromJsonString(json, LinkedHashMap.class);
                    if (map != null && map.containsKey(SAVE_TIME_KEY)) {
                        return String.valueOf(map.get(SAVE_TIME_KEY));
                    }
                }
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    // ── ReconSummary 持久化 ───────────────────────────────────────────────────

    private static final String RECON_SUMMARY_MD = "recon-summary.md";
    private static final String RECON_SUMMARY_JSON = "recon-summary.json";

    /**
     * 将侦察摘要持久化到 puppet 工作目录（recon-summary.md）。
     * content 为 null 或空白时删除文件（对应"清空"操作）。
     *
     * @param sessionId 会话 ID（用于解析 puppetId 和 userId）
     * @param content   摘要内容，null/blank 表示清空
     * @return 写入的文件；清空时返回 null；失败时返回 null
     */
    public static synchronized File saveReconSummary(String sessionId, String content) {
        if (sessionId == null || sessionId.isBlank()) return null;
        try {
            PuppetNodeSession session = PuppetNodeSessionContainer.getSession(sessionId);
            File targetDir = resolvePuppetDirFromSession(sessionId);
            File file = new File(targetDir, RECON_SUMMARY_MD);
            String normalized = normalizeReconSummary(content);
            if (normalized == null) {
                Files.deleteIfExists(file.toPath());
                syncReconSummaryAcrossPuppetSessions(session, null);
                return null;
            }
            Files.write(file.toPath(), normalized.getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            syncReconSummaryAcrossPuppetSessions(session, normalized);
            return file;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 以 puppet 级持久化文件为准追加侦察摘要，并同步同一 puppet 的内存会话。
     *
     * <p>相比先读内存再覆盖写，这里先读取 puppet 级文件，避免同一 puppet 的多个
     * session 并发追加时基于过期内存覆盖彼此的新发现。
     *
     * @param sessionId 会话 ID（用于解析 puppetId 和 userId）
     * @param content   要追加的内容，null/blank 时不写入
     * @return 追加后的完整摘要；失败或内容为空时返回 null
     */
    public static synchronized String appendReconSummary(String sessionId, String content) {
        String normalizedContent = normalizeReconSummary(content);
        if (sessionId == null || sessionId.isBlank() || normalizedContent == null) return null;
        try {
            PuppetNodeSession session = PuppetNodeSessionContainer.getSession(sessionId);
            File targetDir = resolvePuppetDirFromSession(sessionId);
            File file = new File(targetDir, RECON_SUMMARY_MD);
            String current = readReconSummaryFile(file);
            if (current == null && session != null) {
                current = normalizeReconSummary(session.getReconSummary());
            }
            String updated = current == null ? normalizedContent : current + "\n\n" + normalizedContent;
            Files.write(file.toPath(), updated.getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            syncReconSummaryAcrossPuppetSessions(session, updated);
            return updated;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 从当前 session 绑定的 puppet 工作目录读取侦察摘要。
     *
     * @param sessionId 会话 ID
     * @return 摘要内容；文件不存在或为空时返回 null
     */
    public static String loadReconSummary(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return null;
        try {
            File targetDir = resolvePuppetDirFromSession(sessionId);
            return readReconSummaryFile(new File(targetDir, RECON_SUMMARY_MD));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 从 puppet 工作目录读取侦察摘要（recon-summary.md）。
     *
     * @param userId   puppet 所属用户 ID
     * @param puppetId puppet ID
     * @return 摘要内容；文件不存在或为空时返回 null
     */
    public static String loadReconSummary(String userId, String puppetId) {
        if (puppetId == null || puppetId.isBlank()) return null;
        try {
            File puppetDir = getPuppetWorkDir(userId, puppetId);
            File file = new File(puppetDir, RECON_SUMMARY_MD);
            return readReconSummaryFile(file);
        } catch (Exception e) {
            return null;
        }
    }

    // ── AI 线程目录 ───────────────────────────────────────────────────────────

    /**
     * 获取 puppet 的 AI 线程目录：root/users/{userId}/puppets/{puppetId}/ai-threads。
     * 若不存在则创建。
     */
    public static File getAiThreadsDir(String userId, String puppetId) {
        File puppetDir = getPuppetWorkDir(userId, puppetId);
        File dir = new File(puppetDir, AI_THREADS_SUBDIR);
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    // ── AI 线程 Checkpoint ───────────────────────────────────────────────────

    /**
     * 获取单个 AI 线程的 checkpoint 目录。
     */
    public static File getAiThreadCheckpointDir(String userId, String puppetId, String threadId) {
        return aiThreadCheckpointDir(userId, puppetId, threadId, true);
    }

    /**
     * 删除单个线程的 checkpoint 文件。
     */
    public static void deleteAiThreadCheckpoints(String userId, String puppetId, String threadId) {
        if (puppetId == null || threadId == null) return;
        ReentrantLock lock = aiThreadLock(userId, puppetId, threadId);
        lock.lock();
        try {
            deleteDir(aiThreadCheckpointDir(userId, puppetId, threadId, false).toPath());
        } finally {
            lock.unlock();
        }
    }

    /**
     * 判断单个线程是否已有持久化 checkpoint。
     */
    public static boolean hasAiThreadCheckpoint(String userId, String puppetId, String threadId) {
        if (puppetId == null || threadId == null) return false;
        try {
            File dir = aiThreadCheckpointDir(userId, puppetId, threadId, false);
            return hasSaverFile(dir);
        } catch (Exception e) {
            return false;
        }
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
     * 删除 puppet 工作目录 root/users/{userId}/puppets/{puppetId}。
     * 应在删除 puppet 记录时调用。
     *
     * @param userId   puppet 所属用户 ID
     * @param puppetId puppet ID
     * @return 删除成功（或目录不存在）返回 true；失败返回 false
     */
    public static boolean deletePuppetWorkDir(String userId, String puppetId) {
        if (puppetId == null || puppetId.isBlank()) {
            throw new IllegalArgumentException("puppetId 不能为空");
        }
        try {
            File root = getRootDir();
            Path puppetsRoot;
            if (userId != null && !userId.isBlank()) {
                puppetsRoot = new File(new File(new File(root, USERS_SUBDIR), userId.trim()), PUPPETS_SUBDIR).toPath();
            } else {
                puppetsRoot = new File(root, PUPPETS_SUBDIR).toPath();
            }
            return deleteDir(puppetsRoot.resolve(puppetId.trim()));
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
        PuppetNodeSession session = PuppetNodeSessionContainer.getSession(sessionId);
        return session != null ? session.getCreateByUser() : null;
    }

    private static ReentrantLock aiThreadLock(String userId, String puppetId, String threadId) {
        return AI_LOCKS.computeIfAbsent("thread:" + safeLockPart(userId) + ":" + safeLockPart(puppetId)
                        + ":" + safeLockPart(threadId),
                k -> new ReentrantLock());
    }

    private static File aiThreadCheckpointDir(String userId, String puppetId, String threadId, boolean create) {
        File dir = new File(new File(getAiThreadsDir(userId, puppetId), AI_THREAD_CHECKPOINTS_SUBDIR),
                safeThreadBaseName(threadId));
        if (create && !dir.exists()) dir.mkdirs();
        return dir;
    }

    private static String safeLockPart(String value) {
        return value == null ? "" : value.trim();
    }

    /**
     * 从 sessionId 解析 puppet 工作目录。
     * 优先从 javaPuppetNode 取 puppetId；缓存模式下从 session.getPuppetId() 取；
     * 均无法解析时降级返回 session 级目录（兼容）。
     */
    private static File resolvePuppetDirFromSession(String sessionId) {
        PuppetNodeSession session = PuppetNodeSessionContainer.getSession(sessionId);
        if (session != null) {
            // 正常模式：从 javaPuppetNode 取 puppetId
            if (session.getJavaPuppetNode() != null) {
                Puppet puppet = session.getJavaPuppetNode().getPuppet();
                if (puppet != null && puppet.getPuppetId() != null && !puppet.getPuppetId().isBlank()) {
                    return getPuppetWorkDir(session.getCreateByUser(), puppet.getPuppetId());
                }
            }
            // 缓存模式：从 session.puppetId 取
            String pId = session.getPuppetId();
            if (pId != null && !pId.isBlank()) {
                return getPuppetWorkDir(session.getCreateByUser(), pId);
            }
        }
        // 降级：写到 session 目录（不应发生，仅作保底）
        return getSessionWorkDir(sessionId);
    }

    /**
     * 从 session 解析 puppetId。缓存模式和正常连接模式都支持。
     */
    public static String resolvePuppetId(PuppetNodeSession session) {
        if (session == null) return null;
        if (session.getJavaPuppetNode() != null) {
            Puppet puppet = session.getJavaPuppetNode().getPuppet();
            if (puppet != null && puppet.getPuppetId() != null && !puppet.getPuppetId().isBlank()) {
                return puppet.getPuppetId();
            }
        }
        String pId = session.getPuppetId();
        return pId != null && !pId.isBlank() ? pId : null;
    }

    private static void syncReconSummaryAcrossPuppetSessions(PuppetNodeSession source, String content) {
        if (source == null) return;
        String puppetId = resolvePuppetId(source);
        if (puppetId == null) return;
        String userId = source.getCreateByUser();
        for (PuppetNodeSession session : PuppetNodeSessionContainer.getAllSession().values()) {
            if (session == null) continue;
            if (!Objects.equals(userId, session.getCreateByUser())) continue;
            if (!puppetId.equals(resolvePuppetId(session))) continue;
            session.setReconSummary(content);
        }
    }

    private static String readReconSummaryFile(File file) throws IOException {
        if (file == null || !file.exists() || file.length() == 0) return null;
        String content = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        return normalizeReconSummary(content);
    }

    private static String normalizeReconSummary(String content) {
        if (content == null || content.isBlank()) return null;
        return content.strip();
    }

    private static boolean hasSaverFile(File dir) {
        if (dir == null || !dir.exists() || !dir.isDirectory()) return false;
        File[] files = dir.listFiles();
        return files != null && files.length > 0;
    }

    private static File writeJson(File file, Map<String, Object> data) throws Exception {
        String json = JsonUtil.toJsonString(data);
        Files.write(file.toPath(), json.getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        return file;
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

    private static String safeThreadBaseName(String threadId) {
        // 只保留字母、数字、连字符、下划线，最长 64 字符
        String safe = threadId.replaceAll("[^a-zA-Z0-9\\-_]", "_");
        if (safe.length() > 64) safe = safe.substring(0, 64);
        return safe;
    }
}
