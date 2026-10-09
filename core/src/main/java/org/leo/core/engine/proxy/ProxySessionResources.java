package org.leo.core.engine.proxy;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Shared connection reservations and bounded dialers for one node session. */
public final class ProxySessionResources implements AutoCloseable {
    private final Semaphore connections;
    private final ThreadPoolExecutor dialers;
    private volatile boolean closed;

    public ProxySessionResources(int maxConnections, int maxDialers) {
        if (maxConnections < 1 || maxDialers < 1) {
            throw new IllegalArgumentException("proxy resource limits must be positive");
        }
        connections = new Semaphore(maxConnections);
        dialers = newDialerExecutor(Math.min(maxDialers, maxConnections), maxConnections);
    }

    public ConnectionPermit tryAcquireConnection() {
        if (closed || !connections.tryAcquire()) return null;
        if (closed) {
            connections.release();
            return null;
        }
        return new ConnectionPermit(connections, new AtomicInteger(1));
    }

    public ThreadPoolExecutor dialers() {
        return dialers;
    }

    public boolean isClosed() {
        return closed;
    }

    public static ThreadPoolExecutor newDialerExecutor(int workers, int queueSize) {
        return newDaemonExecutor("Proxy-Dialer", workers, queueSize);
    }

    public static ThreadPoolExecutor newDaemonExecutor(String prefix, int workers, int queueSize) {
        AtomicInteger sequence = new AtomicInteger();
        ThreadPoolExecutor executor = new ThreadPoolExecutor(workers, workers, 60, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(queueSize), task -> {
                    Thread thread = new Thread(task, prefix + "-" + sequence.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }

    @Override
    public void close() {
        closed = true;
        dialers.shutdownNow();
    }

    /** Keep the slot until every relay and in-flight RPC has finished, not just socket close. */
    public static final class ConnectionPermit implements AutoCloseable {
        private final Semaphore slots;
        private final AtomicInteger owners;
        private final AtomicBoolean released = new AtomicBoolean();

        private ConnectionPermit(Semaphore slots, AtomicInteger owners) {
            this.slots = slots;
            this.owners = owners;
        }

        public ConnectionPermit retain() {
            int count;
            do {
                count = owners.get();
                if (count == 0 || released.get()) throw new IllegalStateException("connection slot released");
            } while (!owners.compareAndSet(count, count + 1));
            return new ConnectionPermit(slots, owners);
        }

        @Override
        public void close() {
            if (released.compareAndSet(false, true) && owners.decrementAndGet() == 0) slots.release();
        }
    }
}
