package org.leo.web.controller.platform.skill;

import org.leo.ai.service.LeoSkillsProvider;
import org.leo.ai.service.SkillExportService;
import org.leo.ai.service.SkillExportService.ConflictPolicy;
import org.leo.ai.service.SkillExportService.ImportResult;
import org.leo.ai.service.SkillExportService.NamedSkill;
import org.leo.ai.service.SkillExportService.SkillImportException;
import org.leo.ai.service.SkillFileService;
import org.leo.ai.service.SkillFileService.SkillFileException;
import org.leo.ai.service.SkillMeta;
import org.leo.ai.service.SkillRegistryService;
import org.leo.core.util.ApiResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;

/**
 * Skill 管理接口。
 *
 * <p>Skills 存储于 VFS/skills/{scope}/{name}/SKILL.md，通过本接口进行 CRUD。
 * 所有写操作完成后调用 {@link SkillRegistryService#invalidate()} 使缓存失效，
 * AI agent 在下次对话时自动感知变更，无需重启。
 */
@RestController
@RequestMapping("/platform/skill")
public class SkillController {

    private static final String PARAM_SCOPE   = "scope";
    private static final String PARAM_NAME    = "name";
    private static final String PARAM_CONTENT = "content";

    private static final String SKILL_FILE = "SKILL.md";

    private final SkillRegistryService skillRegistry;
    private final LeoSkillsProvider leoSkillsProvider;
    private final SkillFileService skillFileService;
    private final SkillExportService skillExportService;

    /**
     * 每个 (scope, name) 一把锁，串行化 save / delete / toggle 的 read-modify-write，
     * 防止并发改写 frontmatter 互相覆盖。锁实例懒建即可，键空间天然受 isSafeName 限制，
     * 实际容量等于现存 skill 数量，不会无限膨胀。
     */
    private final ConcurrentHashMap<String, ReentrantLock> skillLocks = new ConcurrentHashMap<>();

    public SkillController(SkillRegistryService skillRegistry,
                           LeoSkillsProvider leoSkillsProvider,
                           SkillFileService skillFileService,
                           SkillExportService skillExportService) {
        this.skillRegistry      = skillRegistry;
        this.leoSkillsProvider   = leoSkillsProvider;
        this.skillFileService    = skillFileService;
        this.skillExportService  = skillExportService;
    }

    private ReentrantLock lockFor(String scope, String name) {
        return skillLocks.computeIfAbsent(scope + "/" + name, k -> new ReentrantLock());
    }

    // ── 列表 ──────────────────────────────────────────────────────────────────

    /**
     * 列出指定 scope 下所有 skill 的元数据（name + description）。
     *
     * @param scope puppet-node 或 platform
     */
    @RequestMapping(value = "/list", method = RequestMethod.GET)
    public HashMap<String, Object> list(@RequestParam(PARAM_SCOPE) String scope) {
        if (scope == null || scope.isBlank()) {
            return ApiResponse.badRequest("scope 不能为空");
        }
        // UI 需要展示全部（含禁用），listAllSkills 不过滤 enabled 字段
        List<SkillMeta> skills = skillRegistry.listAllSkills(scope);
        return ApiResponse.success(skills);
    }

    // ── 内容 ──────────────────────────────────────────────────────────────────

    /**
     * 读取指定 skill 的完整 SKILL.md 内容。
     *
     * @param scope puppet-node 或 platform
     * @param name  skill 目录名，如 recon-basic-info
     */
    @RequestMapping(value = "/content", method = RequestMethod.GET)
    public HashMap<String, Object> content(
            @RequestParam(PARAM_SCOPE) String scope,
            @RequestParam(PARAM_NAME) String name) {

        if (scope == null || scope.isBlank()) return ApiResponse.badRequest("scope 不能为空");
        if (name  == null || name.isBlank())  return ApiResponse.badRequest("name 不能为空");
        if (!isSafeName(name)) return ApiResponse.badRequest("name 包含非法字符");

        String text = skillRegistry.getSkillContent(scope.trim(), name.trim());
        if (text == null) return ApiResponse.notFound("skill 不存在：" + scope + "/" + name);

        HashMap<String, Object> data = new HashMap<>();
        data.put(PARAM_CONTENT, text);
        return ApiResponse.success(data);
    }

