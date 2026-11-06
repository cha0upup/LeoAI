package org.leo.ai.service.skill;

import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * {@code manifest.yaml} 的唯一解析和校验入口。
 *
 * <p>当前项目处于开发阶段，采用严格模式：manifest 缺失、字段错误、目录名不一致、
 * 或 SKILL.md frontmatter 含 name/description 之外的管理字段时，skill 均不可运行。
 */
@Service
public class SkillManifestService {

    public static final String SKILL_FILE = "SKILL.md";
    public static final String MANIFEST_FILE = "manifest.yaml";
    public static final int SCHEMA_VERSION = 1;

    private static final long MAX_METADATA_BYTES = 256L * 1024;
    private static final Pattern ID_PATTERN =
            Pattern.compile("[a-z0-9]+(?:[.-][a-z0-9][a-z0-9-]*)+");
    private static final Pattern VERSION_PATTERN =
            Pattern.compile("(?:0|[1-9]\\d*)\\.(?:0|[1-9]\\d*)\\.(?:0|[1-9]\\d*)(?:[-+][0-9A-Za-z.-]+)?");
    private static final Pattern TECHNIQUE_PATTERN = Pattern.compile("T\\d{4}(?:\\.\\d{3})?");

    private static final Set<String> SKILL_FRONTMATTER_FIELDS = Set.of("name", "description");
    private static final Set<String> MANIFEST_FIELDS = Set.of(
            "schemaVersion", "id", "name", "version", "scope", "domain", "category", "mode",
            "attack", "platforms", "targets", "pack", "risk", "accessMode", "status", "source",
            "owner", "enabled", "requires", "produces", "next");
    private static final Set<String> ATTACK_FIELDS = Set.of("tactics", "techniques");
    private static final Set<String> REQUIRES_FIELDS = Set.of("tools", "skills", "facts");

    public static final Set<String> DOMAINS = Set.of(
            "operation", "capability-development", "decision-support", "governance");
    public static final Set<String> CATEGORIES = Set.of(
            "reconnaissance", "initial-access", "execution", "persistence",
            "privilege-escalation", "defense-evasion", "credential-access", "discovery",
            "lateral-movement", "collection", "command-and-control", "exfiltration", "cleanup",
            "fingerprint-development", "communication-profile", "payload-generation",
            "exploit-development", "automation-development", "vulnerability-analysis",
            "exploit-planning", "attack-path-analysis", "target-prioritization",
            "evidence-analysis", "reporting", "authorization", "roe-validation", "risk-review",
            "artifact-tracking", "cleanup-verification", "audit");
    public static final Set<String> MODES = Set.of("assess", "plan", "execute", "generate", "manage");
    public static final Set<String> RISKS = Set.of("low", "medium", "high", "critical");
    public static final Set<String> ACCESS_MODES = Set.of(
            "read-only", "read-only-sensitive", "active-login", "write",
            "write-destructive", "destructive", "artifact-generation");
    public static final Set<String> STATUSES = Set.of("draft", "reviewed", "published", "deprecated");
    public static final Set<String> SOURCES = Set.of("builtin", "imported", "custom");

    public SkillInspection inspect(Path skillDir, String expectedScope) {
        String directoryName = skillDir != null && skillDir.getFileName() != null
                ? skillDir.getFileName().toString() : "<unknown>";
        List<SkillValidationIssue> ioIssues = new ArrayList<>();
        String skillContent = readMetadataFile(skillDir, SKILL_FILE, ioIssues);
        String manifestContent = readMetadataFile(skillDir, MANIFEST_FILE, ioIssues);
        SkillInspection inspection = inspect(expectedScope, directoryName, skillContent, manifestContent);
        if (ioIssues.isEmpty()) return inspection;

        List<SkillValidationIssue> merged = new ArrayList<>(ioIssues);
        merged.addAll(inspection.issues());
        return new SkillInspection(expectedScope, directoryName, false,
                inspection.descriptor(), merged);
    }

