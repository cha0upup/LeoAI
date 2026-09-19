package org.leo.phpcore.component;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;

import static org.leo.phpcore.PhpTestSupport.commandSucceeds;
import static org.leo.phpcore.PhpTestSupport.runJson;

class PhpExecCommandSimpleComponentTest {

    private static Path component;

    @BeforeAll
    static void setUp() throws Exception {
        Assumptions.assumeTrue(commandSucceeds("php", "-r",
                "exit(function_exists('proc_open')&&function_exists('exec')?0:1);"), "PHP CLI is not installed");
        URL resource = Objects.requireNonNull(
                PhpExecCommandSimpleComponentTest.class.getResource("/components/ExecCommandSimpleComponent.php"));
        component = Paths.get(resource.toURI());
    }

    @ParameterizedTest(name = "exec fallback={0}")
    @ValueSource(booleans = {false, true})
    void bothBackendsPreserveOutputAndExitCode(boolean fallback, @TempDir Path directory) throws Exception {
        Path source = component;
        if (fallback) {
            source = directory.resolve("fallback.php");
            Files.writeString(source, Files.readString(component, StandardCharsets.UTF_8)
                    .replace("if ($available('proc_open')) {", "if (false) {"));
        }
        Map<String, Object> result = invoke(source);
        assertEquals("simple-ok-err", result.get("data"));
        assertEquals(7, ((Number) result.get("exitCode")).intValue());
    }

    private static Map<String, Object> invoke(Path source) throws Exception {
        String script = "$component=require $argv[1];"
                + "$code=\"fwrite(STDOUT,'simple-ok');fwrite(STDERR,'-err');exit(7);\";"
                + "$cmd=escapeshellarg(PHP_BINARY).' -r '.escapeshellarg($code);"
                + "echo json_encode(call_user_func($component['handle'],'exec',array('cmd'=>$cmd)));";
        return runJson(10, "php", "-r", script, source.toString());
    }
}
