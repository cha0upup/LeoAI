package org.leo.core.component;

import javassist.ClassPool;
import javassist.CtClass;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import static org.leo.core.component.ComponentTestSupport.assertTransformedRunnable;
import static org.leo.core.component.ComponentTestSupport.code;
import static org.leo.core.component.ComponentTestSupport.invokeComponent;
import static org.leo.core.component.ComponentTestSupport.params;
import static org.leo.core.component.ComponentTestSupport.setField;

class ExecutionAndDatabaseComponentTest {

    @Test
    void transformedPayloadsRemainRunnableAfterMethodRandomization() throws Exception {
        assertTransformedRunnable("DatabaseComponent");
        assertTransformedRunnable("ExecCommandSimpleComponent");
        assertTransformedRunnable("ExecScriptComponent");
        assertTransformedRunnable("PluginComponent");
    }

    @Test
    void databaseRequiresDriverAndKeepsStableErrorShape() throws Exception {
        Map<String, Object> response = invokeComponent(new DatabaseComponent(), params(
                "jdbcUrl", "jdbc:sqlite::memory:", "sql", "SELECT 1"));

        assertEquals(400, code(response));
        assertTrue(((java.util.List<?>) response.get("columns")).isEmpty());
        assertTrue(((java.util.List<?>) response.get("rows")).isEmpty());
        assertEquals(0, response.get("affectedRows"));
        assertTrue(response.containsKey("rowCount"));
        assertTrue(response.containsKey("generatedKey"));
    }

    @Test
    void databaseAcceptsUtf8ByteParameters() throws Exception {
        Map<String, Object> response = invokeComponent(new DatabaseComponent(), params(
                "driverClass", utf8("org.sqlite.JDBC"),
                "jdbcUrl", utf8("jdbc:sqlite::memory:"),
                "sql", utf8("SELECT 1 AS value")));

        assertEquals(200, code(response));
        assertEquals(1, response.get("rowCount"));
        assertEquals(1, ((java.util.List<?>) response.get("rows")).size());
        assertEquals("jdbc", ((Map<?, ?>) response.get("runtimeMetadata")).get("provider"));
    }

    @Test
    void databaseBindsParametersWithoutConcatenatingSql() throws Exception {
        Map<String, Object> response = invokeComponent(new DatabaseComponent(), params(
                "driverClass", "org.sqlite.JDBC",
                "jdbcUrl", "jdbc:sqlite::memory:",
                "sql", "SELECT ? AS value",
                "parameters", List.of("bound-value")));

        assertEquals(200, code(response));
        Map<?, ?> row = (Map<?, ?>) ((java.util.List<?>) response.get("rows")).get(0);
        assertEquals("bound-value", row.get("value"));
    }

    @Test
    void databaseLimitsRowsAndKeepsDuplicateColumnsAddressable() throws Exception {
        Map<String, Object> response = invokeComponent(new DatabaseComponent(), params(
                "driverClass", "org.sqlite.JDBC",
                "jdbcUrl", "jdbc:sqlite::memory:",
                "sql", "WITH RECURSIVE numbers(value) AS (SELECT 1 UNION ALL "
                        + "SELECT value + 1 FROM numbers WHERE value < 5) "
                        + "SELECT value AS id, value + 10 AS id FROM numbers",
                "maxRows", "2"));

        assertEquals(200, code(response));
        assertEquals(2, response.get("rowCount"));
        assertEquals(true, response.get("truncated"));
        assertEquals("MAX_ROWS", response.get("truncationReason"));
        java.util.List<?> columns = (java.util.List<?>) response.get("columns");
        assertEquals("id", ((Map<?, ?>) columns.get(0)).get("name"));
        assertEquals("id_2", ((Map<?, ?>) columns.get(1)).get("name"));
        Map<?, ?> row = (Map<?, ?>) ((java.util.List<?>) response.get("rows")).get(0);
        assertEquals(1, row.get("id"));
        assertEquals(11, row.get("id_2"));
    }

    @Test
    void databaseCapsOversizedCellsAndReportsTheBoundary() throws Exception {
        Map<String, Object> response = invokeComponent(new DatabaseComponent(), params(
                "driverClass", "org.sqlite.JDBC",
                "jdbcUrl", "jdbc:sqlite::memory:",
                "sql", "SELECT printf('%0300d', 0) AS payload",
                "maxCellBytes", 256));

        assertEquals(200, code(response));
        assertEquals(true, response.get("truncated"));
        assertEquals("MAX_CELL_BYTES", response.get("truncationReason"));
        Map<?, ?> row = (Map<?, ?>) ((java.util.List<?>) response.get("rows")).get(0);
        assertEquals(256, String.valueOf(row.get("payload")).length());
    }

