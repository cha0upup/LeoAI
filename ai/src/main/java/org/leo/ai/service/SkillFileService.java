package org.leo.ai.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Skill 目录内的文件级操作：列树、读、写、删、移动。
 *
 * <p>所有方法对 {@code relativePath} 做严格校验：
 * <ul>
 *   <li>不能为空、不能含 NUL、不能含 {@code ..}、不能是绝对路径</li>
 *   <li>{@link Path#normalize()} 后仍必须落在 skill 根目录内</li>
 * </ul>
 *
 * <p>文件大小限制（默认）：单文件 10MB，单 skill 总和 50MB，文件数 200。
 * 写入前都会校验，超限直接抛 {@link SkillFileException}。
 */
@Service
public class SkillFileService {

    private static final Logger log = LoggerFactory.getLogger(SkillFileService.class);

    public static final long MAX_FILE_BYTES   = 10L * 1024 * 1024;
    public static final long MAX_SKILL_BYTES  = 50L * 1024 * 1024;
    public static final int  MAX_FILES        = 200;
    public static final int  MAX_DEPTH        = 10;

    /** 文本扩展名白名单（小写，无前导点）。其他扩展名视为二进制。 */
    private static final Set<String> TEXT_EXTENSIONS = Set.of(
            "md", "markdown", "txt", "text", "rst",
            "py", "js", "mjs", "cjs", "ts", "tsx", "jsx", "vue", "svelte",
            "java", "kt", "scala", "go", "rs", "rb", "php", "swift", "c", "cc", "cpp", "h", "hpp",
            "json", "yaml", "yml", "toml", "ini", "conf", "properties", "env",
            "sh", "bash", "zsh", "fish", "ps1", "bat", "cmd",
            "sql", "graphql", "proto",
            "html", "htm", "xml", "css", "scss", "sass", "less",
            "log", "csv", "tsv", "diff", "patch",
            "gitignore", "dockerignore", "editorconfig"
    );

    /** SKILL.md 是必需文件，禁止删除。 */
    public static final String SKILL_FILE = "SKILL.md";

    /**
     * 列出 skill 目录下所有文件（深度限制 {@link #MAX_DEPTH}），按路径升序。
     *
     * <p>跳过隐藏文件（点号开头）和符号链接。
     */
    public List<FileNode> listFiles(Path skillDir) throws IOException {
        if (!Files.isDirectory(skillDir)) return List.of();
        List<FileNode> nodes = new ArrayList<>();
        Files.walkFileTree(skillDir, java.util.EnumSet.noneOf(java.nio.file.FileVisitOption.class),
                MAX_DEPTH, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (attrs.isSymbolicLink()) return FileVisitResult.CONTINUE;
                String name = file.getFileName().toString();
                if (name.startsWith(".")) return FileVisitResult.CONTINUE;
                String rel = skillDir.relativize(file).toString().replace('\\', '/');
                nodes.add(new FileNode(rel, attrs.size(), isTextPath(rel),
                        attrs.lastModifiedTime().toMillis()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (dir.equals(skillDir)) return FileVisitResult.CONTINUE;
                String name = dir.getFileName().toString();
                if (name.startsWith(".")) return FileVisitResult.SKIP_SUBTREE;
                return FileVisitResult.CONTINUE;
            }
        });
        nodes.sort(Comparator.comparing(FileNode::path));
        return nodes;
    }

    /**
     * 读取单个文件。返回内容 + 编码标记（"text" 或 "base64"）+ 大小。
     */
    public Map<String, Object> readFile(Path skillDir, String relativePath) throws IOException {
        Path target = resolveSafe(skillDir, relativePath);
        if (!Files.isRegularFile(target)) {
            throw new SkillFileException("文件不存在：" + relativePath);
        }
        long size = Files.size(target);
        if (size > MAX_FILE_BYTES) {
            throw new SkillFileException("文件超出大小限制（" + (MAX_FILE_BYTES / 1024 / 1024) + "MB）");
        }
        byte[] bytes = Files.readAllBytes(target);
        boolean asText = isTextPath(relativePath) && looksLikeText(bytes);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("path", relativePath);
        result.put("size", size);
        result.put("encoding", asText ? "text" : "base64");
        result.put("content", asText
                ? new String(bytes, StandardCharsets.UTF_8)
                : java.util.Base64.getEncoder().encodeToString(bytes));
        return result;
    }

    /**
     * 写入单个文件，自动创建父目录。content 长度按 encoding 解释为字节。
     *
     * @param encoding "text" 或 "base64"；其他值抛 SkillFileException
     */
    public void writeFile(Path skillDir, String relativePath, String content, String encoding) throws IOException {
        if (content == null) content = "";
        if (encoding == null || encoding.isBlank()) encoding = "text";

        byte[] bytes;
        switch (encoding.toLowerCase(Locale.ROOT)) {
            case "text" -> bytes = content.getBytes(StandardCharsets.UTF_8);
            case "base64" -> {
                try {
                    bytes = java.util.Base64.getDecoder().decode(content);
                } catch (IllegalArgumentException e) {
                    throw new SkillFileException("base64 解码失败");
                }
            }
            default -> throw new SkillFileException("不支持的 encoding: " + encoding);
        }

        if (bytes.length > MAX_FILE_BYTES) {
            throw new SkillFileException("文件超出大小限制（" + (MAX_FILE_BYTES / 1024 / 1024) + "MB）");
        }

        Path target = resolveSafe(skillDir, relativePath);

        // 写入前估算 skill 总大小（含本次新增/覆盖）
        long currentTotal = totalSize(skillDir);
        long existing = Files.isRegularFile(target) ? Files.size(target) : 0L;
        long projected = currentTotal - existing + bytes.length;
        if (projected > MAX_SKILL_BYTES) {
            throw new SkillFileException("skill 总大小超出限制（" + (MAX_SKILL_BYTES / 1024 / 1024) + "MB）");
        }
        if (!Files.isRegularFile(target) && countFiles(skillDir) >= MAX_FILES) {
            throw new SkillFileException("skill 文件数已达上限（" + MAX_FILES + "）");
        }

        Path parent = target.getParent();
        if (parent != null) Files.createDirectories(parent);
        Files.write(target, bytes);
    }

    /**
     * 删除单个文件。SKILL.md 禁止删除（应整 skill 删除）。
     * 文件不存在不报错。
     */
    public void deleteFile(Path skillDir, String relativePath) throws IOException {
        if (SKILL_FILE.equalsIgnoreCase(relativePath.replace('\\', '/'))) {
            throw new SkillFileException("SKILL.md 不可删除（请使用整 skill 删除接口）");
        }
        Path target = resolveSafe(skillDir, relativePath);
        if (Files.isDirectory(target)) {
            // 递归删除目录及其下所有文件
            Files.walkFileTree(target, new SimpleFileVisitor<>() {
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
            Files.deleteIfExists(target);
        }
    }

    /**
     * 重命名/移动文件或目录。SKILL.md 禁止重命名。
     */
    public void moveFile(Path skillDir, String fromPath, String toPath) throws IOException {
        if (SKILL_FILE.equalsIgnoreCase(fromPath.replace('\\', '/'))) {
            throw new SkillFileException("SKILL.md 不可重命名");
        }
        Path src = resolveSafe(skillDir, fromPath);
        Path dst = resolveSafe(skillDir, toPath);
        if (!Files.exists(src)) throw new SkillFileException("源文件不存在：" + fromPath);
        if (Files.exists(dst))  throw new SkillFileException("目标已存在：" + toPath);
        Path dstParent = dst.getParent();
        if (dstParent != null) Files.createDirectories(dstParent);
        Files.move(src, dst);
    }

    // ── 工具方法 ──────────────────────────────────────────────────────────

    /**
     * 解析相对路径为绝对路径，保证落在 skillDir 内。
     */
    public static Path resolveSafe(Path skillDir, String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            throw new SkillFileException("path 不能为空");
        }
        if (relativePath.contains(" ")) {
            throw new SkillFileException("path 含非法字符");
        }
        String norm = relativePath.replace('\\', '/').trim();
        if (norm.startsWith("/")) {
            throw new SkillFileException("path 必须为相对路径");
        }
        // 拒绝 .. 段，避免穿越
        for (String seg : norm.split("/")) {
            if (seg.equals("..")) throw new SkillFileException("path 含非法段 ..");
        }
        Path target = skillDir.resolve(norm).normalize();
        if (!target.startsWith(skillDir.normalize())) {
            throw new SkillFileException("path 越界");
        }
        return target;
    }

    /**
     * 按扩展名判定是否为文本文件（无扩展名时按文件名匹配 .gitignore 等）。
     */
    public static boolean isTextPath(String relativePath) {
        String name = relativePath;
        int slash = name.lastIndexOf('/');
        if (slash >= 0) name = name.substring(slash + 1);
        String lower = name.toLowerCase(Locale.ROOT);
        if (TEXT_EXTENSIONS.contains(lower)) return true;
        int dot = lower.lastIndexOf('.');
        if (dot < 0) return false;
        return TEXT_EXTENSIONS.contains(lower.substring(dot + 1));
    }

    /**
     * 启发式判断字节流是否像文本：前 8KB 不含 NUL 字节即视为文本。
     */
    private static boolean looksLikeText(byte[] bytes) {
        int len = Math.min(bytes.length, 8192);
        for (int i = 0; i < len; i++) {
            if (bytes[i] == 0) return false;
        }
        return true;
    }

    private static long totalSize(Path skillDir) throws IOException {
        if (!Files.isDirectory(skillDir)) return 0L;
        long[] sum = {0L};
        Files.walkFileTree(skillDir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                sum[0] += attrs.size();
                return FileVisitResult.CONTINUE;
            }
        });
        return sum[0];
    }

    private static int countFiles(Path skillDir) throws IOException {
        if (!Files.isDirectory(skillDir)) return 0;
        int[] cnt = {0};
        Files.walkFileTree(skillDir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                cnt[0]++;
                return FileVisitResult.CONTINUE;
            }
        });
        return cnt[0];
    }

    /**
     * 文件树节点。path 为相对 skill 目录的 POSIX 风格路径（用 / 分隔）。
     */
    public record FileNode(String path, long size, boolean isText, long mtime) {}

    /**
     * 表示文件操作的业务异常（路径越界、超限、SKILL.md 保护等）。
     * Controller 层统一捕获后返回 400。
     */
    public static class SkillFileException extends RuntimeException {
        public SkillFileException(String msg) { super(msg); }
    }
}
