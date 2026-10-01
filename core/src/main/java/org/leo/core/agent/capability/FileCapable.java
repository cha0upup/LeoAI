package org.leo.core.agent.capability;

import java.util.Map;

/**
 * Capability marker for nodes that can perform remote file operations.
 */
public interface FileCapable {

    Map<String, Object> getFileSystemProfile() throws Exception;

    Map<String, Object> getFileList(String path) throws Exception;

    Map<String, Object> fileDownloadChunk(String path, long size, long offset) throws Exception;

    Map<String, Object> fileUploadChunk(String path, long offset, byte[] data) throws Exception;

    Map<String, Object> getFileMD5(String path) throws Exception;

    Map<String, Object> createDir(String dirName) throws Exception;

    Map<String, Object> deleteFile(String path) throws Exception;

    Map<String, Object> copyFile(String srcPath, String destPath, String conflictStrategy) throws Exception;

    Map<String, Object> moveFile(String srcPath, String newPath, String conflictStrategy) throws Exception;

    Map<String, Object> createFile(String path, String content) throws Exception;

    Map<String, Object> compressFile(String src, String des, String excludePattern) throws Exception;

    Map<String, Object> editFile(String path, String content) throws Exception;

    Map<String, Object> decompressFile(String src, String des, String format) throws Exception;
}
