package org.leo.web.controller.platform.skill;

import org.leo.ai.service.LeoSkillsProvider;
import org.leo.ai.service.SkillMeta;
import org.leo.ai.service.SkillRegistryService;
import org.leo.core.util.ApiResponse;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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

    /**
     * 每个 (scope, name) 一把锁，串行化 save / delete / toggle 的 read-modify-write，
     * 防止并发改写 frontmatter 互相覆盖。锁实例懒建即可，键空间天然受 isSafeName 限制，
     * 实际容量等于现存 skill 数量，不会无限膨胀。
     */
    private final ConcurrentHashMap<String, ReentrantLock> skillLocks = new ConcurrentHashMap<>();

    public SkillController(SkillRegistryService skillRegistry,
                           LeoSkillsProvider leoSkillsProvider) {
        this.skillRegistry    = skillRegistry;
        this.leoSkillsProvider = leoSkillsProvider;
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
