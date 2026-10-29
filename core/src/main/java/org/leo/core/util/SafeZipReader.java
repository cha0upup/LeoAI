package org.leo.core.util;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 安全的 zip 解析工具：在内存中遍历条目时强制限制条目数、单条目大小、
 * 以及解压后总字节数，防止 zip bomb 让服务 OOM。
 *
 * <p>本工具只读取 entry 字节，不向磁盘写入；写入磁盘的场景请见 SkillExportService
 * 的 copyWithLimit + 路径校验逻辑。
 *
 * <p>调用 {@link #forEach} 逐条处理匹配文件；每次只保留一个条目的字节。
 * 输入流由遍历器关闭。
 */
public final class SafeZipReader {

    /**
     * Zip 条目的解压限制。可通过 {@link Limits#DEFAULT} 取默认值，
     * 或通过构造函数自定义。
     */
    public record Limits(int maxEntries, long maxEntryBytes, long maxTotalBytes) {

        /** 默认：1000 条，单条 5MB，整体 50MB。 */
        public static final Limits DEFAULT = new Limits(1000, 5L * 1024 * 1024, 50L * 1024 * 1024);
    }

    /** 超限时抛出。HTTP 层应映射为 400/413。 */
    public static class ZipLimitExceededException extends IOException {
        public ZipLimitExceededException(String msg) {
            super(msg);
        }
    }

    private SafeZipReader() {}

    /**
     * 逐条遍历：不一次性加载所有条目，调用方按需处理每条。
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
