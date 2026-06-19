package org.leo.ai.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Skill 的打包导出 / 解包导入。
 *
 * <p>导出格式：
 * <ul>
 *   <li>单条 .skill 文件：zip 包，内容平铺到根（SKILL.md 在根目录）</li>
 *   <li>批量 zip 文件：内含多个 {skillName}/ 目录，每个目录下是该 skill 的所有文件</li>
 * </ul>
 *
 * <p>导入格式自动识别：
 * <ul>
 *   <li>zip 根有 SKILL.md → 视为单 skill，目标 name 由调用方提供（如上传文件名）</li>
 *   <li>zip 根全是目录且每个子目录有 SKILL.md → 批量导入</li>
 * </ul>
 *
 * <p>所有 zip entry 名称都做严格校验：禁止 {@code ..}、绝对路径、NUL，
 * 解析后必须落在目标 skill 目录内（防 zip slip）。
 */
@Service
public class SkillExportService {

    private static final Logger log = LoggerFactory.getLogger(SkillExportService.class);

    private static final String SKILL_FILE = "SKILL.md";

    /** 单个 zip entry 最大尺寸，与 SkillFileService.MAX_FILE_BYTES 一致。 */
    private static final long MAX_ENTRY_BYTES = SkillFileService.MAX_FILE_BYTES;
    /** 单 skill 解压总尺寸上限，防 zip bomb。 */
    private static final long MAX_SKILL_BYTES = SkillFileService.MAX_SKILL_BYTES;
    /** 单 skill 文件数上限。 */
    private static final int  MAX_FILES       = SkillFileService.MAX_FILES;

    public enum ConflictPolicy {
        SKIP, OVERWRITE, RENAME;

        public static ConflictPolicy parse(String s) {
            if (s == null) return RENAME;
            return switch (s.toLowerCase()) {
                case "skip" -> SKIP;
                case "overwrite" -> OVERWRITE;
                default -> RENAME;
            };
        }
    }

