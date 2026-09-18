package org.leo.web.service;

import org.leo.ai.service.LeoSkillsProvider;
import org.leo.ai.service.SkillInspection;
import org.leo.ai.service.SkillManifestService;
import org.leo.ai.service.SkillRegistryService;
import org.leo.core.util.ApiResponse;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Skill 写操作的应用边界。
 *
 * <p>Controller 只负责 HTTP 参数和响应转换；路径解析、并发保护、manifest
 * 校验、文件写入以及 catalog 缓存失效在这里保持一致，避免单项和批量接口
 * 各自维护一套略有差异的实现。
 */
@Service
public class SkillManagementService {

    private static final String SKILL_FILE = SkillManifestService.SKILL_FILE;
    private static final String MANIFEST_FILE = SkillManifestService.MANIFEST_FILE;
    private static final int MAX_BATCH_ITEMS = 500;

    private final SkillRegistryService skillRegistry;
    private final LeoSkillsProvider leoSkillsProvider;
    private final SkillManifestService manifestService;
    private final SkillOperationLock operationLock;

    public SkillManagementService(SkillRegistryService skillRegistry,
                                  LeoSkillsProvider leoSkillsProvider,
                                  SkillManifestService manifestService,
                                  SkillOperationLock operationLock) {
        this.skillRegistry = skillRegistry;
        this.leoSkillsProvider = leoSkillsProvider;
        this.manifestService = manifestService;
        this.operationLock = operationLock;
    }

    public OperationResult save(String scope, String name, String content, String manifest) {
        if (isBlank(scope)) return OperationResult.failure(ApiResponse.CODE_BAD_REQUEST, "scope 不能为空");
        if (isBlank(name)) return OperationResult.failure(ApiResponse.CODE_BAD_REQUEST, "name 不能为空");
        if (isBlank(content)) return OperationResult.failure(ApiResponse.CODE_BAD_REQUEST, "content 不能为空");
        if (isBlank(manifest)) return OperationResult.failure(ApiResponse.CODE_BAD_REQUEST, "manifest 不能为空");
        String normalizedScope = scope.trim();
        String normalizedName = name.trim();
        if (!SkillRegistryService.isValidSkillName(normalizedName)) {
            return OperationResult.failure(ApiResponse.CODE_BAD_REQUEST,
                    "name 包含非法字符（只允许字母、数字、连字符、下划线）");
        }

        Path skillDir;
        try {
            skillDir = resolveSkillDir(normalizedScope, normalizedName);
        } catch (IllegalArgumentException e) {
            return OperationResult.failure(ApiResponse.CODE_BAD_REQUEST, e.getMessage());
        }
        ReentrantLock lock = operationLock.lockFor(normalizedScope, normalizedName);
        lock.lock();
        try {
            SkillInspection inspection = manifestService.inspect(
                    normalizedScope, normalizedName, content, manifest);
            if (!inspection.valid()) {
                return OperationResult.failure(ApiResponse.CODE_BAD_REQUEST,
                        "skill 校验失败：" + SkillManifestService.summarizeErrors(inspection));
            }
            Files.createDirectories(skillDir);
            Files.writeString(skillDir.resolve(SKILL_FILE), content, StandardCharsets.UTF_8);
            Files.writeString(skillDir.resolve(MANIFEST_FILE), manifest, StandardCharsets.UTF_8);
            invalidateCatalog();
            return OperationResult.success("skill 保存成功");
        } catch (IOException e) {
            return OperationResult.failure(ApiResponse.CODE_ERROR, "skill 保存失败：" + e.getMessage());
        } finally {
            lock.unlock();
        }
    }

    public OperationResult delete(String scope, String name) {
        OperationResult result = deleteOne(scope, name);
        if (result.succeeded()) invalidateCatalog();
        return result;
    }

