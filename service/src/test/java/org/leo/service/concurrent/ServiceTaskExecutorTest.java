package org.leo.service.concurrent;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServiceTaskExecutorTest {

    @Test
    void boundsSqlExportQueueAndUsesDaemonThreads() throws Exception {
        try (ServiceTaskExecutor executor = executor()) {
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            AtomicBoolean daemon = new AtomicBoolean(false);
            AtomicReference<String> threadName = new AtomicReference<>();

            executor.submitSqlExport(() -> {
                daemon.set(Thread.currentThread().isDaemon());
                threadName.set(Thread.currentThread().getName());
                await(started, release);
            });
            assertTrue(started.await(1, TimeUnit.SECONDS));
            executor.submitSqlExport(() -> { });

            assertEquals(1, executor.activeSqlTasks());
            assertEquals(1, executor.queuedSqlTasks());
            assertThrows(RejectedExecutionException.class,
                    () -> executor.submitSqlExport(() -> { }));
            assertTrue(daemon.get());
            assertTrue(threadName.get().startsWith("sql-export-"));
            release.countDown();
        }
    }

    @Test
    void boundsUploadQueue() throws Exception {
        try (ServiceTaskExecutor executor = executor()) {
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            executor.submitUpload(() -> await(started, release));
            assertTrue(started.await(1, TimeUnit.SECONDS));
            Future<?> queued = executor.submitUpload(() -> { });

            assertEquals(1, executor.activeUploadTasks());
            assertEquals(1, executor.queuedUploadTasks());
            assertThrows(RejectedExecutionException.class,
                    () -> executor.submitUpload(() -> { }));
            executor.cancelUpload(queued);
            assertEquals(0, executor.queuedUploadTasks());
            release.countDown();
        }
    }

    @Test
    void rollsBackPartiallyAcceptedDownloadWorkers() throws Exception {
        try (ServiceTaskExecutor executor = executor()) {
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            executor.submitDownloadWorkers(1, () -> await(started, release));
            assertTrue(started.await(1, TimeUnit.SECONDS));

            assertThrows(RejectedExecutionException.class,
                    () -> executor.submitDownloadWorkers(2, () -> { }));

            assertEquals(1, executor.activeDownloadWorkers());
            assertEquals(0, executor.queuedDownloadWorkers());
            release.countDown();
        }
    }

    @Test
    void closeCancelsQueuedWorkInEveryExecutionDomain() throws Exception {
        ServiceTaskExecutor executor = executor();
        try {
            CountDownLatch started = new CountDownLatch(3);
            CountDownLatch release = new CountDownLatch(1);
            executor.submitSqlExport(() -> await(started, release));
            executor.submitUpload(() -> await(started, release));
            executor.submitDownloadWorkers(1, () -> await(started, release));
            assertTrue(started.await(5, TimeUnit.SECONDS));
            Future<?> sql = executor.submitSqlExport(() -> { });
            Future<?> upload = executor.submitUpload(() -> { });
            Future<?> download = executor.submitDownloadWorkers(1, () -> { }).get(0);

            executor.close();

            assertTrue(sql.isCancelled());
            assertTrue(upload.isCancelled());
            assertTrue(download.isCancelled());
        } finally {
            executor.close();
        }
    }

    private ServiceTaskExecutor executor() {
        return new ServiceTaskExecutor(1, 1, 1, 1, 1, 1);
    }

    private static void await(CountDownLatch started, CountDownLatch release) {
        started.countDown();
        try {
            release.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
