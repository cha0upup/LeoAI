package org.leo.ai.service.workspace;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.leo.ai.config.AiWorkspaceProperties;
import org.leo.core.config.LeoConfig;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import static org.junit.jupiter.api.Assertions.*;

class AiWorkspaceLockingTest {

    @TempDir Path temp;
    private String previousVfs;
    private AiWorkspaceService service;

    @BeforeEach
    void setUp() {
        previousVfs = LeoConfig.getVfsPath();
        ReflectionTestUtils.setField(LeoConfig.class, "VFS_PATH", temp.toString());
        service = new AiWorkspaceService(new AiWorkspaceProperties());
    }

    @AfterEach
    void tearDown() {
        ReflectionTestUtils.setField(LeoConfig.class, "VFS_PATH", previousVfs);
    }

    @Test
    void boundsLockStorageWhileKeepingTheIdentityOfPreviouslyOpenedWorkspaces() {
        var original = open("original");
        Set<ReentrantReadWriteLock> identities = Collections.newSetFromMap(new IdentityHashMap<>());
        for (int i = 0; i < 600; i++) identities.add(open("workspace-" + i).lock());
        assertTrue(identities.size() <= 256, "Workspace history should not grow the lock registry");
        assertSame(original.lock(), open("original").lock());
    }

    @Test
    void reopeningAnActiveWorkspaceStillWaitsForItsOriginalWriter() throws Exception {
        var original = open("active");
        var executor = Executors.newSingleThreadExecutor();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(1);
        original.lock().writeLock().lock();
        try {
            var reopened = open("active");
            assertSame(original.lock(), reopened.lock());
            Future<?> writer = executor.submit(() -> {
                started.countDown();
                service.writeText(reopened, "result.txt", "written", null);
                completed.countDown();
            });
            assertTrue(started.await(2, TimeUnit.SECONDS));
            assertFalse(completed.await(100, TimeUnit.MILLISECONDS));
            original.lock().writeLock().unlock();
            writer.get(2, TimeUnit.SECONDS);
            assertEquals("written", Files.readString(reopened.filesRoot().resolve("result.txt")));
        } finally {
            if (original.lock().isWriteLockedByCurrentThread()) original.lock().writeLock().unlock();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void concurrentUpdatesWithTheSameVersionCommitOnlyOnce() throws Exception {
        var first = open("shared");
        var second = open("shared");
        String hash = String.valueOf(service.writeText(first, "value.txt", "initial", null).get("sha256"));
        var executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            var a = executor.submit(() -> update(start, first, "first", hash));
            var b = executor.submit(() -> update(start, second, "second", hash));
            start.countDown();
            assertNotEquals(a.get(3, TimeUnit.SECONDS), b.get(3, TimeUnit.SECONDS));
            assertTrue(Set.of("first", "second").contains(Files.readString(first.filesRoot().resolve("value.txt"))));
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    private boolean update(CountDownLatch start, AiWorkspaceService.Workspace workspace,
                           String value, String hash) throws InterruptedException {
        start.await();
        try {
            service.writeText(workspace, "value.txt", value, hash);
            return true;
        } catch (IllegalArgumentException staleVersion) {
            return false;
        }
    }

    private AiWorkspaceService.Workspace open(String name) {
        return service.open("fixture-user", AiWorkspaceService.sha256(name.getBytes(StandardCharsets.UTF_8)).substring(0, 32));
    }
}