    // ── 保存（新建 / 更新）───────────────────────────────────────────────────

    /**
     * 保存 skill。若目录不存在则新建；若已存在则覆盖 SKILL.md。
     *
     * <p>请求体：{scope, name, content}
     */
    @RequestMapping(value = "/save", method = RequestMethod.POST)
    public HashMap<String, Object> save(@RequestBody HashMap<String, Object> params) {
        if (params == null) return ApiResponse.badRequest("请求体不能为空");

        String scope   = (String) params.get(PARAM_SCOPE);
        String name    = (String) params.get(PARAM_NAME);
        String content = (String) params.get(PARAM_CONTENT);

        if (scope   == null || scope.isBlank())   return ApiResponse.badRequest("scope 不能为空");
        if (name    == null || name.isBlank())     return ApiResponse.badRequest("name 不能为空");
        if (content == null || content.isBlank())  return ApiResponse.badRequest("content 不能为空");
        if (!isSafeName(name)) return ApiResponse.badRequest("name 包含非法字符（只允许字母、数字、连字符、下划线）");

        Path skillsRoot;
        try {
            skillsRoot = skillRegistry.getSkillsRoot(scope.trim());
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        }
        Path skillDir = skillsRoot.resolve(name.trim()).normalize();

        // 路径安全：确保解析后仍在 scope 根目录内
        if (!skillDir.startsWith(skillsRoot)) {
            return ApiResponse.badRequest("路径非法");
        }

        ReentrantLock lock = lockFor(scope.trim(), name.trim());
        lock.lock();
        try {
            Path skillFile = skillDir.resolve(SKILL_FILE);
            Files.createDirectories(skillDir);
            Files.writeString(skillFile, content);
            skillRegistry.invalidate();
            leoSkillsProvider.invalidate();
            return ApiResponse.success("skill 保存成功");
        } catch (IOException e) {
            return ApiResponse.error("skill 保存失败：" + e.getMessage());
        } finally {
            lock.unlock();
        }
    }

    // ── 删除 ──────────────────────────────────────────────────────────────────

    /**
     * 删除指定 skill 目录（含 SKILL.md 及目录下所有文件）。
     *
     * <p>请求体：{scope, name}
     */
    @RequestMapping(value = "/delete", method = RequestMethod.POST)
    public HashMap<String, Object> delete(@RequestBody HashMap<String, Object> params) {
        if (params == null) return ApiResponse.badRequest("请求体不能为空");

        String scope = (String) params.get(PARAM_SCOPE);
        String name  = (String) params.get(PARAM_NAME);

        if (scope == null || scope.isBlank()) return ApiResponse.badRequest("scope 不能为空");
        if (name  == null || name.isBlank())  return ApiResponse.badRequest("name 不能为空");
        if (!isSafeName(name)) return ApiResponse.badRequest("name 包含非法字符");

        Path skillsRoot;
        try {
            skillsRoot = skillRegistry.getSkillsRoot(scope.trim());
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        }
        Path skillDir = skillsRoot.resolve(name.trim()).normalize();

        // 路径安全
        if (!skillDir.startsWith(skillsRoot)) {
            return ApiResponse.badRequest("路径非法");
        }

        if (!Files.exists(skillDir)) {
            return ApiResponse.notFound("skill 不存在：" + scope + "/" + name);
        }

        ReentrantLock lock = lockFor(scope.trim(), name.trim());
        lock.lock();
        try {
            deleteRecursively(skillDir);
            skillRegistry.invalidate();
            leoSkillsProvider.invalidate();
            // 删除成功后回收锁条目，避免长期运行时键空间膨胀
            skillLocks.remove(scope.trim() + "/" + name.trim());
            return ApiResponse.success("skill 删除成功");
        } catch (IOException e) {
            return ApiResponse.error("skill 删除失败：" + e.getMessage());
        } finally {
            lock.unlock();
        }
    }

