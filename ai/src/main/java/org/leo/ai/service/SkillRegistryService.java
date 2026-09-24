package org.leo.ai.service;

import org.leo.core.config.LeoConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 文件型 Skill Catalog。
 *
 * <p>物理目录保持 {@code skills/{scope}/{name}}；每个目录必须同时包含符合规范的
 * {@code SKILL.md} 和 {@code manifest.yaml}。无效项会出现在管理列表和健康检查中，
 * 但不会进入 system prompt，也不能通过 {@code activate_skill} 激活。
 */
@Service
public class SkillRegistryService {

    private static final Logger log = LoggerFactory.getLogger(SkillRegistryService.class);

    public static final String SCOPE_PUPPET_NODE = "puppet-node";
    public static final String SCOPE_PLATFORM = "platform";
    public static final Set<String> ALLOWED_SCOPES = Set.of(SCOPE_PUPPET_NODE, SCOPE_PLATFORM);

    private static final String SKILLS_DIR = "skills";
    private static final long TTL_MS = 30_000L;
    private static final Pattern SKILL_NAME_PATTERN =
            Pattern.compile("[a-z0-9][a-z0-9-]{0,63}");

    private final SkillManifestService manifestService;
    private final ConcurrentHashMap<String, List<SkillMeta>> metaCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, List<SkillInspection>> inspectionCache = new ConcurrentHashMap<>();
    private final AtomicLong cacheFilledAt = new AtomicLong(0L);
    private final Object refreshLock = new Object();

    public SkillRegistryService(SkillManifestService manifestService) {
        this.manifestService = manifestService;
    }

    /** 仅列出严格校验通过、已发布且启用的 skill，供 AI 运行时使用。 */
    public List<SkillMeta> listSkills(String scope) {
        if (!isAllowedScope(scope)) return List.of();
        refreshIfStale();
        return metaCache.getOrDefault(scope, List.of()).stream()
                .filter(SkillMeta::isRuntimeEligible)
                .toList();
    }

    /** 列出管理目录中的全部 skill，包括禁用、草稿和无效项。 */
    public List<SkillMeta> listAllSkills(String scope) {
        if (!isAllowedScope(scope)) return List.of();
        refreshIfStale();
        return metaCache.getOrDefault(scope, List.of());
    }

    /** 返回 catalog 级健康检查结果，包括悬空依赖、重复 ID 和依赖环。 */
    public List<SkillInspection> health(String scope) {
        if (!isAllowedScope(scope)) return List.of();
        refreshIfStale();
        return inspectionCache.getOrDefault(scope, List.of());
    }

    /** 立即检查单个 skill；不使用 catalog 缓存。 */
    public SkillInspection inspectSkill(String scope, String name) {
        if (!isAllowedScope(scope) || !isValidSkillName(name)) {
            return new SkillInspection(scope, name, false, null,
                    List.of(SkillValidationIssue.error("skill", "scope 或 name 非法")));
        }
        return manifestService.inspect(resolveSkillDir(scope, name), scope);
    }

    /** 返回单项和 catalog 级校验均通过的 descriptor；无效或不存在时返回 null。 */
    public SkillDescriptor getDescriptor(String scope, String name) {
        SkillInspection inspection = inspectRuntimeSkill(scope, name);
        return inspection.valid() ? inspection.descriptor() : null;
    }

    /** 管理接口读取完整 SKILL.md；不代表该 skill 可运行。 */
    public String getSkillContent(String scope, String name) {
        if (!isAllowedScope(scope) || !isValidSkillName(name)) return null;
        Path skillFile = resolveSkillDir(scope, name).resolve(SkillManifestService.SKILL_FILE);
        Path skillDir = skillFile.getParent();
        if (skillDir == null || Files.isSymbolicLink(skillDir)
                || Files.isSymbolicLink(skillFile) || !Files.isRegularFile(skillFile)) return null;
        try {
            return Files.readString(skillFile);
        } catch (IOException e) {
            log.warn("[SkillRegistry] 读取 skill 失败: scope={}, name={}, err={}",
                    scope, name, e.getMessage());
            return null;
        }
    }