    public BatchDeleteResult deleteBatch(String scope, List<?> requestedNames) {
        if (isBlank(scope)) throw new IllegalArgumentException("scope 不能为空");
        if (requestedNames == null || requestedNames.isEmpty()) {
            throw new IllegalArgumentException("names 必须是非空数组");
        }
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (Object value : requestedNames) {
            if (!(value instanceof String name) || !SkillRegistryService.isValidSkillName(name)) {
                throw new IllegalArgumentException("names 包含非法 skill 名称");
            }
            names.add(name.trim());
        }
        if (names.size() > MAX_BATCH_ITEMS) {
            throw new IllegalArgumentException("单次最多处理 " + MAX_BATCH_ITEMS + " 个 skill");
        }
        String normalizedScope = scope.trim();
        SkillRegistryService.validateScope(normalizedScope);
        List<Map<String, Object>> results = new ArrayList<>();
        int deleted = 0;
        for (String name : names) {
            OperationResult result = deleteOne(normalizedScope, name);
            results.add(Map.of("name", name,
                    "status", result.succeeded() ? "deleted" : "failed",
                    "message", result.succeeded() ? "Skill 已删除" : result.message()));
            if (result.succeeded()) deleted++;
        }
        if (deleted > 0) invalidateCatalog();
        return new BatchDeleteResult(normalizedScope, names.size(), deleted, List.copyOf(results));
    }

    private OperationResult deleteOne(String scope, String name) {
        if (isBlank(scope)) return OperationResult.failure(ApiResponse.CODE_BAD_REQUEST, "scope 不能为空");
        if (isBlank(name)) return OperationResult.failure(ApiResponse.CODE_BAD_REQUEST, "name 不能为空");
        String normalizedScope = scope.trim();
        String normalizedName = name.trim();
        if (!SkillRegistryService.isValidSkillName(normalizedName)) {
            return OperationResult.failure(ApiResponse.CODE_BAD_REQUEST, "name 包含非法字符");
        }

        Path skillDir;
        try {
            skillDir = resolveSkillDir(normalizedScope, normalizedName);
        } catch (IllegalArgumentException e) {
            return OperationResult.failure(ApiResponse.CODE_BAD_REQUEST, e.getMessage());
        }
        ReentrantLock lock = operationLock.lockFor(normalizedScope, normalizedName);
        lock.lock();
        try {
            if (!Files.exists(skillDir)) {
                return OperationResult.failure(ApiResponse.CODE_NOT_FOUND,
                        "skill 不存在：" + scope + "/" + name);
            }
            deleteRecursively(skillDir);
            return OperationResult.success("skill 删除成功");
        } catch (IOException e) {
            return OperationResult.failure(ApiResponse.CODE_ERROR, "skill 删除失败：" + e.getMessage());
        } finally {
            lock.unlock();
            operationLock.removeIfUnused(normalizedScope, normalizedName, lock);
        }
    }

    public ToggleResult toggle(String scope, String name, boolean enabled) {
        if (isBlank(scope)) {
            return ToggleResult.failed(name, ApiResponse.CODE_BAD_REQUEST, "scope 不能为空");
        }
        String normalizedName = name == null ? null : name.trim();
        if (isBlank(normalizedName)) {
            return ToggleResult.failed(name, ApiResponse.CODE_BAD_REQUEST, "name 不能为空");
        }
        if (!SkillRegistryService.isValidSkillName(normalizedName)) {
            return ToggleResult.failed(name, ApiResponse.CODE_BAD_REQUEST, "name 包含非法字符");
        }
        String normalizedScope = scope.trim();
        try {
            SkillRegistryService.validateScope(normalizedScope);
        } catch (IllegalArgumentException e) {
            return ToggleResult.failed(normalizedName, ApiResponse.CODE_BAD_REQUEST, e.getMessage());
        }
        Map<String, SkillInspection> catalog = catalogByName(normalizedScope);
        ToggleResult result = toggleOne(
                normalizedScope, normalizedName, enabled, catalog.get(normalizedName));
        if (result.changed()) invalidateCatalog();
        return result;
    }