    // ── 全文搜索 ──────────────────────────────────────────────────────────────

    /**
     * 在指定 scope 下全文搜索 skill（匹配 name、description 或正文内容）。
     *
     * @param scope   puppet-node 或 platform
     * @param keyword 搜索关键字（不区分大小写）
     */
    @RequestMapping(value = "/search", method = RequestMethod.GET)
    public HashMap<String, Object> search(
            @RequestParam(PARAM_SCOPE)    String scope,
            @RequestParam("keyword")      String keyword) {

        if (scope   == null || scope.isBlank())   return ApiResponse.badRequest("scope 不能为空");
        if (keyword == null || keyword.isBlank())  return ApiResponse.badRequest("keyword 不能为空");

        String kw = keyword.toLowerCase();
        List<SkillMeta> all = skillRegistry.listAllSkills(scope);

        List<SkillMeta> matched = all.stream()
            .filter(s -> {
                if (s.getName() != null && s.getName().toLowerCase().contains(kw))        return true;
                if (s.getDescription() != null && s.getDescription().toLowerCase().contains(kw)) return true;
                // 全文匹配：读取 SKILL.md 正文
                String content = skillRegistry.getSkillContent(scope, s.getName());
                return content != null && content.toLowerCase().contains(kw);
            })
            .toList();

        return ApiResponse.success(matched);
    }

    // ── 启用 / 禁用 ───────────────────────────────────────────────────────────

    /**
     * 切换 skill 的启用状态。
     *
     * <p>通过直接改写 SKILL.md frontmatter 中的 enabled 字段实现，
     * 不存在该字段时自动插入。
     *
     * <p>请求体：{scope, name, enabled}
     */
    @RequestMapping(value = "/toggle", method = RequestMethod.POST)
    public HashMap<String, Object> toggle(@RequestBody HashMap<String, Object> params) {
        if (params == null) return ApiResponse.badRequest("请求体不能为空");

        String  scope      = (String)  params.get(PARAM_SCOPE);
        String  name       = (String)  params.get(PARAM_NAME);
        Object  enabledObj = params.get("enabled");

        if (scope      == null || scope.isBlank())   return ApiResponse.badRequest("scope 不能为空");
        if (name       == null || name.isBlank())     return ApiResponse.badRequest("name 不能为空");
        if (enabledObj == null)                       return ApiResponse.badRequest("enabled 不能为空");
        if (!isSafeName(name)) return ApiResponse.badRequest("name 包含非法字符");

        boolean enabled = Boolean.parseBoolean(String.valueOf(enabledObj));

        Path skillsRoot;
        try {
            skillsRoot = skillRegistry.getSkillsRoot(scope.trim());
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        }
        Path skillFile = skillsRoot.resolve(name.trim()).resolve(SKILL_FILE).normalize();

        if (!skillFile.startsWith(skillsRoot)) return ApiResponse.badRequest("路径非法");
        if (!Files.exists(skillFile))          return ApiResponse.notFound("skill 不存在：" + scope + "/" + name);

        ReentrantLock lock = lockFor(scope.trim(), name.trim());
        lock.lock();
        try {
            String original = Files.readString(skillFile, StandardCharsets.UTF_8);
            String updated  = setFrontmatterEnabled(original, enabled);
            Files.writeString(skillFile, updated, StandardCharsets.UTF_8);
            skillRegistry.invalidate();
            leoSkillsProvider.invalidate();
            return ApiResponse.success(enabled ? "skill 已启用" : "skill 已禁用");
        } catch (IOException e) {
            return ApiResponse.error("操作失败：" + e.getMessage());
        } finally {
            lock.unlock();
        }
    }

    // ── 文件树 / 文件级操作 ───────────────────────────────────────────────────