    /** 运行时读取。manifest 无效、非 published 或 disabled 时一律拒绝。 */
    public String getEnabledSkillContent(String scope, String name) {
        String content = getSkillContent(scope, name);
        if (content == null) return null;
        SkillInspection inspection = inspectRuntimeSkill(scope, name);
        if (!inspection.valid() || inspection.descriptor() == null
                || !inspection.descriptor().isPublishedAndEnabled()) return null;
        return content;
    }

    public boolean isSkillEnabled(String scope, String name) {
        return getEnabledSkillContent(scope, name) != null;
    }

    public void invalidate() {
        metaCache.clear();
        inspectionCache.clear();
        cacheFilledAt.set(0L);
    }

    public Path getSkillsRoot(String scope) {
        validateScope(scope);
        return Path.of(LeoConfig.getVfsPath()).resolve(SKILLS_DIR).resolve(scope).normalize();
    }

    public static void validateScope(String scope) {
        if (!ALLOWED_SCOPES.contains(scope)) {
            throw new IllegalArgumentException("非法 scope: " + scope + "（仅允许 " + ALLOWED_SCOPES + "）");
        }
    }

    /** 名称与 skill-creator 规范一致：小写字母、数字和连字符，最长 64。 */
    public static boolean isValidSkillName(String name) {
        return name != null && SKILL_NAME_PATTERN.matcher(name.trim()).matches();
    }

