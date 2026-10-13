package org.leo.core.engine.proxy;

import org.leo.core.agent.capability.ComponentInvokeCapable;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadPoolExecutor;

/** Session-scoped, bounded RPC lanes; only requests already queued are coalesced. */
public final class ProxyBatchDispatcher implements AutoCloseable {
    private static final int MAX_FRAMES = 16;
    private static final int MAX_BYTES = 1024 * 1024;
    private final ThreadPoolExecutor workers = ProxySessionResources.newDaemonExecutor("Proxy-Batch", 2, 2);
    private final ArrayDeque<Request> queue = new ArrayDeque<>();
    private int lanes;
    private volatile boolean closed;

    public Map<String, Object> exchange(ComponentInvokeCapable node, String component, Map<String, Object> frame,
                                        ProxySessionResources.ConnectionPermit permit)
            throws Exception {
        Request request = new Request(node, component, frame, new CompletableFuture<>());
        synchronized (this) {
            if (closed || queue.size() >= 512) throw new IOException("proxy batch queue unavailable");
            if (permit != null) {
                var reservation = permit.retain();
                request.result.whenComplete((reply, error) -> reservation.close());
            }
            queue.addLast(request);
            if (lanes < 2) {
                lanes++;
                workers.execute(this::drain);
            }
        }
        try {
            return request.result.get();
        } catch (ExecutionException error) {
            if (error.getCause() instanceof Exception cause) throw cause;
            throw new IOException("proxy batch failed", error.getCause());
        }
    }

    private void drain() {
        while (true) {
            List<Request> batch = new ArrayList<>();
            List<Map<String, Object>> frames = new ArrayList<>();
            synchronized (this) {
                if (queue.isEmpty()) { lanes--; return; }
                Request first = queue.peekFirst();
                int bytes = 0;
                var iterator = queue.iterator();
                while (iterator.hasNext() && batch.size() < MAX_FRAMES) {
                    Request request = iterator.next();
                    if (request.node != first.node || !request.component.equals(first.component)) continue;
                    int size = ((byte[]) request.frame.get("data")).length + ProxyIoBatch.BUFFER_BYTES;
                    if (bytes + size > MAX_BYTES) break;
                    Map<String, Object> frame = new HashMap<>(request.frame);
                    frame.put("maxBytes", ProxyIoBatch.BUFFER_BYTES);
                    bytes += size;
                    batch.add(request);
                    frames.add(frame);
                    iterator.remove();
                }
            }
            // Credit follows the selected batch, not unrelated requests still queued.
            if (frames.size() == 1) frames.get(0).put("maxBytes", ProxyIoBatch.MAX_BYTES);
            try {
                Request first = batch.get(0);
                Map<String, Object> params = Map.of("op", "ProxyForwardComponent".equals(first.component) ? 5 : 8,
                        "frames", frames);
                // Exactly one replay layer, and only this offset/ACK protocol is replayable.
                Map<String, Object> response = null;
                for (int attempt = 0; attempt < 3; attempt++) {
                    if (closed) throw new IOException("proxy batch dispatcher closed");
                    try {
                        response = first.node.invokeComponent(first.component, params);
                        if (response != null && response.get("code") instanceof Number code && code.intValue() < 500) break;
                    } catch (Exception error) {
                        if (error instanceof InterruptedException || attempt == 2) throw error;
                    }
                    if (attempt < 2) Thread.sleep(2L << attempt);
                }
                if (response == null || !(response.get("code") instanceof Number code) || code.intValue() != 200
                        || !(response.get("frames") instanceof List<?> replies) || replies.size() != batch.size()) {
                    throw new IOException("proxy BATCH failed");
                }
                // Validate correlation for the entire response before completing any connection.
                for (int i = 0; i < batch.size(); i++) {
                    if (!(replies.get(i) instanceof Map<?, ?> reply)
                            || !batch.get(i).frame.get("connId").equals(reply.get("connId"))
                            || reply.get("data") instanceof byte[] data
                            && data.length > (int) frames.get(i).get("maxBytes")) {
                        throw new IOException("proxy BATCH returned invalid correlation");
                    }
                }
                for (int i = 0; i < batch.size(); i++) {
                    @SuppressWarnings("unchecked") Map<String, Object> reply = new HashMap<>((Map<String, Object>) replies.get(i));
                    reply.put("readLimit", frames.get(i).get("maxBytes"));
                    batch.get(i).result.complete(reply);
                }
            } catch (Exception error) {
                for (Request request : batch) request.result.completeExceptionally(error);
            }
        }
    }

    @Override
    public synchronized void close() {
        closed = true;
        IOException error = new IOException("proxy batch dispatcher closed");
        for (Request request : queue) request.result.completeExceptionally(error);
        queue.clear();
        workers.shutdownNow();
    }

    private record Request(ComponentInvokeCapable node, String component, Map<String, Object> frame,
                           CompletableFuture<Map<String, Object>> result) { }
}
