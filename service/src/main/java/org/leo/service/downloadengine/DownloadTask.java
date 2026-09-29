package org.leo.service.downloadengine;

import org.leo.core.puppet.capability.FileCapable;
import org.leo.service.concurrent.ServiceTaskExecutor;
import org.leo.service.transfer.TransferStage;
import org.leo.service.transfer.TransferTaskState;

import java.io.File;
import java.io.FileInputStream;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class DownloadTask {
    private final FileCapable fileNode;
    private final String userId;
    private final String sessionId;
    private final String taskId;
    private final DownloadStore store;
    private final ServiceTaskExecutor taskExecutor;

    private final String filePath;
    private final int threads;
    private final int chunkSize;
    private final long expectedLength;
    private final String expectedMd5;

    private final File tempFile;
    private final File finalFile;

    private final BitSet doneChunks;
    private final BitSet inProgressChunks;
    private final int totalChunks;
    private final AtomicInteger doneCount;

    private int scanCursor = 0;
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final AtomicLong downloadedBytes = new AtomicLong(0);
    private final AtomicLong lastPersistAtMs = new AtomicLong(0);

    private final AtomicLong bytesAtStart = new AtomicLong(0);
    private final AtomicLong startAtForSpeedMs = new AtomicLong(0);

    private volatile TransferTaskState state = TransferTaskState.NEW;
    private volatile TransferStage currentStage = TransferStage.CREATED;
    private volatile TransferStage errorStage;
    private volatile String lastError;
    private volatile long createAtMs;
    private volatile long startAtMs;
    private volatile long endAtMs;
    private volatile long updatedAtMs;

    private final List<Future<?>> workerFutures =
            Collections.synchronizedList(new ArrayList<Future<?>>());
    private final Object writeLock = new Object();

    private DownloadTask(FileCapable fileNode,
                         String userId,
                         String sessionId,
                         String taskId,
                         String filePath,
                         File finalFile,
                         int threads,
                         int chunkSize,
                         long expectedLength,
                         String expectedMd5,
                         DownloadStore store,
                         ServiceTaskExecutor taskExecutor,
                         BitSet doneChunks,
                         int totalChunks) {
        this.fileNode = fileNode;
        this.userId = userId;
        this.sessionId = sessionId;
        this.taskId = taskId;
        this.filePath = filePath;
        this.finalFile = finalFile;
        this.tempFile = store.getTempFile();
        this.threads = threads;
        this.chunkSize = chunkSize;
        this.expectedLength = expectedLength;
        this.expectedMd5 = expectedMd5;
        this.store = store;
        this.taskExecutor = taskExecutor;
        this.doneChunks = doneChunks;
        this.inProgressChunks = new BitSet(totalChunks);
        this.totalChunks = totalChunks;
        this.doneCount = new AtomicInteger(doneChunks.cardinality());
        this.createAtMs = System.currentTimeMillis();
        this.updatedAtMs = this.createAtMs;
    }

    public static DownloadTask createNewOrLoad(FileCapable fileNode,
                                               String userId,
                                               String sessionId,
                                               String taskId,
                                               String filePath,
                                               int threads,
                                               int chunkSize,
                                               long expectedLength,
                                               String expectedMd5,
                                               DownloadStore store,
                                               ServiceTaskExecutor taskExecutor) throws Exception {
        Map<String, Object> meta = store.readMeta();
        if (meta != null) {
            String metaMd5 = Objects.toString(meta.get("expectedMd5"), null);
            long metaLen = toLong(meta.get("expectedLength"));
            String metaPath = Objects.toString(meta.get("filePath"), null);
            if (Objects.equals(metaPath, filePath) && Objects.equals(metaMd5, expectedMd5) && metaLen == expectedLength) {
                return loadFromDisk(fileNode, userId, sessionId, taskId, store, taskExecutor);
            }
        }

        File finalFile = resolveFinalFile(store, filePath);
        int totalChunks = (int) ((expectedLength + chunkSize - 1) / (long) chunkSize);
        BitSet done = new BitSet(totalChunks);
        store.requireUsableSpace(expectedLength);

        DownloadTask task = new DownloadTask(
                fileNode, userId, sessionId, taskId, filePath, finalFile, threads, chunkSize,
                expectedLength, expectedMd5, store, taskExecutor, done, totalChunks
        );
        task.persistMeta(TransferTaskState.NEW);
        task.persistChunks();
        task.prepareTempFile();
        return task;
    }

    public static DownloadTask loadFromDisk(FileCapable fileNode,
                                            String userId,
                                            String sessionId,
                                            String taskId,
                                            DownloadStore store,
                                            ServiceTaskExecutor taskExecutor) throws Exception {
        Map<String, Object> meta = store.readMeta();
        if (meta == null) {
            throw new IllegalStateException("任务元数据缺失: " + taskId);
        }
        if (!Objects.equals(userId, Objects.toString(meta.get("userId"), null))
                || !Objects.equals(sessionId, Objects.toString(meta.get("sessionId"), null))) {
            throw new IllegalArgumentException("任务归属不匹配: " + taskId);
        }
        String filePath = Objects.toString(meta.get("filePath"), null);
        int threads = (int) toLong(meta.get("threads"));
        int chunkSize = (int) toLong(meta.get("chunkSize"));
        long expectedLength = toLong(meta.get("expectedLength"));
        String expectedMd5 = Objects.toString(meta.get("expectedMd5"), null);
        String finalPath = Objects.toString(meta.get("finalPath"), null);
        File finalFile = finalPath != null ? new File(finalPath) : resolveFinalFile(store, filePath);

        int totalChunks = (int) ((expectedLength + chunkSize - 1) / (long) chunkSize);
        BitSet done = new BitSet(totalChunks);
        byte[] bm = store.readChunksBitmap();
        if (bm != null && bm.length > 0) {
            done = BitSet.valueOf(bm);
        }

        DownloadTask task = new DownloadTask(
                fileNode, userId, sessionId, taskId, filePath, finalFile, threads, chunkSize,
                expectedLength, expectedMd5, store, taskExecutor, done, totalChunks
        );
        task.createAtMs = toLong(meta.get("createAtMs"));
        task.lastError = Objects.toString(meta.get("lastError"), null);
        task.state = parseState(Objects.toString(meta.get("state"), "NEW"));
        if (task.state == TransferTaskState.RUNNING) {
            task.state = TransferTaskState.PAUSED;
        }
        task.currentStage = parseStage(Objects.toString(meta.get("currentStage"), "CREATED"));
        task.errorStage = parseNullableStage(Objects.toString(meta.get("errorStage"), null));
        task.startAtMs = toLong(meta.get("startAtMs"));
        task.endAtMs = toLong(meta.get("endAtMs"));
        task.updatedAtMs = Math.max(task.createAtMs, toLong(meta.get("updatedAtMs")));
        task.downloadedBytes.set(calculateDownloadedBytes(done, totalChunks, chunkSize, expectedLength));
        if (!task.state.isTerminal() || task.state == TransferTaskState.FAILED) {
            task.prepareTempFile();
        }
        if (Objects.toString(meta.get("state"), "NEW").equals(TransferTaskState.RUNNING.name())) {
            task.persistMetaQuiet(TransferTaskState.PAUSED);
        }
        return task;
    }

    public synchronized void ensureStarted() {
        if (state == TransferTaskState.RUNNING) {
            return;
        }
        if (!state.canStart()) {
            return;
        }
        cancelled.set(false);
        if (!started.compareAndSet(false, true)) {
            return;
        }
        this.state = TransferTaskState.RUNNING;
        this.currentStage = TransferStage.PREPARING;
        this.startAtMs = System.currentTimeMillis();
        this.endAtMs = 0L;
        touch();
        this.startAtForSpeedMs.set(this.startAtMs);
        this.bytesAtStart.set(this.downloadedBytes.get());
        try {
            store.requireUsableSpace(Math.max(0L, expectedLength - downloadedBytes.get()));
            currentStage = TransferStage.TRANSFERRING;
            persistMeta(TransferTaskState.RUNNING);
        } catch (Exception e) {
            fail("准备下载任务失败: " + e.getMessage());
            return;
        }

        try {
            workerFutures.addAll(taskExecutor.submitDownloadWorkers(threads, this::workerLoop));
            if (cancelled.get() || state.isTerminal()) {
                cancelWorkers();
            }
        } catch (RejectedExecutionException error) {
            cancelled.set(true);
            fail("下载任务队列繁忙");
        }
    }

    public synchronized void pause() {
        if (state != TransferTaskState.RUNNING) {
            return;
        }
        cancelled.set(true);
        state = TransferTaskState.PAUSED;
        touch();
        cancelWorkers();
        persistChunksQuiet();
        persistMetaQuiet(TransferTaskState.PAUSED);
        started.set(false);
    }

    public synchronized void cancel() {
        if (state.isTerminal()) {
            return;
        }
        cancelled.set(true);
        state = TransferTaskState.CANCELLED;
        currentStage = TransferStage.FINISHED;
        endAtMs = System.currentTimeMillis();
        touch();
        persistMetaQuiet(TransferTaskState.CANCELLED);
        cancelWorkers();
        started.set(false);
    }

    public synchronized void suspendForShutdown() {
        if (state != TransferTaskState.RUNNING) {
            return;
        }
        cancelled.set(true);
        state = TransferTaskState.PAUSED;
        touch();
        cancelWorkers();
        persistChunksQuiet();
        persistMetaQuiet(TransferTaskState.PAUSED);
        started.set(false);
    }

    public synchronized boolean resetFailedForRetry() {
        if (state != TransferTaskState.FAILED) {
            return false;
        }
        state = TransferTaskState.PAUSED;
        currentStage = TransferStage.PREPARING;
        errorStage = null;
        lastError = null;
        endAtMs = 0L;
        cancelled.set(false);
        started.set(false);
        touch();
        persistMetaQuiet(TransferTaskState.PAUSED);
        return true;
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> m = new HashMap<>();
        m.put("taskId", taskId);
        m.put("sessionId", sessionId);
        m.put("filePath", filePath);
        m.put("state", state.name());
        m.put("currentStage", currentStage.name());
        m.put("threads", threads);
        m.put("chunkSize", chunkSize);
        m.put("expectedLength", expectedLength);
        m.put("expectedMd5", expectedMd5);
        m.put("doneChunks", doneCount.get());
        m.put("totalChunks", totalChunks);
        m.put("downloadedBytes", downloadedBytes.get());
        m.put("speedBytesPerSec", calcSpeedBytesPerSec());
        // Never expose absolute paths to frontend; provide paths usable by download-local API.
        String downloadPath = store.toUserRelativePath(finalFile);
        String taskTempPath = store.toUserRelativePath(tempFile);
        if (downloadPath != null) {
            m.put("downloadPath", downloadPath);
        }
        if (taskTempPath != null) {
            m.put("taskTempPath", taskTempPath);
        }
        if (lastError != null) {
            m.put("lastError", lastError);
        }
        if (errorStage != null) {
            m.put("errorStage", errorStage.name());
        }
        m.put("createAtMs", createAtMs);
        m.put("startAtMs", startAtMs);
        m.put("endAtMs", endAtMs);
        m.put("updatedAtMs", updatedAtMs);
        return m;
    }

    private long calcSpeedBytesPerSec() {
        long startMs = startAtForSpeedMs.get();
        if (startMs <= 0) {
            return 0;
        }
        long elapsedMs = Math.max(1, System.currentTimeMillis() - startMs);
        long delta = downloadedBytes.get() - bytesAtStart.get();
        if (delta < 0) {
            delta = 0;
        }
        return (delta * 1000L) / elapsedMs;
    }

    private void workerLoop() {
        try {
            while (!cancelled.get()) {
                int idx = allocateChunkIndex();
                if (idx < 0) {
                    if (doneCount.get() >= totalChunks) {
                        completeIfNeeded();
                    }
                    return;
                }
                long offset = (long) idx * (long) chunkSize;
                int reqSize = chunkSize;
                if (offset + reqSize > expectedLength) {
                    reqSize = (int) (expectedLength - offset);
                }
                try {
                    downloadOneChunk(idx, offset, reqSize);
                } finally {
                    releaseInProgress(idx);
                }
            }
        } catch (Throwable t) {
            if (cancelled.get() || state == TransferTaskState.PAUSED
                    || state == TransferTaskState.CANCELLED) {
                return;
            }
            fail("worker异常: " + t.getMessage());
        }
    }

    private void downloadOneChunk(int chunkIndex, long offset, int size) throws Exception {
        if (isChunkDone(chunkIndex)) {
            return;
        }

        int maxRetries = 5;
        long backoffMs = 200;
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            if (cancelled.get()) return;
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> res = fileNode.fileDownloadChunk(filePath, size, offset);
                int code = res.get("code") instanceof Number number ? number.intValue() : -1;
                if (code == 404 || code == 403 || code == 416) {
                    throw new IllegalStateException("不可恢复错误: code=" + code + ", msg=" + res.get("msg"));
                }
                if (code != 100 && code != 200) {
                    throw new IllegalStateException("远端下载返回状态异常: code=" + code);
                }
                Object dataObj = res.get("data");
                byte[] data = (dataObj instanceof byte[]) ? (byte[]) dataObj : null;
                if (data == null) {
                    throw new IllegalStateException("响应缺少data");
                }
                if (!(res.get("bytesRead") instanceof Number readCount)
                        || readCount.intValue() != data.length) {
                    throw new IllegalStateException("远端下载返回字节数无效");
                }
                int bytesRead = readCount.intValue();
                if (bytesRead <= 0) {
                    throw new IllegalStateException("读取到空数据: offset=" + offset);
                }

                writeChunk(offset, data, bytesRead);
                markChunkDone(chunkIndex, bytesRead);
                maybePersist();
                return;
            } catch (Exception e) {
                if (attempt == maxRetries) {
                    throw e;
                }
                Thread.sleep(backoffMs);
                backoffMs = Math.min(2000, backoffMs * 2);
            }
        }
    }

    private void writeChunk(long offset, byte[] data, int len) throws Exception {
        synchronized (writeLock) {
            try (RandomAccessFile raf = new RandomAccessFile(tempFile, "rw");
                 FileChannel ch = raf.getChannel()) {
                ch.position(offset);
                ch.write(java.nio.ByteBuffer.wrap(data, 0, len));
            }
        }
    }

    private void markChunkDone(int chunkIndex, int bytes) {
        synchronized (doneChunks) {
            if (!doneChunks.get(chunkIndex)) {
                doneChunks.set(chunkIndex);
                inProgressChunks.clear(chunkIndex);
                doneCount.incrementAndGet();
                downloadedBytes.addAndGet(bytes);
                touch();
            }
        }
    }

    private boolean isChunkDone(int chunkIndex) {
        synchronized (doneChunks) {
            return doneChunks.get(chunkIndex);
        }
    }

    private int allocateChunkIndex() {
        synchronized (doneChunks) {
            if (doneChunks.cardinality() >= totalChunks) {
                return -1;
            }
            int start = scanCursor;
            for (int i = 0; i < totalChunks; i++) {
                int idx = (start + i) % totalChunks;
                if (!doneChunks.get(idx) && !inProgressChunks.get(idx)) {
                    inProgressChunks.set(idx);
                    scanCursor = (idx + 1) % totalChunks;
                    return idx;
                }
            }
            return -1;
        }
    }

    private void releaseInProgress(int chunkIndex) {
        synchronized (doneChunks) {
            if (!doneChunks.get(chunkIndex)) {
                inProgressChunks.clear(chunkIndex);
            }
        }
    }

    private void completeIfNeeded() {
        synchronized (this) {
            if (state.isTerminal() || state == TransferTaskState.PAUSED) {
                return;
            }
            if (doneCount.get() < totalChunks) {
                return;
            }
            try {
                currentStage = TransferStage.VERIFYING_REMOTE;
                touch();
                @SuppressWarnings("unchecked")
                Map<String, Object> md5Res = fileNode.getFileMD5(filePath);
                if (!(md5Res.get("code") instanceof Number code) || code.intValue() != 200) {
                    throw new IllegalStateException(Objects.toString(
                            md5Res.get("msg"), "远端校验未返回成功状态"));
                }
                String remoteMd5 = Objects.toString(md5Res.get("md5"), null);
                if (remoteMd5 == null || !remoteMd5.equalsIgnoreCase(expectedMd5)) {
                    fail("远端文件MD5不一致，可能发生变更，expected=" + expectedMd5 + ", actual=" + remoteMd5);
                    return;
                }
                currentStage = TransferStage.VERIFYING_LOCAL;
                touch();
                String localMd5 = md5Hex(tempFile);
                if (!localMd5.equalsIgnoreCase(expectedMd5)) {
                    fail("本地临时文件MD5不一致，expected=" + expectedMd5 + ", actual=" + localMd5);
                    return;
                }
                currentStage = TransferStage.COMMITTING;
                touch();
                finalizeFile();
                state = TransferTaskState.COMPLETED;
                currentStage = TransferStage.FINISHED;
                endAtMs = System.currentTimeMillis();
                touch();
                persistMeta(TransferTaskState.COMPLETED);
                started.set(false);
                cancelWorkers();
            } catch (Exception e) {
                fail("完成阶段失败: " + e.getMessage());
            }
        }
    }

    private void finalizeFile() throws Exception {
        File parent = finalFile.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        java.nio.file.Files.move(tempFile.toPath(), finalFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    private void fail(String msg) {
        if (state == TransferTaskState.PAUSED || state == TransferTaskState.CANCELLED
                || state == TransferTaskState.COMPLETED) {
            return;
        }
        lastError = msg;
        errorStage = currentStage;
        state = TransferTaskState.FAILED;
        endAtMs = System.currentTimeMillis();
        touch();
        persistMetaQuiet(TransferTaskState.FAILED);
        started.set(false);
        cancelWorkers();
    }

    private void cancelWorkers() {
        synchronized (workerFutures) {
            taskExecutor.cancelDownloadWorkers(workerFutures);
            workerFutures.clear();
        }
    }

    private void prepareTempFile() throws Exception {
        File parent = tempFile.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        if (!tempFile.exists()) {
            tempFile.createNewFile();
        }
        try (RandomAccessFile raf = new RandomAccessFile(tempFile, "rw")) {
            if (raf.length() != expectedLength) {
                raf.setLength(expectedLength);
            }
        }
    }

    private void maybePersist() {
        long now = System.currentTimeMillis();
        long last = lastPersistAtMs.get();
        if (now - last < 1000) {
            return;
        }
        if (!lastPersistAtMs.compareAndSet(last, now)) {
            return;
        }
        persistMetaQuiet(state);
        persistChunksQuiet();
    }

    private void persistMeta(TransferTaskState newState) throws Exception {
        Map<String, Object> meta = new HashMap<>();
        meta.put("taskId", taskId);
        meta.put("userId", userId);
        meta.put("sessionId", sessionId);
        meta.put("filePath", filePath);
        meta.put("threads", threads);
        meta.put("chunkSize", chunkSize);
        meta.put("expectedLength", expectedLength);
        meta.put("expectedMd5", expectedMd5);
        meta.put("finalPath", finalFile.getAbsolutePath());
        meta.put("tempPath", tempFile.getAbsolutePath());
        meta.put("totalChunks", totalChunks);
        meta.put("doneChunks", doneCount.get());
        meta.put("downloadedBytes", downloadedBytes.get());
        meta.put("state", newState.name());
        meta.put("currentStage", currentStage.name());
        meta.put("createAtMs", createAtMs);
        meta.put("startAtMs", startAtMs);
        meta.put("endAtMs", endAtMs);
        meta.put("updatedAtMs", updatedAtMs);
        meta.put("lastUpdate", Instant.now().toString());
        if (lastError != null) {
            meta.put("lastError", lastError);
        }
        if (errorStage != null) {
            meta.put("errorStage", errorStage.name());
        }
        store.writeMeta(meta);
    }

    private void persistMetaQuiet(TransferTaskState s) {
        try {
            persistMeta(s);
        } catch (Exception ignored) {
        }
    }

    private void persistChunks() throws Exception {
        store.writeChunksBitmap(doneChunks.toByteArray());
    }

    private void persistChunksQuiet() {
        try {
            persistChunks();
        } catch (Exception ignored) {
        }
    }

    private static File resolveFinalFile(DownloadStore store, String remoteFilePath) {
        // Default: store under user's downloads dir, filename same as remote (auto rename if conflict).
        return store.resolveUniqueFinalFile(remoteFilePath);
    }

    private static long toLong(Object obj) {
        return obj instanceof Number number ? number.longValue() : 0L;
    }

    public TransferTaskState getState() {
        return state;
    }

    public long getEndAtMs() {
        return endAtMs;
    }

    public boolean belongsTo(String expectedUserId, String expectedSessionId) {
        return Objects.equals(userId, expectedUserId) && Objects.equals(sessionId, expectedSessionId);
    }

    public void requireOwner(String expectedUserId, String expectedSessionId) {
        if (!belongsTo(expectedUserId, expectedSessionId)) {
            throw new IllegalArgumentException("任务归属不匹配: " + taskId);
        }
    }

    public String getUserId() {
        return userId;
    }

    public String getTaskId() {
        return taskId;
    }

    public boolean isActive() {
        return state.isActive();
    }

    private void touch() {
        updatedAtMs = System.currentTimeMillis();
    }

    private static long calculateDownloadedBytes(BitSet done, int totalChunks,
                                                 int chunkSize, long expectedLength) {
        long total = 0L;
        for (int index = done.nextSetBit(0); index >= 0 && index < totalChunks;
             index = done.nextSetBit(index + 1)) {
            long offset = (long) index * chunkSize;
            total += Math.min(chunkSize, expectedLength - offset);
        }
        return total;
    }

    private static String md5Hex(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("MD5");
        FileInputStream input = new FileInputStream(file);
        try {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) {
                    digest.update(buffer, 0, read);
                }
            }
        } finally {
            input.close();
        }
        byte[] value = digest.digest();
        StringBuilder hex = new StringBuilder(value.length * 2);
        for (byte b : value) {
            hex.append(String.format("%02x", b & 0xff));
        }
        return hex.toString();
    }

    private static TransferTaskState parseState(String s) {
        try {
            return TransferTaskState.valueOf(s);
        } catch (Exception e) {
            return TransferTaskState.NEW;
        }
    }

    private static TransferStage parseStage(String value) {
        try {
            return TransferStage.valueOf(value);
        } catch (Exception error) {
            return TransferStage.CREATED;
        }
    }

    private static TransferStage parseNullableStage(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return parseStage(value);
    }
}
