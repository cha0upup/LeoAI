package org.leo.ai.service.skill;

import java.util.LinkedHashSet;
import java.util.List;

/** 管理 UI 和检索接口使用的扁平 Skill 元数据视图。 */
public class SkillMeta {

    private final String scope;
    private final String name;
    private final String description;
    private final boolean enabled;
    private final List<String> tags;
    private final int fileCount;
    private final String id;
    private final String version;
    private final String domain;
    private final String category;
    private final String mode;
    private final List<String> tactics;
    private final List<String> techniques;
    private final List<String> platforms;
    private final List<String> targets;
    private final String pack;
    private final String risk;
    private final String accessMode;
    private final String status;
    private final String source;
    private final String owner;
    private final boolean valid;
    private final boolean requiresExplicitApproval;
    private final List<String> requiredTools;
    private final List<String> requiredSkills;
    private final List<String> requiredFacts;
    private final List<String> produces;
    private final List<String> next;
    private final List<SkillValidationIssue> issues;

    public SkillMeta(String fallbackScope, String fallbackName, SkillDescriptor descriptor,
                     int fileCount, boolean valid, List<SkillValidationIssue> issues) {
        // 管理 API 的定位键永远使用物理目录，保证 manifest.name 写错时仍可打开并修复。
        this.scope = fallbackScope;
        this.name = fallbackName;
        this.description = descriptor != null ? descriptor.description() : null;
        this.enabled = descriptor != null && descriptor.enabled();
        this.fileCount = Math.max(fileCount, 0);
        this.id = descriptor != null ? descriptor.id() : null;
        this.version = descriptor != null ? descriptor.version() : null;
        this.domain = descriptor != null ? descriptor.domain() : null;
        this.category = descriptor != null ? descriptor.category() : null;
        this.mode = descriptor != null ? descriptor.mode() : null;
        this.tactics = descriptor != null ? descriptor.tactics() : List.of();
        this.techniques = descriptor != null ? descriptor.techniques() : List.of();
        this.platforms = descriptor != null ? descriptor.platforms() : List.of();
        this.targets = descriptor != null ? descriptor.targets() : List.of();
        this.pack = descriptor != null ? descriptor.pack() : null;
        this.risk = descriptor != null ? descriptor.risk() : null;
        this.accessMode = descriptor != null ? descriptor.accessMode() : null;
        this.status = descriptor != null ? descriptor.status() : null;
        this.source = descriptor != null ? descriptor.source() : null;
        this.owner = descriptor != null ? descriptor.owner() : null;
        this.valid = valid;
        this.requiresExplicitApproval = descriptor != null && descriptor.requiresExplicitApproval();
        this.requiredTools = descriptor != null ? descriptor.requiredTools() : List.of();
        this.requiredSkills = descriptor != null ? descriptor.requiredSkills() : List.of();
        this.requiredFacts = descriptor != null ? descriptor.requiredFacts() : List.of();
        this.produces = descriptor != null ? descriptor.produces() : List.of();
        this.next = descriptor != null ? descriptor.next() : List.of();
        this.issues = issues == null ? List.of() : List.copyOf(issues);

        LinkedHashSet<String> searchableTags = new LinkedHashSet<>();
        add(searchableTags, domain);
        add(searchableTags, category);
        searchableTags.addAll(tactics);
        searchableTags.addAll(platforms);
        searchableTags.addAll(targets);
        add(searchableTags, pack);
        this.tags = List.copyOf(searchableTags);
    }

    public boolean isRuntimeEligible() {
        return valid && enabled && "published".equals(status);
    }

    private static void add(LinkedHashSet<String> values, String value) {
        if (value != null && !value.isBlank()) values.add(value);
    }

    public String getScope() { return scope; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public boolean isEnabled() { return enabled; }
    public List<String> getTags() { return tags; }
    public int getFileCount() { return fileCount; }
    public String getId() { return id; }
    public String getVersion() { return version; }
    public String getDomain() { return domain; }
    public String getCategory() { return category; }
    public String getMode() { return mode; }
    public List<String> getTactics() { return tactics; }
    public List<String> getTechniques() { return techniques; }
    public List<String> getPlatforms() { return platforms; }
    public List<String> getTargets() { return targets; }
    public String getPack() { return pack; }
    public String getRisk() { return risk; }
    public String getAccessMode() { return accessMode; }
    public String getStatus() { return status; }
    public String getSource() { return source; }
    public String getOwner() { return owner; }
    public boolean isValid() { return valid; }
    public boolean isRequiresExplicitApproval() { return requiresExplicitApproval; }
    public List<String> getRequiredTools() { return requiredTools; }
    public List<String> getRequiredSkills() { return requiredSkills; }
    public List<String> getRequiredFacts() { return requiredFacts; }
    public List<String> getProduces() { return produces; }
    public List<String> getNext() { return next; }
    public List<SkillValidationIssue> getIssues() { return issues; }
}