    public SkillInspection inspect(String expectedScope, String directoryName,
                                   String skillContent, String manifestContent) {
        List<SkillValidationIssue> issues = new ArrayList<>();
        Map<String, Object> frontmatter = parseSkillFrontmatter(skillContent, issues);
        Map<String, Object> manifest = parseYamlMap(
                manifestContent, MANIFEST_FILE, "manifest", issues);

        checkUnknownFields(frontmatter, SKILL_FRONTMATTER_FIELDS, "SKILL.md.frontmatter", issues);
        checkUnknownFields(manifest, MANIFEST_FIELDS, "manifest", issues);

        Integer schemaVersion = integer(manifest, "schemaVersion", true, issues);
        String id = string(manifest, "id", true, issues);
        String manifestName = string(manifest, "name", true, issues);
        String version = string(manifest, "version", true, issues);
        String scope = string(manifest, "scope", true, issues);
        String domain = string(manifest, "domain", true, issues);
        String category = string(manifest, "category", true, issues);
        String mode = string(manifest, "mode", true, issues);
        String pack = string(manifest, "pack", false, issues);
        String risk = string(manifest, "risk", true, issues);
        String accessMode = string(manifest, "accessMode", true, issues);
        String status = string(manifest, "status", true, issues);
        String source = string(manifest, "source", true, issues);
        String owner = string(manifest, "owner", true, issues);
        Boolean enabled = bool(manifest, "enabled", true, issues);

        String frontmatterName = string(frontmatter, "name", true, issues, "SKILL.md.frontmatter");
        String description = string(frontmatter, "description", true, issues, "SKILL.md.frontmatter");

        Map<String, Object> attack = nestedMap(manifest, "attack", false, issues);
        checkUnknownFields(attack, ATTACK_FIELDS, "manifest.attack", issues);
        List<String> tactics = stringList(attack, "tactics", false, issues, "manifest.attack");
        List<String> techniques = stringList(attack, "techniques", false, issues, "manifest.attack");
        List<String> platforms = stringList(manifest, "platforms", true, issues, "manifest");
        List<String> targets = stringList(manifest, "targets", true, issues, "manifest");

        Map<String, Object> requires = nestedMap(manifest, "requires", false, issues);
        checkUnknownFields(requires, REQUIRES_FIELDS, "manifest.requires", issues);
        List<String> requiredTools = stringList(requires, "tools", false, issues, "manifest.requires");
        List<String> requiredSkills = stringList(requires, "skills", false, issues, "manifest.requires");
        List<String> requiredFacts = stringList(requires, "facts", false, issues, "manifest.requires");
        List<String> produces = stringList(manifest, "produces", false, issues, "manifest");
        List<String> next = stringList(manifest, "next", false, issues, "manifest");

        checkEquals(directoryName, manifestName, "manifest.name", "必须与 skill 目录名一致", issues);
        checkEquals(directoryName, frontmatterName, "SKILL.md.frontmatter.name",
                "必须与 skill 目录名一致", issues);
        if (!SkillRegistryService.isValidSkillName(directoryName)) {
            issues.add(SkillValidationIssue.error("skill.name",
                    "目录名必须使用小写字母、数字和连字符，且最长 64 个字符"));
        }
        checkEquals(expectedScope, scope, "manifest.scope", "必须与所在 scope 目录一致", issues);
        checkAllowed(schemaVersion, Set.of(SCHEMA_VERSION), "manifest.schemaVersion", issues);
        checkPattern(id, ID_PATTERN, "manifest.id", "必须是点分隔的稳定小写 ID", issues);
        checkPattern(version, VERSION_PATTERN, "manifest.version", "必须是 SemVer，例如 1.0.0", issues);
        checkAllowed(domain, DOMAINS, "manifest.domain", issues);
        checkAllowed(category, CATEGORIES, "manifest.category", issues);
        checkAllowed(mode, MODES, "manifest.mode", issues);
        checkAllowed(risk, RISKS, "manifest.risk", issues);
        checkAllowed(accessMode, ACCESS_MODES, "manifest.accessMode", issues);
        checkAllowed(status, STATUSES, "manifest.status", issues);
        checkAllowed(source, SOURCES, "manifest.source", issues);
        for (String technique : techniques) {
            checkPattern(technique, TECHNIQUE_PATTERN, "manifest.attack.techniques",
                    "必须使用 MITRE ATT&CK technique ID，例如 T1021.004", issues);
        }
        for (String dependency : requiredSkills) {
            if (!SkillRegistryService.isValidSkillName(dependency)) {
                issues.add(SkillValidationIssue.error("manifest.requires.skills",
                        "非法 skill 依赖名称: " + dependency));
            }
        }
        for (String nextSkill : next) {
            if (!SkillRegistryService.isValidSkillName(nextSkill)) {
                issues.add(SkillValidationIssue.error("manifest.next",
                        "非法后续 skill 名称: " + nextSkill));
            }
        }
        if (Boolean.TRUE.equals(enabled) && !"published".equals(status)) {
            issues.add(SkillValidationIssue.error("manifest.enabled",
                    "只有 status=published 的 skill 才能启用"));
        }
        if (Boolean.TRUE.equals(enabled) && "deprecated".equals(status)) {
            issues.add(SkillValidationIssue.error("manifest.enabled", "已弃用 skill 不能启用"));
        }
        if (platforms.isEmpty()) {
            issues.add(SkillValidationIssue.error("manifest.platforms", "至少声明一个适用平台"));
        }
        if (targets.isEmpty()) {
            issues.add(SkillValidationIssue.error("manifest.targets", "至少声明一个目标类型"));
        }
        if (("high".equals(risk) || "critical".equals(risk)) && Boolean.TRUE.equals(enabled)) {
            issues.add(SkillValidationIssue.warning("manifest.enabled",
                    "高风险 skill 已启用；每次执行仍必须绑定精确授权和显式确认"));
        }

        SkillDescriptor descriptor = new SkillDescriptor(
                schemaVersion != null ? schemaVersion : 0,
                id, manifestName != null ? manifestName : directoryName, version, scope,
                description, domain, category, mode, tactics, techniques, platforms, targets,
                pack, risk, accessMode, status, source, owner, Boolean.TRUE.equals(enabled),
                requiredTools, requiredSkills, requiredFacts, produces, next);
        boolean valid = issues.stream()
                .noneMatch(issue -> issue.severity() == SkillValidationIssue.Severity.ERROR);
        return new SkillInspection(expectedScope, directoryName, valid, descriptor, issues);
    }

