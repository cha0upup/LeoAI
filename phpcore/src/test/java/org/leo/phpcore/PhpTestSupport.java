package org.leo.phpcore;

import org.leo.core.util.json.PortableJsonCodec;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public final class PhpTestSupport {

    private PhpTestSupport() {
    }

    public static boolean phpAvailable() {
        return commandSucceeds("php", "-v");
    }

    public static boolean commandSucceeds(String... command) {
        Process process = null;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            return process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (IOException | InterruptedException error) {
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            return false;
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
        }
    }

    public static Map<String, Object> invokeComponent(String name, String action, String paramsExpression)
            throws Exception {
        return invokeComponent(name, action, paramsExpression, false);
    }

    public static Map<String, Object> invokeComponent(String name, String action, String paramsExpression,
                                                     boolean disableCommands) throws Exception {
        Path component = Path.of(Objects.requireNonNull(
                PhpTestSupport.class.getResource("/components/" + name)).toURI());
        String script = "$component=require $argv[1];echo json_encode(call_user_func($component['handle'],"
                + phpString(action) + "," + paramsExpression + "));";
        return disableCommands
                ? runJson(20, "php", "-d", "disable_functions=exec,shell_exec", "-r", script, component.toString())
                : runJson(20, "php", "-r", script, component.toString());
    }

    public static Map<String, Object> runJson(long timeoutSeconds, String... command) throws Exception {
        Path outputFile = Files.createTempFile("php-test-", ".json");
        Process process = null;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true)
                    .redirectOutput(outputFile.toFile()).start();
            assertTrue(process.waitFor(timeoutSeconds, TimeUnit.SECONDS), "PHP command timed out");
            String output = Files.readString(outputFile, StandardCharsets.UTF_8);
            assertEquals(0, process.exitValue(), output);
            return PortableJsonCodec.decode(output.getBytes(StandardCharsets.UTF_8));
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
            Files.deleteIfExists(outputFile);
        }
    }

    public static int code(Map<String, Object> response) {
        return ((Number) response.get("code")).intValue();
    }

    public static String phpString(String value) {
        return "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }
}