    /**
     * 列出 skill 目录下的所有文件（含子目录），用于前端构建文件树。
     */
    @RequestMapping(value = "/files", method = RequestMethod.GET)
    public HashMap<String, Object> listFiles(
            @RequestParam(PARAM_SCOPE) String scope,
            @RequestParam(PARAM_NAME) String name) {

        Path skillDir = resolveSkillDir(scope, name);
        if (skillDir == null) return ApiResponse.badRequest("scope/name 非法");
        if (!Files.exists(skillDir)) return ApiResponse.notFound("skill 不存在：" + scope + "/" + name);

        try {
            return ApiResponse.success(skillFileService.listFiles(skillDir));
        } catch (IOException e) {
            return ApiResponse.error("列出文件失败：" + e.getMessage());
        }
    }

    /**
     * 读取 skill 目录下单个文件。
     *
     * <p>响应：{path, size, encoding: "text"|"base64", content}
     */
    @RequestMapping(value = "/file", method = RequestMethod.GET)
    public HashMap<String, Object> getFile(
            @RequestParam(PARAM_SCOPE) String scope,
            @RequestParam(PARAM_NAME) String name,
            @RequestParam("path") String relativePath) {

        Path skillDir = resolveSkillDir(scope, name);
        if (skillDir == null) return ApiResponse.badRequest("scope/name 非法");
        if (!Files.exists(skillDir)) return ApiResponse.notFound("skill 不存在");

        try {
            return ApiResponse.success(skillFileService.readFile(skillDir, relativePath));
        } catch (SkillFileException e) {
            return ApiResponse.badRequest(e.getMessage());
        } catch (IOException e) {
            return ApiResponse.error("读取文件失败：" + e.getMessage());
        }
    }

    /**
     * 保存 skill 目录下单个文件（创建或覆盖）。
     *
     * <p>请求体：{scope, name, path, content, encoding: "text"|"base64"}
     */
    @RequestMapping(value = "/file/save", method = RequestMethod.POST)
    public HashMap<String, Object> saveFile(@RequestBody HashMap<String, Object> params) {
        if (params == null) return ApiResponse.badRequest("请求体不能为空");

        String scope    = (String) params.get(PARAM_SCOPE);
        String name     = (String) params.get(PARAM_NAME);
        String relPath  = (String) params.get("path");
        String content  = (String) params.get(PARAM_CONTENT);
        String encoding = (String) params.getOrDefault("encoding", "text");

        Path skillDir = resolveSkillDir(scope, name);
        if (skillDir == null) return ApiResponse.badRequest("scope/name 非法");
        if (!Files.exists(skillDir)) return ApiResponse.notFound("skill 不存在");
        if (relPath == null || relPath.isBlank()) return ApiResponse.badRequest("path 不能为空");

        ReentrantLock lock = lockFor(scope.trim(), name.trim());
        lock.lock();
        try {
            skillFileService.writeFile(skillDir, relPath, content, encoding);
            // SKILL.md 内容变化要刷新 registry 缓存（其他文件不影响 listSkills，但保险起见统一失效）
            skillRegistry.invalidate();
            leoSkillsProvider.invalidate();
            return ApiResponse.success("文件已保存");
        } catch (SkillFileException e) {
            return ApiResponse.badRequest(e.getMessage());
        } catch (IOException e) {
            return ApiResponse.error("保存失败：" + e.getMessage());
        } finally {
            lock.unlock();
        }
    }

