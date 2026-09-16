package org.leo.phpcore.component;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.leo.core.util.json.PortableJsonCodec;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class PhpExecCommandComponentTest {
    @TempDir Path directory;
    private Path component;
    private final List<Request> requests = new ArrayList<>();

    private record Request(Process process, Path output) {}

    @BeforeEach
    void setUp() throws Exception {
        Assumptions.assumeFalse(System.getProperty("os.name").toLowerCase().contains("windows"));
        try {
            Process php = new ProcessBuilder("php", "-r", "exit(function_exists('proc_open') ? 0 : 1);").start();
            Assumptions.assumeTrue(php.waitFor(5, TimeUnit.SECONDS) && php.exitValue() == 0);
        } catch (java.io.IOException unavailable) {
            Assumptions.abort("PHP CLI unavailable");
        }
        String source;
        try (var stream = Objects.requireNonNull(getClass().getResourceAsStream("/components/ExecCommandComponent.php"))) {
            source = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        component = directory.resolve("terminal.php");
        Files.writeString(component, source.replace("$state = $startPty($paths, 80, 24);", "$state = null;"));
        assertEquals("unix-command", call("init", "").get("backend"));
        call("read", "");
    }

    @AfterEach
    void tearDown() throws Exception {
        if (component == null) return;
        try {
            call("stop", "");
            for (Request request : requests) {
                if (!request.process.waitFor(5, TimeUnit.SECONDS)) request.process.destroyForcibly();
            }
            call("stop", "");
        } finally {
            for (Request request : requests) if (request.process.isAlive()) request.process.destroyForcibly();
        }
    }

    @Test
    void keepsLiteralInitAsInputAndRepeatedInitPreservesStateAndSequence() throws Exception {
        call("write", "cd /\n");
        Map<String, Object> previous = call("read", "");
        call("write", "printf 'literal-%s-done\\n' ");
        Map<String, Object> initialized = finish(start("init", Map.of(
                "processId", "test-terminal", "includeOutput", true)));
        Map<?, ?> output = (Map<?, ?>) initialized.get("output");
        assertEquals(((Number) previous.get("outputSequence")).intValue() + 1,
                ((Number) output.get("outputSequence")).intValue());
        Map<String, Object> input = call("write", "init");
        assertEquals(4, input.get("written"));
        assertFalse(input.containsKey("initialized"));
        call("write", "\npwd\n");
        String text = text(call("read", ""));
        assertTrue(text.contains("literal-init-done"), text);
        assertTrue(text.contains("\n/\n"), text);
    }

    @Test
    void rejectsMissingWritesAndInvalidParametersWithoutCreatingASession() throws Exception {
        call("stop", "");
        for (String value : List.of("init", "echo late\n")) {
            Request invalid = start("write", value);
            assertTrue(invalid.process.waitFor(2, TimeUnit.SECONDS));
            assertNotEquals(0, invalid.process.exitValue());
            assertEquals(Boolean.TRUE, call("read", "").get("missing"));
        }
        for (Map<String, Object> parameters : List.<Map<String, Object>>of(
                Map.of("cmd", "init"), Map.of("cmd", 12), Map.of("includeOutput", "true"),
                Map.of("terminalMode", "pipe"), Map.of("waitMs", "10"))) {
            Map<String, Object> params = new java.util.HashMap<>(parameters);
            params.put("processId", "test-terminal");
            Request invalid = start("init", params);
            assertTrue(invalid.process.waitFor(2, TimeUnit.SECONDS));
            assertNotEquals(0, invalid.process.exitValue());
            assertEquals(Boolean.TRUE, call("read", "").get("missing"));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void writesCarryBoundedOutputAndShareTheSequenceWithBatchReads() throws Exception {
        call("stop", "");
        Map<String, Object> initialized = finish(start("init", Map.of(
                "processId", "test-terminal", "includeOutput", true)));
        Map<String, Object> initialOutput = (Map<String, Object>) initialized.get("output");
        assertEquals(1, ((Number) initialOutput.get("outputSequence")).intValue());
        assertTrue(text(initialOutput).contains("PHP command terminal ready"));
        Map<String, Object> written = finish(start("write", Map.of("processId", "test-terminal",
                "cmd", "head -c 70000 /dev/zero | tr '\\000' A\nexit\n", "includeOutput", true)));
        Map<String, Object> first = (Map<String, Object>) written.get("output");
        assertEquals(200, ((Number) written.get("code")).intValue());
        assertEquals(2, ((Number) first.get("outputSequence")).intValue());
        assertEquals(65536, ((byte[]) first.get("data")).length);
        assertEquals(Boolean.TRUE, first.get("hasMore"));
        assertEquals(Boolean.FALSE, first.get("eof"));
        Map<?, ?> batch = (Map<?, ?>) finish(start("read-batch", Map.of("processIds", List.of("test-terminal")))).get("terminals");
        Map<String, Object> last = (Map<String, Object>) batch.get("test-terminal");
        assertEquals(3, ((Number) last.get("outputSequence")).intValue());
        assertEquals(Boolean.TRUE, last.get("eof"));
        assertTrue((text(first) + text(last)).contains("A".repeat(70000)));
        assertEquals("", text(call("read", "")));
    }

    @Test
    @SuppressWarnings("unchecked")
    void attachedOutputDoesNotPreventStreamingOrInterruptingALongCommand() throws Exception {
        Request running = start("write", Map.of("processId", "test-terminal",
                "cmd", "printf live-output; sleep 10\n", "includeOutput", true));
        assertTrue(readUntil("\r\nlive-output").contains("live-output"));
        assertTrue(running.process.isAlive());
        Map<String, Object> interrupt = finish(start("write", Map.of(
                "processId", "test-terminal", "cmd", "\u0003", "includeOutput", true)));
        Map<String, Object> interruptedOutput = (Map<String, Object>) interrupt.get("output");
        Map<String, Object> finalOutput = (Map<String, Object>) finish(running).get("output");
        assertTrue(((Number) finalOutput.get("outputSequence")).longValue()
                > ((Number) interruptedOutput.get("outputSequence")).longValue());
        assertTrue((text(interruptedOutput) + text(finalOutput)).contains("^C"));
        assertEquals(Boolean.FALSE, finalOutput.get("busy"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void outputFailureLeavesSuccessfulInputAcknowledgedAndAvailableForReading() throws Exception {
        String source = Files.readString(component);
        Files.writeString(component, source.replace("$data = $readOutput($paths['output'], $limit, $nonBlocking);",
                "throw new RuntimeException('simulated output failure');"));
        Map<String, Object> result = finish(start("write", Map.of(
                "processId", "test-terminal", "cmd", "typed-once", "includeOutput", true)));
        assertEquals(200, ((Number) result.get("code")).intValue());
        assertEquals(10, ((Number) result.get("written")).intValue());
        assertEquals(500, ((Number) ((Map<?, ?>) result.get("output")).get("code")).intValue());
        Files.writeString(component, source);
        assertEquals("typed-once", text(call("read", "")));
    }

    @Test
    void batchReadsBoundOutputAndPreserveMissingNumericIdentifiers() throws Exception {
        call("write", "head -c 70000 /dev/zero | tr '\\000' A\nexit\n");
        Map<String, Object> params = Map.of("processIds", List.of("test-terminal", "0"));
        Map<?, ?> terminals = (Map<?, ?>) finish(start("read-batch", params)).get("terminals");
        Map<?, ?> first = (Map<?, ?>) terminals.get("test-terminal");
        assertEquals(65536, ((byte[]) first.get("data")).length);
        assertEquals(Boolean.TRUE, first.get("hasMore"));
        assertEquals(Boolean.FALSE, first.get("eof"));
        assertEquals(Boolean.TRUE, ((Map<?, ?>) terminals.get("0")).get("missing"));
        Map<?, ?> last = (Map<?, ?>) ((Map<?, ?>) finish(start("read-batch", params)).get("terminals")).get("test-terminal");
        assertEquals(Boolean.TRUE, last.get("eof"));
        String output = new String((byte[]) first.get("data"), StandardCharsets.UTF_8)
                + new String((byte[]) last.get("data"), StandardCharsets.UTF_8);
        assertTrue(output.contains("A".repeat(70000)));
        Map<?, ?> numeric = (Map<?, ?>) finish(start("read-batch", Map.of("processIds", List.of("0", "1")))).get("terminals");
        assertEquals(Boolean.TRUE, ((Map<?, ?>) numeric.get("1")).get("missing"));
    }

    @Test
    void rejectsInvalidBatchesBeforeConsumingOutput() throws Exception {
        call("write", "pending-input");
        for (List<String> ids : List.of(List.<String>of(), List.of("test-terminal", "../invalid"),
                List.of("test-terminal", "test-terminal"), java.util.Collections.nCopies(17, "test-terminal"))) {
            Request invalid = start("read-batch", Map.of("processIds", ids));
            assertTrue(invalid.process.waitFor(2, TimeUnit.SECONDS));
            assertNotEquals(0, invalid.process.exitValue());
        }
        assertEquals("pending-input", text(call("read", "")));
    }

    @Test
    void busyStateOrOutputLockDoesNotBlockTheRestOfABatch() throws Exception {
        call("write", "pending-input");
        for (String kind : List.of("lock", "output")) {
            Path ready = directory.resolve(kind + "-ready");
            Request holder = start("hold-lock", Map.of("processId", "test-terminal", "kind", kind, "ready", ready.toString()));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (!Files.exists(ready) && System.nanoTime() < deadline) Thread.sleep(10);
            assertTrue(Files.exists(ready));
            Map<?, ?> terminals = (Map<?, ?>) finish(start("read-batch",
                    Map.of("processIds", List.of("test-terminal", "other-terminal")))).get("terminals");
            assertTrue(holder.process.isAlive(), "batch must not wait for another terminal's lock");
            assertEquals(500, ((Number) ((Map<?, ?>) terminals.get("test-terminal")).get("code")).intValue());
            assertEquals(Boolean.TRUE, ((Map<?, ?>) terminals.get("other-terminal")).get("missing"));
            finish(holder);
        }
        assertEquals("pending-input", text(call("read", "")));
    }

    @Test
    void streamsOutputAndInterruptsWhileTheWriteIsStillRunning() throws Exception {
        Request running = start("write", "printf stream-start; sleep 10; printf should-not-run\n");
        String early = readUntil("\r\nstream-start");
        assertTrue(running.process.isAlive(), "write should still be executing");
        assertTrue(early.endsWith("stream-start"), early);
        long began = System.nanoTime();
        assertEquals(200, call("write", "\u0003").get("code"));
        finish(running);
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began) < 2000);
        String output = text(call("read", ""));
        assertTrue(output.contains("^C"), output);
        assertFalse(output.contains("should-not-run"), output);
        call("write", "printf usable-again\n");
        assertTrue(text(call("read", "")).contains("usable-again"));
    }

    @Test
    void stopsARunningWriteWithoutRecreatingItsSession() throws Exception {
        Request running = start("write", "printf started; sleep 10\n");
        readUntil("\r\nstarted");
        long began = System.nanoTime();
        call("stop", "");
        assertEquals(Boolean.FALSE, finish(running).get("alive"));
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began) < 2000);
        Map<String, Object> missing = call("read", "");
        assertEquals(Boolean.TRUE, missing.get("missing"));
        assertEquals(Boolean.TRUE, missing.get("eof"));
    }

    @Test
    void drainsMultipleChunksAfterExit() throws Exception {
        call("write", "head -c 700000 /dev/zero | tr '\\000' A\n"
                + "head -c 700000 /dev/zero | tr '\\000' B\nexit\n");
        Map<String, Object> first = call("read", "");
        assertEquals(Boolean.FALSE, first.get("alive"));
        assertEquals(Boolean.FALSE, first.get("eof"));
        assertEquals(Boolean.TRUE, first.get("hasMore"));
        assertEquals(1048576, ((byte[]) first.get("data")).length);
        Map<String, Object> last = call("read", "");
        assertEquals(Boolean.TRUE, last.get("eof"));
        assertTrue(((byte[]) last.get("data")).length > 350000);
    }

    @Test
    void consumesSplitArrowSequencesAndBackspacesCompleteUtf8Characters() throws Exception {
        call("write", "printf '");
        call("write", "\u001b");
        call("write", "[");
        call("write", "A");
        assertEquals("printf '", text(call("read", "")));
        call("write", "中\u007fOK'\n");
        String output = text(call("read", ""));
        assertFalse(output.contains("�"), output);
        assertTrue(output.contains("OK"), output);
    }

    @Test
    void retainsCwdAndRejectsAnOverlappingCommand() throws Exception {
        call("write", "cd /\n");
        call("read", "");
        Request running = start("write", "printf started; sleep 10\n");
        readUntil("\r\nstarted");
        assertEquals(409, call("write", "printf overlap\n").get("code"));
        call("write", "\u0003");
        finish(running);
        call("read", "");
        call("write", "pwd\n");
        assertTrue(text(call("read", "")).contains("\n/\n"));
    }

    @Test
    void sharesTheExecutionDeadlineAcrossPastedCommands() throws Exception {
        // Shorten the real deadline for this timing test without changing the loop.
        Files.writeString(component, Files.readString(component)
                .replace("$deadline = microtime(true) + 20;", "$deadline = microtime(true) + 1;"));
        long began = System.nanoTime();
        call("write", "sleep 0.7\nsleep 0.7\nprintf should-not-run\n");
        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began);
        assertTrue(elapsed >= 900 && elapsed < 2500, "elapsed=" + elapsed);
        String output = text(call("read", ""));
        assertTrue(output.contains("command stopped by terminal limit"), output);
        assertFalse(output.contains("should-not-run"), output);
    }

    @Test
    void drainsNativePtyOutputBeforeReportingEof() throws Exception {
        startNativePty();
        call("write", "stty -echo; printf '\\npty-ready\\n'\r");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        StringBuilder ready = new StringBuilder();
        while (ready.indexOf("pty-ready\r\n") < 0 && System.nanoTime() < deadline) {
            ready.append(text(call("read", "")));
            Thread.sleep(20);
        }
        assertTrue(ready.toString().contains("pty-ready\r\n"), ready.toString());
        call("write", "head -c 1400000 /dev/zero | tr '\\000' X; printf tail-marker; exit 7\r");
        StringBuilder output = new StringBuilder();
        Map<String, Object> response;
        do {
            response = call("read", "");
            output.append(text(response));
            if (Boolean.TRUE.equals(response.get("eof"))) break;
            Thread.sleep(20);
        } while (System.nanoTime() < deadline);
        assertEquals(Boolean.TRUE, response.get("eof"));
        // Interactive shells may echo the command; validate the uninterrupted
        // output body and its final marker independently of that echo.
        assertTrue(output.indexOf("X".repeat(1400000) + "tail-marker") >= 0,
                "PTY output body or final marker was truncated");
        assertEquals(7, response.get("exitCode"));
    }

    @Test
    void boundsNativePtyInputWhenTheChildDoesNotRead() throws Exception {
        startNativePty();
        call("write", "stty raw -echo; printf '\\nblocked-ready\\n'; exec sleep 30\r");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        StringBuilder ready = new StringBuilder();
        while (ready.indexOf("\nblocked-ready\n") < 0 && System.nanoTime() < deadline) {
            ready.append(text(call("read", "")));
            Thread.sleep(20);
        }
        assertTrue(ready.indexOf("\nblocked-ready\n") >= 0, ready.toString());
        Request writing = start("write", "x".repeat(512 * 1024));
        try {
            assertTrue(writing.process.waitFor(4, TimeUnit.SECONDS), "blocked PTY input must release the session lock");
            assertTrue(Files.readString(writing.output).contains("terminal input timed out"));
            assertEquals(Boolean.TRUE, call("stop", "").get("stopped"));
        } finally {
            writing.process.destroyForcibly();
            writing.process.waitFor(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void preservesTheRotationNoticeAfterEarlierOutputWasConsumed() throws Exception {
        startNativePty();
        call("write", "stty -echo; head -c 8192 /dev/zero | tr '\\000' A; printf '\\nrotation-ready\\n'\r");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        StringBuilder ready = new StringBuilder();
        while (ready.indexOf("\r\nrotation-ready\r\n") < 0 && System.nanoTime() < deadline) {
            ready.append(text(call("read", "")));
            Thread.sleep(20);
        }
        assertTrue(ready.indexOf("\r\nrotation-ready\r\n") >= 0, ready.toString());
        call("write", "head -c 12000000 /dev/zero | tr '\\000' X; printf rotation-%s tail\r");
        while (!Boolean.TRUE.equals(call("has-tail", "").get("found")) && System.nanoTime() < deadline) Thread.sleep(20);
        assertEquals(Boolean.TRUE, call("has-tail", "").get("found"));
        StringBuilder output = new StringBuilder();
        Map<String, Object> response;
        do {
            response = call("read", "");
            output.append(text(response));
        } while (Boolean.TRUE.equals(response.get("hasMore")) && System.nanoTime() < deadline);
        assertTrue(output.indexOf("[terminal output rotated]") >= 0, "rotation notice must not be skipped by an old cursor");
        assertTrue(output.indexOf("rotation-tail") >= 0);
    }

    @Test
    void expiresAnUnresponsivePtyShellWithoutMoreRequests() throws Exception {
        startNativePty();
        call("write", "trap '' HUP TERM; printf '\\nstubborn-ready\\n'\r");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        StringBuilder ready = new StringBuilder();
        while (ready.indexOf("\r\nstubborn-ready\r\n") < 0 && System.nanoTime() < deadline) {
            ready.append(text(call("read", "")));
            Thread.sleep(20);
        }
        assertTrue(ready.indexOf("\r\nstubborn-ready\r\n") >= 0);
        call("expire", "");
        Thread.sleep(1500);
        Map<String, Object> response = call("read", "");
        assertEquals(Boolean.FALSE, response.get("alive"));
        assertEquals(Boolean.TRUE, response.get("eof"));
    }

    private void startNativePty() throws Exception {
        call("stop", "");
        try (var stream = Objects.requireNonNull(getClass().getResourceAsStream("/components/ExecCommandComponent.php"))) {
            Files.write(component, stream.readAllBytes());
        }
        Map<String, Object> initialized = call("init", "");
        Assumptions.assumeTrue(Boolean.TRUE.equals(initialized.get("pty")), "Python PTY unavailable");
    }

    private String readUntil(String expected) throws Exception {
        StringBuilder output = new StringBuilder();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        do {
            Map<String, Object> response = call("read", "");
            output.append(text(response));
            if (Boolean.TRUE.equals(response.get("busy")) && output.indexOf(expected) >= 0) return output.toString();
            Thread.sleep(20);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("missing live output: " + output);
    }

    private String text(Map<String, Object> response) {
        return new String((byte[]) response.get("data"), StandardCharsets.UTF_8);
    }

    private Map<String, Object> call(String action, String command) throws Exception {
        return finish(start(action, command));
    }

    private Request start(String action, String command) throws Exception {
        return start(action, Map.of("processId", "test-terminal", "cmd", command));
    }

    private Request start(String action, Map<String, Object> parameters) throws Exception {
        String script = "function leo_binary($v){return array('$leoBinary'=>base64_encode($v));}"
                + "$component=require $argv[1];$params=json_decode(file_get_contents($argv[3]),true);"
                + "if($argv[2]==='expire'){$paths=$pathsForKey(hash('sha256',$params['processId']));"
                + "touch($paths['state'],time()-1900);echo json_encode(array('code'=>200));}"
                + "elseif($argv[2]==='has-tail'){$paths=$pathsForKey(hash('sha256',$params['processId']));"
                + "echo json_encode(array('found'=>strpos(file_get_contents($paths['output']),'rotation-tail')!==false));}"
                + "elseif($argv[2]==='hold-lock'){$paths=$pathsForKey(hash('sha256',$params['processId']));"
                + "$lock=fopen($paths[$params['kind']],'c+');flock($lock,LOCK_EX);"
                + "file_put_contents($params['ready'],'ready');usleep(1500000);"
                + "flock($lock,LOCK_UN);fclose($lock);echo json_encode(array('code'=>200));}"
                + "else echo json_encode(call_user_func($component['handle'],$argv[2],$params));"
                + "if($argv[2]==='stop')@rmdir($baseDirectory);";
        Path params = Files.createTempFile(directory, "request-", ".json");
        Files.write(params, PortableJsonCodec.encode(parameters));
        Path output = Files.createTempFile(directory, "response-", ".json");
        Process process = new ProcessBuilder("php", "-r", script, component.toString(), action, params.toString())
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        Request request = new Request(process, output);
        requests.add(request);
        return request;
    }

    private Map<String, Object> finish(Request request) throws Exception {
        assertTrue(request.process.waitFor(6, TimeUnit.SECONDS), "PHP request timed out");
        String output = Files.readString(request.output);
        assertEquals(0, request.process.exitValue(), output);
        return PortableJsonCodec.decode(output.getBytes(StandardCharsets.UTF_8));
    }
}
