package org.leo.service.transfer;

import org.leo.core.agent.capability.FileCapable;
import org.springframework.util.DigestUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Owns one upload's protocol, checkpoint and commit/cancellation gates.
 * The coordinator supplies cancellation, keeping executor policy outside this task.
 */
final class UploadTask {
    private final String taskId;
    private final String userId;
    private final String sessionId;
    private final String filePath;
    private final File localFile;
    private final String originalFilename;
    private final int chunkSize;
    private final long totalBytes;
    private final Consumer<Future<?>> cancelRunning;
    private final long createdAt = System.currentTimeMillis();
    private final AtomicLong uploadedBytes = new AtomicLong(0L);
    private final AtomicLong bytesAtStart = new AtomicLong(0L);
    private final AtomicLong speedStartAt = new AtomicLong(0L);
    private final String tempPath;

    private volatile TransferTaskState state = TransferTaskState.NEW;
    private volatile TransferStage currentStage = TransferStage.CREATED;
    private volatile TransferStage errorStage;
    private volatile String errorMessage;
    private volatile long updatedAt = createdAt;
    private volatile long startedAt;
    private volatile long endAt;
    private volatile boolean cancelRequested = false;
    private volatile boolean pauseRequested = false;
    private volatile boolean tempInitialized = false;
    private volatile boolean cleanupComplete = true;
    private volatile Future<?> future;
    private volatile FileCapable activeFileNode;

    UploadTask(String taskId,
               String userId,
               String sessionId,
               String filePath,
               File localFile,
               String originalFilename,
               int chunkSize,
               Consumer<Future<?>> cancelRunning) {
        this.taskId = taskId;
        this.userId = userId;
        this.sessionId = sessionId;
        this.filePath = filePath;
        this.localFile = localFile;
        this.originalFilename = originalFilename;
        this.chunkSize = chunkSize;
        this.cancelRunning = cancelRunning;
        this.totalBytes = Math.max(localFile.length(), 0L);
        String tempToken = taskId.length() > 12 ? taskId.substring(0, 12) : taskId;
        int separatorIndex = Math.max(filePath.lastIndexOf('/'), filePath.lastIndexOf('\\'));
        String parentPrefix = separatorIndex >= 0 ? filePath.substring(0, separatorIndex + 1) : "";
        this.tempPath = parentPrefix + ".leo-upload-" + tempToken + ".part";
    }

    void runUpload(FileCapable fileNode) {
        if (!beginUpload(fileNode)) return;
        InputStream inputStream = null;
        boolean committed = false;
        try {
            prepareRemoteFile(fileNode);
            inputStream = new FileInputStream(localFile);
            skipFully(inputStream, uploadedBytes.get());
            if (!writeChunks(fileNode, inputStream)) return;
            if (!verifyChecksums(fileNode)) return;
            if (!beginCommit()) return;
            ensureSuccess(fileNode.moveFile(tempPath, filePath, "overwrite"));
            committed = true;
            completeUpload();
        } catch (Exception e) {
            failUpload(e);
        } finally {
            finishUpload(inputStream, fileNode, committed);
        }
    }

    private synchronized boolean beginUpload(FileCapable fileNode) {
        if (state != TransferTaskState.NEW) return false;
        state = TransferTaskState.RUNNING;
        currentStage = TransferStage.PREPARING;
        pauseRequested = false;
        cancelRequested = false;
        long runStartedAt = System.currentTimeMillis();
        if (startedAt <= 0L) startedAt = runStartedAt;
        bytesAtStart.set(uploadedBytes.get());
        speedStartAt.set(runStartedAt);
        endAt = 0L;
        activeFileNode = fileNode;
        cleanupComplete = false;
        touch();
        return true;
    }

    private void prepareRemoteFile(FileCapable fileNode) throws Exception {
        if (!tempInitialized) {
            ensureSuccess(fileNode.createFile(tempPath, ""));
            tempInitialized = true;
            uploadedBytes.set(0L);
        }
    }

    private boolean writeChunks(FileCapable fileNode, InputStream inputStream) throws Exception {
        byte[] buffer = new byte[chunkSize];
        long offset = uploadedBytes.get();
        currentStage = TransferStage.TRANSFERRING;
        touch();
        int read;
        while ((read = inputStream.read(buffer)) >= 0) {
            if (finishIfRequested()) return false;
            if (read == 0) continue;
            byte[] chunk = buffer;
            if (read != buffer.length) {
                chunk = new byte[read];
                System.arraycopy(buffer, 0, chunk, 0, read);
            }
            Map<String, Object> result = fileNode.fileUploadChunk(tempPath, offset, chunk);
            ensureSuccess(result);
            offset += read;
            uploadedBytes.set(offset);
            touch();
        }
        return true;
    }

