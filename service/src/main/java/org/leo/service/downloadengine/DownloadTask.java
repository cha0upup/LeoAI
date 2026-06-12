package org.leo.service.downloadengine;

import org.leo.core.puppet.impl.JavaPuppetNode;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.time.Instant;
import java.util.BitSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class DownloadTask {
    public enum State {
        NEW,
        RUNNING,
        CANCELLED,
        FAILED,
        COMPLETED
    }

    private final JavaPuppetNode puppetNode;
    private final String sessionId;
    private final String taskId;
    private final DownloadStore store;

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

    private volatile State state = State.NEW;
    private volatile String lastError;
    private volatile long createAtMs;
    private volatile long startAtMs;
    private volatile long endAtMs;

    private ExecutorService executor;
    private final Object writeLock = new Object();

    private DownloadTask(JavaPuppetNode puppetNode,
                         String sessionId,
                         String taskId,
                         String filePath,
                         File finalFile,
                         int threads,
                         int chunkSize,
                         long expectedLength,
                         String expectedMd5,
                         DownloadStore store,
                         BitSet doneChunks,
                         int totalChunks) {
        this.puppetNode = puppetNode;
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
        this.doneChunks = doneChunks;
        this.inProgressChunks = new BitSet(totalChunks);
        this.totalChunks = totalChunks;
        this.doneCount = new AtomicInteger(doneChunks.cardinality());
        this.createAtMs = System.currentTimeMillis();
    }

    public static DownloadTask createNewOrLoad(JavaPuppetNode puppetNode,
                                               String sessionId,
                                               String taskId,
                                               String filePath,
                                               int threads,
                                               int chunkSize,
                                               long expectedLength,
                                               String expectedMd5,
                                               DownloadStore store) throws Exception {
        Map<String, Object> meta = store.readMeta();
        if (meta != null) {
            String metaMd5 = Objects.toString(meta.get("expectedMd5"), null);
            long metaLen = toLong(meta.get("expectedLength"));
            String metaPath = Objects.toString(meta.get("filePath"), null);
            if (Objects.equals(metaPath, filePath) && Objects.equals(metaMd5, expectedMd5) && metaLen == expectedLength) {
                return loadFromDisk(puppetNode, sessionId, taskId, store);
            }
        }

        File finalFile = resolveFinalFile(store, filePath);
        int totalChunks = (int) ((expectedLength + chunkSize - 1) / (long) chunkSize);
        BitSet done = new BitSet(totalChunks);

        DownloadTask task = new DownloadTask(
                puppetNode, sessionId, taskId, filePath, finalFile, threads, chunkSize, expectedLength, expectedMd5, store, done, totalChunks
        );
        task.persistMeta(State.NEW);
        task.persistChunks();
        task.prepareTempFile();
        return task;
    }

    public static DownloadTask loadFromDisk(JavaPuppetNode puppetNode, String sessionId, String taskId, DownloadStore store) throws Exception {
        Map<String, Object> meta = store.readMeta();
        if (meta == null) {
            throw new IllegalStateException("任务元数据缺失: " + taskId);
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
                puppetNode, sessionId, taskId, filePath, finalFile, threads, chunkSize, expectedLength, expectedMd5, store, done, totalChunks
        );
        task.createAtMs = toLong(meta.get("createAtMs"));
        task.lastError = Objects.toString(meta.get("lastError"), null);
        task.state = parseState(Objects.toString(meta.get("state"), "NEW"));
        task.prepareTempFile();
        return task;
    }

    public void ensureStarted() {
        if (cancelled.get()) {
            return;
        }
        if (!started.compareAndSet(false, true)) {
            return;
        }
        if (state == State.COMPLETED) {
            return;
        }
        this.executor = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r);
            t.setName("download-" + taskId);
            t.setDaemon(true);
            return t;
        });
        this.state = State.RUNNING;
        this.startAtMs = System.currentTimeMillis();
        this.startAtForSpeedMs.set(this.startAtMs);
        this.bytesAtStart.set(this.downloadedBytes.get());
        try {
            persistMeta(State.RUNNING);
        } catch (Exception e) {
            fail("写入任务元数据失败: " + e.getMessage());
            return;
        }

        for (int i = 0; i < threads; i++) {
            executor.submit(this::workerLoop);
        }
    }

    public void cancel() {
        cancelled.set(true);
        state = State.CANCELLED;
        persistMetaQuiet(State.CANCELLED);
        ExecutorService ex = executor;
        if (ex != null) {
            ex.shutdownNow();
        }
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> m = new HashMap<>();
        m.put("taskId", taskId);
        m.put("sessionId", sessionId);
        m.put("filePath", filePath);
        m.put("state", state.name());
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
        m.put("createAtMs", createAtMs);
        m.put("startAtMs", startAtMs);
        m.put("endAtMs", endAtMs);
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
                Map<String, Object> res = puppetNode.fileDownloadChunk(filePath, size, offset);
                int code = (int) toLong(res.get("code"));
                if (code == 404 || code == 403 || code == 416) {
                    throw new IllegalStateException("不可恢复错误: code=" + code + ", msg=" + res.get("msg"));
                }
                Object dataObj = res.get("data");
                byte[] data = (dataObj instanceof byte[]) ? (byte[]) dataObj : null;
                if (data == null) {
                    throw new IllegalStateException("响应缺少data");
                }
                int bytesRead = (int) toLong(res.get("bytesRead"));
                if (bytesRead != data.length) {
                    bytesRead = data.length;
                }
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
            if (state == State.COMPLETED || state == State.FAILED || state == State.CANCELLED) {
                return;
            }
            if (doneCount.get() < totalChunks) {
                return;
            }
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> md5Res = puppetNode.getFileMD5(filePath);
                String remoteMd5 = Objects.toString(md5Res.get("md5"), Objects.toString(md5Res.get("data"), null));
                if (remoteMd5 == null || !remoteMd5.equalsIgnoreCase(expectedMd5)) {
                    fail("远端文件MD5不一致，可能发生变更，expected=" + expectedMd5 + ", actual=" + remoteMd5);
                    return;
                }
                finalizeFile();
                state = State.COMPLETED;
                endAtMs = System.currentTimeMillis();
                persistMeta(State.COMPLETED);
                shutdownExecutor();
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
        lastError = msg;
        state = State.FAILED;
        endAtMs = System.currentTimeMillis();
        persistMetaQuiet(State.FAILED);
        shutdownExecutor();
    }

    private void shutdownExecutor() {
        ExecutorService ex = executor;
        if (ex != null) {
            ex.shutdownNow();
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

    private void persistMeta(State newState) throws Exception {
        Map<String, Object> meta = new HashMap<>();
        meta.put("taskId", taskId);
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
        meta.put("createAtMs", createAtMs);
        meta.put("startAtMs", startAtMs);
        meta.put("endAtMs", endAtMs);
        meta.put("lastUpdate", Instant.now().toString());
        if (lastError != null) {
            meta.put("lastError", lastError);
        }
        store.writeMeta(meta);
    }

    private void persistMetaQuiet(State s) {
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
        if (obj == null) return 0L;
        if (obj instanceof Number) return ((Number) obj).longValue();
        return Long.parseLong(String.valueOf(obj));
    }

    public State getState() {
        return state;
    }

    public long getEndAtMs() {
        return endAtMs;
    }

    private static State parseState(String s) {
        try {
            return State.valueOf(s);
        } catch (Exception e) {
            return State.NEW;
        }
    }
}

