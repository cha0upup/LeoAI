package org.leo.core.component;

import java.io.*;
import java.util.HashMap;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 文件压缩组件
 * 提供跨平台的ZIP文件压缩功能，支持正则表达式排除文件
 * 设计为在被控主机上稳定执行，兼容Java 1.5+
 * 
 * @author LeoSpring
 * @version 2.3
 */
public class CompressComponent implements Runnable {

    // 缓冲区大小
    private static final int BUFFER_SIZE = 8192;

    
    private HashMap params;
    private HashMap results;
    
    
    // 排除模式（正则表达式）
    private Pattern excludePattern;
    private File sourceRoot;

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
        File parentDir = zipFileObj.getParentFile();
        if (parentDir != null && !parentDir.exists()) {
            if (!parentDir.mkdirs()) {
                throw new IOException("无法创建目标目录: " + parentDir.getAbsolutePath());
            }
        }
        
        // 初始化排除模式
        initializeExcludePattern(excludePattern);
        sourceRoot = sourceFile.isDirectory() ? sourceFile : sourceFile.getParentFile();
        
        ZipOutputStream zos = null;
        try {
            zos = new ZipOutputStream(new FileOutputStream(zipFile));
            if (sourceFile.isDirectory()) {
                compressDirectory(sourceFile, sourceFile.getName(), zos);
            } else if (!shouldExclude(sourceFile, sourceFile.getName())) {
                compressFile(sourceFile, zos, "");
            }
        } finally {
            closeStream(zos);
        }
        
        results.put("code", 200);
        results.put("msg", "ZIP压缩完成: " + sourcePath + " -> " + zipFile);
        results.put("sourcePath", sourcePath);
        results.put("zipFile", zipFile);
        results.put("format", "zip");
    }

    /**
     * 递归压缩目录（ZIP格式）
     */
    private void compressDirectory(File folder, String parentFolder, ZipOutputStream zos) throws IOException {
        File[] files = folder.listFiles();
        if (files != null) {
            for (File file : files) {
                String entryPath = parentFolder + "/" + file.getName();
                // 检查是否应该排除
                if (shouldExclude(file, entryPath)) {
                    continue;
                }
                
                if (file.isDirectory()) {
                    compressDirectory(file, entryPath, zos);
                } else {
                    compressFile(file, zos, parentFolder + "/");
                }
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
        if (excludePattern == null) {
            return false;
        }
        
        // 标准化路径（使用正斜杠）
        String normalizedPath = entryPath.replace("\\", "/");
        
        // 获取相对于源根目录的路径
        String relativePath = normalizedPath;
        try {
            if (sourceRoot != null) {
                String filePath = file.getCanonicalPath();
                String rootPath = sourceRoot.getCanonicalPath();
                if (filePath.startsWith(rootPath)) {
                    relativePath = filePath.substring(rootPath.length());
                    if (relativePath.startsWith(File.separator)) {
                        relativePath = relativePath.substring(1);
                    }
                    relativePath = relativePath.replace("\\", "/");
                }
            }
        } catch (IOException e) {
            // 使用标准化路径
        }
        
        // 匹配完整路径或文件名
        return excludePattern.matcher(relativePath).matches() 
            || excludePattern.matcher(normalizedPath).matches()
            || excludePattern.matcher(file.getName()).matches();
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

    /**
     * 主执行方法
     */
    public void invoke() throws Exception {
        // 参数验证
        Object srcObj = params.get("src");
        Object desObj = params.get("des");


        String src = new String((byte[]) srcObj, "utf-8");
        String des = new String((byte[]) desObj, "utf-8");

        // 获取排除文件模式（正则表达式，String类型）
        String excludePattern = null;
        Object excludeObj = params.get("exclude");
        if (excludeObj != null) {
            if (excludeObj instanceof String) {
                excludePattern = (String) excludeObj;
                if (excludePattern.trim().isEmpty()) {
                    excludePattern = null;
                }
            }
        }

        try {
            compress(src, des, excludePattern);
        } catch (Exception e) {
            results.put("code",500);
            results.put("msg", e.getMessage());
            throw e;
        }
    }
}
