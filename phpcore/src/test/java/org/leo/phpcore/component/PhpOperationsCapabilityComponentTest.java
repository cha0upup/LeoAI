package org.leo.phpcore.component;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.io.BufferedReader;
import java.io.InputStreamReader;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.CompletableFuture;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import static org.leo.phpcore.PhpTestSupport.code;
import static org.leo.phpcore.PhpTestSupport.invokeComponent;
import static org.leo.phpcore.PhpTestSupport.phpAvailable;

class PhpOperationsCapabilityComponentTest {

    @BeforeAll
    static void requirePhp() {
        Assumptions.assumeTrue(phpAvailable(), "PHP CLI is not installed");
    }

    @Test
    void listsConnectionsAndBuildsSummary() throws Exception {
        Map<String, Object> listed = invokeComponent("NetworkConnectionComponent.php", "list", "array('maxEntries'=>20)");
        assertEquals(200, code(listed));
        assertInstanceOf(List.class, listed.get("connections"));
        assertTrue(listed.containsKey("filtered"));

        Map<String, Object> summary = invokeComponent("NetworkConnectionComponent.php", "summary", "array()");
        assertEquals(200, code(summary));
        assertInstanceOf(Map.class, summary.get("byState"));
        assertInstanceOf(List.class, summary.get("listeningPorts"));
        if (System.getProperty("os.name", "").toLowerCase().contains("linux")) {
            assertTrue(assertInstanceOf(List.class, listed.get("diagnostics")).contains("source=/proc/net"));
        }
    }

    @Test
    void linuxInspectionWorksWithCommandFunctionsDisabled() throws Exception {
        Assumptions.assumeTrue(System.getProperty("os.name", "").toLowerCase().contains("linux"));
        Map<String, Object> processes = invokeComponent("ProcessComponent.php", "list", "array()", true);
        assertEquals(200, code(processes));
        assertFalse(assertInstanceOf(List.class, processes.get("processes")).isEmpty());

        Map<String, Object> connections = invokeComponent(
                "NetworkConnectionComponent.php", "list", "array('maxEntries'=>20)", true);
        assertEquals(200, code(connections));
        assertTrue(assertInstanceOf(List.class, connections.get("diagnostics")).contains("source=/proc/net"));

    }

    @Test
    void listsServicesAndScheduledTasks() throws Exception {
        Map<String, Object> services = invokeComponent("ServiceComponent.php", "list", "array()");
        assertEquals(200, code(services));
        Map<?, ?> serviceData = assertInstanceOf(Map.class, services.get("data"));
        assertInstanceOf(List.class, serviceData.get("services"));

        Map<String, Object> tasks = invokeComponent("ScheduledTaskComponent.php", "list", "array()");
        assertEquals(200, code(tasks));
        Map<?, ?> taskData = assertInstanceOf(Map.class, tasks.get("data"));
        assertInstanceOf(List.class, taskData.get("tasks"));
    }

    @Test
    void runsPersistentNetworkProbeWorker() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            int port = server.getLocalPort();
            Map<String, Object> started = invokeComponent("NetworkProbeComponent.php", "startTask",
                    "array('plan'=>array('targets'=>array(array('host'=>'127.0.0.1','port'=>" + port + ")),'stages'=>array('tcp-connect')))");
            assertEquals(200, code(started));
            String taskId = String.valueOf(started.get("taskId"));
            assertFalse(taskId.isBlank());

