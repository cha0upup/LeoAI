package org.leo.ai.service;

import java.util.Collections;
import java.util.List;

/**
 * Skill 元数据：从 SKILL.md frontmatter 解析的轻量描述对象。
 *
 * <p>tags 来自 frontmatter 的 {@code tags} 字段，支持 YAML 数组或逗号分隔字符串两种写法；
 * 解析逻辑见 {@link SkillRegistryService#scanScope(String)}。
 * 始终为不可变列表，可能为空但不会为 null。
 */
public class SkillMeta {

    private final String       scope;
    private final String       name;
    private final String       description;
    private final boolean      enabled;
    private final List<String> tags;
    private final int          fileCount;

    public SkillMeta(String scope, String name, String description, boolean enabled) {
        this(scope, name, description, enabled, Collections.emptyList(), 1);
    }

    public SkillMeta(String scope, String name, String description, boolean enabled,
                     List<String> tags) {
        this(scope, name, description, enabled, tags, 1);
    }

    public SkillMeta(String scope, String name, String description, boolean enabled,
                     List<String> tags, int fileCount) {
        this.scope       = scope;
        this.name        = name;
        this.description = description;
        this.enabled     = enabled;
        this.tags        = tags == null ? Collections.emptyList()
                : Collections.unmodifiableList(tags);
        this.fileCount   = Math.max(fileCount, 1);
    }

    public String       getScope()       { return scope; }
    public String       getName()        { return name; }
    public String       getDescription() { return description; }
    public boolean      isEnabled()      { return enabled; }
    public List<String> getTags()        { return tags; }
    public int          getFileCount()   { return fileCount; }

    @Override
    public String toString() {
        return "SkillMeta{scope='" + scope + "', name='" + name
                + "', description='" + description + "', enabled=" + enabled
                + ", tags=" + tags + ", fileCount=" + fileCount + '}';
    }
}
