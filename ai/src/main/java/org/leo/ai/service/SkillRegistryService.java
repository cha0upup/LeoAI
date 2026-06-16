package org.leo.ai.service;

import org.leo.core.config.LeoConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * Skill 注册表：从 VFS/skills/ 动态读取 skill 列表和内容，带 TTL 缓存。
 *
 * <p>缓存设计：每个 scope 独立缓存条目（ConcurrentHashMap），整体过期时间共用一个
 * AtomicLong 时间戳。调用 {@link #invalidate()} 后下次读取强制重新扫描目录。
 *
 * <p>frontmatter 解析使用 SnakeYAML，与 Spring Boot 内置版本一致，无需额外依赖。
 */
@Service
public class SkillRegistryService {

    private static final Logger log = LoggerFactory.getLogger(SkillRegistryService.class);

    public static final String SCOPE_PUPPET_NODE = "puppet-node";
    public static final String SCOPE_PLATFORM    = "platform";

    /**
     * 允许的 scope 白名单。所有读取/写入 path 之前必须先通过 {@link #validateScope(String)}，
     * 防止 {@code scope="../foo"} 这类路径遍历攻击。
     */
    public static final Set<String> ALLOWED_SCOPES = Set.of(SCOPE_PUPPET_NODE, SCOPE_PLATFORM);

    private static final String SKILLS_DIR  = "skills";
    private static final String SKILL_FILE  = "SKILL.md";
    private static final long   TTL_MS      = 30_000L;

    /** scope → SkillMeta 列表缓存 */
    private final ConcurrentHashMap<String, List<SkillMeta>> metaCache = new ConcurrentHashMap<>();
    /** 上次缓存填充时间（ms），0 表示需要刷新 */
    private final AtomicLong cacheFilledAt = new AtomicLong(0L);
    /** 仅用于串行化 refreshIfStale，避免多线程同时扫盘 */
    private final Object refreshLock = new Object();

    // ── 公开 API ──────────────────────────────────────────────────────────────

    /**
     * 列出指定 scope 下所有 skill 的元数据（name + description + tags）。
     * 结果按 skill 目录名排序。缓存 30s，保存/删除后自动失效。
     * <p><b>仅返回 enabled=true 的 skill</b>，供 AI system prompt 使用。
     *
     * @param scope "puppet-node" 或 "platform"
     */
    public List<SkillMeta> listSkills(String scope) {
        if (!isAllowedScope(scope)) return Collections.emptyList();
        refreshIfStale();
        return metaCache.getOrDefault(scope, Collections.emptyList())
                .stream().filter(SkillMeta::isEnabled).toList();
    }

    /**
     * 列出指定 scope 下所有 skill（含禁用项），供管理 UI 使用。
     *
     * @param scope "puppet-node" 或 "platform"
     */
    public List<SkillMeta> listAllSkills(String scope) {
        if (!isAllowedScope(scope)) return Collections.emptyList();
        refreshIfStale();
        return metaCache.getOrDefault(scope, Collections.emptyList());
    }

    /**
     * 读取指定 skill 的完整 SKILL.md 内容。
     *
     * @param scope "puppet-node" 或 "platform"
     * @param name  skill 目录名（如 "recon-basic-info"）
     * @return SKILL.md 全文，文件不存在时返回 null
     */
    public String getSkillContent(String scope, String name) {
        if (!isAllowedScope(scope)) return null;
        if (name == null || name.isBlank()) return null;
        Path skillFile = resolveSkillFile(scope, name);
        if (!Files.exists(skillFile)) return null;
        try {
            return Files.readString(skillFile);
        } catch (IOException e) {
            log.warn("[SkillRegistry] 读取 skill 失败: scope={}, name={}, err={}", scope, name, e.getMessage());
            return null;
        }
    }

    /**
     * 使缓存立即失效，下次调用 listSkills 时重新扫描 VFS。
     * 在 save / delete 操作后调用。
     */
    public void invalidate() {
        metaCache.clear();
        cacheFilledAt.set(0L);
    }

    /**
     * 返回 VFS 中 skills/{scope} 的根目录路径。
     *
     * @throws IllegalArgumentException 当 scope 不在 {@link #ALLOWED_SCOPES} 中时；
     *                                  这道防护防止 scope="../foo" 把根目录穿越到 VFS 之外。
     */
    public Path getSkillsRoot(String scope) {
        validateScope(scope);
        return Path.of(LeoConfig.getVfsPath()).resolve(SKILLS_DIR).resolve(scope).normalize();
    }

    /** 校验 scope 是否在允许列表中，否则抛 IllegalArgumentException。 */
    public static void validateScope(String scope) {
        if (!ALLOWED_SCOPES.contains(scope)) {
            throw new IllegalArgumentException("非法 scope: " + scope
                    + "（仅允许 " + ALLOWED_SCOPES + "）");
        }
    }

    /** 静默版校验：用于 list/getContent 这类返回空集合即可的入口，避免无谓抛错。 */
    private static boolean isAllowedScope(String scope) {
        return scope != null && ALLOWED_SCOPES.contains(scope);
    }

    // ── 内部逻辑 ──────────────────────────────────────────────────────────────

    private void refreshIfStale() {
        long now = System.currentTimeMillis();
        if (now - cacheFilledAt.get() <= TTL_MS) return;
        // 串行化刷新：第一个抢到锁的线程扫盘填缓存，其余线程在锁释放后看到新时间戳直接返回。
        synchronized (refreshLock) {
            // double-check：可能在等锁期间已被另一线程刷新
            if (System.currentTimeMillis() - cacheFilledAt.get() <= TTL_MS) return;
            for (String scope : ALLOWED_SCOPES) {
                metaCache.put(scope, scanScope(scope));
            }
            cacheFilledAt.set(System.currentTimeMillis());
        }
    }

    private List<SkillMeta> scanScope(String scope) {
        Path scopeDir = getSkillsRoot(scope);
        if (!Files.isDirectory(scopeDir)) return Collections.emptyList();

        Yaml yaml = new Yaml();
        List<SkillMeta> result = new ArrayList<>();
        try (Stream<Path> dirs = Files.list(scopeDir)) {
            dirs.filter(Files::isDirectory)
                .sorted()
                .forEach(skillDir -> {
                    Path skillFile = skillDir.resolve(SKILL_FILE);
                    if (!Files.isRegularFile(skillFile)) return;
                    String skillName = skillDir.getFileName().toString();
                    Map<String, Object> fm = parseFrontmatter(skillFile, yaml);
                    String description = fm != null ? asString(fm.get("description")) : null;
                    Object enabledVal  = fm != null ? fm.get("enabled") : null;
                    // 默认启用；只有明确写 enabled: false 时才禁用
                    boolean enabled = !Boolean.FALSE.equals(enabledVal);
                    List<String> tags = fm != null ? extractTags(fm.get("tags")) : Collections.emptyList();
                    result.add(new SkillMeta(scope, skillName, description, enabled, tags));
                });
        } catch (IOException e) {
            log.warn("[SkillRegistry] 扫描 scope 失败: scope={}, err={}", scope, e.getMessage());
        }
        return Collections.unmodifiableList(result);
    }

    private static String asString(Object value) {
        return value == null ? null : value.toString();
    }

    /**
     * 把 frontmatter 的 tags 字段规范化成 List&lt;String&gt;。支持 YAML 数组、逗号分隔字符串、
     * 单一字符串等常见写法，未识别格式回退为空列表。
     */
    @SuppressWarnings("unchecked")
    private static List<String> extractTags(Object raw) {
        if (raw == null) return Collections.emptyList();
        if (raw instanceof List<?> list) {
            List<String> out = new ArrayList<>(list.size());
            for (Object item : list) {
                if (item == null) continue;
                String s = item.toString().trim();
                if (!s.isEmpty()) out.add(s);
            }
            return Collections.unmodifiableList(out);
        }
        if (raw instanceof String s) {
            List<String> out = new ArrayList<>();
            for (String token : s.split(",")) {
                String t = token.trim();
                if (!t.isEmpty()) out.add(t);
            }
            return Collections.unmodifiableList(out);
        }
        return Collections.emptyList();
    }

    /**
     * 用 SnakeYAML 解析 SKILL.md 的 frontmatter，返回字段 Map。
     * 文件不存在、无 frontmatter 或解析失败时返回 null。
     *
     * <p>使用逐行扫描定位闭合 ---，避免误匹配 Markdown 正文中的水平线（--- 是合法 HR 语法）。
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseFrontmatter(Path skillFile, Yaml yaml) {
        try {
            String content = Files.readString(skillFile);
            if (!content.startsWith("---")) return null;

            // 逐行扫描，找到第一个单独占一行且内容为 --- 的闭合分隔符
            String[] lines = content.split("\n", -1);
            int closeLineIdx = -1;
            for (int i = 1; i < lines.length; i++) {
                if (lines[i].trim().equals("---")) {
                    closeLineIdx = i;
                    break;
                }
            }
            if (closeLineIdx < 0) {
                log.warn("[SkillRegistry] frontmatter 缺少闭合分隔符: {}", skillFile);
                return null;
            }

            String fmYaml = String.join("\n",
                    java.util.Arrays.copyOfRange(lines, 1, closeLineIdx));
            try {
                return yaml.load(fmYaml);
            } catch (RuntimeException yamlErr) {
                // SnakeYAML 抛 YAMLException 等运行时异常，捕获后告警，避免拖垮 scanScope
                log.warn("[SkillRegistry] frontmatter 解析失败: file={}, err={}", skillFile, yamlErr.getMessage());
                return null;
            }
        } catch (IOException e) {
            log.warn("[SkillRegistry] frontmatter 读取失败: file={}, err={}", skillFile, e.getMessage());
            return null;
        }
    }

    /**
     * 把 SKILL.md 的 frontmatter 段剥掉，仅返回正文。供 AI 工具调用使用，
     * 避免 enabled / tags / description 这类元数据污染上下文。
     *
     * <p>语义：与 {@link #parseFrontmatter} 共用同一套分隔符识别规则。
     * 没有 frontmatter（不以 --- 开头或缺少闭合分隔符）时原样返回。
     */
    public static String stripFrontmatter(String content) {
        if (content == null) return null;
        if (!content.startsWith("---")) return content;
        String[] lines = content.split("\n", -1);
        int closeLineIdx = -1;
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].trim().equals("---")) {
                closeLineIdx = i;
                break;
            }
        }
        if (closeLineIdx < 0) return content;
        // 跳过闭合 --- 之后所有前导空行，让正文从第一段实际内容开始
        int bodyStart = closeLineIdx + 1;
        while (bodyStart < lines.length && lines[bodyStart].isBlank()) bodyStart++;
        if (bodyStart >= lines.length) return "";
        return String.join("\n",
                java.util.Arrays.copyOfRange(lines, bodyStart, lines.length));
    }

    private Path resolveSkillFile(String scope, String name) {
        return getSkillsRoot(scope).resolve(name).resolve(SKILL_FILE).normalize();
    }
}