    private boolean verifyChecksums(FileCapable fileNode) throws Exception {
        if (finishIfRequested()) return false;
        currentStage = TransferStage.VERIFYING_LOCAL;
        touch();
        String localMd5 = calculateMd5(localFile);
        if (finishIfRequested()) return false;
        currentStage = TransferStage.VERIFYING_REMOTE;
        touch();
        Map<String, Object> remoteChecksum = fileNode.getFileMD5(tempPath);
        ensureSuccess(remoteChecksum);
        if (finishIfRequested()) return false;
        Object remoteMd5Value = remoteChecksum.get("md5");
        String remoteMd5 = remoteMd5Value == null ? null : String.valueOf(remoteMd5Value);
        if (remoteMd5 == null || !localMd5.equalsIgnoreCase(remoteMd5)) {
            throw new IllegalStateException(
                    "上传完整性校验失败: local=" + localMd5 + ", remote=" + remoteMd5);
        }
        return true;
    }

    private void completeUpload() {
        tempInitialized = false;
        state = TransferTaskState.COMPLETED;
        currentStage = TransferStage.FINISHED;
        endAt = System.currentTimeMillis();
        touch();
    }

    private void failUpload(Exception e) {
        if (!finishIfRequested()) {
            errorStage = currentStage;
            errorMessage = e.getMessage();
            state = TransferTaskState.FAILED;
            endAt = System.currentTimeMillis();
            touch();
        }
    }

    private void finishUpload(InputStream inputStream, FileCapable fileNode, boolean committed) {
        if (inputStream != null) {
            try {
                inputStream.close();
            } catch (Exception ignored) {
            }
        }
        synchronized (this) {
            if (!committed && state != TransferTaskState.PAUSED) {
                tryDeleteRemoteFile(fileNode, tempPath);
                tempInitialized = false;
            }
            cleanupComplete = true;
        }
    }

    private synchronized boolean beginCommit() {
        if (finishIfRequested()) return false;
        currentStage = TransferStage.COMMITTING;
        touch();
        return true;
    }

    private void requireCancellable() {
        if (isCommitting()) {
            throw new IllegalStateException("文件正在提交，请等待提交结果");
        }
    }

    private boolean isCommitting() {
        return state == TransferTaskState.RUNNING && currentStage == TransferStage.COMMITTING;
    }

    synchronized void pause() {
        if (state != TransferTaskState.RUNNING && state != TransferTaskState.NEW) {
            return;
        }
        requireCancellable();
        pauseRequested = true;
        state = TransferTaskState.PAUSED;
        touch();
        Future<?> running = future;
        cancelRunning.accept(running);
    }

    synchronized void cancel() {
        if (state.isTerminal()) {
            return;
        }
        requireCancellable();
        boolean cleanImmediately = state == TransferTaskState.PAUSED && tempInitialized && cleanupComplete;
        cancelRequested = true;
        pauseRequested = false;
        state = TransferTaskState.CANCELLED;
        currentStage = TransferStage.FINISHED;
        endAt = System.currentTimeMillis();
        touch();
        Future<?> running = future;
        cancelRunning.accept(running);
        if (cleanImmediately && activeFileNode != null) {
            tryDeleteRemoteFile(activeFileNode, tempPath);
            tempInitialized = false;
        }
    }

    synchronized void attachFuture(Future<?> future) {
        this.future = future;
        if (cancelRequested || pauseRequested) cancelRunning.accept(future);
    }

    synchronized void reject(String message) {
        errorMessage = message;
        errorStage = currentStage;
        state = TransferTaskState.FAILED;
        endAt = System.currentTimeMillis();
        touch();
    }

    synchronized void resetForRetry() {
        if (state != TransferTaskState.FAILED) {
            return;
        }
        if (!cleanupComplete) {
            throw new IllegalStateException("上传任务正在清理临时文件");
        }
        state = TransferTaskState.NEW;
        currentStage = TransferStage.CREATED;
        errorStage = null;
        errorMessage = null;
        cancelRequested = false;
        pauseRequested = false;
        tempInitialized = false;
        uploadedBytes.set(0L);
        endAt = 0L;
        touch();
    }

    synchronized void prepareResume() {
        if (state != TransferTaskState.PAUSED) {
            return;
        }
        if (!cleanupComplete) throw new IllegalStateException("上传任务正在暂停，请稍后恢复");
        state = TransferTaskState.NEW;
        pauseRequested = false;
        cancelRequested = false;
        touch();
    }