    public static String stripFrontmatter(String content) {
        if (content == null || !content.startsWith("---")) return content;
        String[] lines = content.split("\n", -1);
        int closeLineIdx = -1;
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].trim().equals("---")) {
                closeLineIdx = i;
                break;
            }
        }
        if (closeLineIdx < 0) return content;
        int bodyStart = closeLineIdx + 1;
        while (bodyStart < lines.length && lines[bodyStart].isBlank()) bodyStart++;
        return bodyStart >= lines.length ? "" : String.join("\n",
                java.util.Arrays.copyOfRange(lines, bodyStart, lines.length));
    }

    private void refreshIfStale() {
        long now = System.currentTimeMillis();
        if (now - cacheFilledAt.get() <= TTL_MS) return;
        synchronized (refreshLock) {
            if (System.currentTimeMillis() - cacheFilledAt.get() <= TTL_MS) return;
            Map<String, List<SkillInspection>> scanned = new LinkedHashMap<>();
            for (String scope : ALLOWED_SCOPES) scanned.put(scope, scanScope(scope));
            Map<String, List<SkillInspection>> validated = validateCatalog(scanned);
            for (String scope : ALLOWED_SCOPES) {
                List<SkillInspection> inspections = validated.getOrDefault(scope, List.of());
                inspectionCache.put(scope, inspections);
                metaCache.put(scope, toMeta(scope, inspections));
            }
            cacheFilledAt.set(System.currentTimeMillis());
        }
    }

    private List<SkillInspection> scanScope(String scope) {
        Path scopeDir = getSkillsRoot(scope);
        if (!Files.isDirectory(scopeDir)) return List.of();
        List<SkillInspection> result = new ArrayList<>();
        try (Stream<Path> dirs = Files.list(scopeDir)) {
            dirs.filter(Files::isDirectory)
                    .filter(path -> !Files.isSymbolicLink(path))
                    .sorted()
                    .forEach(skillDir -> result.add(manifestService.inspect(skillDir, scope)));
        } catch (IOException e) {
            log.warn("[SkillRegistry] 扫描 scope 失败: scope={}, err={}", scope, e.getMessage());
        }
        return List.copyOf(result);
    }

    /**
     * 运行时必须同时通过当前文件校验和 catalog 级校验。前者确保 enabled/status 等
     * 立即生效，后者阻止重复 ID、悬空依赖和依赖环被直接激活。
     */
    private SkillInspection inspectRuntimeSkill(String scope, String name) {
        SkillInspection current = inspectSkill(scope, name);
        if (!current.valid()) return current;

        refreshIfStale();
        SkillInspection catalog = inspectionCache.getOrDefault(scope, List.of()).stream()
                .filter(item -> name.equals(item.name()))
                .findFirst()
                .orElse(null);
        if (catalog == null) {
            return new SkillInspection(scope, name, false, current.descriptor(),
                    List.of(SkillValidationIssue.error("skill", "skill 尚未进入有效 catalog")));
        }
        return catalog.valid() ? current : catalog;
    }

    private Map<String, List<SkillInspection>> validateCatalog(
            Map<String, List<SkillInspection>> scanned) {
        Map<String, Integer> idCounts = new HashMap<>();
        for (List<SkillInspection> values : scanned.values()) {
            for (SkillInspection inspection : values) {
                String id = inspection.descriptor() != null ? inspection.descriptor().id() : null;
                if (id != null) idCounts.merge(id, 1, Integer::sum);
            }
        }

        Map<String, List<SkillInspection>> result = new LinkedHashMap<>();
        for (Map.Entry<String, List<SkillInspection>> scopeEntry : scanned.entrySet()) {
            String scope = scopeEntry.getKey();
            Map<String, SkillInspection> byName = new LinkedHashMap<>();
            for (SkillInspection inspection : scopeEntry.getValue()) {
                byName.put(inspection.name(), inspection);
            }
            List<SkillInspection> validated = new ArrayList<>();
            for (SkillInspection inspection : scopeEntry.getValue()) {
                List<SkillValidationIssue> issues = new ArrayList<>(inspection.issues());
                SkillDescriptor descriptor = inspection.descriptor();
                if (descriptor != null) {
                    if (descriptor.id() != null && idCounts.getOrDefault(descriptor.id(), 0) > 1) {
                        issues.add(SkillValidationIssue.error("manifest.id",
                                "稳定 ID 在 catalog 中重复: " + descriptor.id()));
                    }
                    for (String dependency : descriptor.requiredSkills()) {
                        if (!byName.containsKey(dependency)) {
                            issues.add(SkillValidationIssue.error("manifest.requires.skills",
                                    "依赖不存在: " + dependency));
                        }
                    }
                    for (String next : descriptor.next()) {
                        if (!byName.containsKey(next)) {
                            issues.add(SkillValidationIssue.error("manifest.next",
                                    "后续 skill 不存在: " + next));
                        }
                    }
                    if (hasDependencyCycle(inspection.name(), inspection.name(), byName, new HashSet<>())) {
                        issues.add(SkillValidationIssue.error("manifest.requires.skills", "存在循环依赖"));
                    }
                }
                boolean valid = issues.stream().noneMatch(
                        issue -> issue.severity() == SkillValidationIssue.Severity.ERROR);
                validated.add(new SkillInspection(scope, inspection.name(), valid, descriptor, issues));
            }
            result.put(scope, List.copyOf(validated));
        }
        return result;
    }

    private static boolean hasDependencyCycle(String origin, String current,
                                              Map<String, SkillInspection> byName,
                                              Set<String> visiting) {
        SkillInspection inspection = byName.get(current);
        if (inspection == null || inspection.descriptor() == null || !visiting.add(current)) return false;
        for (String dependency : inspection.descriptor().requiredSkills()) {
            if (origin.equals(dependency)) return true;
            if (hasDependencyCycle(origin, dependency, byName, visiting)) return true;
        }
        visiting.remove(current);
        return false;
    }

    private List<SkillMeta> toMeta(String scope, List<SkillInspection> inspections) {
        List<SkillMeta> result = new ArrayList<>();
        for (SkillInspection inspection : inspections) {
            Path skillDir = resolveSkillDir(scope, inspection.name());
            result.add(new SkillMeta(scope, inspection.name(), inspection.descriptor(),
                    countSkillFiles(skillDir), inspection.valid(), inspection.issues()));
        }
        return Collections.unmodifiableList(result);
    }

    private static int countSkillFiles(Path skillDir) {
        if (!Files.isDirectory(skillDir)) return 0;
        try (Stream<Path> stream = Files.walk(skillDir)) {
            return (int) stream.filter(Files::isRegularFile)
                    .filter(path -> !Files.isSymbolicLink(path))
                    .filter(path -> !path.getFileName().toString().startsWith("."))
                    .count();
        } catch (IOException e) {
            return 0;
        }
    }

    private Path resolveSkillDir(String scope, String name) {
        Path scopeRoot = getSkillsRoot(scope);
        Path skillDir = scopeRoot.resolve(name.trim()).normalize();
        if (!skillDir.startsWith(scopeRoot)) throw new IllegalArgumentException("skill 路径越界: " + name);
        return skillDir;
    }

    private static boolean isAllowedScope(String scope) {
        return scope != null && ALLOWED_SCOPES.contains(scope);
    }
}
