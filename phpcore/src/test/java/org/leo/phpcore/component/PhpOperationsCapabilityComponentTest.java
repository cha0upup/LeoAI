package org.leo.phpcore.component;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.leo.core.util.json.PortableJsonCodec;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PhpOperationsCapabilityComponentTest {

    @BeforeAll
    static void requirePhp() {
        Assumptions.assumeTrue(phpAvailable(), "PHP CLI is not installed");
    }

    @Test
    void listsConnectionsAndBuildsSummary() throws Exception {
        Map<String, Object> listed = invoke("NetworkConnectionComponent.php", "list", "array('maxEntries'=>20)");
        assertEquals(200, code(listed));
        assertInstanceOf(List.class, listed.get("connections"));
        assertTrue(listed.containsKey("filtered"));

        Map<String, Object> summary = invoke("NetworkConnectionComponent.php", "summary", "array()");
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
        Map<String, Object> processes = invokeWithDisabledCommands("ProcessComponent.php", "list", "array()");
        assertEquals(200, code(processes));
        assertFalse(assertInstanceOf(List.class, processes.get("processes")).isEmpty());

        Map<String, Object> connections = invokeWithDisabledCommands(
                "NetworkConnectionComponent.php", "list", "array('maxEntries'=>20)");
        assertEquals(200, code(connections));
        assertTrue(assertInstanceOf(List.class, connections.get("diagnostics")).contains("source=/proc/net"));

    }

    @Test
    void listsServicesAndScheduledTasks() throws Exception {
        Map<String, Object> services = invoke("ServiceComponent.php", "list", "array()");
        assertEquals(200, code(services));
        Map<?, ?> serviceData = assertInstanceOf(Map.class, services.get("data"));
        assertInstanceOf(List.class, serviceData.get("services"));

        Map<String, Object> tasks = invoke("ScheduledTaskComponent.php", "list", "array()");
        assertEquals(200, code(tasks));
        Map<?, ?> taskData = assertInstanceOf(Map.class, tasks.get("data"));
        assertInstanceOf(List.class, taskData.get("tasks"));
    }

    @Test
    void runsPersistentNetworkProbeWorker() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            int port = server.getLocalPort();
            Map<String, Object> started = invoke("NetworkProbeComponent.php", "startTask",
                    "array('plan'=>array('targets'=>array(array('host'=>'127.0.0.1','port'=>" + port + ")),'stages'=>array('tcp-connect')))");
            assertEquals(200, code(started));
            String taskId = String.valueOf(started.get("taskId"));
            assertFalse(taskId.isBlank());

            Map<String, Object> queried = null;
            for (int attempt = 0; attempt < 50; attempt++) {
                queried = invoke("NetworkProbeComponent.php", "queryTask", "array('taskId'=>'" + taskId + "')");
                Map<?, ?> info = assertInstanceOf(Map.class, queried.get("result"));
                if ("STOPPED".equals(info.get("status"))) break;
                Thread.sleep(50);
            }
            assertEquals(200, code(Objects.requireNonNull(queried)));
            Map<?, ?> info = assertInstanceOf(Map.class, queried.get("result"));
            assertEquals("STOPPED", info.get("status"));
            assertTrue(assertInstanceOf(List.class, info.get("observations")).stream()
                    .anyMatch(value -> "open".equals(((Map<?, ?>) value).get("state"))));
        }
    }

    @Test
    void returnsSeparatedHttpHeadersAndBodyEvidence() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            Thread responder = new Thread(() -> {
                try (Socket socket = server.accept()) {
                    socket.getOutputStream().write((
                            "HTTP/1.1 200 OK\r\n" +
                            "Server: php-probe-test\r\n" +
                            "Content-Type: text/plain\r\n" +
                            "Content-Length: 5\r\n\r\n" +
                            "hello").getBytes(StandardCharsets.ISO_8859_1));
                    socket.getOutputStream().flush();
                } catch (IOException ignored) {
                    // The worker may be stopped while the test is cleaning up.
                }
            }, "php-network-probe-responder");
            responder.start();

            Map<String, Object> started = invoke("NetworkProbeComponent.php", "startTask",
                    "array('plan'=>array('targets'=>array(array('host'=>'127.0.0.1','port'=>" + server.getLocalPort() + ",'protocol'=>'http','httpRequest'=>array('method'=>'GET','path'=>'/'))),'stages'=>array('http-request')))");
            assertEquals(200, code(started));
            String taskId = String.valueOf(started.get("taskId"));

            Map<?, ?> info = null;
            for (int attempt = 0; attempt < 50; attempt++) {
                Map<String, Object> queried = invoke("NetworkProbeComponent.php", "queryTask",
                        "array('taskId'=>'" + taskId + "')");
                info = assertInstanceOf(Map.class, queried.get("result"));
                if ("STOPPED".equals(info.get("status"))) break;
                Thread.sleep(50);
            }
            responder.join(5000);
            assertEquals("STOPPED", info.get("status"));
            Map<?, ?> observation = assertInstanceOf(Map.class,
                    assertInstanceOf(List.class, info.get("observations")).get(0));
            Map<?, ?> evidence = assertInstanceOf(Map.class, observation.get("evidence"));
            assertEquals(200, evidence.get("statusCode"));
            assertTrue(String.valueOf(evidence.get("headers")).contains("Server: php-probe-test"));
            assertEquals("hello", evidence.get("body"));
            assertEquals(5, evidence.get("bodyLength"));
        }
    }

    private Map<String, Object> invoke(String name, String action, String paramsExpression) throws Exception {
        return invoke(name, action, paramsExpression, false);
    }

    private Map<String, Object> invokeWithDisabledCommands(String name, String action,
                                                            String paramsExpression) throws Exception {
        return invoke(name, action, paramsExpression, true);
    }

    private Map<String, Object> invoke(String name, String action, String paramsExpression,
                                       boolean disableCommands) throws Exception {
        URL resource = Objects.requireNonNull(getClass().getResource("/components/" + name));
        Path component = Paths.get(resource.toURI());
        String script = "$component=require $argv[1];echo json_encode(call_user_func($component['handle'],'"
                + action + "'," + paramsExpression + "));";
        Path outputFile = Files.createTempFile("php-operations-component-", ".json");
        try {
            ProcessBuilder builder = disableCommands
                    ? new ProcessBuilder("php", "-d", "disable_functions=exec,shell_exec", "-r", script, component.toString())
                    : new ProcessBuilder("php", "-r", script, component.toString());
            Process process = builder
                    .redirectErrorStream(true).redirectOutput(outputFile.toFile()).start();
            assertTrue(process.waitFor(20, TimeUnit.SECONDS), name + " timed out");
            String output = Files.readString(outputFile, StandardCharsets.UTF_8);
            assertEquals(0, process.exitValue(), output);
            return PortableJsonCodec.decode(output.getBytes(StandardCharsets.UTF_8));
        } finally {
            Files.deleteIfExists(outputFile);
        }
    }

    private int code(Map<String, Object> response) {
        return ((Number) response.get("code")).intValue();
    }

    private static boolean phpAvailable() {
        try {
            Process process = new ProcessBuilder("php", "-v").redirectErrorStream(true).start();
            return process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return false;
        }
    }
}