    private synchronized void finishPaused() {
        if (state.isTerminal()) {
            return;
        }
        state = TransferTaskState.PAUSED;
        touch();
    }

    private synchronized void finishCancelled() {
        if (state == TransferTaskState.COMPLETED || state == TransferTaskState.FAILED) {
            return;
        }
        state = TransferTaskState.CANCELLED;
        currentStage = TransferStage.FINISHED;
        endAt = System.currentTimeMillis();
        touch();
    }

    private boolean finishIfRequested() {
        if (cancelRequested) {
            finishCancelled();
            return true;
        }
        if (pauseRequested) {
            finishPaused();
            return true;
        }
        return false;
    }

    Map<String, Object> snapshot() {
        HashMap<String, Object> map = new HashMap<>();
        long uploaded = uploadedBytes.get();
        map.put("taskId", taskId);
        map.put("sessionId", sessionId);
        map.put("filePath", filePath);
        map.put("fileName", originalFilename);
        map.put("fileSize", totalBytes);
        map.put("uploadedBytes", uploaded);
        map.put("speedBytesPerSec", speedBytesPerSecond(uploaded));
        map.put("chunkSize", chunkSize);
        map.put("state", state.name());
        map.put("currentStage", currentStage.name());
        map.put("createdAt", createdAt);
        map.put("startedAt", startedAt);
        map.put("endAt", endAt);
        map.put("updatedAt", updatedAt);
        map.put("progress", totalBytes <= 0
                ? (state == TransferTaskState.COMPLETED ? 100D : 0D)
                : Math.min(100D, (uploaded * 100D) / totalBytes));
        map.put("ephemeral", true);
        if (errorMessage != null && !errorMessage.isBlank()) {
            map.put("errorMessage", errorMessage);
        }
        if (errorStage != null) {
            map.put("errorStage", errorStage.name());
        }
        return map;
    }

    boolean isActive() {
        return state.isActive();
    }

    boolean occupiesSlot(String expectedUserId) {
        return expectedUserId.equals(userId)
                && (state == TransferTaskState.NEW || state == TransferTaskState.RUNNING);
    }

    void requireSession(String expectedSessionId) {
        if (!sessionId.equals(expectedSessionId)) {
            throw new IllegalArgumentException("任务归属不匹配: " + taskId);
        }
    }

    boolean isPaused() {
        return state == TransferTaskState.PAUSED;
    }

    boolean isFailed() {
        return state == TransferTaskState.FAILED;
    }

    boolean isTerminal() {
        return state.isTerminal();
    }

    boolean belongsTo(String expectedUserId, String expectedSessionId) {
        return expectedUserId.equals(userId) && expectedSessionId.equals(sessionId);
    }

    boolean isExpired(long now, long retentionMillis) {
        return state.isTerminal() && now - updatedAt > retentionMillis;
    }

    synchronized void stopOnShutdown() {
        if (!isCommitting()) cancel();
    }

    private void touch() {
        updatedAt = System.currentTimeMillis();
    }

    private long speedBytesPerSecond(long uploaded) {
        long started = speedStartAt.get();
        if (started <= 0L || state != TransferTaskState.RUNNING) {
            return 0L;
        }
        long elapsed = Math.max(1L, System.currentTimeMillis() - started);
        return Math.max(0L, uploaded - bytesAtStart.get()) * 1000L / elapsed;
    }

    private void skipFully(InputStream input, long bytes) throws Exception {
        long remaining = bytes;
        while (remaining > 0L) {
            long skipped = input.skip(remaining);
            if (skipped <= 0L) {
                if (input.read() < 0) {
                    throw new IllegalStateException("上传源文件长度发生变化");
                }
                skipped = 1L;
            }
            remaining -= skipped;
        }
    }

    private void ensureSuccess(Map<String, Object> result) {
        if (result == null) {
            throw new IllegalStateException("远端操作未返回结果");
        }
        Object codeObj = result.get("code");
        if (!(codeObj instanceof Number number)) {
            throw new IllegalStateException("远端操作返回状态无效");
        }
        if (number.intValue() != 200) {
            Object msg = result.get("msg");
            throw new IllegalStateException(msg == null ? "远端上传失败" : String.valueOf(msg));
        }
    }

    private void tryDeleteRemoteFile(FileCapable fileNode, String targetPath) {
        try {
            fileNode.deleteFile(targetPath);
        } catch (Exception ignored) {
        }
    }

    private String calculateMd5(File file) throws Exception {
        try (InputStream input = new FileInputStream(file)) {
            return DigestUtils.md5DigestAsHex(input);
        }
    }
}