    public BatchToggleResult toggleBatch(String scope, List<String> requestedNames, boolean enabled) {
        String normalizedScope = scope == null ? null : scope.trim();
        LinkedHashSet<String> names = new LinkedHashSet<>();
        if (requestedNames != null) {
            for (String name : requestedNames) {
                if (name != null && !name.isBlank()) names.add(name.trim());
            }
        }
        if (names.size() > MAX_BATCH_ITEMS) {
            throw new IllegalArgumentException("单次最多处理 " + MAX_BATCH_ITEMS + " 个 skill");
        }
        Map<String, SkillInspection> catalog = catalogByName(normalizedScope);
        List<ToggleResult> results = new ArrayList<>();
        int changed = 0;
        int unchanged = 0;
        int failed = 0;
        for (String name : names) {
            ToggleResult result = toggleOne(normalizedScope, name, enabled, catalog.get(name));
            results.add(result);
            if (result.changed()) changed++;
            else if (result.failed()) failed++;
            else unchanged++;
        }
        if (changed > 0) invalidateCatalog();
        return new BatchToggleResult(normalizedScope, enabled, names.size(), changed,
                unchanged, failed, results);
    }

    private ToggleResult toggleOne(String scope, String name, boolean enabled,
                                   SkillInspection catalogInspection) {
        if (isBlank(scope)) return ToggleResult.failed(name, ApiResponse.CODE_BAD_REQUEST, "scope 不能为空");
        if (isBlank(name)) return ToggleResult.failed(name, ApiResponse.CODE_BAD_REQUEST, "name 不能为空");
        String normalizedScope = scope.trim();
        String normalizedName = name.trim();
        if (!SkillRegistryService.isValidSkillName(normalizedName)) {
            return ToggleResult.failed(name, ApiResponse.CODE_BAD_REQUEST, "name 包含非法字符");
        }

        Path skillDir;
        try {
            skillDir = resolveSkillDir(normalizedScope, normalizedName);
        } catch (IllegalArgumentException e) {
            return ToggleResult.failed(normalizedName, ApiResponse.CODE_BAD_REQUEST, e.getMessage());
        }
        Path skillFile = skillDir.resolve(SKILL_FILE);
        Path manifestFile = skillDir.resolve(MANIFEST_FILE);
        if (!Files.isRegularFile(skillFile) || !Files.isRegularFile(manifestFile)) {
            return ToggleResult.failed(normalizedName, ApiResponse.CODE_NOT_FOUND,
                    "skill 或 manifest 不存在：" + scope + "/" + name);
        }

        ReentrantLock lock = operationLock.lockFor(normalizedScope, normalizedName);
        lock.lock();
        try {
            String skillContent = Files.readString(skillFile, StandardCharsets.UTF_8);
            String original = Files.readString(manifestFile, StandardCharsets.UTF_8);
            SkillInspection originalInspection = manifestService.inspect(
                    normalizedScope, normalizedName, skillContent, original);
            if (enabled) {
                if (catalogInspection == null) {
                    return ToggleResult.failed(normalizedName, ApiResponse.CODE_BAD_REQUEST,
                            "skill 尚未进入 catalog");
                }
                if (!catalogInspection.valid()) {
                    return ToggleResult.failed(normalizedName, ApiResponse.CODE_BAD_REQUEST,
                            "skill catalog 校验失败：" + SkillManifestService.summarizeErrors(catalogInspection));
                }
                if (!originalInspection.valid()) {
                    return ToggleResult.failed(normalizedName, ApiResponse.CODE_BAD_REQUEST,
                            "skill 校验失败：" + SkillManifestService.summarizeErrors(originalInspection));
                }
            }
            if (originalInspection.descriptor() != null
                    && originalInspection.descriptor().enabled() == enabled) {
                return ToggleResult.unchanged(normalizedName,
                        enabled ? "skill 已处于启用状态" : "skill 已处于禁用状态");
            }
            String updated = manifestService.setEnabled(original, enabled);
            SkillInspection updatedInspection = manifestService.inspect(
                    normalizedScope, normalizedName, skillContent, updated);
            if (enabled && !updatedInspection.valid()) {
                return ToggleResult.failed(normalizedName, ApiResponse.CODE_BAD_REQUEST,
                        "skill 校验失败：" + SkillManifestService.summarizeErrors(updatedInspection));
            }
            Files.writeString(manifestFile, updated, StandardCharsets.UTF_8);
            String message = enabled ? "skill 已启用" : "skill 已禁用";
            if (!enabled && !updatedInspection.valid()) message += "；其余 manifest 错误仍需修复";
            return ToggleResult.changed(normalizedName, message);
        } catch (IllegalArgumentException e) {
            return ToggleResult.failed(normalizedName, ApiResponse.CODE_BAD_REQUEST,
                    "manifest 无法修改：" + e.getMessage());
        } catch (IOException e) {
            return ToggleResult.failed(normalizedName, ApiResponse.CODE_ERROR,
                    "操作失败：" + e.getMessage());
        } finally {
            lock.unlock();
        }
    }

