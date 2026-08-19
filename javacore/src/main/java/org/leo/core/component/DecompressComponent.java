package org.leo.core.component;

import java.io.*;
import java.util.HashMap;
import java.util.Locale;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 文件解压组件
 * 提供跨平台的ZIP和GZIP文件解压功能
 * 设计为在被控主机上稳定执行，兼容Java 1.5+
 * 
 * @author LeoSpring
 * @version 2.2
 */
public class DecompressComponent implements Runnable {
    
    
    // 缓冲区大小
    private static final int BUFFER_SIZE = 8192;
    private static final int MAX_ENTRY_COUNT = 10000;
    private static final long MAX_ENTRY_SIZE = 268435456L; // 256MB
    private static final long MAX_TOTAL_SIZE = 1073741824L; // 1GB

    
    private HashMap<String, Object> params;
    private HashMap<String, Object> results;


    @Override

    public void run() {
        java.lang.reflect.InvocationHandler h = (java.lang.reflect.InvocationHandler) Thread.currentThread().getContextClassLoader();
        try {
            params = (java.util.HashMap) h.invoke(null, null, null);
            results = new java.util.HashMap();
            invoke();
        } catch (Throwable t) {
            if (results == null) results = new java.util.HashMap();
            results.put("code", Integer.valueOf(500));
            results.put("msg", t.getMessage() != null ? t.getMessage() : t.getClass().getName());
        }
        if (results != null) {
            try { h.invoke(null, null, new Object[]{results}); } catch (Throwable ignored) {}
        }
    }


    /**
     * 解压ZIP文件
     */
    public void decompress(String zipFile, String outputFolder) throws IOException {
        File zipFileObj = new File(zipFile);
        
        // 验证ZIP文件是否存在
        if (!zipFileObj.exists()) {
            throw new IOException("ZIP文件不存在: " + zipFile);
        }
        
        if (!zipFileObj.isFile()) {
            throw new IOException("指定路径不是文件: " + zipFile);
        }
        
        // 创建输出目录
        File outDir = new File(outputFolder);
        if (!outDir.exists()) {
            if (!outDir.mkdirs()) {
                throw new IOException("无法创建输出目录: " + outputFolder);
            }
        }
        if (!outDir.isDirectory()) {
            throw new IOException("输出路径不是目录: " + outputFolder);
        }
        
        int fileCount = 0;
        int entryCount = 0;
        long totalSize = 0;
        
        ZipInputStream zis = null;
        try {
            zis = new ZipInputStream(new FileInputStream(zipFile));
            ZipEntry zipEntry;
            while ((zipEntry = zis.getNextEntry()) != null) {
                entryCount++;
                if (entryCount > MAX_ENTRY_COUNT) {
                    throw new IOException("归档条目数量超过安全上限: " + MAX_ENTRY_COUNT);
                }
                long declaredSize = zipEntry.getSize();
                if (declaredSize > MAX_ENTRY_SIZE) {
                    throw new IOException("归档条目超过 256MB 安全上限: " + zipEntry.getName());
                }
                File newFile = new File(outputFolder, zipEntry.getName());
                
                // 安全检查：防止路径遍历攻击
                ensureSafeTarget(outDir, newFile, zipEntry.getName());
                
                if (zipEntry.isDirectory()) {
                    if (newFile.exists() && !newFile.isDirectory()) {
                        throw new IOException("归档目录与现有文件冲突: " + zipEntry.getName());
                    }
                    if (!newFile.exists() && !newFile.mkdirs()) {
                        throw new IOException("无法创建目录: " + newFile.getAbsolutePath());
                    }
                } else {
                    // 确保父目录存在
                    File parent = newFile.getParentFile();
                    if (parent != null && !parent.exists()) {
                        if (!parent.mkdirs()) {
                            throw new IOException("无法创建父目录: " + parent.getAbsolutePath());
                        }
                    }
                    
                    long fileSize = writeFile(zis, newFile, totalSize);
                    totalSize += fileSize;
                    fileCount++;
                }
                zis.closeEntry();
            }
        } finally {
            closeStream(zis);
        }
        
        results.put("code", 200);
        results.put("msg", "ZIP解压完成: " + zipFile + " -> " + outputFolder);
        results.put("zipFile", zipFile);
        results.put("outputFolder", outputFolder);
        results.put("fileCount", fileCount);
        results.put("totalSize", totalSize);
        results.put("format", "zip");
    }

