package org.leo.core.puppet.service;

import org.leo.core.net.Communication;
import org.leo.core.net.layer.RequestLayer;
import org.leo.core.net.layer.ResponseLayer;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class FileService extends ComponentService {

    public FileService(Communication communication, List<RequestLayer> requestLayers, List<ResponseLayer> responseLayers) {
        super(communication, requestLayers, responseLayers);
    }

    public Map<String, Object> fileDownloadChunk(String path, long size, long offset) throws Exception {
        HashMap<String, Object> params = new HashMap<String, Object>();
        params.put("op", 1);
        params.put("path", path.getBytes("UTF-8"));
        params.put("size", size);
        params.put("offset", offset);
        return invokeComponent("FileDownloadComponent", params);
    }

    public Map<String, Object> fileUploadChunk(String path, long offset, byte[] data) throws Exception {
        HashMap<String, Object> params = new HashMap<String, Object>();
        params.put("op", 2);
        params.put("path", path.getBytes("UTF-8"));
        params.put("offset", offset);
        params.put("data", data);
        return invokeComponent("FileUploadComponent", params);
    }

    public Map<String, Object> compress(String src, String des, String excludePattern) throws Exception {
        HashMap<String, Object> params = new HashMap<String, Object>();
        params.put("src", src.getBytes("UTF-8"));
        params.put("des", des.getBytes("UTF-8"));
        if (excludePattern != null && !excludePattern.isBlank()) {
            params.put("exclude", excludePattern.trim());
        }
        return invokeComponent("CompressComponent", params);
    }

    public Map<String, Object> decompress(String src, String des) throws Exception {
        HashMap<String, Object> params = new HashMap<String, Object>();
        params.put("src", src.getBytes("UTF-8"));
        params.put("des", des.getBytes("UTF-8"));
        return invokeComponent("DecompressComponent", params);
    }

    public Map<String, Object> getFileList(String path) throws Exception {
        HashMap<String, Object> payload = new HashMap<String, Object>();
        payload.put("action", 1);
        payload.put("path", path.getBytes("utf-8"));
        return invokeComponent("FileComponent", payload);
    }

    public Map<String, Object> getRootList() throws Exception {
        HashMap<String, Object> payload = new HashMap<String, Object>();
        payload.put("action", 6);
        return invokeComponent("FileComponent", payload);
    }

    public Map<String, Object> getFileMD5(String path) throws Exception {
        HashMap<String, Object> payload = new HashMap<String, Object>();
        payload.put("action", 10);
        payload.put("path", path.getBytes("utf-8"));
        return invokeComponent("FileComponent", payload);
    }

    public Map<String, Object> createDir(String dirName) throws Exception {
        HashMap<String, Object> payload = new HashMap<String, Object>();
        payload.put("action", 3);
        payload.put("path", dirName.getBytes("utf-8"));
        return invokeComponent("FileComponent", payload);
    }

    public Map<String, Object> deleteFile(String path) throws Exception {
        HashMap<String, Object> payload = new HashMap<String, Object>();
        payload.put("action", 2);
        payload.put("path", path.getBytes("utf-8"));
        return invokeComponent("FileComponent", payload);
    }

    public Map<String, Object> copyFile(String srcPath, String destPath) throws Exception {
        return copyFile(srcPath, destPath, null);
    }

    public Map<String, Object> copyFile(String srcPath, String destPath, String conflictStrategy) throws Exception {
        HashMap<String, Object> payload = new HashMap<String, Object>();
        payload.put("action", 9);
        payload.put("path", srcPath.getBytes("utf-8"));
        payload.put("destPath", destPath.getBytes("utf-8"));
        if (conflictStrategy != null && !conflictStrategy.isBlank()) {
            payload.put("conflictStrategy", conflictStrategy.getBytes("utf-8"));
        }
        return invokeComponent("FileComponent", payload);
    }

    public Map<String, Object> moveFile(String srcPath, String newPath) throws Exception {
        return moveFile(srcPath, newPath, null);
    }

    public Map<String, Object> moveFile(String srcPath, String newPath, String conflictStrategy) throws Exception {
        HashMap<String, Object> payload = new HashMap<String, Object>();
        payload.put("action", 5);
        payload.put("path", srcPath.getBytes("utf-8"));
        payload.put("newPath", newPath.getBytes("utf-8"));
        if (conflictStrategy != null && !conflictStrategy.isBlank()) {
            payload.put("conflictStrategy", conflictStrategy.getBytes("utf-8"));
        }
        return invokeComponent("FileComponent", payload);
    }

    public Map<String, Object> createFile(String path, String content) throws Exception {
        HashMap<String, Object> payload = new HashMap<String, Object>();
        payload.put("action", 4);
        payload.put("path", path.getBytes("utf-8"));
        payload.put("content", content.getBytes("utf-8"));
        return invokeComponent("FileComponent", payload);
    }

    public Map<String, Object> editFile(String path, String content) throws Exception {
        HashMap<String, Object> payload = new HashMap<String, Object>();
        payload.put("action", 7);
        payload.put("path", path.getBytes("utf-8"));
        payload.put("content", content.getBytes("utf-8"));
        return invokeComponent("FileComponent", payload);
    }
}