            Map<?, ?> info = awaitNetworkProbeTask(taskId);
            assertEquals("STOPPED", info.get("status"));
            assertTrue(assertInstanceOf(List.class, info.get("observations")).stream()
                    .anyMatch(value -> "open".equals(((Map<?, ?>) value).get("state"))));
            assertEquals(200, code(invokeComponent("NetworkProbeComponent.php", "releaseTask",
                    "array('taskId'=>'" + taskId + "')")));
            assertEquals(404, code(invokeComponent("NetworkProbeComponent.php", "queryTask",
                    "array('taskId'=>'" + taskId + "','cursor'=>0,'maxItems'=>128,'maxBytes'=>524288,'includeEvidence'=>true)")));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"http-head", "http-request"})
    void returnsHttpEvidenceForBothStages(String stage) throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            CompletableFuture<String> requestLine = new CompletableFuture<>();
            Thread responder = new Thread(() -> {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(2000);
                    BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
                    requestLine.complete(reader.readLine());
                    while (!reader.readLine().isEmpty()) { }
                    socket.getOutputStream().write((
                            "HTTP/1.1 200 OK\r\n" +
                            "Server: php-probe-test\r\n" +
                            "Content-Type: text/plain\r\n" +
                            "Content-Length: 24\r\n\r\n" +
                            "<title>Console</title>ok").getBytes(StandardCharsets.ISO_8859_1));
                    socket.getOutputStream().flush();
                } catch (IOException ignored) {
                    // The worker may be stopped while the test is cleaning up.
                }
            }, "php-network-probe-responder");
            responder.start();

            Map<String, Object> started = invokeComponent("NetworkProbeComponent.php", "startTask",
                    "array('plan'=>array('targets'=>array(array('host'=>'127.0.0.1','port'=>" + server.getLocalPort()
                            + ",'protocol'=>'http','baseUrl'=>'http://127.0.0.1:" + server.getLocalPort()
                            + "/console?view=1','httpRequest'=>array('method'=>'GET','path'=>'/console?view=1'))),'stages'=>array('" + stage + "')))");
            assertEquals(200, code(started));
            String taskId = String.valueOf(started.get("taskId"));

            Map<?, ?> info = awaitNetworkProbeTask(taskId);
            responder.join(5000);
            assertEquals("STOPPED", info.get("status"));
            Map<?, ?> observation = assertInstanceOf(Map.class,
                    assertInstanceOf(List.class, info.get("observations")).get(0));
            Map<?, ?> evidence = assertInstanceOf(Map.class, observation.get("evidence"));
            assertEquals(200, evidence.get("statusCode"));
            assertEquals("GET /console?view=1 HTTP/1.1", requestLine.get(2, TimeUnit.SECONDS));
            assertEquals("Console", evidence.get("title"));
            assertEquals(24, evidence.get("bodyLength"));
            if ("http-request".equals(stage)) {
                assertTrue(String.valueOf(evidence.get("headers")).contains("Server: php-probe-test"));
                assertEquals("<title>Console</title>ok", evidence.get("body"));
            } else {
                assertFalse(evidence.containsKey("headers"));
                assertFalse(evidence.containsKey("body"));
            }
            assertEquals(200, code(invokeComponent("NetworkProbeComponent.php", "releaseTask",
                    "array('taskId'=>'" + taskId + "')")));
        }
    }

    @Test
    void preservesVirtualHostAndRuleBodiesBeyondBannerLimit() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            CompletableFuture<String> request = new CompletableFuture<>();
            String body = "x".repeat(5000) + "component-marker";
            Thread responder = new Thread(() -> {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(2000);
                    BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
                    StringBuilder headers = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null && !line.isEmpty()) headers.append(line).append("\n");
                    request.complete(headers.toString());
                    socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Length: " + body.length()
                            + "\r\nConnection: close\r\n\r\n" + body).getBytes(StandardCharsets.US_ASCII));
                    socket.getOutputStream().flush();
                } catch (IOException error) { request.completeExceptionally(error); }
            });
            responder.start();
            String taskId = "";
            try {
                var started = invokeComponent("NetworkProbeComponent.php", "startTask",
                        "array('plan'=>array('targets'=>array(array('host'=>'127.0.0.1','port'=>" + server.getLocalPort()
                        + ",'protocol'=>'http','baseUrl'=>'http://virtual.example.invalid:" + server.getLocalPort()
                        + "/','httpRequest'=>array('method'=>'GET','path'=>'/app/probe'))),'stages'=>array('http-request'),"
                        + "'limits'=>array('maxReadBytes'=>8192)))");
                assertEquals(200, code(started));
                taskId = String.valueOf(started.get("taskId"));
                Map<?, ?> info = awaitNetworkProbeTask(taskId);
                Map<?, ?> observation = (Map<?, ?>) ((List<?>) info.get("observations")).get(0);
                Map<?, ?> evidence = (Map<?, ?>) observation.get("evidence");
                assertEquals(body, evidence.get("body"));
                assertEquals(false, evidence.get("truncated"));
                assertTrue(request.get(2, TimeUnit.SECONDS).contains("Host: virtual.example.invalid:" + server.getLocalPort()));
                assertTrue(request.get().startsWith("GET /app/probe HTTP/1.1"));
            } finally {
                if (!taskId.isEmpty()) invokeComponent("NetworkProbeComponent.php", "releaseTask", "array('taskId'=>'" + taskId + "')");
                responder.join(3000);
            }
        }
    }

    @Test
    void persistsBinaryBannerAndKeepsAcknowledgementMonotonic() throws Exception {
        ExecutorService responder = Executors.newSingleThreadExecutor();
        try (ServerSocket server = new ServerSocket(0)) {
            Future<?> response = responder.submit(() -> {
                try (Socket socket = server.accept()) {
                    socket.getOutputStream().write(new byte[]{0, (byte) 255, 'X'});
                }
                return null;
            });
            Map<String, Object> started = invokeComponent("NetworkProbeComponent.php", "startTask",
                    "array('plan'=>array('targets'=>array(array('host'=>'127.0.0.1','port'=>"
                            + server.getLocalPort() + ")),'stages'=>array('tcp-exchange'),'limits'=>array('threads'=>1)))");
            assertEquals(200, code(started));
            String taskId = String.valueOf(started.get("taskId"));
            try {
                Map<?, ?> info = awaitNetworkProbeTask(taskId);
                response.get(2, TimeUnit.SECONDS);
                Map<?, ?> observation = (Map<?, ?>) ((List<?>) info.get("observations")).get(0);
                Map<?, ?> evidence = (Map<?, ?>) observation.get("evidence");
                assertEquals(3, evidence.get("bytes"));
                assertEquals("ÿX", evidence.get("banner"));
                assertEquals(200, code(invokeComponent("NetworkProbeComponent.php", "ackTask",
                        "array('taskId'=>'" + taskId + "','cursor'=>1)")));
                Map<String, Object> oldAck = invokeComponent("NetworkProbeComponent.php", "ackTask",
                        "array('taskId'=>'" + taskId + "','cursor'=>0)");
                assertEquals(1, oldAck.get("cursor"));
                Map<?, ?> drained = awaitNetworkProbeTask(taskId);
                assertEquals(List.of(), drained.get("observations"));
                assertEquals(1, drained.get("nextCursor"));
            } finally {
                invokeComponent("NetworkProbeComponent.php", "stopTask", "array('taskId'=>'" + taskId + "')");
                invokeComponent("NetworkProbeComponent.php", "releaseTask", "array('taskId'=>'" + taskId + "')");
            }
        } finally {
            responder.shutdownNow();
        }
    }

    @Test
    void releasedWorkerDoesNotProbeRemainingTargets() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            server.setSoTimeout(3000);
            String target = "array('host'=>'127.0.0.1','port'=>" + server.getLocalPort() + ")";
            Map<String, Object> started = invokeComponent("NetworkProbeComponent.php", "startTask",
                    "array('plan'=>array('targets'=>array(" + target + "," + target
                            + "),'stages'=>array('tcp-exchange'),'limits'=>array('threads'=>1,'timeout'=>3000)))");
            assertEquals(200, code(started));
            String taskId = String.valueOf(started.get("taskId"));
            try (Socket socket = server.accept()) {
                assertEquals(200, code(invokeComponent("NetworkProbeComponent.php", "stopTask", "array('taskId'=>'" + taskId + "')")));
                assertEquals(200, code(invokeComponent("NetworkProbeComponent.php", "releaseTask", "array('taskId'=>'" + taskId + "')")));
                socket.shutdownOutput();
                server.setSoTimeout(1000);
                assertThrows(SocketTimeoutException.class, () -> {
                    try (Socket unexpected = server.accept()) {
                        throw new AssertionError("released worker opened another connection");
                    }
                });
                assertEquals(404, code(invokeComponent("NetworkProbeComponent.php", "queryTask", "array('taskId'=>'" + taskId + "')")));
            } finally {
                invokeComponent("NetworkProbeComponent.php", "stopTask", "array('taskId'=>'" + taskId + "')");
                invokeComponent("NetworkProbeComponent.php", "releaseTask", "array('taskId'=>'" + taskId + "')");
            }
        }
    }

    @Test
    void emptyPlanCompletesWithoutLaunchingAProcess() throws Exception {
        Map<String, Object> started = invokeComponent("NetworkProbeComponent.php", "startTask",
                "array('plan'=>array('targets'=>array()))", true);
        assertEquals(200, code(started));
        String taskId = String.valueOf(started.get("taskId"));
        try {
            Map<?, ?> info = awaitNetworkProbeTask(taskId);
            assertEquals("COMPLETED", info.get("outcome"));
            assertEquals(0, info.get("completed"));
        } finally {
            invokeComponent("NetworkProbeComponent.php", "releaseTask", "array('taskId'=>'" + taskId + "')");
        }
    }

    private Map<?, ?> awaitNetworkProbeTask(String taskId) throws Exception {
        for (int attempt = 0; attempt < 50; attempt++) {
            Map<String, Object> queried = invokeComponent("NetworkProbeComponent.php", "queryTask",
                    "array('taskId'=>'" + taskId + "','cursor'=>0,'maxItems'=>128,'maxBytes'=>524288,'includeEvidence'=>true)");
            assertEquals(200, code(queried));
            Map<?, ?> info = assertInstanceOf(Map.class, queried.get("result"));
            if ("STOPPED".equals(info.get("status"))) return info;
            Thread.sleep(50);
        }
        throw new AssertionError("network probe task did not finish: " + taskId);
    }
}
