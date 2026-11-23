package org.leo.service.concurrent;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
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
            Future<?> queued = executor.submitSqlExport(() -> { });

            assertThrows(RejectedExecutionException.class,
                    () -> executor.submitSqlExport(() -> { }));
            assertTrue(daemon.get());
            assertTrue(threadName.get().startsWith("sql-export-"));
            release.countDown();
            queued.get(5, TimeUnit.SECONDS);
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

            assertThrows(RejectedExecutionException.class,
                    () -> executor.submitUpload(() -> { }));
            executor.cancelUpload(queued);
            assertTrue(queued.isCancelled());
            Future<?> replacement = executor.submitUpload(() -> { });
            release.countDown();
            replacement.get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void rollsBackPartiallyAcceptedDownloadWorkers() throws Exception {
        try (ServiceTaskExecutor executor = executor()) {
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            AtomicBoolean rejectedWorkerRan = new AtomicBoolean(false);
            executor.submitDownloadWorkers(1, () -> await(started, release));
            assertTrue(started.await(1, TimeUnit.SECONDS));

            assertThrows(RejectedExecutionException.class,
                    () -> executor.submitDownloadWorkers(2, () -> rejectedWorkerRan.set(true)));

            Future<?> replacement = executor.submitDownloadWorkers(1, () -> { }).get(0);
            release.countDown();
            replacement.get(5, TimeUnit.SECONDS);
            assertFalse(rejectedWorkerRan.get());
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