    /** 更新 manifest 中的启用状态；返回规范化后的 YAML。 */
    public String setEnabled(String manifestContent, boolean enabled) {
        Map<String, Object> manifest = requireYamlMap(manifestContent, MANIFEST_FILE);
        manifest.put("enabled", enabled);
        return dump(manifest);
    }

    /** 导入后强制进入待审核状态，避免外部高风险能力直接进入运行时。 */
    public String markImportedDraft(String manifestContent) {
        Map<String, Object> manifest = requireYamlMap(manifestContent, MANIFEST_FILE);
        manifest.put("source", "imported");
        manifest.put("status", "draft");
        manifest.put("enabled", false);
        return dump(manifest);
    }

    public static String summarizeErrors(SkillInspection inspection) {
        if (inspection == null) return "skill 校验失败";
        String summary = inspection.issues().stream()
                .filter(issue -> issue.severity() == SkillValidationIssue.Severity.ERROR)
                .map(issue -> issue.field() + ": " + issue.message())
                .reduce((a, b) -> a + "; " + b)
                .orElse("skill 校验失败");
        return summary.length() <= 800 ? summary : summary.substring(0, 800) + "...";
    }

    private static String readMetadataFile(Path skillDir, String fileName,
                                           List<SkillValidationIssue> issues) {
        Path file = skillDir != null ? skillDir.resolve(fileName) : null;
        if (file == null || Files.isSymbolicLink(file) || !Files.isRegularFile(file)) {
            issues.add(SkillValidationIssue.error(fileName, "缺少必需文件 " + fileName));
            return null;
        }
        try {
            long size = Files.size(file);
            if (size > MAX_METADATA_BYTES) {
                issues.add(SkillValidationIssue.error(fileName, "元数据文件超过 256KB 限制"));
                return null;
            }
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            issues.add(SkillValidationIssue.error(fileName, "读取失败: " + e.getMessage()));
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseSkillFrontmatter(
            String content, List<SkillValidationIssue> issues) {
        if (content == null || !content.startsWith("---")) {
            issues.add(SkillValidationIssue.error("SKILL.md.frontmatter", "缺少 YAML frontmatter"));
            return new LinkedHashMap<>();
        }
        String[] lines = content.split("\\n", -1);
        int closeLine = -1;
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].trim().equals("---")) {
                closeLine = i;
                break;
            }
        }
        if (closeLine < 0) {
            issues.add(SkillValidationIssue.error("SKILL.md.frontmatter", "缺少闭合分隔符 ---"));
            return new LinkedHashMap<>();
        }
        String yamlText = String.join("\n", java.util.Arrays.copyOfRange(lines, 1, closeLine));
        return parseYamlMap(yamlText, "SKILL.md", "SKILL.md.frontmatter", issues);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseYamlMap(
            String content, String fileName, String field, List<SkillValidationIssue> issues) {
        if (content == null || content.isBlank()) return new LinkedHashMap<>();
        try {
            Object parsed = yaml().load(content);
            if (!(parsed instanceof Map<?, ?> raw)) {
                issues.add(SkillValidationIssue.error(field, fileName + " 必须是 YAML mapping"));
                return new LinkedHashMap<>();
            }
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : raw.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    issues.add(SkillValidationIssue.error(field, "字段名必须是字符串"));
                    continue;
                }
                result.put(key, entry.getValue());
            }
            return result;
        } catch (RuntimeException e) {
            issues.add(SkillValidationIssue.error(field, fileName + " YAML 解析失败: " + e.getMessage()));
            return new LinkedHashMap<>();
        }
    }

    private static Map<String, Object> requireYamlMap(String content, String fileName) {
        List<SkillValidationIssue> issues = new ArrayList<>();
        Map<String, Object> parsed = parseYamlMap(content, fileName, "manifest", issues);
        if (!issues.isEmpty()) throw new IllegalArgumentException(issues.get(0).message());
        return parsed;
    }

    private static Map<String, Object> nestedMap(Map<String, Object> parent, String key,
                                                  boolean required, List<SkillValidationIssue> issues) {
        Object raw = parent.get(key);
        if (raw == null) {
            if (required) issues.add(SkillValidationIssue.error("manifest." + key, "必填字段缺失"));
            return new LinkedHashMap<>();
        }
        if (!(raw instanceof Map<?, ?> map)) {
            issues.add(SkillValidationIssue.error("manifest." + key, "必须是 YAML mapping"));
            return new LinkedHashMap<>();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() instanceof String field) result.put(field, entry.getValue());
            else issues.add(SkillValidationIssue.error("manifest." + key, "字段名必须是字符串"));
        }
        return result;
    }

    private static String string(Map<String, Object> map, String key, boolean required,
                                 List<SkillValidationIssue> issues) {
        return string(map, key, required, issues, "manifest");
    }

    private static String string(Map<String, Object> map, String key, boolean required,
                                 List<SkillValidationIssue> issues, String prefix) {
        Object raw = map.get(key);
        if (raw == null) {
            if (required) issues.add(SkillValidationIssue.error(prefix + "." + key, "必填字段缺失"));
            return null;
        }
        if (!(raw instanceof String value) || value.isBlank()) {
            issues.add(SkillValidationIssue.error(prefix + "." + key, "必须是非空字符串"));
            return null;
        }
        return value.trim();
    }

    private static Integer integer(Map<String, Object> map, String key, boolean required,
                                   List<SkillValidationIssue> issues) {
        Object raw = map.get(key);
        if (raw == null) {
            if (required) issues.add(SkillValidationIssue.error("manifest." + key, "必填字段缺失"));
            return null;
        }
        if (raw instanceof Number number) return number.intValue();
        issues.add(SkillValidationIssue.error("manifest." + key, "必须是整数"));
        return null;
    }

    private static Boolean bool(Map<String, Object> map, String key, boolean required,
                                List<SkillValidationIssue> issues) {
        Object raw = map.get(key);
        if (raw == null) {
            if (required) issues.add(SkillValidationIssue.error("manifest." + key, "必填字段缺失"));
            return null;
        }
        if (raw instanceof Boolean value) return value;
        issues.add(SkillValidationIssue.error("manifest." + key, "必须是 boolean"));
        return null;
    }

    private static List<String> stringList(Map<String, Object> map, String key, boolean required,
                                           List<SkillValidationIssue> issues, String prefix) {
        Object raw = map.get(key);
        if (raw == null) {
            if (required) issues.add(SkillValidationIssue.error(prefix + "." + key, "必填字段缺失"));
            return List.of();
        }
        if (!(raw instanceof List<?> values)) {
            issues.add(SkillValidationIssue.error(prefix + "." + key, "必须是字符串数组"));
            return List.of();
        }
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (Object value : values) {
            if (!(value instanceof String text) || text.isBlank()) {
                issues.add(SkillValidationIssue.error(prefix + "." + key,
                        "数组元素必须是非空字符串"));
            } else {
                result.add(text.trim());
            }
        }
        return List.copyOf(result);
    }

    private static void checkUnknownFields(Map<String, Object> values, Set<String> allowed,
                                           String prefix, List<SkillValidationIssue> issues) {
        for (String key : values.keySet()) {
            if (!allowed.contains(key)) {
                issues.add(SkillValidationIssue.error(prefix + "." + key, "未知字段"));
            }
        }
    }

    private static void checkEquals(String expected, String actual, String field,
                                    String message, List<SkillValidationIssue> issues) {
        if (expected != null && actual != null && !expected.equals(actual)) {
            issues.add(SkillValidationIssue.error(field,
                    message + "（期望 " + expected + "，实际 " + actual + "）"));
        }
    }

    private static <T> void checkAllowed(T value, Set<T> allowed, String field,
                                         List<SkillValidationIssue> issues) {
        if (value != null && !allowed.contains(value)) {
            issues.add(SkillValidationIssue.error(field,
                    "不支持的值 " + value + "，允许值: " + allowed));
        }
    }

    private static void checkPattern(String value, Pattern pattern, String field,
                                     String message, List<SkillValidationIssue> issues) {
        if (value != null && !pattern.matcher(value).matches()) {
            issues.add(SkillValidationIssue.error(field, message));
        }
    }

    private static Yaml yaml() {
        LoaderOptions options = new LoaderOptions();
        options.setMaxAliasesForCollections(20);
        options.setCodePointLimit((int) MAX_METADATA_BYTES);
        return new Yaml(new SafeConstructor(options));
    }

    private static String dump(Map<String, Object> values) {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        options.setIndent(2);
        return new Yaml(options).dump(values);
    }
}
