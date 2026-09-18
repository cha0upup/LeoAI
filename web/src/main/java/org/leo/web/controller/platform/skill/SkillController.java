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
import org.leo.ai.service.SkillInspection;
import org.leo.ai.service.SkillManifestService;
import org.leo.ai.service.SkillRegistryService;
import org.leo.ai.service.SkillValidationIssue;
import org.leo.core.util.ApiResponse;
import org.leo.web.security.AdminOnlyEndpoint;
import org.leo.web.service.SkillManagementService;
import org.leo.web.service.SkillOperationLock;
import org.leo.web.util.DownloadHeaders;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Skill 管理接口。
 *
 * <p>Skills 存储于 VFS/skills/{scope}/{name}/，每项必须包含 SKILL.md 和 manifest.yaml，
 * 通过本接口进行 CRUD。
 * 所有写操作完成后调用 {@link SkillRegistryService#invalidate()} 使缓存失效，
 * AI agent 在下次对话时自动感知变更，无需重启。
 */
@RestController
@RequestMapping("/platform/skill")
public class SkillController {

    private static final String PARAM_SCOPE   = "scope";
    private static final String PARAM_NAME    = "name";
    private static final String PARAM_CONTENT = "content";
    private static final String PARAM_MANIFEST = "manifest";

    private static final String SKILL_FILE = "SKILL.md";
    private static final String MANIFEST_FILE = "manifest.yaml";
    private static final int MAX_BATCH_TOGGLE_ITEMS = 500;

    private final SkillRegistryService skillRegistry;
    private final LeoSkillsProvider leoSkillsProvider;
    private final SkillFileService skillFileService;
    private final SkillExportService skillExportService;
    private final SkillManifestService skillManifestService;
    private final SkillManagementService skillManagementService;
    private final SkillOperationLock operationLock;

    @org.springframework.beans.factory.annotation.Autowired
    public SkillController(SkillRegistryService skillRegistry,
                           LeoSkillsProvider leoSkillsProvider,
                           SkillFileService skillFileService,
                           SkillExportService skillExportService,
                           SkillManifestService skillManifestService,
                           SkillManagementService skillManagementService,
                           SkillOperationLock operationLock) {
        this.skillRegistry      = skillRegistry;
        this.leoSkillsProvider   = leoSkillsProvider;
        this.skillFileService    = skillFileService;
        this.skillExportService  = skillExportService;
        this.skillManifestService = skillManifestService;
        this.skillManagementService = skillManagementService;
        this.operationLock = operationLock;
    }

    /** 测试和非 Spring 使用场景。 */
    public SkillController(SkillRegistryService skillRegistry,
                           LeoSkillsProvider leoSkillsProvider,
                           SkillFileService skillFileService,
                           SkillExportService skillExportService,
                           SkillManifestService skillManifestService) {
        this(skillRegistry, leoSkillsProvider, skillFileService, skillExportService,
                skillManifestService, new SkillOperationLock());
    }

    private SkillController(SkillRegistryService skillRegistry,
                            LeoSkillsProvider leoSkillsProvider,
                            SkillFileService skillFileService,
                            SkillExportService skillExportService,
                            SkillManifestService skillManifestService,
                            SkillOperationLock operationLock) {
        this(skillRegistry, leoSkillsProvider, skillFileService, skillExportService,
                skillManifestService,
                new SkillManagementService(skillRegistry, leoSkillsProvider,
                        skillManifestService, operationLock), operationLock);
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

    /** 返回 skill catalog 健康状态，包括字段错误、悬空依赖、重复 ID 和依赖环。 */
    @RequestMapping(value = "/health", method = RequestMethod.GET)
    public HashMap<String, Object> health(@RequestParam(PARAM_SCOPE) String scope) {
        if (scope == null || scope.isBlank()) return ApiResponse.badRequest("scope 不能为空");
        try {
            SkillRegistryService.validateScope(scope.trim());
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        }
        List<SkillInspection> inspections = skillRegistry.health(scope.trim());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("scope", scope.trim());
        data.put("total", inspections.size());
        data.put("valid", inspections.stream().filter(SkillInspection::valid).count());
        data.put("invalid", inspections.stream().filter(item -> !item.valid()).count());
        data.put("warning", inspections.stream()
                .filter(SkillInspection::valid)
                .filter(item -> item.issues().stream().anyMatch(issue ->
                        issue.severity() == SkillValidationIssue.Severity.WARNING))
                .count());
        data.put("healthy", inspections.stream()
                .filter(SkillInspection::valid)
                .filter(item -> item.issues().stream().noneMatch(issue ->
                        issue.severity() == SkillValidationIssue.Severity.WARNING))
                .count());
        data.put("skills", inspections);
        return ApiResponse.success(data);
    }

    /** 返回前端筛选器和编辑器应使用的受控分类枚举。 */
    @RequestMapping(value = "/taxonomy", method = RequestMethod.GET)
    public HashMap<String, Object> taxonomy() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("schemaVersion", SkillManifestService.SCHEMA_VERSION);
        data.put("scopes", SkillRegistryService.ALLOWED_SCOPES.stream().sorted().toList());
        data.put("domains", SkillManifestService.DOMAINS.stream().sorted().toList());
        data.put("categories", SkillManifestService.CATEGORIES.stream().sorted().toList());
        data.put("modes", SkillManifestService.MODES.stream().sorted().toList());
        data.put("risks", SkillManifestService.RISKS.stream().sorted().toList());
        data.put("accessModes", SkillManifestService.ACCESS_MODES.stream().sorted().toList());
        data.put("statuses", SkillManifestService.STATUSES.stream().sorted().toList());
        data.put("sources", SkillManifestService.SOURCES.stream().sorted().toList());
        return ApiResponse.success(data);
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
     * <p>请求体：{scope, name, content, manifest}。两份元数据会在写盘前一起严格校验。
     */
    @RequestMapping(value = "/save", method = RequestMethod.POST)
    @AdminOnlyEndpoint
    public HashMap<String, Object> save(@RequestBody HashMap<String, Object> params) {
        if (params == null) return ApiResponse.badRequest("请求体不能为空");

        String scope   = (String) params.get(PARAM_SCOPE);
        String name    = (String) params.get(PARAM_NAME);
        String content = (String) params.get(PARAM_CONTENT);
        String manifest = (String) params.get(PARAM_MANIFEST);

        SkillManagementService.OperationResult result =
                skillManagementService.save(scope, name, content, manifest);
        return result.succeeded()
                ? ApiResponse.success(result.message())
                : ApiResponse.error(result.code(), result.message());
    }

    // ── 删除 ──────────────────────────────────────────────────────────────────

    /**
     * 删除指定 skill 目录（含两份必需元数据及目录下所有资源）。
     *
     * <p>请求体：{scope, name}
     */
    @RequestMapping(value = "/delete", method = RequestMethod.POST)
    @AdminOnlyEndpoint
    public HashMap<String, Object> delete(@RequestBody HashMap<String, Object> params) {
        if (params == null) return ApiResponse.badRequest("请求体不能为空");

        String scope = (String) params.get(PARAM_SCOPE);
        String name  = (String) params.get(PARAM_NAME);

        SkillManagementService.OperationResult result = skillManagementService.delete(scope, name);
        return result.succeeded()
                ? ApiResponse.success(result.message())
                : ApiResponse.error(result.code(), result.message());
    }

    /**
     * 批量删除 Skill。批次允许部分成功，并返回逐项结果，避免客户端并发删除后
     * 只能得到一个模糊的整体失败状态。
     *
     * <p>请求体：{scope, names: [...]}。
     */
    @RequestMapping(value = "/delete/batch", method = RequestMethod.POST)
    @AdminOnlyEndpoint
    public HashMap<String, Object> deleteBatch(@RequestBody HashMap<String, Object> params) {
        if (params == null) return ApiResponse.badRequest("请求体不能为空");

        Object scopeObj = params.get(PARAM_SCOPE);
        Object namesObj = params.get("names");
        if (!(scopeObj instanceof String scope) || scope.isBlank()) {
            return ApiResponse.badRequest("scope 不能为空");
        }
        if (!(namesObj instanceof List<?> rawNames) || rawNames.isEmpty()) {
            return ApiResponse.badRequest("names 必须是非空数组");
        }

        try {
            SkillManagementService.BatchDeleteResult result = skillManagementService.deleteBatch(scope, rawNames);
            return ApiResponse.success(
                    "批量删除完成：成功 " + result.deleted() + "，失败 " + result.failed(), result.toMap());
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
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
                if (s.getId() != null && s.getId().toLowerCase().contains(kw)) return true;
                if (s.getDomain() != null && s.getDomain().toLowerCase().contains(kw)) return true;
                if (s.getCategory() != null && s.getCategory().toLowerCase().contains(kw)) return true;
                if (s.getTags().stream().anyMatch(tag -> tag.toLowerCase().contains(kw))) return true;
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
     * <p>通过改写 manifest.yaml 中的 enabled 字段实现。
     *
     * <p>请求体：{scope, name, enabled}
     */
    @RequestMapping(value = "/toggle", method = RequestMethod.POST)
    @AdminOnlyEndpoint
    public HashMap<String, Object> toggle(@RequestBody HashMap<String, Object> params) {
        if (params == null) return ApiResponse.badRequest("请求体不能为空");

        String  scope      = (String)  params.get(PARAM_SCOPE);
        String  name       = (String)  params.get(PARAM_NAME);
        Object  enabledObj = params.get("enabled");

        if (!(enabledObj instanceof Boolean enabled)) return ApiResponse.badRequest("enabled 必须是 boolean");
        SkillManagementService.ToggleResult result =
                skillManagementService.toggle(scope, name, enabled);
        if (result.failed()) return ApiResponse.error(result.errorCode(), result.message());
        return ApiResponse.success(result.message());
    }

    /**
     * 批量设置 skill 启用状态。批次允许部分成功，并返回每个条目的处理结果。
     *
     * <p>请求体：{scope, names: [...], enabled}。批量启用不会绕过 catalog 校验；
     * 批量禁用允许将其他元数据已损坏的条目先关闭，以保持失败关闭。
     */
    @RequestMapping(value = "/toggle/batch", method = RequestMethod.POST)
    @AdminOnlyEndpoint
    public HashMap<String, Object> toggleBatch(@RequestBody HashMap<String, Object> params) {
        if (params == null) return ApiResponse.badRequest("请求体不能为空");

        Object scopeObj = params.get(PARAM_SCOPE);
        Object namesObj = params.get("names");
        Object enabledObj = params.get("enabled");
        if (!(scopeObj instanceof String scope) || scope.isBlank()) {
            return ApiResponse.badRequest("scope 不能为空");
        }
        if (!(namesObj instanceof List<?> rawNames) || rawNames.isEmpty()) {
            return ApiResponse.badRequest("names 必须是非空数组");
        }
        if (!(enabledObj instanceof Boolean enabled)) {
            return ApiResponse.badRequest("enabled 必须是 boolean");
        }

        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (Object rawName : rawNames) {
            if (!(rawName instanceof String name) || !isSafeName(name)) {
                return ApiResponse.badRequest("names 包含非法 skill 名称");
            }
            names.add(name.trim());
        }
        if (names.size() > MAX_BATCH_TOGGLE_ITEMS) {
            return ApiResponse.badRequest("单次最多处理 " + MAX_BATCH_TOGGLE_ITEMS + " 个 skill");
        }

        String normalizedScope = scope.trim();
        SkillManagementService.BatchToggleResult batch =
                skillManagementService.toggleBatch(normalizedScope, new ArrayList<>(names), enabled);
        Map<String, Object> data = batch.toMap();
        String message = "批量" + (enabled ? "启用" : "禁用") + "完成：成功 "
                + batch.changed() + "，未变更 " + batch.unchanged() + "，失败 " + batch.failed();
        return ApiResponse.success(message, data);
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
    @AdminOnlyEndpoint
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

        ReentrantLock lock = operationLock.lockFor(scope.trim(), name.trim());
        lock.lock();
        try {
            if (SkillFileService.isRequiredMetadataFile(relPath)) {
                String normalizedMetadataPath = relPath.replace('\\', '/').trim();
                while (normalizedMetadataPath.startsWith("./")) {
                    normalizedMetadataPath = normalizedMetadataPath.substring(2);
                }
                String candidateSkill = SkillFileService.SKILL_FILE.equalsIgnoreCase(normalizedMetadataPath)
                        ? content : Files.readString(skillDir.resolve(SKILL_FILE), StandardCharsets.UTF_8);
                String candidateManifest = SkillFileService.MANIFEST_FILE.equalsIgnoreCase(normalizedMetadataPath)
                        ? content : Files.readString(skillDir.resolve(MANIFEST_FILE), StandardCharsets.UTF_8);
                SkillInspection inspection = skillManifestService.inspect(
                        scope.trim(), name.trim(), candidateSkill, candidateManifest);
                if (!inspection.valid()) {
                    return ApiResponse.badRequest("skill 校验失败："
                            + SkillManifestService.summarizeErrors(inspection));
                }
            }
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
     * 删除 skill 目录下单个文件或子目录。SKILL.md 和 manifest.yaml 不可删除。
     *
     * <p>请求体：{scope, name, path}
     */
    @RequestMapping(value = "/file/delete", method = RequestMethod.POST)
    @AdminOnlyEndpoint
    public HashMap<String, Object> deleteFile(@RequestBody HashMap<String, Object> params) {
        if (params == null) return ApiResponse.badRequest("请求体不能为空");

        String scope   = (String) params.get(PARAM_SCOPE);
        String name    = (String) params.get(PARAM_NAME);
        String relPath = (String) params.get("path");

        Path skillDir = resolveSkillDir(scope, name);
        if (skillDir == null) return ApiResponse.badRequest("scope/name 非法");
        if (!Files.exists(skillDir)) return ApiResponse.notFound("skill 不存在");
        if (relPath == null || relPath.isBlank()) return ApiResponse.badRequest("path 不能为空");

        ReentrantLock lock = operationLock.lockFor(scope.trim(), name.trim());
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
     * 重命名/移动 skill 目录下文件。SKILL.md 和 manifest.yaml 不可重命名。
     *
     * <p>请求体：{scope, name, from, to}
     */
    @RequestMapping(value = "/file/move", method = RequestMethod.POST)
    @AdminOnlyEndpoint
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

        ReentrantLock lock = operationLock.lockFor(scope.trim(), name.trim());
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

        ReentrantLock lock = operationLock.lockFor(scope.trim(), name.trim());
        lock.lock();
        try {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            skillExportService.exportSkill(skillDir, buf);
            byte[] bytes = buf.toByteArray();

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.parseMediaType("application/zip"));
            headers.setContentDisposition(DownloadHeaders.attachment(name.trim() + ".skill"));
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
                ReentrantLock lock = operationLock.lockFor(scope.trim(), n.trim());
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
            headers.setContentDisposition(DownloadHeaders.attachment(filename));
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
     *   <li>conflictPolicy: skip / overwrite，默认 skip</li>
     * </ul>
     *
     * <p>响应：{results: [{originalName, finalName, status, message}]}
     */
    @RequestMapping(value = "/import", method = RequestMethod.POST)
    @AdminOnlyEndpoint
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

    /**
     * 名称安全检查：只允许字母、数字、连字符、下划线，防止路径遍历。
     */
    private static boolean isSafeName(String name) {
        return SkillRegistryService.isValidSkillName(name);
    }

}