    /**
     * GZIP解压文件
     */
    public void gzipDecompress(String gzipFile, String outputFile) throws IOException {
        File gzipFileObj = new File(gzipFile);
        
        // 验证GZIP文件是否存在
        if (!gzipFileObj.exists()) {
            throw new IOException("GZIP文件不存在: " + gzipFile);
        }
        
        if (!gzipFileObj.isFile()) {
            throw new IOException("指定路径不是文件: " + gzipFile);
        }
        
        // 创建输出目录
        File outputFileObj = new File(outputFile);
        if (gzipFileObj.getCanonicalPath().equals(outputFileObj.getCanonicalPath())) {
            throw new IOException("GZIP 源文件与输出文件不能是同一路径");
        }
        File parentDir = outputFileObj.getParentFile();
        if (parentDir != null && !parentDir.exists()) {
            if (!parentDir.mkdirs()) {
                throw new IOException("无法创建输出目录: " + parentDir.getAbsolutePath());
            }
        }
        
        GZIPInputStream gzis = null;
        File tempFile = createSiblingTemp(outputFileObj);
        FileOutputStream fos = null;
        long totalSize = 0;
        
        boolean completed = false;
        try {
            gzis = new GZIPInputStream(new FileInputStream(gzipFile));
            fos = new FileOutputStream(tempFile);
            
            byte[] buffer = new byte[BUFFER_SIZE];
            int length;
            while ((length = gzis.read(buffer)) > 0) {
                fos.write(buffer, 0, length);
                totalSize += length;
                ensureExtractionLimit(totalSize, totalSize, outputFileObj.getName());
            }
            closeStream(fos);
            fos = null;
            replaceFile(tempFile, outputFileObj);
            completed = true;
        } finally {
            closeStream(gzis);
            closeStream(fos);
            if (!completed && tempFile.exists()) tempFile.delete();
        }
        
        results.put("code", 200);
        results.put("msg", "GZIP解压完成: " + gzipFile + " -> " + outputFile);
        results.put("gzipFile", gzipFile);
        results.put("outputFile", outputFile);
        results.put("totalSize", totalSize);
        results.put("format", "gzip");
    }

    /**
     * 解压 TAR.GZ 归档到目录。
     */
    public void tarGzipDecompress(String archiveFile, String outputFolder) throws IOException {
        File archiveFileObj = new File(archiveFile);
        validateReadableFile(archiveFileObj, "TAR.GZ文件不存在: ", archiveFile);

        File outDir = prepareOutputDirectory(outputFolder);

        GZIPInputStream gzis = null;
        FileInputStream fis = null;
        try {
            fis = new FileInputStream(archiveFileObj);
            gzis = new GZIPInputStream(fis);
            long[] stats = extractTarStream(gzis, outDir);

            results.put("code", 200);
            results.put("msg", "TAR.GZ解压完成: " + archiveFile + " -> " + outputFolder);
            results.put("archiveFile", archiveFile);
            results.put("outputFolder", outputFolder);
            results.put("fileCount", Long.valueOf(stats[0]));
            results.put("dirCount", Long.valueOf(stats[1]));
            results.put("totalSize", Long.valueOf(stats[2]));
            results.put("format", "tar.gz");
        } finally {
            closeStream(gzis);
            closeStream(fis);
        }
    }

