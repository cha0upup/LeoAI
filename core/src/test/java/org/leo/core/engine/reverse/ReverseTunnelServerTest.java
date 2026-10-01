package org.leo.core.engine.reverse;

import org.junit.jupiter.api.Test;
import org.leo.core.agent.capability.ComponentInvokeCapable;

import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReverseTunnelServerTest {

    @Test
    void remoteListenerDeathClosesLocalResourcesBeforeRemovalCallback() throws Exception {
        CountDownLatch forwardAccepted = new CountDownLatch(1);
        CountDownLatch removed = new CountDownLatch(1);
        AtomicReference<Socket> accepted = new AtomicReference<Socket>();

        try (ServerSocket target = new ServerSocket(0)) {
            Thread acceptThread = new Thread(() -> {
                try {
                    accepted.set(target.accept());
                    forwardAccepted.countDown();
                } catch (Exception ignored) {
                }
            });
            acceptThread.setDaemon(true);
            acceptThread.start();

            RemoteDeathNode node = new RemoteDeathNode(forwardAccepted);
            ReverseTunnelServer server = new ReverseTunnelServer(node, 18080,
                    "127.0.0.1", "127.0.0.1", target.getLocalPort(), 10L, 5);
            server.setOnDead(removed::countDown);
            try {
                server.start();

                assertTrue(removed.await(2, TimeUnit.SECONDS));
                assertFalse(server.isRunning());
                assertTrue(server.getLocalConns().isEmpty());
                assertEquals(0, node.stopCalls.get());

                Socket peer = accepted.get();
                assertTrue(peer != null);
                peer.setSoTimeout(1000);
                assertEquals(-1, peer.getInputStream().read());
            } finally {
                server.stop();
                Socket peer = accepted.get();
                if (peer != null) peer.close();
            }
        }
    }

    private static final class RemoteDeathNode implements ComponentInvokeCapable {
        private final CountDownLatch forwardAccepted;
        private final AtomicInteger acceptCalls = new AtomicInteger();
        private final AtomicInteger stopCalls = new AtomicInteger();

        private RemoteDeathNode(CountDownLatch forwardAccepted) {
            this.forwardAccepted = forwardAccepted;
        }

        @Override
        public Map<String, Object> invokeComponent(String componentId,
                                                   Map<String, Object> params) throws Exception {
            int op = ((Number) params.get("op")).intValue();
            if (op == ReverseTunnelServer.OP_START_LISTEN) {
                return Map.of("code", 200);
            }
            if (op == ReverseTunnelServer.OP_STOP_LISTEN) {
                stopCalls.incrementAndGet();
                return Map.of("code", 200);
            }
            if (op == ReverseTunnelServer.OP_ACCEPT) {
                if (acceptCalls.getAndIncrement() == 0) {
                    return Map.of("code", 200, "newConns",
                            List.of(Map.of("connId", "conn-1", "clientAddr", "client")));
                }
                forwardAccepted.await(1, TimeUnit.SECONDS);
                return Map.of("code", 404);
            }
            if (op == ReverseTunnelServer.OP_READ) {
                return Map.of("code", 204);
            }
            return Map.of("code", 200);
        }
    }
}