    private Map<String, SkillInspection> catalogByName(String scope) {
        if (isBlank(scope)) return Map.of();
        Map<String, SkillInspection> result = new LinkedHashMap<>();
        for (SkillInspection inspection : skillRegistry.health(scope.trim())) {
            result.put(inspection.name(), inspection);
        }
        return result;
    }

    private Path resolveSkillDir(String scope, String name) {
        Path skillsRoot = skillRegistry.getSkillsRoot(scope);
        Path skillDir = skillsRoot.resolve(name).normalize();
        if (!skillDir.startsWith(skillsRoot)) throw new IllegalArgumentException("路径非法");
        return skillDir;
    }

    private void invalidateCatalog() {
        skillRegistry.invalidate();
        leoSkillsProvider.invalidate();
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (Files.isDirectory(path)) {
            try (var children = Files.list(path)) {
                for (Path child : children.toList()) deleteRecursively(child);
            }
        }
        Files.delete(path);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public record OperationResult(int code, String message) {
        static OperationResult success(String message) { return new OperationResult(ApiResponse.CODE_SUCCESS, message); }
        static OperationResult failure(int code, String message) { return new OperationResult(code, message); }
        public boolean succeeded() { return code == ApiResponse.CODE_SUCCESS; }
    }

    public record BatchDeleteResult(String scope, int requested, int deleted,
                                    List<Map<String, Object>> results) {
        public int failed() { return requested - deleted; }

        public Map<String, Object> toMap() {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("scope", scope); data.put("requested", requested);
            data.put("changed", deleted); data.put("deleted", deleted);
            data.put("unchanged", 0); data.put("failed", failed());
            data.put("results", results);
            return data;
        }
    }

    public record ToggleResult(String name, String status, String message, int errorCode) {
        static ToggleResult changed(String name, String message) { return new ToggleResult(name, "changed", message, ApiResponse.CODE_SUCCESS); }
        static ToggleResult unchanged(String name, String message) { return new ToggleResult(name, "unchanged", message, ApiResponse.CODE_SUCCESS); }
        static ToggleResult failed(String name, int code, String message) { return new ToggleResult(name, "failed", message, code); }
        public boolean changed() { return "changed".equals(status); }
        public boolean failed() { return "failed".equals(status); }
        public Map<String, Object> toMap() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("name", name); result.put("status", status); result.put("message", message);
            return result;
        }
    }

    public record BatchToggleResult(String scope, boolean enabled, int requested, int changed,
                                    int unchanged, int failed, List<ToggleResult> results) {
        public Map<String, Object> toMap() {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("scope", scope); data.put("enabled", enabled); data.put("requested", requested);
            data.put("changed", changed); data.put("unchanged", unchanged); data.put("failed", failed);
            data.put("results", results.stream().map(ToggleResult::toMap).toList());
            return data;
        }
    }
}