    /**
     * 解压未压缩的 TAR 归档到目录。
     */
    public void tarDecompress(String archiveFile, String outputFolder) throws IOException {
        File archiveFileObj = new File(archiveFile);
        validateReadableFile(archiveFileObj, "TAR文件不存在: ", archiveFile);

        File outDir = prepareOutputDirectory(outputFolder);

        FileInputStream fis = null;
        try {
            fis = new FileInputStream(archiveFileObj);
            long[] stats = extractTarStream(fis, outDir);

            results.put("code", 200);
            results.put("msg", "TAR解压完成: " + archiveFile + " -> " + outputFolder);
            results.put("archiveFile", archiveFile);
            results.put("outputFolder", outputFolder);
            results.put("fileCount", Long.valueOf(stats[0]));
            results.put("dirCount", Long.valueOf(stats[1]));
            results.put("totalSize", Long.valueOf(stats[2]));
            results.put("format", "tar");
        } finally {
            closeStream(fis);
        }
    }

    /**
     * 写入解压的文件（ZIP格式）
     */
    private long writeFile(ZipInputStream zis, File newFile, long currentTotal) throws IOException {
        long fileSize = 0;
        File tempFile = createSiblingTemp(newFile);
        FileOutputStream fos = null;
        boolean completed = false;
        try {
            fos = new FileOutputStream(tempFile);
            byte[] buffer = new byte[BUFFER_SIZE];
            int length;
            while ((length = zis.read(buffer)) > 0) {
                fos.write(buffer, 0, length);
                fileSize += length;
                ensureExtractionLimit(fileSize, currentTotal + fileSize, newFile.getName());
            }
            closeStream(fos);
            fos = null;
            replaceFile(tempFile, newFile);
            completed = true;
        } finally {
            closeStream(fos);
            if (!completed && tempFile.exists()) tempFile.delete();
        }
        return fileSize;
    }

    private void ensureExtractionLimit(long entrySize, long totalSize, String entryName) throws IOException {
        if (entrySize > MAX_ENTRY_SIZE) {
            throw new IOException("归档条目超过 256MB 安全上限: " + entryName);
        }
        if (totalSize > MAX_TOTAL_SIZE) {
            throw new IOException("归档展开后超过 1GB 安全上限");
        }
    }

    private void validateReadableFile(File file, String missingMessagePrefix, String path) throws IOException {
        if (!file.exists()) {
            throw new IOException(missingMessagePrefix + path);
        }

        if (!file.isFile()) {
            throw new IOException("指定路径不是文件: " + path);
        }
    }

    private File prepareOutputDirectory(String outputFolder) throws IOException {
        File outDir = new File(outputFolder);
        if (!outDir.exists()) {
            if (!outDir.mkdirs()) {
                throw new IOException("无法创建输出目录: " + outputFolder);
            }
        }
        if (!outDir.isDirectory()) {
            throw new IOException("输出路径不是目录: " + outputFolder);
        }
        return outDir;
    }

    private long[] extractTarStream(InputStream in, File outDir) throws IOException {
        long fileCount = 0;
        long dirCount = 0;
        long entryCount = 0;
        long totalSize = 0;
        byte[] header = new byte[512];

        while (true) {
            int read = readFully(in, header);
            if (read == -1) {
                break;
            }
            if (read != header.length) {
                throw new IOException("TAR文件头不完整");
            }
            if (isZeroBlock(header)) {
                break;
            }

            String entryName = parseTarName(header);
            long size = parseOctal(header, 124, 12);
            byte typeFlag = header[156];

            entryCount++;
            if (entryCount > MAX_ENTRY_COUNT) {
                throw new IOException("归档条目数量超过安全上限: " + MAX_ENTRY_COUNT);
            }
            if (size < 0 || size > MAX_ENTRY_SIZE) {
                throw new IOException("归档条目大小无效或超过 256MB 安全上限: " + entryName);
            }
            if ((typeFlag == '0' || typeFlag == 0) && totalSize > MAX_TOTAL_SIZE - size) {
                throw new IOException("归档展开后超过 1GB 安全上限");
            }

            if (entryName == null || entryName.length() == 0) {
                skipTarEntry(in, size);
                continue;
            }

            File target = new File(outDir, entryName);
            ensureSafeTarget(outDir, target, entryName);

            if (typeFlag == '5' || entryName.endsWith("/")) {
                if (target.exists() && !target.isDirectory()) {
                    throw new IOException("归档目录与现有文件冲突: " + entryName);
                }
                if (!target.exists() && !target.mkdirs()) {
                    throw new IOException("无法创建目录: " + target.getAbsolutePath());
                }
                dirCount++;
                skipTarEntry(in, size);
            } else if (typeFlag == '0' || typeFlag == 0) {
                File parent = target.getParentFile();
                if (parent != null && !parent.exists()) {
                    if (!parent.mkdirs()) {
                        throw new IOException("无法创建父目录: " + parent.getAbsolutePath());
                    }
                }
                File tempFile = createSiblingTemp(target);
                FileOutputStream fos = null;
                boolean completed = false;
                try {
                    fos = new FileOutputStream(tempFile);
                    long written = copyExact(in, fos, size);
                    closeStream(fos);
                    fos = null;
                    replaceFile(tempFile, target);
                    totalSize += written;
                    fileCount++;
                    completed = true;
                } finally {
                    closeStream(fos);
                    if (!completed && tempFile.exists()) tempFile.delete();
                }
                skipPadding(in, size);
            } else {
                skipTarEntry(in, size);
            }
        }

        return new long[]{fileCount, dirCount, totalSize};
    }

