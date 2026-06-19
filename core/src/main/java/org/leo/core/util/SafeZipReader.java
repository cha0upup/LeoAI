package org.leo.core.util;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 安全的 zip 解析工具：在内存中遍历条目时强制限制条目数、单条目大小、
 * 以及解压后总字节数，防止 zip bomb 让服务 OOM。
 *
 * <p>本工具只读取 entry 字节，不向磁盘写入；写入磁盘的场景请见 SkillExportService
 * 的 copyWithLimit + 路径校验逻辑。
 *
 * <p>典型用法：
 * <pre>{@code
 * List<SafeZipReader.Entry> entries = SafeZipReader.readAll(
 *     inputStream,
 *     name -> name.toLowerCase().endsWith(".disguise"),
 *     SafeZipReader.Limits.DEFAULT
 * );
 * for (SafeZipReader.Entry e : entries) {
 *     handle(e.name(), e.bytes());
 * }
 * }</pre>
 */
public final class SafeZipReader {

    /**
     * Zip 条目的解压限制。可通过 {@link Limits#DEFAULT} 取默认值，
     * 或自定义 {@link Limits#of(int, long, long)}。
     */
    public record Limits(int maxEntries, long maxEntryBytes, long maxTotalBytes) {

        /** 默认：1000 条，单条 5MB，整体 50MB。 */
        public static final Limits DEFAULT = new Limits(1000, 5L * 1024 * 1024, 50L * 1024 * 1024);

        public static Limits of(int maxEntries, long maxEntryBytes, long maxTotalBytes) {
            return new Limits(maxEntries, maxEntryBytes, maxTotalBytes);
        }
    }

    /** 单个 zip 条目的内存表示。 */
    public record Entry(String name, byte[] bytes) {}

    /** 超限时抛出。HTTP 层应映射为 400/413。 */
    public static class ZipLimitExceededException extends IOException {
        public ZipLimitExceededException(String msg) {
            super(msg);
        }
    }

    private SafeZipReader() {}

    /**
     * 读取 zip 中所有满足 nameFilter 的条目到内存。
     *
     * @param in         zip 输入流，方法返回时不关闭（调用方控制 try-with-resources）
     * @param nameFilter null 表示接受所有非目录条目
     * @param limits     解压限制
     * @return 满足过滤条件的条目列表，按 zip 内顺序
     * @throws ZipLimitExceededException 任一限制被触发
     * @throws IOException               zip 损坏或 IO 错误
     */
    public static List<Entry> readAll(InputStream in,
                                      java.util.function.Predicate<String> nameFilter,
                                      Limits limits) throws IOException {
        if (limits == null) limits = Limits.DEFAULT;
        List<Entry> result = new ArrayList<>();
        long totalBytes = 0;
        int totalEntries = 0;

        try (ZipInputStream zis = new ZipInputStream(in)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                totalEntries++;
                if (totalEntries > limits.maxEntries()) {
                    throw new ZipLimitExceededException(
                            "zip 条目数超过上限 " + limits.maxEntries());
                }
                String name = entry.getName();
                if (entry.isDirectory() || (nameFilter != null && !nameFilter.test(name))) {
                    zis.closeEntry();
                    continue;
                }
                byte[] data = readEntryWithLimit(zis, limits.maxEntryBytes());
                totalBytes += data.length;
                if (totalBytes > limits.maxTotalBytes()) {
                    throw new ZipLimitExceededException(
                            "zip 解压后总大小超过上限 " + limits.maxTotalBytes() + " 字节");
                }
                result.add(new Entry(name, data));
                zis.closeEntry();
            }
        }
        return result;
    }

    /**
     * 流式遍历版本：不一次性加载所有条目，调用方按需处理每条。
     * 适合条目可能较大、不需要全部驻留内存的场景。
     */
    public static void forEach(InputStream in,
                               java.util.function.Predicate<String> nameFilter,
                               Limits limits,
                               EntryHandler handler) throws IOException {
        if (limits == null) limits = Limits.DEFAULT;
        long totalBytes = 0;
        int totalEntries = 0;

        try (ZipInputStream zis = new ZipInputStream(in)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                totalEntries++;
                if (totalEntries > limits.maxEntries()) {
                    throw new ZipLimitExceededException(
                            "zip 条目数超过上限 " + limits.maxEntries());
                }
                String name = entry.getName();
                if (entry.isDirectory() || (nameFilter != null && !nameFilter.test(name))) {
                    zis.closeEntry();
                    continue;
                }
                byte[] data = readEntryWithLimit(zis, limits.maxEntryBytes());
                totalBytes += data.length;
                if (totalBytes > limits.maxTotalBytes()) {
                    throw new ZipLimitExceededException(
                            "zip 解压后总大小超过上限 " + limits.maxTotalBytes() + " 字节");
                }
                handler.handle(name, data);
                zis.closeEntry();
            }
        }
    }

    @FunctionalInterface
    public interface EntryHandler {
        void handle(String name, byte[] bytes) throws IOException;
    }

    private static byte[] readEntryWithLimit(InputStream in, long max) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        long read = 0;
        int n;
        while ((n = in.read(buf)) != -1) {
            read += n;
            if (read > max) {
                throw new ZipLimitExceededException(
                        "zip 单个条目解压后大小超过上限 " + max + " 字节");
            }
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }
}