    @Test
    void databaseReturnsStructuredDriverErrorsWithoutThrowing() throws Exception {
        Map<String, Object> response = invokeComponent(new DatabaseComponent(), params(
                "driverClass", "missing.jdbc.Driver",
                "jdbcUrl", "jdbc:missing:value",
                "sql", "SELECT 1"));

        assertEquals(503, code(response));
        assertEquals("DRIVER_NOT_FOUND", response.get("errorCategory"));
        assertEquals(false, response.get("retryable"));
        assertEquals(0, response.get("rowCount"));
    }

    @Test
    void databaseReportsAvailableRuntimeDriversBeforeConnecting() throws Exception {
        Map<String, Object> response = invokeComponent(new DatabaseComponent(), params(
                "operation", "capabilities",
                "requestedDriver", "org.sqlite.JDBC"));

        assertEquals(200, code(response));
        assertEquals("java", response.get("runtime"));
        assertEquals("jdbc", response.get("provider"));
        assertEquals(true, response.get("available"));
        assertEquals(true, ((Map<?, ?>) response.get("requestedDriver")).get("available"));
        assertTrue(((List<?>) response.get("drivers")).stream()
                .anyMatch(item -> "org.sqlite.JDBC".equals(((Map<?, ?>) item).get("className"))));
    }

    @Test
    void simpleCommandAcceptsByteCommandAndReturnsLifecycleFlags() throws Exception {
        Map<String, Object> response = invokeComponent(new ExecCommandSimpleComponent(), params(
                "cmd", utf8("echo simple-ok"), "timeout", "2"));

        assertEquals(200, code(response));
        assertFalse((Boolean) response.get("timedOut"));
        assertFalse((Boolean) response.get("truncated"));
        assertTrue(new String((byte[]) response.get("data"), StandardCharsets.UTF_8)
                .contains("simple-ok"));
    }

    @Test
    void simpleCommandTimeoutTerminatesPromptly() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(isWindows());
        long startedAt = System.nanoTime();
        Map<String, Object> response = invokeComponent(new ExecCommandSimpleComponent(), params(
                "cmd", "sleep 3", "timeout", 1));
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;

        assertEquals(200, code(response));
        assertTrue((Boolean) response.get("timedOut"));
        assertEquals(-1, response.get("exitCode"));
        assertTrue(elapsedMs < 2500L, "timeout should not wait for the original process");
    }

    @Test
    void simpleCommandReportsOutputTruncation() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(isWindows());
        Map<String, Object> response = invokeComponent(new ExecCommandSimpleComponent(), params(
                "cmd", "head -c 4198400 /dev/zero", "timeout", 5));

        assertEquals(200, code(response));
        assertFalse((Boolean) response.get("timedOut"));
        assertTrue((Boolean) response.get("truncated"));
        assertEquals(4 * 1024 * 1024, ((byte[]) response.get("data")).length);
    }

    @Test
    void scriptAcceptsByteParametersBeforeEngineLookup() throws Exception {
        Map<String, Object> response = invokeComponent(new ExecScriptComponent(), params(
                "language", utf8("missing-engine-for-test"), "script", utf8("1 + 1")));

        assertEquals(500, code(response));
        assertTrue(String.valueOf(response.get("msg")).contains("missing-engine-for-test"));
    }

    @Test
    void pluginRejectsInvalidParameterTypesWithClientError() throws Exception {
        Map<String, Object> invalidBytecode = invokeComponent(new PluginComponent(), params(
                "pluginBytecode", "not-bytes"));
        assertEquals(400, code(invalidBytecode));

        Map<String, Object> invalidParams = invokeComponent(new PluginComponent(), params(
                "pluginBytecode", new byte[]{1}, "pluginParam", "not-a-map"));
        assertEquals(400, code(invalidParams));
    }

    @Test
    void pluginDeletesTemporaryDirectoryWhenClassLoadingFails() throws Exception {
        byte[] bytecode = generatedClassBytecode("org.leo.generated.FuturePlugin" + System.nanoTime());
        bytecode[6] = (byte) 0x7f;
        bytecode[7] = (byte) 0xff;
        Set<String> before = pluginTempDirectories();

        PluginComponent component = new PluginComponent();
        setField(component, "params", params("pluginBytecode", bytecode));
        setField(component, "results", new HashMap<String, Object>());

        assertThrows(UnsupportedClassVersionError.class, component::invoke);
        assertEquals(before, pluginTempDirectories());
    }

    private byte[] utf8(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("windows");
    }

    private byte[] generatedClassBytecode(String className) throws Exception {
        CtClass generated = new ClassPool(true).makeClass(className);
        try {
            return generated.toBytecode();
        } finally {
            generated.detach();
        }
    }

    private Set<String> pluginTempDirectories() {
        File[] files = new File(System.getProperty("java.io.tmpdir")).listFiles();
        Set<String> paths = new HashSet<>();
        if (files == null) return paths;
        for (File file : files) {
            if (file.isDirectory() && file.getName().startsWith("leo-plugin-")) {
                paths.add(file.getAbsolutePath());
            }
        }
        return paths;
    }
}