    /**
     * 删除 skill 目录下单个文件或子目录。SKILL.md 不可删除。
     *
     * <p>请求体：{scope, name, path}
     */
    @RequestMapping(value = "/file/delete", method = RequestMethod.POST)
    public HashMap<String, Object> deleteFile(@RequestBody HashMap<String, Object> params) {
        if (params == null) return ApiResponse.badRequest("请求体不能为空");

        String scope   = (String) params.get(PARAM_SCOPE);
        String name    = (String) params.get(PARAM_NAME);
        String relPath = (String) params.get("path");

        Path skillDir = resolveSkillDir(scope, name);
        if (skillDir == null) return ApiResponse.badRequest("scope/name 非法");
        if (!Files.exists(skillDir)) return ApiResponse.notFound("skill 不存在");
        if (relPath == null || relPath.isBlank()) return ApiResponse.badRequest("path 不能为空");

        ReentrantLock lock = lockFor(scope.trim(), name.trim());
        lock.lock();
        try {
            skillFileService.deleteFile(skillDir, relPath);
            return ApiResponse.success("已删除");
        } catch (SkillFileException e) {
            return ApiResponse.badRequest(e.getMessage());
        } catch (IOException e) {
            return ApiResponse.error("删除失败：" + e.getMessage());
        } finally {
            lock.unlock();
        }
    }

    /**
     * 重命名/移动 skill 目录下文件。SKILL.md 不可重命名。
     *
     * <p>请求体：{scope, name, from, to}
     */
    @RequestMapping(value = "/file/move", method = RequestMethod.POST)
    public HashMap<String, Object> moveFile(@RequestBody HashMap<String, Object> params) {
        if (params == null) return ApiResponse.badRequest("请求体不能为空");

        String scope = (String) params.get(PARAM_SCOPE);
        String name  = (String) params.get(PARAM_NAME);
        String from  = (String) params.get("from");
        String to    = (String) params.get("to");

        Path skillDir = resolveSkillDir(scope, name);
        if (skillDir == null) return ApiResponse.badRequest("scope/name 非法");
        if (!Files.exists(skillDir)) return ApiResponse.notFound("skill 不存在");
        if (from == null || from.isBlank() || to == null || to.isBlank()) {
            return ApiResponse.badRequest("from/to 不能为空");
        }

        ReentrantLock lock = lockFor(scope.trim(), name.trim());
        lock.lock();
        try {
            skillFileService.moveFile(skillDir, from, to);
            return ApiResponse.success("已重命名");
        } catch (SkillFileException e) {
            return ApiResponse.badRequest(e.getMessage());
        } catch (IOException e) {
            return ApiResponse.error("重命名失败：" + e.getMessage());
        } finally {
            lock.unlock();
        }
    }

