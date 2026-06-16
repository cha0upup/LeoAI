package org.leo.ai.service;

import dev.langchain4j.skills.FileSystemSkill;
import dev.langchain4j.skills.FileSystemSkillLoader;
import dev.langchain4j.skills.Skill;
import dev.langchain4j.skills.Skills;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * LeoAI Skills 提供者：基于 langchain4j-skills 标准模块，从 VFS 加载 skill 内容，
 * 按 enabled 字段过滤，并缓存 {@link Skills} 实例供 system prompt 格式化和工具调用使用。
 *
 * <p>缓存策略：
 * <ul>
 *   <li>首次访问时懒加载，建立 per-scope 的 {@link Skills} 缓存。</li>
 *   <li>调用 {@link #invalidate()} 后下次访问重新从 VFS 构建（由 {@link org.leo.web.controller.platform.skill}
 *       在保存/删除/toggle 后触发）。</li>
 * </ul>
 *
 * <p>enabled 过滤委托给 {@link SkillRegistryService#listSkills(String)}，
 * 保证与 web 管理 API 的语义一致（默认启用，只有明确 enabled:false 时禁用）。
 */
@Component
public class LeoSkillsProvider {

    private static final Logger log = LoggerFactory.getLogger(LeoSkillsProvider.class);

    private final SkillRegistryService skillRegistry;

    /** per-scope 缓存，volatile 保证可见性，synchronized 块保证懒建唯一性 */
    private volatile Skills puppetNodeSkills;
    private volatile Skills platformSkills;

    public LeoSkillsProvider(SkillRegistryService skillRegistry) {
        this.skillRegistry = skillRegistry;
    }

    // ── 公开 API ──────────────────────────────────────────────────────────────

    /**
     * 获取指定 scope 的 {@link Skills} 实例（懒加载、缓存）。
     *
     * @param scope {@link SkillRegistryService#SCOPE_PUPPET_NODE} 或 {@link SkillRegistryService#SCOPE_PLATFORM}
     * @return 当前 scope 下所有已启用 skill 的 {@link Skills} 实例
     */
    public Skills getSkills(String scope) {
        if (SkillRegistryService.SCOPE_PUPPET_NODE.equals(scope)) {
            Skills s = puppetNodeSkills;
            if (s == null) {
                synchronized (this) {
                    if (puppetNodeSkills == null) {
                        puppetNodeSkills = buildSkills(scope);
                    }
                    s = puppetNodeSkills;
                }
            }
            return s;
        } else {
            Skills s = platformSkills;
            if (s == null) {
                synchronized (this) {
                    if (platformSkills == null) {
                        platformSkills = buildSkills(scope);
                    }
                    s = platformSkills;
                }
            }
            return s;
        }
    }

    /**
     * 清除所有 scope 的 {@link Skills} 缓存，下次访问时重新从 VFS 加载。
     * 由 SkillController 在 save / delete / toggle 后调用。
     */
    public synchronized void invalidate() {
        puppetNodeSkills = null;
        platformSkills   = null;
    }

    // ── 内部逻辑 ──────────────────────────────────────────────────────────────

    private Skills buildSkills(String scope) {
        Path skillsRoot = skillRegistry.getSkillsRoot(scope);
        if (!Files.isDirectory(skillsRoot)) {
            log.warn("[LeoSkillsProvider] skills 目录不存在: {}", skillsRoot);
            return Skills.from(Collections.emptyList());
        }

        // 从 SkillRegistryService 获取已启用的 skill 名称（利用其 TTL 缓存和 enabled 解析逻辑）
        Set<String> enabledNames = skillRegistry.listSkills(scope).stream()
                .map(SkillMeta::getName)
                .collect(Collectors.toSet());

        if (enabledNames.isEmpty()) {
            return Skills.from(Collections.emptyList());
        }

        List<FileSystemSkill> all = FileSystemSkillLoader.loadSkills(skillsRoot);
        List<FileSystemSkill> enabled = all.stream()
                .filter(s -> enabledNames.contains(s.name()))
                .collect(Collectors.toList());
        log.debug("[LeoSkillsProvider] scope={} 加载 {}/{} 个已启用 skill",
                scope, enabled.size(), all.size());
        return Skills.from(enabled);
    }
}