    /**
     * 把单个 skill 目录写为 .skill (zip) 流。entry 名直接是相对 skill 根的路径，
     * 不带 skill 名作为前缀，符合 Anthropic .skill 约定。
     */
    public void exportSkill(Path skillDir, OutputStream out) throws IOException {
        try (ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(out))) {
            writeSkillDirToZip(skillDir, "", zos);
        }
    }

    /**
     * 把多个 skill 目录打包到一个 zip，entry 名带 {skillName}/ 前缀作为顶层目录。
     */
    public void exportSkills(List<NamedSkill> skills, OutputStream out) throws IOException {
        try (ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(out))) {
            for (NamedSkill ns : skills) {
                writeSkillDirToZip(ns.dir(), ns.name() + "/", zos);
            }
        }
    }

    /**
     * 解包导入。返回每个 skill 的处理结果。
     *
     * <p>调用方负责：
     * <ul>
     *   <li>resolveSkillRoot(scope) 提供目标 scope 的根目录</li>
     *   <li>调用前后调用 lockFor 保证并发安全</li>
     *   <li>导入完成后调用 invalidate 刷新缓存</li>
     * </ul>
     *
     * @param defaultName 当 zip 是单 skill（根有 SKILL.md）时，作为目标 name。
     *                    若为 null 或空，单 skill 会被拒绝（无法决定目录名）。
     */
    public List<ImportResult> importSkills(MultipartFile file,
                                           Path scopeRoot,
                                           String defaultName,
                                           ConflictPolicy policy) throws IOException {
        Path tempRoot = Files.createTempDirectory("skill-import-");
        try {
            // 阶段 1：解压到临时目录，按顶层目录拆分。
            // tempRoot/{skillName}/<files>
            extractToTemp(file, tempRoot, defaultName);

            // 阶段 2：原子化移动到目标 scopeRoot/{name}/，按 policy 处理冲突
            return commitFromTemp(tempRoot, scopeRoot, policy);
        } finally {
            deleteRecursively(tempRoot);
        }
    }

    // ── 内部实现 ──────────────────────────────────────────────────────────

    private void writeSkillDirToZip(Path skillDir, String prefix, ZipOutputStream zos) throws IOException {
        if (!Files.isDirectory(skillDir)) return;
        Files.walkFileTree(skillDir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (attrs.isSymbolicLink()) return FileVisitResult.CONTINUE;
                String rel = skillDir.relativize(file).toString().replace('\\', '/');
                ZipEntry entry = new ZipEntry(prefix + rel);
                entry.setTime(attrs.lastModifiedTime().toMillis());
                zos.putNextEntry(entry);
                Files.copy(file, zos);
                zos.closeEntry();
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void extractToTemp(MultipartFile file, Path tempRoot, String defaultName) throws IOException {
        // 先 peek 一下顶层结构：是单 skill（根有 SKILL.md）还是多 skill（顶层都是目录）
        boolean rootHasSkillMd = peekRootSkillMd(file);

        try (ZipInputStream zis = new ZipInputStream(file.getInputStream())) {
            ZipEntry entry;
            long totalRead = 0L;
            int  fileCount = 0;
            String skillNameForRoot = null;
            if (rootHasSkillMd) {
                if (defaultName == null || defaultName.isBlank()) {
                    throw new SkillImportException("zip 根含 SKILL.md 但未指定目标 name");
                }
                if (!isSafeSkillName(defaultName)) {
                    throw new SkillImportException("name 包含非法字符");
                }
                skillNameForRoot = defaultName.trim();
                Files.createDirectories(tempRoot.resolve(skillNameForRoot));
            }

            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) { zis.closeEntry(); continue; }
                String name = entry.getName().replace('\\', '/');
                validateEntryName(name);

                Path target;
                if (rootHasSkillMd) {
                    // 单 skill：所有 entry 都放到 tempRoot/{skillNameForRoot}/<name>
                    target = tempRoot.resolve(skillNameForRoot).resolve(name).normalize();
                } else {
                    // 批量：顶层就是 skill 目录名
                    int slash = name.indexOf('/');
                    if (slash <= 0) {
                        // 顶层平铺文件，但又不是 SKILL.md，无法归属
                        log.warn("[SkillImport] 跳过无法归属的 entry: {}", name);
                        zis.closeEntry();
                        continue;
                    }
                    String topName = name.substring(0, slash);
                    if (!isSafeSkillName(topName)) {
                        throw new SkillImportException("skill 名含非法字符: " + topName);
                    }
                    target = tempRoot.resolve(name).normalize();
                }

                if (!target.startsWith(tempRoot.normalize())) {
                    throw new SkillImportException("非法路径: " + name);
                }

                Path parent = target.getParent();
                if (parent != null) Files.createDirectories(parent);

                long entrySize = copyWithLimit(zis, target, MAX_ENTRY_BYTES);
                totalRead += entrySize;
                fileCount += 1;
                if (totalRead > MAX_SKILL_BYTES * 50) { // 50 个 skill 上限
                    throw new SkillImportException("导入文件总大小超出限制");
                }
                if (fileCount > MAX_FILES * 50) {
                    throw new SkillImportException("导入文件数超出限制");
                }
                zis.closeEntry();
            }
        }
    }

    /** 复制 zip entry 到文件，单文件超限抛错（防 zip bomb）。 */
    private long copyWithLimit(InputStream in, Path target, long limit) throws IOException {
        try (OutputStream out = Files.newOutputStream(target)) {
            byte[] buf = new byte[8192];
            long total = 0L;
            int n;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > limit) {
                    throw new SkillImportException("zip entry 超出单文件大小限制");
                }
                out.write(buf, 0, n);
            }
            return total;
        }
    }

    /**
     * 第一遍 peek：检查 zip 根是否直接有 SKILL.md。
     * 因为 ZipInputStream 一次性消费，需要 file.getInputStream() 重新读取。
     */
    private boolean peekRootSkillMd(MultipartFile file) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(file.getInputStream())) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName().replace('\\', '/');
                if (!entry.isDirectory() && name.equalsIgnoreCase(SKILL_FILE)) {
                    return true;
                }
                zis.closeEntry();
            }
        }
        return false;
    }

    private List<ImportResult> commitFromTemp(Path tempRoot, Path scopeRoot, ConflictPolicy policy) throws IOException {
        List<ImportResult> results = new ArrayList<>();
        if (!Files.isDirectory(tempRoot)) return results;

        Files.createDirectories(scopeRoot);
        try (var stream = Files.list(tempRoot)) {
            for (Path skillTmpDir : stream.toList()) {
                if (!Files.isDirectory(skillTmpDir)) continue;
                String name = skillTmpDir.getFileName().toString();
                if (!isSafeSkillName(name)) {
                    results.add(ImportResult.failed(name, "skill 名含非法字符"));
                    continue;
                }
                // SKILL.md 必须存在
                if (!Files.isRegularFile(skillTmpDir.resolve(SKILL_FILE))) {
                    results.add(ImportResult.failed(name, "缺少 SKILL.md"));
                    continue;
                }

                Path target = scopeRoot.resolve(name).normalize();
                if (!target.startsWith(scopeRoot)) {
                    results.add(ImportResult.failed(name, "路径越界"));
                    continue;
                }

                String finalName = name;
                ImportResult.Status status = ImportResult.Status.IMPORTED;

                if (Files.exists(target)) {
                    switch (policy) {
                        case SKIP -> {
                            results.add(ImportResult.of(name, name, ImportResult.Status.SKIPPED));
                            continue;
                        }
                        case OVERWRITE -> {
                            deleteRecursively(target);
                            status = ImportResult.Status.OVERWRITTEN;
                        }
                        case RENAME -> {
                            String renamed = generateRenamed(scopeRoot, name);
                            target = scopeRoot.resolve(renamed).normalize();
                            finalName = renamed;
                            status = ImportResult.Status.RENAMED;
                        }
                    }
                }

                try {
                    Files.move(skillTmpDir, target, StandardCopyOption.ATOMIC_MOVE);
                } catch (Exception atomicFail) {
                    // 跨文件系统时 ATOMIC_MOVE 不支持，退化为非原子但等价的递归复制
                    copyRecursively(skillTmpDir, target);
                    deleteRecursively(skillTmpDir);
                }
                results.add(ImportResult.of(name, finalName, status));
            }
        }
        return results;
    }

    private static String generateRenamed(Path scopeRoot, String original) {
        String base = original + "_imported_" + System.currentTimeMillis();
        Path candidate = scopeRoot.resolve(base);
        int suffix = 1;
        while (Files.exists(candidate)) {
            candidate = scopeRoot.resolve(base + "_" + suffix++);
        }
        return candidate.getFileName().toString();
    }

    private static void validateEntryName(String name) {
        if (name == null || name.isBlank()) throw new SkillImportException("entry 名为空");
        if (name.contains("..")) throw new SkillImportException("entry 名含 .. : " + name);
        if (name.startsWith("/")) throw new SkillImportException("entry 名为绝对路径: " + name);
        if (name.indexOf('\0') >= 0) throw new SkillImportException("entry 名含 NUL");
    }

    private static boolean isSafeSkillName(String name) {
        return name != null && name.matches("[A-Za-z0-9_-]+");
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) return;
        if (Files.isDirectory(path)) {
            Files.walkFileTree(path, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }
                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    Files.delete(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        } else {
            Files.delete(path);
        }
    }

    private static void copyRecursively(Path src, Path dst) throws IOException {
        Files.walkFileTree(src, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Files.createDirectories(dst.resolve(src.relativize(dir).toString()));
                return FileVisitResult.CONTINUE;
            }
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.copy(file, dst.resolve(src.relativize(file).toString()),
                        StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    public record NamedSkill(String name, Path dir) {}

    public record ImportResult(String originalName, String finalName, Status status, String message) {
        public enum Status { IMPORTED, OVERWRITTEN, RENAMED, SKIPPED, FAILED }

        public static ImportResult of(String original, String finalName, Status status) {
            return new ImportResult(original, finalName, status, null);
        }
        public static ImportResult failed(String original, String message) {
            return new ImportResult(original, null, Status.FAILED, message);
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("originalName", originalName);
            m.put("finalName", finalName);
            m.put("status", status.name().toLowerCase());
            if (message != null) m.put("message", message);
            return m;
        }
    }

    public static class SkillImportException extends RuntimeException {
        public SkillImportException(String msg) { super(msg); }
    }
}
