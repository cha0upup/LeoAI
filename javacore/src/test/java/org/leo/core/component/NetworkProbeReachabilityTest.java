package org.leo.core.component;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.leo.core.util.javassist.CloneWithJavassist;

import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class NetworkProbeReachabilityTest {
    @ParameterizedTest
    @CsvSource({"false,false", "true,false", "false,true", "true,true"})
    void hostDiscoveryStopsAtTheFirstPositiveProbe(boolean transformed, boolean javaProbe) throws Exception {
        Class<?> type = transformed ? new ComponentTestSupport.BytecodeLoader().load(CloneWithJavassist.cloneClass(
                "NetworkProbeComponent", "org.leo.generated.HostDiscovery" + System.nanoTime())) : NetworkProbeComponent.class;
        try (ServerSocket first = new ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"));
             ServerSocket second = new ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"))) {
            var started = call(type, Map.of("action", "startTask", "plan", Map.of("stages", List.of("host-reachability"),
                    "targets", List.of(Map.of("host", "127.0.0.1", "targetId", "127.0.0.1", "stage", "host-reachability",
                            "ports", List.of(first.getLocalPort(), second.getLocalPort()), "javaReachability", javaProbe)),
                    "limits", Map.of("threads", 16, "timeout", 100))));
            assertEquals(200, started.get("code"), started.toString());
            String id = String.valueOf(started.get("taskId"));
            try {
                Map<?, ?> snapshot = await(type, id);
                assertEquals(1, snapshot.get("total")); assertEquals(1, snapshot.get("completed"));
                List<?> observations = (List<?>) snapshot.get("observations");
                assertEquals(1, observations.size(), snapshot.toString());
                var row = (Map<?, ?>) observations.get(0);
                assertEquals(javaProbe ? "java-reachability" : "tcp-connect", row.get("stage"));
                assertEquals(javaProbe ? 0 : first.getLocalPort(), row.get("port"));
                assertEquals(true, row.get("hostComplete")); assertEquals("open", row.get("state"));
                second.setSoTimeout(30);
                assertThrows(java.net.SocketTimeoutException.class, second::accept);
            } finally {
                call(type, Map.of("action", "stopTask", "taskId", id));
                call(type, Map.of("action", "releaseTask", "taskId", id));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aNegativeJavaCheckFallsThroughToTcpInsteadOfDiscardingTheHost(boolean error) throws Exception {
        var worker = new NetworkProbeComponent() {
            @Override protected boolean isHostReachable(String host, int timeout) throws java.io.IOException {
                if (error) throw new java.io.IOException("fixture network error");
                return false;
            }
        };
        String id = "negative-java-fixture";
        Field registry = NetworkProbeComponent.class.getDeclaredField("TASKS"); registry.setAccessible(true);
        Map tasks = (Map) registry.get(null);
        try (ServerSocket server = new ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"))) {
            var target = ComponentTestSupport.call(new NetworkProbeComponent(), "normalizeTarget", new Class[]{Object.class},
                    Map.of("host", "127.0.0.1", "stage", "host-reachability", "ports", List.of(server.getLocalPort()), "javaReachability", true));
            Map task = new HashMap();
            task.put("taskId", id); task.put("status", "RUNNING"); task.put("outcome", "RUNNING");
            task.put("targets", List.of(target)); task.put("total", 1); task.put("completed", 0);
            task.put("observations", new ArrayList<>()); task.put("errors", new ArrayList<>());
            task.put("plan", Map.of("stages", List.of("host-reachability"), "timeout", 100));
            task.put("workers", List.of(worker)); tasks.put(id, task);
            Field taskId = NetworkProbeComponent.class.getDeclaredField("taskId"); taskId.setAccessible(true);
            taskId.set(worker, id); worker.run();
            var snapshot = await(NetworkProbeComponent.class, id);
            var rows = (List<Map<String, Object>>) snapshot.get("observations");
            assertEquals(List.of("java-reachability", "tcp-connect"), rows.stream().map(row -> row.get("stage")).toList());
            assertEquals("unknown", rows.get(0).get("state"));
            assertEquals("open", rows.get(1).get("state"));
            assertEquals(true, rows.get(1).get("hostComplete"));
        } finally { tasks.remove(id); }
    }

    private static Map<String, Object> call(Class<?> type, Map<String, Object> params) throws Exception {
        return ComponentTestSupport.runComponent((Runnable) type.getDeclaredConstructor().newInstance(), params);
    }

    private static Map<?, ?> await(Class<?> type, String id) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        Map<?, ?> snapshot = Map.of();
        do {
            var response = call(type, Map.of("action", "queryTask", "taskId", id));
            assertEquals(200, response.get("code"), response.toString());
            snapshot = (Map<?, ?>) response.get("result");
            if ("STOPPED".equals(snapshot.get("status"))) {
                assertEquals("COMPLETED", snapshot.get("outcome"), snapshot.toString()); return snapshot;
            }
            Thread.sleep(2);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("Host discovery did not finish: " + snapshot);
    }
}