    /**
     * 解析并校验 (scope, name) 对应的 skill 根目录。
     * 路径越界或参数非法时返回 null（调用方按 badRequest 处理）。
     */
    private Path resolveSkillDir(String scope, String name) {
        if (scope == null || scope.isBlank()) return null;
        if (name  == null || name.isBlank())  return null;
        if (!isSafeName(name)) return null;
        try {
            Path skillsRoot = skillRegistry.getSkillsRoot(scope.trim());
            Path skillDir = skillsRoot.resolve(name.trim()).normalize();
            if (!skillDir.startsWith(skillsRoot)) return null;
            return skillDir;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // ── 导出 / 导入 ───────────────────────────────────────────────────────────

    /**
     * 导出单个 skill 为 .skill 文件（zip 格式，内容为 skill 目录下所有文件）。
     */
    @RequestMapping(value = "/export", method = RequestMethod.GET)
    public ResponseEntity<byte[]> exportSkill(
            @RequestParam(PARAM_SCOPE) String scope,
            @RequestParam(PARAM_NAME) String name) {

        Path skillDir = resolveSkillDir(scope, name);
        if (skillDir == null) return ResponseEntity.badRequest().body(("scope/name 非法").getBytes(StandardCharsets.UTF_8));
        if (!Files.exists(skillDir)) return ResponseEntity.notFound().build();

        ReentrantLock lock = lockFor(scope.trim(), name.trim());
        lock.lock();
        try {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            skillExportService.exportSkill(skillDir, buf);
            byte[] bytes = buf.toByteArray();

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.parseMediaType("application/zip"));
            headers.setContentDisposition(buildAttachmentDisposition(name.trim() + ".skill"));
            return ResponseEntity.ok().headers(headers).body(bytes);
        } catch (IOException e) {
            return ResponseEntity.internalServerError().body(("导出失败：" + e.getMessage()).getBytes(StandardCharsets.UTF_8));
        } finally {
            lock.unlock();
        }
    }

    /**
     * 批量导出。请求体：{scope, names: [...]}，返回 zip，内含 {name}.skill 多个 entry，
     * 整体再用一个外层 zip 包装。
     *
     * <p>简化做法：直接用 SkillExportService 的批量模式，每个 skill 的文件以 {name}/ 为前缀
     * 打入同一个 zip。下载文件名为 skills_{scope}_{date}.zip。
     */
    @RequestMapping(value = "/export/batch", method = RequestMethod.POST)
    public ResponseEntity<byte[]> exportSkillsBatch(@RequestBody HashMap<String, Object> params) {
        if (params == null) return ResponseEntity.badRequest().body("请求体不能为空".getBytes(StandardCharsets.UTF_8));

        String scope = (String) params.get(PARAM_SCOPE);
        Object namesObj = params.get("names");
        if (scope == null || scope.isBlank()) return ResponseEntity.badRequest().body("scope 不能为空".getBytes(StandardCharsets.UTF_8));
        if (!(namesObj instanceof List<?> rawNames) || rawNames.isEmpty()) {
            return ResponseEntity.badRequest().body("names 不能为空".getBytes(StandardCharsets.UTF_8));
        }

        // 收集所有目标目录，全部加锁
        List<NamedSkill> namedSkills = new ArrayList<>();
        List<ReentrantLock> heldLocks = new ArrayList<>();
        try {
            for (Object o : rawNames) {
                if (!(o instanceof String n) || n.isBlank()) continue;
                Path dir = resolveSkillDir(scope, n);
                if (dir == null || !Files.exists(dir)) continue;
                ReentrantLock lock = lockFor(scope.trim(), n.trim());
                lock.lock();
                heldLocks.add(lock);
                namedSkills.add(new NamedSkill(n.trim(), dir));
            }
            if (namedSkills.isEmpty()) {
                return ResponseEntity.badRequest().body("没有可导出的 skill".getBytes(StandardCharsets.UTF_8));
            }

            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            skillExportService.exportSkills(namedSkills, buf);
            byte[] bytes = buf.toByteArray();

            String filename = "skills_" + scope.trim() + "_" + LocalDate.now() + ".zip";
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.parseMediaType("application/zip"));
            headers.setContentDisposition(buildAttachmentDisposition(filename));
            return ResponseEntity.ok().headers(headers).body(bytes);
        } catch (IOException e) {
            return ResponseEntity.internalServerError().body(("导出失败：" + e.getMessage()).getBytes(StandardCharsets.UTF_8));
        } finally {
            for (ReentrantLock l : heldLocks) l.unlock();
        }
    }

    /**
     * 导入 .skill 或 zip 文件。
     *
     * <p>请求：multipart/form-data，参数：
     * <ul>
     *   <li>file: .skill 或 .zip 文件</li>
     *   <li>scope: 目标 scope</li>
     *   <li>defaultName: 当 zip 是单 skill（根有 SKILL.md）时使用的目标名；批量导入时可省略</li>
     *   <li>conflictPolicy: skip / overwrite / rename，默认 rename</li>
     * </ul>
     *
     * <p>响应：{results: [{originalName, finalName, status, message}]}
     */
    @RequestMapping(value = "/import", method = RequestMethod.POST)
    public HashMap<String, Object> importSkills(
            @RequestParam("file") MultipartFile file,
            @RequestParam(PARAM_SCOPE) String scope,
            @RequestParam(value = "defaultName", required = false) String defaultName,
            @RequestParam(value = "conflictPolicy", required = false) String conflictPolicy) {

        if (file == null || file.isEmpty()) return ApiResponse.badRequest("file 不能为空");
        if (scope == null || scope.isBlank()) return ApiResponse.badRequest("scope 不能为空");

        Path scopeRoot;
        try {
            scopeRoot = skillRegistry.getSkillsRoot(scope.trim());
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        }

        ConflictPolicy policy = ConflictPolicy.parse(conflictPolicy);

        try {
            List<ImportResult> results = skillExportService.importSkills(file, scopeRoot, defaultName, policy);
            skillRegistry.invalidate();
            leoSkillsProvider.invalidate();
            HashMap<String, Object> data = new HashMap<>();
            data.put("results", results.stream().map(ImportResult::toMap).toList());
            return ApiResponse.success(data);
        } catch (SkillImportException e) {
            return ApiResponse.badRequest(e.getMessage());
        } catch (IOException e) {
            return ApiResponse.error("导入失败：" + e.getMessage());
        }
    }

