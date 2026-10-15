package org.leo.web.service;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Serial, finite turns per task. Timers never perform node I/O or database work. */
final class NetworkProbeScheduler implements AutoCloseable {
    interface Step { long run() throws Exception; } // Negative delay parks until explicitly woken.
    static final class Ticket {
        final Step step;
        final Consumer<Exception> failure;
        ScheduledFuture<?> timer;
        boolean running, again;
        volatile boolean retired;
        long revision;
        Ticket(Step step, Consumer<Exception> failure) { this.step = step; this.failure = failure; }
    }
    private final ExecutorService workers;
    private final ScheduledThreadPoolExecutor timers;
    private final Set<Ticket> tickets = new HashSet<>();
    private boolean closed;

    NetworkProbeScheduler(ExecutorService workers, String name) {
        this.workers = workers;
        timers = new ScheduledThreadPoolExecutor(1, action -> {
            Thread thread = new Thread(action, name + "-timer"); thread.setDaemon(true); return thread;
        });
        timers.setRemoveOnCancelPolicy(true);
    }
    synchronized Ticket ticket(Step step, Consumer<Exception> failure) {
        if (closed) throw new IllegalStateException("网络探测调度器已关闭");
        Ticket ticket = new Ticket(step, failure); tickets.add(ticket); return ticket;
    }
    void wake(Ticket ticket) { schedule(ticket, 0); }
    void schedule(Ticket ticket, long delay) {
        synchronized (ticket) {
            if (ticket.retired) return;
            if (ticket.running) { ticket.again = true; return; }
            if (ticket.timer != null) ticket.timer.cancel(false);
            long revision = ++ticket.revision;
            ticket.timer = timers.schedule(() -> dispatch(ticket, revision), delay, TimeUnit.MILLISECONDS);
        }
    }
    private void dispatch(Ticket ticket, long revision) {
        synchronized (ticket) {
            if (ticket.retired || revision != ticket.revision) return;
            ticket.timer = null; ticket.running = true;
        }
        try { workers.execute(() -> turn(ticket)); }
        catch (RuntimeException error) { try { ticket.failure.accept(error); } finally { retire(ticket); } }
    }
    private void turn(Ticket ticket) {
        long delay = -1;
        try { if (!ticket.retired) delay = ticket.step.run(); }
        catch (Exception error) { try { ticket.failure.accept(error); } finally { retire(ticket); } }
        finally {
            synchronized (ticket) {
                ticket.running = false;
                if (ticket.again) { ticket.again = false; delay = 0; }
                if (!ticket.retired && delay >= 0) schedule(ticket, delay);
            }
        }
    }
    void retire(Ticket ticket) {
        synchronized (ticket) {
            ticket.retired = true; ++ticket.revision;
            if (ticket.timer != null) ticket.timer.cancel(false);
        }
        synchronized (this) { tickets.remove(ticket); }
    }
    @Override public void close() {
        Set<Ticket> copy;
        synchronized (this) { closed = true; copy = new HashSet<>(tickets); }
        for (Ticket ticket : copy) retire(ticket);
        timers.shutdownNow(); workers.shutdownNow();
    }
}