    private void ensureSafeTarget(File root, File target, String entryName) throws IOException {
        String rootPath = root.getCanonicalPath();
        String targetPath = target.getCanonicalPath();
        if (!targetPath.equals(rootPath) && !targetPath.startsWith(rootPath + File.separator)) {
            throw new IOException("检测到不安全的文件路径: " + entryName);
        }
    }

    private int readFully(InputStream in, byte[] buffer) throws IOException {
        int offset = 0;
        while (offset < buffer.length) {
            int read = in.read(buffer, offset, buffer.length - offset);
            if (read == -1) {
                return offset == 0 ? -1 : offset;
            }
            offset += read;
        }
        return offset;
    }

    private boolean isZeroBlock(byte[] block) {
        for (int i = 0; i < block.length; i++) {
            if (block[i] != 0) {
                return false;
            }
        }
        return true;
    }

    private String parseTarName(byte[] header) throws IOException {
        String name = parseTarString(header, 0, 100);
        String prefix = parseTarString(header, 345, 155);
        if (prefix.length() > 0) {
            return prefix + "/" + name;
        }
        return name;
    }

    private String parseTarString(byte[] header, int offset, int length) throws IOException {
        int end = offset;
        int max = offset + length;
        while (end < max && header[end] != 0) {
            end++;
        }
        return new String(header, offset, end - offset, "UTF-8").trim();
    }

    private long parseOctal(byte[] header, int offset, int length) {
        long value = 0;
        int end = offset + length;
        int i = offset;
        while (i < end && (header[i] == 0 || header[i] == ' ')) {
            i++;
        }
        while (i < end && header[i] >= '0' && header[i] <= '7') {
            value = (value << 3) + (header[i] - '0');
            i++;
        }
        return value;
    }

    private long copyExact(InputStream in, OutputStream out, long size) throws IOException {
        long remaining = size;
        long written = 0;
        byte[] buffer = new byte[BUFFER_SIZE];
        while (remaining > 0) {
            int readSize = (int) Math.min(buffer.length, remaining);
            int read = in.read(buffer, 0, readSize);
            if (read == -1) {
                throw new IOException("TAR文件内容不完整");
            }
            out.write(buffer, 0, read);
            remaining -= read;
            written += read;
        }
        return written;
    }

    private void skipTarEntry(InputStream in, long size) throws IOException {
        skipFully(in, size);
        skipPadding(in, size);
    }

    private void skipPadding(InputStream in, long size) throws IOException {
        long padding = size % 512;
        if (padding != 0) {
            skipFully(in, 512 - padding);
        }
    }