    /** 构造 RFC 5987 兼容的 attachment Content-Disposition，处理中文/特殊字符。 */
    private static org.springframework.http.ContentDisposition buildAttachmentDisposition(String filename) {
        return org.springframework.http.ContentDisposition.attachment()
                .filename(filename, StandardCharsets.UTF_8)
                .build();
    }

    // ── 工具方法 ──────────────────────────────────────────────────────────────

    /**
     * 将 SKILL.md 中 frontmatter 的 enabled 字段设为指定值。
     * 使用 SnakeYAML 解析 → 修改 Map → 重新序列化，彻底消除正则/字符串拼接的边缘情况。
     */
    @SuppressWarnings("unchecked")
    private static String setFrontmatterEnabled(String content, boolean enabled) {
        if (!content.startsWith("---")) {
            // 无 frontmatter，在头部插入最小 frontmatter
            return "---\nenabled: " + enabled + "\n---\n\n" + content;
        }

        // 逐行扫描，寻找闭合的 --- 分隔符（避免误匹配 Markdown 正文中的水平线）
        String[] lines = content.split("\n", -1);
        int closeLineIdx = -1;
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].trim().equals("---")) {
                closeLineIdx = i;
                break;
            }
        }

        if (closeLineIdx < 0) {
            // frontmatter 未正常闭合，兜底：头部插入
            return "---\nenabled: " + enabled + "\n---\n\n" + content;
        }

        // 提取 frontmatter YAML 正文（第 1 行到 closeLineIdx-1 行）
        String fmYaml = String.join("\n", java.util.Arrays.copyOfRange(lines, 1, closeLineIdx));

        // 提取 body（闭合 --- 之后的全部行，含前导空行，保持原样）
        String body = closeLineIdx + 1 < lines.length
                ? "\n" + String.join("\n", java.util.Arrays.copyOfRange(lines, closeLineIdx + 1, lines.length))
                : "";

        // 解析 → 修改 → 序列化
        DumperOptions opts = new DumperOptions();
        opts.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        Yaml yaml = new Yaml(opts);

        Map<String, Object> raw = yaml.load(fmYaml);
        // 用 LinkedHashMap 包装，dump 时按插入顺序输出，保留 name/description 在前
        Map<String, Object> fm = raw != null ? new LinkedHashMap<>(raw) : new LinkedHashMap<>();
        fm.put("enabled", enabled);

        // dump() 末尾带换行，stripTrailing 后手动补 \n---
        String newFmYaml = yaml.dump(fm).stripTrailing();
        return "---\n" + newFmYaml + "\n---" + body;
    }

    /**
     * 名称安全检查：只允许字母、数字、连字符、下划线，防止路径遍历。
     */
    private static boolean isSafeName(String name) {
        return name.matches("[A-Za-z0-9_-]+");
    }

    /**
     * 递归删除目录（先删文件，再删目录）。
     */
    private static void deleteRecursively(Path path) throws IOException {
        if (Files.isDirectory(path)) {
            try (Stream<Path> children = Files.list(path)) {
                for (Path child : children.toList()) {
                    deleteRecursively(child);
                }
            }
        }
        Files.delete(path);
    }
}
