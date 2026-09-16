package org.leo.phpcore.component;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class PhpFileComponentTest {
    @TempDir Path directory;

    @ParameterizedTest
    @ValueSource(strings = {"chunks", "short-write", "zip-roundtrip", "zip-links", "zip-limits", "zip-failure", "profile"})
    void executesFileContractOnPhp(String scenario) throws Exception {
        Assumptions.assumeFalse(System.getProperty("os.name").toLowerCase().contains("windows"));
        try {
            Process available = new ProcessBuilder("php", "-r", "exit(extension_loaded('zip') ? 0 : 1);").start();
            Assumptions.assumeTrue(available.waitFor(5, TimeUnit.SECONDS) && available.exitValue() == 0, "PHP CLI with zip required");
        } catch (java.io.IOException missing) { Assumptions.abort("PHP CLI unavailable"); }
        Path components = Files.createDirectory(directory.resolve("components"));
        for (String name : new String[]{"FileComponent", "FileUploadComponent", "FileDownloadComponent", "CompressComponent", "DecompressComponent"}) {
            try (var input = getClass().getResourceAsStream("/components/" + name + ".php")) {
                assertNotNull(input);
                Files.copy(input, components.resolve(name + ".php"));
            }
        }
        Path script = directory.resolve("contract.php");
        try (var input = getClass().getResourceAsStream("/file-component-contract.php")) {
            assertNotNull(input); Files.copy(input, script);
        }
        Path output = directory.resolve("result.txt");
        Process process = new ProcessBuilder("php", script.toString(), components.toString(),
                Files.createDirectory(directory.resolve("fixtures")).toString(), scenario)
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            assertTrue(process.waitFor(20, TimeUnit.SECONDS), "PHP contract timed out");
            String text = Files.readString(output);
            assertEquals(0, process.exitValue(), text);
            Assumptions.assumeFalse(text.contains("SKIP"), text);
            assertTrue(text.contains("PASS " + scenario), text);
        } finally { if (process.isAlive()) process.destroyForcibly(); }
    }
}