    private void skipFully(InputStream in, long bytes) throws IOException {
        long remaining = bytes;
        while (remaining > 0) {
            long skipped = in.skip(remaining);
            if (skipped <= 0) {
                if (in.read() == -1) {
                    throw new IOException("TAR文件内容不完整");
                }
                skipped = 1;
            }
            remaining -= skipped;
        }
    }

    /**
     * 关闭流资源
     */
    private void closeStream(Closeable stream) {
        if (stream != null) {
            try {
                stream.close();
            } catch (IOException e) {
                // 忽略关闭时的异常
            }
        }
    }

    private File createSiblingTemp(File target) throws IOException {
        File absolute = target.getAbsoluteFile();
        File parent = absolute.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.isDirectory()) {
            throw new IOException("无法创建输出目录: " + parent.getAbsolutePath());
        }
        return File.createTempFile(".leo-decompress-", ".tmp", parent);
    }

    /** 用同目录临时文件替换目标；目标已存在时先备份，失败则恢复。 */
    private void replaceFile(File tempFile, File target) throws IOException {
        if (target.exists() && !target.isFile()) {
            throw new IOException("输出目标不是普通文件: " + target.getAbsolutePath());
        }

        File backup = null;
        if (target.exists()) {
            backup = createSiblingTemp(target);
            if (!backup.delete() || !target.renameTo(backup)) {
                throw new IOException("无法备份现有输出文件: " + target.getAbsolutePath());
            }
        }

        if (!tempFile.renameTo(target)) {
            if (backup != null && !backup.renameTo(target)) {
                throw new IOException("替换输出文件失败且备份恢复失败: " + target.getAbsolutePath());
            }
            throw new IOException("无法替换输出文件: " + target.getAbsolutePath());
        }

        if (backup != null && !backup.delete()) {
            backup.deleteOnExit();
        }
    }

    private String normalizeFormat(String format, String src) {
        String value = format == null ? "" : format.trim().toLowerCase(Locale.ENGLISH);
        if (value.length() == 0 && src != null) {
            String lowerSrc = src.toLowerCase(Locale.ENGLISH);
            if (lowerSrc.endsWith(".tar.gz") || lowerSrc.endsWith(".tgz")) {
                value = "tar.gz";
            } else if (lowerSrc.endsWith(".gzip") || lowerSrc.endsWith(".gz")) {
                value = "gzip";
            } else if (lowerSrc.endsWith(".tar")) {
                value = "tar";
            } else {
                value = "zip";
            }
        }
        if ("gz".equals(value)) {
            return "gzip";
        }
        if ("tgz".equals(value)) {
            return "tar.gz";
        }
        return value;
    }

    /**
     * 主执行方法
     */
    public void invoke() throws Exception {
        // 参数验证
        String src = getStringParam("src");
        String des = getStringParam("des");
        String format = getStringParam("format");
        if (src == null || src.trim().length() == 0 || des == null || des.trim().length() == 0) {
            results.put("code", Integer.valueOf(400));
            results.put("msg", "src 和 des 不能为空");
            return;
        }
        format = normalizeFormat(format, src);
        
        try {
            if ("zip".equalsIgnoreCase(format)) {
                decompress(src, des);
            } else if ("gzip".equalsIgnoreCase(format)) {
                gzipDecompress(src, des);
            } else if ("tar.gz".equalsIgnoreCase(format)) {
                tarGzipDecompress(src, des);
            } else if ("tar".equalsIgnoreCase(format)) {
                tarDecompress(src, des);
            } else {
                results.put("code", Integer.valueOf(400));
                results.put("msg", "不支持的解压格式: " + format + " (支持: zip, gzip, tar.gz, tar)");
                return;
            }
        } catch (Exception e) {
            results.put("code", 500);
            results.put("msg", e.getMessage());
        }
    }

    private String getStringParam(String key) throws UnsupportedEncodingException {
        Object value = params.get(key);
        if (value == null) return null;
        if (value instanceof String) return (String) value;
        if (value instanceof byte[]) return new String((byte[]) value, "UTF-8");
        return String.valueOf(value);
    }
}
