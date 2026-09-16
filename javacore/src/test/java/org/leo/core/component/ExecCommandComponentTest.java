package org.leo.core.component;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.leo.core.util.javassist.CloneWithJavassist;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecCommandComponentTest {

    @Test
    void acceptsCompletePipeLinesWithoutEchoAndDoesNotCreateMissingSessions() throws Exception {
        String processId = "local-line-" + UUID.randomUUID();
        try {
            assertThrows(IllegalStateException.class, () -> invoke(processId, 4, "echo missing\n"));
            assertThrows(IllegalStateException.class, () -> invoke(new HashMap<>(Map.of(
                    "processId", processId.getBytes(StandardCharsets.UTF_8), "op", 4,
                    "cmd", "echo missing\n".getBytes(StandardCharsets.UTF_8), "includeOutput", true))));
            assertEquals(Boolean.TRUE, invoke(processId, 1, "").get("missing"));
            Map<String, Object> initialized = invoke(processId, 6, "");
            assertEquals(Boolean.TRUE, initialized.get("lineInput"));
            String command = "LINE_VALUE=local; echo ${LINE_VALUE}-line-ok\n";
            assertEquals(command.getBytes(StandardCharsets.UTF_8).length, invoke(processId, 4, command).get("written"));
            String output = readUntil(processId, "local-line-ok", 3000);
            assertTrue(output.contains("local-line-ok"), output);
            assertFalse(output.contains("LINE_VALUE=local"), "locally echoed input must not be echoed by the component");
            assertThrows(IllegalArgumentException.class, () -> invoke(processId, 4, "partial"));
            assertThrows(IllegalArgumentException.class, () -> invoke(processId, 4, "x".repeat(1048576) + "\n"));
        } finally {
            invoke(processId, 2, "");
        }
    }

    @Test
    void transformedPayloadStartsReadsAndStopsAfterMethodRandomization() throws Exception {
        String className = "org.leo.generated.Terminal" + System.nanoTime();
        byte[] bytecode = CloneWithJavassist.cloneClass("ExecCommandComponent", className);
        Class<?> transformed = new BytecodeLoader().define(className, bytecode);
        assertTrue(Runnable.class.isAssignableFrom(transformed));
        String processId = "payload-" + UUID.randomUUID();
        try {
            Map<String, Object> started = invokePayload(transformed, Map.of(
                    "processId", processId.getBytes(StandardCharsets.UTF_8), "op", 6, "cmd", "".getBytes(StandardCharsets.UTF_8), "includeOutput", true));
            assertEquals(1L, ((Map<?, ?>) started.get("output")).get("outputSequence"));
            assertEquals(Boolean.TRUE, started.get("lineInput"));
            assertEquals(Boolean.TRUE, started.get("batchRead"));
            Map<String, Object> written = invokePayload(transformed, Map.of("processId", processId.getBytes(StandardCharsets.UTF_8), "op", 4,
                    "cmd", "PAYLOAD_VALUE=payload-worker; echo ${PAYLOAD_VALUE}-ok\n".getBytes(StandardCharsets.UTF_8), "includeOutput", true));
            assertEquals(200, written.get("code"), written.toString());
            assertEquals(200, started.get("code"), started.toString());
            StringBuilder output = new StringBuilder();
            Map<?, ?> attached = (Map<?, ?>) written.get("output");
            assertEquals(2L, attached.get("outputSequence"));
            output.append(new String((byte[]) attached.get("data"), StandardCharsets.UTF_8));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (System.nanoTime() < deadline && output.indexOf("payload-worker-ok") < 0) {
                Map<String, Object> batch = invokePayload(transformed, Map.of(
                        "op", 5, "processIds", (processId + "\nmissing-terminal").getBytes(StandardCharsets.UTF_8)));
                Map<?, ?> terminals = (Map<?, ?>) batch.get("terminals");
                assertEquals(Boolean.TRUE, ((Map<?, ?>) terminals.get("missing-terminal")).get("missing"));
                Map<?, ?> response = (Map<?, ?>) terminals.get(processId);
                assertEquals(200, response.get("code"), response.toString());
                output.append(new String((byte[]) response.get("data"), StandardCharsets.UTF_8));
                Thread.sleep(20);
            }
            assertTrue(output.toString().contains("payload-worker-ok"), output.toString());
        } finally {
            Map<String, Object> stopped = invokePayload(transformed, processId, 2, "");
            assertEquals(200, stopped.get("code"), stopped.toString());
            assertEquals(Boolean.FALSE, stopped.get("alive"));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void concurrentReadAndWriteConsumeOutputOnceWithSharedSequences() throws Exception {
        Assumptions.assumeFalse(System.getProperty("os.name", "").toLowerCase().contains("windows"));
        String id = "write-output-" + UUID.randomUUID();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            initializeQuietPipe(id);
            Map<String, Object> empty = invoke(id, 1, "");
            long previous = ((Number) empty.get("outputSequence")).longValue();
            Field field = ExecCommandComponent.class.getDeclaredField("env");
            field.setAccessible(true);
            Map<String, Map<String, Object>> environment = (Map<String, Map<String, Object>>) field.get(null);
            ByteArrayOutputStream buffer = (ByteArrayOutputStream) environment.get(id).get("output");
            byte[] expected = "中".repeat(30000).getBytes(StandardCharsets.UTF_8);
            synchronized (buffer) { buffer.write(expected); }
            Future<Map<String, Object>> write = executor.submit(() -> invoke(new HashMap<>(Map.of(
                    "processId", id.getBytes(StandardCharsets.UTF_8), "op", 4, "cmd", ":\n".getBytes(StandardCharsets.UTF_8), "includeOutput", true))));
            Future<Map<String, Object>> read = executor.submit(() -> invoke(id, 1, ""));
            Map<String, Object> acknowledgement = write.get(3, TimeUnit.SECONDS);
            assertEquals(200, acknowledgement.get("code"));
            Map<String, Object> attached = (Map<String, Object>) acknowledgement.get("output");
            assertTrue(((byte[]) attached.get("data")).length <= 65536);
            Map<String, Object> readResult = read.get(3, TimeUnit.SECONDS);
            java.util.List<Map<String, Object>> chunks = new java.util.ArrayList<>(java.util.List.of(attached, readResult));
            chunks.sort(java.util.Comparator.comparingLong(chunk -> ((Number) chunk.get("outputSequence")).longValue()));
            assertEquals(previous + 1, ((Number) chunks.get(0).get("outputSequence")).longValue());
            assertEquals(previous + 2, ((Number) chunks.get(1).get("outputSequence")).longValue());
            ByteArrayOutputStream actual = new ByteArrayOutputStream();
            for (Map<String, Object> chunk : chunks) actual.write((byte[]) chunk.get("data"));
            org.junit.jupiter.api.Assertions.assertArrayEquals(expected, actual.toByteArray());
        } finally {
            executor.shutdownNow();
            invoke(id, 2, "");
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void batchReadBoundsOutputAndIsolatesFailuresWithoutConsumingInvalidBatches() throws Exception {
        String id = "batch-" + UUID.randomUUID();
        Field field = ExecCommandComponent.class.getDeclaredField("env");
        field.setAccessible(true);
        Map<String, Map<String, Object>> environment = (Map<String, Map<String, Object>>) field.get(null);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] expected = "中".repeat(30000).getBytes(StandardCharsets.UTF_8);
        output.write(expected);
        environment.put(id, new ConcurrentHashMap<>(Map.of("output", output, "exited", true)));
        environment.put(id + "-broken", new ConcurrentHashMap<>(Map.of("output", "invalid state")));
        try {
            for (String invalid : new String[]{"", id + "\n../invalid", id + "\n" + id,
                    java.util.stream.IntStream.range(0, 17).mapToObj(i -> "p" + i).collect(java.util.stream.Collectors.joining("\n"))}) {
                assertThrows(IllegalArgumentException.class,
                        () -> invoke(new HashMap<>(Map.of("op", 5, "processIds", invalid.getBytes(StandardCharsets.UTF_8)))));
                assertEquals(expected.length, output.size());
            }
            Map<String, Object> params = Map.of("op", 5, "processIds",
                    (id + "\n" + id + "-broken\nmissing-batch").getBytes(StandardCharsets.UTF_8));
            Map<?, ?> terminals = (Map<?, ?>) invoke(new HashMap<>(params)).get("terminals");
            Map<?, ?> first = (Map<?, ?>) terminals.get(id);
            assertEquals(65536, ((byte[]) first.get("data")).length);
            assertEquals(Boolean.TRUE, first.get("hasMore"));
            assertEquals(Boolean.FALSE, first.get("eof"));
            assertEquals(500, ((Map<?, ?>) terminals.get(id + "-broken")).get("code"));
            assertEquals(Boolean.TRUE, ((Map<?, ?>) terminals.get("missing-batch")).get("missing"));
            Map<?, ?> last = (Map<?, ?>) ((Map<?, ?>) invoke(new HashMap<>(params)).get("terminals")).get(id);
            assertEquals(Boolean.TRUE, last.get("eof"));
            ByteArrayOutputStream combined = new ByteArrayOutputStream();
            combined.write((byte[]) first.get("data"));
            combined.write((byte[]) last.get("data"));
            org.junit.jupiter.api.Assertions.assertArrayEquals(expected, combined.toByteArray());
            assertFalse(environment.containsKey("missing-batch"));
        } finally {
            environment.remove(id);
            environment.remove(id + "-broken");
        }
    }

    @Test
    void transformedTerminalClassesExposeDistinctRoutingInstanceIds() throws Exception {
        String firstName = "org.leo.generated.TerminalA" + System.nanoTime();
        String secondName = "org.leo.generated.TerminalB" + System.nanoTime();
        BytecodeLoader loader = new BytecodeLoader();
        Class<?> first = loader.define(firstName,
                CloneWithJavassist.cloneClass("ExecCommandComponent", firstName));
        Class<?> second = loader.define(secondName,
                CloneWithJavassist.cloneClass("ExecCommandComponent", secondName));
        assertNotEquals(routingInstanceId(first), routingInstanceId(second));
    }

    @Test
    void rejectsBackendThatExitsDuringStartupProbe() throws Exception {
        Assumptions.assumeFalse(System.getProperty("os.name", "").toLowerCase().contains("windows"));
        Process process = new ProcessBuilder("/bin/sh", "-c", "exit 1").start();
        try {
            ExecCommandComponent component = new ExecCommandComponent();
            Method method = ExecCommandComponent.class.getDeclaredMethod("waitForBackendReady", Process.class);
            method.setAccessible(true);
            assertEquals(Boolean.FALSE, method.invoke(component, process));
        } finally {
            process.destroy();
        }
    }

    @Test
    void usesDependencyFreePipeByDefault() throws Exception {
        Assumptions.assumeFalse(System.getProperty("os.name", "").toLowerCase().contains("windows"));
        ExecCommandComponent component = new ExecCommandComponent();
        Method create = ExecCommandComponent.class.getDeclaredMethod(
                "createProcessBuilder", Map.class);
        Method probe = ExecCommandComponent.class.getDeclaredMethod("waitForBackendReady", Process.class);
        create.setAccessible(true);
        probe.setAccessible(true);
        Map<String, Object> processState = new HashMap<>();
        ProcessBuilder builder = (ProcessBuilder) create.invoke(component, processState);
        builder.redirectErrorStream(true);
        Process process = builder.start();
        try {
            assertEquals(Boolean.TRUE, probe.invoke(component, process));
            assertEquals("unix-pipe", processState.get("backend"));
            assertEquals(Boolean.FALSE, processState.get("pty"));
            process.getOutputStream().write(
                    "JAVA_PIPE_A=java-pipe; JAVA_PIPE_B=fallback-ok; echo ${JAVA_PIPE_A}-${JAVA_PIPE_B}\n"
                            .getBytes(StandardCharsets.UTF_8));
            process.getOutputStream().flush();

            ByteArrayOutputStream output = new ByteArrayOutputStream();
            InputStream input = process.getInputStream();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (System.nanoTime() < deadline && output.toString("UTF-8").indexOf("java-pipe-fallback-ok") < 0) {
                while (input.available() > 0) output.write(input.read());
                Thread.sleep(25L);
            }
            assertTrue(output.toString("UTF-8").contains("java-pipe-fallback-ok"), output.toString("UTF-8"));
        } finally {
            process.destroy();
        }
    }

    @Test
    void prefersConfiguredInteractiveShellBeforeCompatibilityFallbacks() throws Exception {
        Assumptions.assumeFalse(System.getProperty("os.name", "").toLowerCase().contains("windows"));
        ExecCommandComponent component = new ExecCommandComponent();
        Method select = ExecCommandComponent.class.getDeclaredMethod("selectShell");
        select.setAccessible(true);

        String selected = (String) select.invoke(component);
        String configured = System.getenv("SHELL");
        if (configured != null) {
            java.io.File configuredFile = new java.io.File(configured);
            if (configuredFile.isFile() && configuredFile.canExecute()) {
                assertEquals(configuredFile.getAbsolutePath(), selected);
                return;
            }
        }
        for (String candidate : new String[]{"/bin/bash", "/bin/zsh", "/bin/ksh", "/bin/sh"}) {
            java.io.File file = new java.io.File(candidate);
            if (file.isFile() && file.canExecute()) {
                assertEquals(file.getAbsolutePath(), selected);
                return;
            }
        }
        assertEquals("/bin/sh", selected);
    }

    @Test
    void selectsConfiguredWindowsCommandProcessorWithPortableFallback() throws Exception {
        ExecCommandComponent component = new ExecCommandComponent();
        Method select = ExecCommandComponent.class.getDeclaredMethod("selectWindowsShell");
        select.setAccessible(true);

        String selected = (String) select.invoke(component);
        String configured = System.getenv("ComSpec");
        if (configured != null && new java.io.File(configured).isFile()) {
            assertEquals(new java.io.File(configured).getAbsolutePath(), selected);
        } else {
            assertTrue(selected.toLowerCase().endsWith("cmd.exe"), selected);
        }
    }

    @Test
    void stopReleasesABlockedPipeWrite() throws Exception {
        Assumptions.assumeFalse(System.getProperty("os.name", "").toLowerCase().contains("windows"));
        String processId = "blocked-write-" + UUID.randomUUID();
        Field field = ExecCommandComponent.class.getDeclaredField("env");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> environment = (Map<String, Map<String, Object>>) field.get(null);
        java.util.concurrent.ExecutorService requests = java.util.concurrent.Executors.newFixedThreadPool(2, task -> {
            Thread thread = new Thread(task);
            thread.setDaemon(true);
            return thread;
        });
        Process process = null;
        try {
            invoke(processId, 6, "");
            process = (Process) environment.get(processId).get("process");
            invoke(processId, 0, "printf '\\nBLOCK_READY\\n'; exec sleep 30\r");
            assertTrue(readUntil(processId, "\nBLOCK_READY\n", 2000).contains("\nBLOCK_READY\n"));
            java.util.concurrent.Future<?> writing = requests.submit(() -> invoke(processId, 0, "x".repeat(512 * 1024) + "\n"));
            Thread.sleep(200);
            assertFalse(writing.isDone(), "input must fill the pipe before testing stop");
            java.util.concurrent.Future<?> stopping = requests.submit(() -> invoke(processId, 2, ""));
            stopping.get(3, TimeUnit.SECONDS);
            assertTrue(process.waitFor(2, TimeUnit.SECONDS));
            try { writing.get(2, TimeUnit.SECONDS); } catch (java.util.concurrent.ExecutionException expected) {
                assertTrue(expected.getCause() instanceof java.io.IOException, expected.toString());
            }
        } finally {
            if (process != null) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.toHandle().destroyForcibly();
            }
            requests.shutdownNow();
            requests.awaitTermination(3, TimeUnit.SECONDS);
            invoke(processId, 2, "");
        }
    }

    @Test
    void encodesWindowsPipeInputUsingTheNativeCharset() throws Exception {
        String previousOs = System.getProperty("os.name");
        String previousEncoding = System.getProperty("sun.jnu.encoding");
        try {
            System.setProperty("os.name", "Windows 10");
            System.setProperty("sun.jnu.encoding", "GBK");
            Method write = ExecCommandComponent.class.getDeclaredMethod(
                    "writePipeInput", Map.class, java.io.OutputStream.class, byte[].class);
            write.setAccessible(true);
            Map<String, Object> state = new HashMap<>();
            ByteArrayOutputStream echo = new ByteArrayOutputStream();
            state.put("output", echo);
            ByteArrayOutputStream input = new ByteArrayOutputStream();
            write.invoke(new ExecCommandComponent(), state, input, "echo 中文\r".getBytes(StandardCharsets.UTF_8));
            assertEquals("echo 中文\r\n", input.toString("GBK"));
            assertEquals("echo 中文\r\n", echo.toString("UTF-8"));
            Method writeLines = ExecCommandComponent.class.getDeclaredMethod(
                    "writePipeLines", Map.class, java.io.OutputStream.class, byte[].class);
            writeLines.setAccessible(true);
            input.reset();
            echo.reset();
            writeLines.invoke(new ExecCommandComponent(), state, input,
                    "echo 中文\r\necho 第二行\n".getBytes(StandardCharsets.UTF_8));
            assertEquals("echo 中文\r\necho 第二行\r\n", input.toString("GBK"));
            assertEquals(0, echo.size());
        } finally {
            System.setProperty("os.name", previousOs);
            if (previousEncoding == null) System.clearProperty("sun.jnu.encoding");
            else System.setProperty("sun.jnu.encoding", previousEncoding);
        }
    }

    @Test
    void protocolLongPollWaitsBeyondTwoSecondsAndWakesOnOutput() throws Exception {
        Assumptions.assumeFalse(System.getProperty("os.name", "").toLowerCase().contains("windows"));
        String processId = "long-poll-" + UUID.randomUUID();
        ExecutorService reader = Executors.newSingleThreadExecutor();
        try {
            initializeQuietPipe(processId);
            Future<Map<String, Object>> reading = reader.submit(
                    () -> invoke(processId, 1, "", null, null, 10000));
            assertThrows(TimeoutException.class, () -> reading.get(2300, TimeUnit.MILLISECONDS));
            invoke(processId, 4, "echo long-poll-output\n");
            Map<String, Object> result = reading.get(2, TimeUnit.SECONDS);
            assertTrue(((byte[]) result.get("data")).length > 0, "output should wake the long poll");
            assertEquals(Boolean.TRUE, result.get("alive"));
        } finally {
            reader.shutdownNow();
            invoke(processId, 2, "");
            assertTrue(reader.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void stoppingProcessReleasesPendingLongPoll() throws Exception {
        Assumptions.assumeFalse(System.getProperty("os.name", "").toLowerCase().contains("windows"));
        String processId = "stop-long-poll-" + UUID.randomUUID();
        ExecutorService reader = Executors.newSingleThreadExecutor();
        try {
            initializeQuietPipe(processId);
            Future<Map<String, Object>> reading = reader.submit(
                    () -> invoke(processId, 1, "", null, null, 10000));
            assertThrows(TimeoutException.class, () -> reading.get(200, TimeUnit.MILLISECONDS));
            assertEquals(Boolean.FALSE, invoke(processId, 2, "").get("alive"));
            // A concurrent read can return final shell output before termination completes.
            assertEquals(200, reading.get(2, TimeUnit.SECONDS).get("code"));
            assertEquals(Boolean.TRUE, invoke(processId, 1, "").get("missing"));
        } finally {
            reader.shutdownNow();
            invoke(processId, 2, "");
            assertTrue(reader.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    private void initializeQuietPipe(String processId) throws Exception {
        invoke(processId, 6, "");
        // Startup prompts are asynchronous output, so silence them before testing an idle read.
        invoke(processId, 4, "PS1=''; PS2=''; printf 'terminal-ready\\n'\n");
        assertTrue(readUntil(processId, "terminal-ready\n", 3000).contains("terminal-ready\n"));
    }

    @Test
    void requiresExplicitInitializationAndRejectsLateWritesAfterStop() throws Exception {
        String processId = "test-" + UUID.randomUUID();
        try {
            assertThrows(IllegalStateException.class, () -> invoke(processId, 0, "init"));
            assertEquals(Boolean.TRUE, invoke(processId, 1, "").get("missing"));
            invoke(processId, 6, "");
            Map<String, Object> started = invoke(processId, 0, "FIRST_VALUE=java-first-write; echo ${FIRST_VALUE}-ok\r");
            assertEquals(200, ((Number) started.get("code")).intValue());
            assertEquals(Boolean.TRUE, started.get("alive"));

            String output = readUntil(processId, "java-first-write-ok", 3000);
            assertTrue(output.contains("java-first-write-ok"), output);
            invoke(processId, 2, "");
            assertThrows(IllegalStateException.class, () -> invoke(processId, 0, "echo late\n"));
            assertEquals(Boolean.TRUE, invoke(processId, 1, "").get("missing"));
        } finally {
            invoke(processId, 2, "");
        }
    }

    @Test
    void internalCommandRunnerInitializesBeforeSendingInput() throws Exception {
        Assumptions.assumeFalse(System.getProperty("os.name", "").toLowerCase().contains("windows"));
        var service = new org.leo.core.puppet.service.ComponentService(
                bytes -> new byte[0], java.util.List.of(), java.util.List.of()) {
            @Override
            public Map<String, Object> invokeComponent(String name, Map<String, Object> params) throws Exception {
                assertEquals("ExecCommandComponent", name);
                return invoke(new HashMap<>(params));
            }

            String execute() throws Exception {
                return execWithTimeout("printf 'internal-%s-ok\\n' runner", 3);
            }
        };
        String output = service.execute();
        assertTrue(output.contains("internal-runner-ok"), output);
    }

    @Test
    void internalCommandRunnerSurfacesWriteFailureAndClosesTheSession() throws Exception {
        String[] processId = {null};
        var service = new org.leo.core.puppet.service.ComponentService(
                bytes -> new byte[0], java.util.List.of(), java.util.List.of()) {
            @Override
            public Map<String, Object> invokeComponent(String name, Map<String, Object> params) throws Exception {
                processId[0] = new String((byte[]) params.get("processId"), StandardCharsets.UTF_8);
                if (Integer.valueOf(4).equals(params.get("op"))) {
                    return Map.of("code", 500, "msg", "terminal input failed");
                }
                return invoke(new HashMap<>(params));
            }

            String execute() throws Exception {
                return execWithTimeout("echo ignored", 3);
            }
        };
        try {
            IllegalStateException failure = assertThrows(IllegalStateException.class, service::execute);
            assertTrue(failure.getMessage().contains("terminal input failed"));
            assertEquals(Boolean.TRUE, invoke(processId[0], 1, "").get("missing"));
        } finally {
            if (processId[0] != null) invoke(processId[0], 2, "");
        }
    }

    @Test
    void literalInitIsInputAndRepeatedInitializationPreservesBufferedInput() throws Exception {
        Assumptions.assumeFalse(System.getProperty("os.name", "").toLowerCase().contains("windows"));
        String id = "literal-init-" + UUID.randomUUID();
        try {
            invoke(id, 6, "");
            invoke(id, 0, "printf 'literal-%s-done\\n' ");
            invoke(id, 6, "");
            Map<String, Object> input = invoke(id, 0, "init");
            assertEquals(4, input.get("written"));
            assertFalse(input.containsKey("initialized"));
            invoke(id, 0, "\n");
            assertTrue(readUntil(id, "literal-init-done", 3000).contains("literal-init-done"));
        } finally {
            invoke(id, 2, "");
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void rejectsInvalidWireTypesBeforeCreatingOrConsumingAProcess() throws Exception {
        String id = "invalid-params-" + UUID.randomUUID();
        byte[] processId = id.getBytes(StandardCharsets.UTF_8);
        for (Map<String, Object> params : java.util.List.<Map<String, Object>>of(
                Map.of("op", "6", "processId", processId),
                Map.of("op", 6.5, "processId", processId),
                Map.of("op", 4294967302L, "processId", processId),
                Map.of("op", 6, "processId", id),
                Map.of("op", 6, "processId", processId, "includeOutput", "true"),
                Map.of("op", 6, "processId", processId, "cmd", "init".getBytes(StandardCharsets.UTF_8)),
                Map.of("op", 6, "processId", processId, "terminalMode", new byte[]{1}))) {
            assertThrows(IllegalArgumentException.class, () -> invoke(new HashMap<>(params)));
        }
        assertEquals(Boolean.TRUE, invoke(id, 1, "").get("missing"));
        Field field = ExecCommandComponent.class.getDeclaredField("env");
        field.setAccessible(true);
        Map<String, Map<String, Object>> environment = (Map<String, Map<String, Object>>) field.get(null);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write("pending".getBytes(StandardCharsets.UTF_8));
        environment.put(id, new ConcurrentHashMap<>(Map.of("output", output, "exited", true)));
        try {
            for (Object wait : new Object[]{"10", 1.5, -1}) {
                assertThrows(IllegalArgumentException.class, () -> invoke(new HashMap<>(Map.of(
                        "op", 1, "processId", processId, "waitMs", wait))));
                assertEquals(7, output.size());
            }
            assertThrows(IllegalArgumentException.class, () -> invoke(new HashMap<>(Map.of(
                    "op", 0, "processId", processId, "cmd", "text"))));
            assertEquals("pending", new String((byte[]) invoke(id, 1, "").get("data"), StandardCharsets.UTF_8));
        } finally {
            environment.remove(id);
        }
    }

    @Test
    void pipeEditsInputAndTreatsSplitCrLfAsOneSubmission() throws Exception {
        Assumptions.assumeFalse(System.getProperty("os.name", "").toLowerCase().contains("windows"));
        String processId = "pipe-edit-" + UUID.randomUUID();
        try {
            invoke(processId, 6, "");
            invoke(processId, 0, "PIPE_VALUE=pipe-edit; echo ${PIPE_VALUE}-o中");
            invoke(processId, 0, "\u007fk\r");
            invoke(processId, 0, "\n");
            String output = readUntil(processId, "pipe-edit-ok", 3000);
            assertTrue(output.contains("pipe-edit-ok"), output);
            assertFalse(output.contains("not found"), output);
        } finally {
            invoke(processId, 2, "");
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void failedStartupRemovesProcessPlaceholder() throws Exception {
        String processId = "failed-" + UUID.randomUUID();
        Field envField = ExecCommandComponent.class.getDeclaredField("env");
        envField.setAccessible(true);
        Map<String, Map<String, Object>> environment =
                (Map<String, Map<String, Object>>) envField.get(null);
        Map<String, Object> failed = new HashMap<>();
        failed.put("exited", Boolean.TRUE);
        failed.put("error", "startup failed");
        failed.put("lastAccessTime", System.currentTimeMillis());
        environment.put(processId, failed);
        try {
            IllegalStateException error = assertThrows(IllegalStateException.class,
                    () -> invoke(processId, 6, ""));
            assertEquals("startup failed", error.getMessage());
            assertFalse(environment.containsKey(processId));
        } finally {
            environment.remove(processId);
        }
    }

    @Test
    void startsReadyStreamsAndInterruptsFixedSizePty() throws Exception {
        requirePythonPty();
        String processId = "test-" + UUID.randomUUID();
        try {
            Map<String, Object> initialized = invoke(processId, 6, "", "python-pty");
            assertEquals(200, ((Number) initialized.get("code")).intValue());
            assertEquals(Boolean.TRUE, initialized.get("initialized"));
            assertEquals(Boolean.TRUE, initialized.get("alive"));
            assertTrue(initialized.get("backend") instanceof String);
            assertTrue(initialized.get("instanceId") instanceof String);
            assertEquals(Boolean.TRUE, initialized.get("longPolling"));

            assertEquals(Boolean.TRUE, initialized.get("pty"));
            assertEquals(Boolean.FALSE, initialized.get("resizable"));

            invoke(processId, 0,
                    "LEO_MARK=native-java-pty; if test -t 0 && test -t 1; then echo ${LEO_MARK}-ok; fi\r");
            String nativePty = readUntil(processId, "native-java-pty-ok", 3000);
            assertTrue(nativePty.contains("native-java-pty-ok"), nativePty);

            Map<String, Object> resized = invoke(processId, 3, "101,33");
            assertEquals(Boolean.FALSE, resized.get("resized"));
            assertEquals(Boolean.FALSE, resized.get("resizable"));

            invoke(processId, 0, "stty -echo; echo java-echo-disabled\r");
            readUntil(processId, "java-echo-disabled", 1500);
            invoke(processId, 1, "");

            long started = System.nanoTime();
            invoke(processId, 0,
                    "LEO_STREAM=java-pty-stream; printf ${LEO_STREAM}-start; sleep 1; printf ${LEO_STREAM}-end\r");
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 750,
                    "PTY writes should return before command completion");
            String early = readUntil(processId, "java-pty-stream-start", 750);
            assertTrue(early.contains("java-pty-stream-start"), early);
            assertFalse(early.contains("java-pty-stream-end"), early);
            String late = readUntil(processId, "java-pty-stream-end", 2500);
            assertTrue(late.contains("java-pty-stream-end"), late);

            invoke(processId, 0, "sleep 5\r");
            Thread.sleep(150);
            invoke(processId, 0, "\u0003");
            invoke(processId, 0, "echo java-interrupt-ok\r");
            String interrupted = readUntil(processId, "java-interrupt-ok", 2500);
            assertTrue(interrupted.contains("java-interrupt-ok"), interrupted);
        } finally {
            invoke(processId, 2, "");
        }
    }

    @Test
    void bothModesWorkWithoutATemporaryDirectoryAndCannotChangeInPlace() throws Exception {
        requirePythonPty();
        String pipeId = "pipe-" + UUID.randomUUID();
        String ptyId = "pty-" + UUID.randomUUID();
        Path notADirectory = Files.createTempFile("terminal-no-directory", ".tmp");
        String previous = System.getProperty("java.io.tmpdir");
        System.setProperty("java.io.tmpdir", notADirectory.toString());
        try {
            Map<String, Object> pipe = invoke(pipeId, 6, "");
            assertEquals("pipe", pipe.get("terminalMode"));
            assertEquals(Boolean.FALSE, pipe.get("pty"));
            Map<String, Object> pty = invoke(ptyId, 6, "", "python-pty");
            assertEquals("python-pty", pty.get("terminalMode"));
            assertEquals(Boolean.TRUE, pty.get("pty"));
            assertThrows(IllegalStateException.class, () -> invoke(pipeId, 6, "", "python-pty"));
            assertEquals(Boolean.TRUE, invoke(pipeId, 1, "").get("alive"));
            invoke(ptyId, 0, "stty -echo; printf '\\npty-ready\\n'\r");
            readUntil(ptyId, "\r\npty-ready\r\n", 2000);
            invoke(ptyId, 0, "head -c 150000 /dev/zero | tr '\\000' X; printf tail-marker; exit 7\r");
            StringBuilder output = new StringBuilder();
            Map<String, Object> response;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            do {
                response = invoke(ptyId, 1, "");
                output.append(new String((byte[]) response.get("data"), StandardCharsets.UTF_8));
                if (Boolean.TRUE.equals(response.get("eof"))) break;
                Thread.sleep(20);
            } while (System.nanoTime() < deadline);
            assertEquals(Boolean.TRUE, response.get("eof"));
            assertEquals(0, response.get("exitCode"), "minimal PTY reports the bridge exit code, not the shell's");
            assertTrue(output.toString().contains("X".repeat(150000) + "tail-marker"));
        } finally {
            if (previous == null) System.clearProperty("java.io.tmpdir");
            else System.setProperty("java.io.tmpdir", previous);
            invoke(pipeId, 2, "");
            invoke(ptyId, 2, "");
            Files.deleteIfExists(notADirectory);
        }
    }

    @Test
    void failedPythonStartupDoesNotCreateAPipeFallback() throws Exception {
        requirePythonPty();
        String processId = "failed-pty-" + UUID.randomUUID();
        try {
            assertThrows(IllegalStateException.class,
                    () -> invoke(processId, 6, "", "python-pty", "raise RuntimeError('bridge startup failed')"));
            assertEquals(Boolean.TRUE, invoke(processId, 1, "").get("missing"));
        } finally {
            invoke(processId, 2, "");
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void bridgeReapsForegroundProcessWhenInputClosesOrBridgeIsTerminated() throws Exception {
        requirePythonPty();
        Field field = ExecCommandComponent.class.getDeclaredField("env");
        field.setAccessible(true);
        Map<String, Map<String, Object>> environment = (Map<String, Map<String, Object>>) field.get(null);
        for (boolean terminate : new boolean[]{false, true}) {
            String processId = "bridge-close-" + UUID.randomUUID();
            ProcessHandle child = null;
            try {
                invoke(processId, 6, "", "python-pty");
                invoke(processId, 0, "stty -echo; printf '\\nPTY_READY\\n'\r");
                assertTrue(readUntil(processId, "\r\nPTY_READY\r\n", 2000).contains("\r\nPTY_READY\r\n"));
                invoke(processId, 0,
                        "sh -c 'printf \"\\nPTY_CHILD=%s CHILD_READY\\n\" \"$$\"; exec sleep 30'\r");
                String output = readUntil(processId, " CHILD_READY\r\n", 2000);
                java.util.regex.Matcher pid = java.util.regex.Pattern.compile("PTY_CHILD=(\\d+) CHILD_READY").matcher(output);
                assertTrue(pid.find(), output);
                child = ProcessHandle.of(Long.parseLong(pid.group(1))).orElseThrow();
                assertTrue(child.isAlive());
                Process bridge = (Process) environment.get(processId).get("process");
                // Bypass Java process-tree cleanup to exercise the bridge itself.
                if (terminate) bridge.destroy();
                else bridge.getOutputStream().close();
                assertTrue(bridge.waitFor(3, TimeUnit.SECONDS), "bridge must exit after its parent disconnects");
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                while (child.isAlive() && System.nanoTime() < deadline) Thread.sleep(20);
                assertFalse(child.isAlive(), "foreground process must not survive bridge shutdown");
            } finally {
                if (child != null && child.isAlive()) child.destroyForcibly();
                invoke(processId, 2, "");
            }
        }
    }

    private void requirePythonPty() throws Exception {
        for (String executable : new String[]{"python3", "python"}) {
            try {
                Process probe = new ProcessBuilder(executable, "-c",
                        "import sys,pty; sys.exit(0 if sys.version_info >= (3, 5) else 1)").start();
                if (probe.waitFor(3, TimeUnit.SECONDS) && probe.exitValue() == 0) return;
                probe.destroyForcibly();
            } catch (java.io.IOException unavailable) {
                // Try the other conventional interpreter name.
            }
        }
        Assumptions.abort("Unix Python 3.5+ PTY unavailable");
    }

    private String readUntil(String processId, String expected, long timeoutMillis) throws Exception {
        StringBuilder output = new StringBuilder();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        do {
            Map<String, Object> response = invoke(processId, 1, "");
            Object raw = response.get("data");
            if (raw instanceof byte[]) output.append(new String((byte[]) raw, StandardCharsets.UTF_8));
            if (output.indexOf(expected) >= 0) break;
            Thread.sleep(40);
        } while (System.nanoTime() < deadline);
        return output.toString();
    }

    @Test
    @SuppressWarnings("unchecked")
    void processExitDoesNotSignalEofUntilTheOutputReaderHasDrained() throws Exception {
        String processId = "drain-" + UUID.randomUUID();
        Field field = ExecCommandComponent.class.getDeclaredField("env");
        field.setAccessible(true);
        Map<String, Map<String, Object>> environment = (Map<String, Map<String, Object>>) field.get(null);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Map<String, Object> state = new ConcurrentHashMap<>();
        state.put("output", output);
        state.put("exited", false);
        environment.put(processId, state);
        try {
            assertEquals(Boolean.FALSE, invoke(processId, 1, "").get("eof"));
            output.write("final output".getBytes(StandardCharsets.UTF_8));
            state.put("exited", true);
            Map<String, Object> response = invoke(processId, 1, "");
            assertEquals("final output", new String((byte[]) response.get("data"), StandardCharsets.UTF_8));
            assertEquals(Boolean.TRUE, response.get("eof"));
        } finally {
            environment.remove(processId);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void expiresAnAbandonedProcessWithoutAnotherTerminalRequest() throws Exception {
        String processId = "expiry-" + UUID.randomUUID();
        Field field = ExecCommandComponent.class.getDeclaredField("env");
        field.setAccessible(true);
        Map<String, Map<String, Object>> environment = (Map<String, Map<String, Object>>) field.get(null);
        try {
            invoke(processId, 6, "");
            Map<String, Object> state = environment.get(processId);
            Process process = (Process) state.get("process");
            state.put("lastAccessTime", System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(31));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (environment.containsKey(processId) && System.nanoTime() < deadline) Thread.sleep(25);
            assertFalse(environment.containsKey(processId));
            assertFalse(process.isAlive());
        } finally {
            invoke(processId, 2, "");
        }
    }

    private String routingInstanceId(Class<?> type) throws Exception {
        for (Field field : type.getDeclaredFields()) {
            if (field.getType() != String.class
                    || !java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
            field.setAccessible(true);
            Object value = field.get(null);
            if (value instanceof String text && text.matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")) return text;
        }
        throw new AssertionError("routing instance id field was not found");
    }

    private Map<String, Object> invoke(String processId, int operation, String command) throws Exception {
        return invoke(processId, operation, command, null);
    }

    private Map<String, Object> invoke(String processId, int operation, String command, String mode) throws Exception {
        String source = null;
        if ("python-pty".equals(mode)) {
            try (InputStream resource = getClass().getResourceAsStream("/terminal/pty_bridge.py")) {
                source = new String(java.util.Objects.requireNonNull(resource).readAllBytes(), StandardCharsets.UTF_8);
            }
        }
        return invoke(processId, operation, command, mode, source);
    }

    private Map<String, Object> invoke(String processId, int operation, String command, String mode, String source) throws Exception {
        return invoke(processId, operation, command, mode, source, 0);
    }

    private Map<String, Object> invoke(String processId, int operation, String command, String mode, String source, int waitMillis) throws Exception {
        HashMap<String, Object> params = new HashMap<>();
        params.put("processId", processId.getBytes(StandardCharsets.UTF_8));
        params.put("op", operation);
        params.put("cmd", command.getBytes(StandardCharsets.UTF_8));
        if (operation == 1) params.put("waitMs", waitMillis);
        if (mode != null) params.put("terminalMode", mode);
        if (source != null) params.put("ptyBridge", source.getBytes(StandardCharsets.UTF_8));
        return invoke(params);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> invoke(HashMap<String, Object> params) throws Exception {
        ExecCommandComponent component = new ExecCommandComponent();
        HashMap<String, Object> results = new HashMap<>();

        Field paramsField = ExecCommandComponent.class.getDeclaredField("params");
        Field resultsField = ExecCommandComponent.class.getDeclaredField("results");
        paramsField.setAccessible(true);
        resultsField.setAccessible(true);
        paramsField.set(component, params);
        resultsField.set(component, results);

        Method method = ExecCommandComponent.class.getDeclaredMethod("execCommand");
        method.setAccessible(true);
        try {
            method.invoke(component);
        } catch (InvocationTargetException error) {
            Throwable cause = error.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            throw error;
        }
        return (Map<String, Object>) resultsField.get(component);
    }

    private static final class BytecodeLoader extends ClassLoader {
        private Class<?> define(String name, byte[] bytecode) {
            return defineClass(name, bytecode, 0, bytecode.length);
        }
    }

    private Map<String, Object> invokePayload(Class<?> type, String processId, int op, String cmd) throws Exception {
        return invokePayload(type, Map.of("processId", processId.getBytes(StandardCharsets.UTF_8), "op", op, "cmd", cmd.getBytes(StandardCharsets.UTF_8)));
    }

    private Map<String, Object> invokePayload(Class<?> type, Map<String, Object> params) throws Exception {
        PayloadContext context = new PayloadContext(params);
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        try {
            thread.setContextClassLoader(context);
            ((Runnable) type.getDeclaredConstructor().newInstance()).run();
            return context.response;
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    private static final class PayloadContext extends ClassLoader implements java.lang.reflect.InvocationHandler {
        private final HashMap<String, Object> request;
        private Map<String, Object> response;

        private PayloadContext(Map<String, Object> request) {
            this.request = new HashMap<>(request);
        }

        @Override
        @SuppressWarnings("unchecked")
        public Object invoke(Object proxy, Method method, Object[] args) {
            if (args == null) return request;
            response = (Map<String, Object>) args[0];
            return null;
        }
    }
}
