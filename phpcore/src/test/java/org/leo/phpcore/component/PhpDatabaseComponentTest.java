package org.leo.phpcore.component;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.leo.core.util.json.PortableJsonCodec;
import org.leo.core.puppet.database.DatabaseConnectionSpec;
import org.leo.phpcore.database.PhpDatabaseConnectionAdapter;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import static org.leo.phpcore.PhpTestSupport.code;
import static org.leo.phpcore.PhpTestSupport.commandSucceeds;
import static org.leo.phpcore.PhpTestSupport.phpAvailable;
import static org.leo.phpcore.PhpTestSupport.runJson;

class PhpDatabaseComponentTest {

    private Path component;
    private Path database;

    @BeforeEach
    void setUp() throws Exception {
        Assumptions.assumeTrue(phpAvailable(), "PHP CLI is not installed");
        Assumptions.assumeTrue(pdoSqliteAvailable(), "pdo_sqlite is not installed");
        URL resource = Objects.requireNonNull(getClass().getResource("/components/DatabaseComponent.php"));
        component = Paths.get(resource.toURI());
        database = Files.createTempFile("leo-php-database-", ".sqlite");
    }

    @AfterEach
    void tearDown() throws Exception {
        if (database != null) Files.deleteIfExists(database);
    }

    @Test
    void executesSchemaCrudAndReturnsTheSharedSqlResultShape() throws Exception {
        Map<String, Object> created = execute("CREATE TABLE inventory ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, quantity INTEGER, payload BLOB)");
        assertSuccess(created);
        assertEquals(0, ((Number) created.get("affectedRows")).intValue());
        assertEquals(List.of(), created.get("columns"));

        Map<String, Object> firstInsert = execute(
                "INSERT INTO inventory(name, quantity, payload) VALUES ('alpha', 3, X'00FF41')");
        assertSuccess(firstInsert);
        assertEquals(1, ((Number) firstInsert.get("affectedRows")).intValue());
        assertEquals("1", firstInsert.get("generatedKey"));
        assertSuccess(execute("INSERT INTO inventory(name, quantity, payload) VALUES ('beta', NULL, NULL)"));

        Map<String, Object> selected = execute(
                "SELECT id, name, quantity, payload FROM inventory ORDER BY id");
        assertSuccess(selected);
        assertEquals(2, ((Number) selected.get("rowCount")).intValue());
        List<?> columns = assertInstanceOf(List.class, selected.get("columns"));
        assertEquals(List.of("id", "name", "quantity", "payload"), columns.stream()
                .map(item -> String.valueOf(((Map<?, ?>) item).get("name"))).toList());
        assertTrue(columns.stream().allMatch(item -> item instanceof Map<?, ?>
                && ((Map<?, ?>) item).containsKey("label") && ((Map<?, ?>) item).containsKey("type")));
        List<?> rows = assertInstanceOf(List.class, selected.get("rows"));
        Map<?, ?> alpha = assertInstanceOf(Map.class, rows.get(0));
        assertEquals("alpha", alpha.get("name"));
        assertEquals(3, ((Number) alpha.get("quantity")).intValue());
        assertArrayEquals(new byte[]{0, (byte) 255, 65}, assertInstanceOf(byte[].class, alpha.get("payload")));
        Map<?, ?> beta = assertInstanceOf(Map.class, rows.get(1));
        assertEquals(null, beta.get("quantity"));
        assertEquals(null, beta.get("payload"));

        Map<String, Object> updated = execute("UPDATE inventory SET quantity = 8 WHERE name = 'alpha'");
        assertSuccess(updated);
        assertEquals(1, ((Number) updated.get("affectedRows")).intValue());
        Map<String, Object> deleted = execute("DELETE FROM inventory WHERE name = 'beta'");
        assertSuccess(deleted);
        assertEquals(1, ((Number) deleted.get("affectedRows")).intValue());
    }

    @Test
    void supportsMetadataQueriesAndReportsConfigurationErrorsWithStableFields() throws Exception {
        assertSuccess(execute("CREATE TABLE metadata_sample (id INTEGER PRIMARY KEY, title VARCHAR(80))"));

        Map<String, Object> version = execute("SELECT sqlite_version() AS version");
        assertSuccess(version);
        assertTrue(String.valueOf(((Map<?, ?>) ((List<?>) version.get("rows")).get(0)).get("version"))
                .matches("\\d+\\.\\d+.*"));

        Map<String, Object> tables = execute(
                "SELECT name, '' AS schema_name, '' AS comment FROM sqlite_master WHERE type = 'table' ORDER BY name");
        assertSuccess(tables);
        assertTrue(((List<?>) tables.get("rows")).stream()
                .anyMatch(row -> "metadata_sample".equals(((Map<?, ?>) row).get("name"))));

        Map<String, Object> missingSql = invoke("exec", pdoConnection(" "));
        assertEquals(400, code(missingSql));
        assertEquals(List.of(), missingSql.get("rows"));
        assertEquals(List.of(), missingSql.get("columns"));

        Map<String, Object> unsupported = invoke("metadata", pdoConnection("SELECT 1"));
        assertEquals(400, code(unsupported));
        assertTrue(String.valueOf(unsupported.get("msg")).contains("unsupported"));
    }

    @Test
    void reportsPdoRuntimeCapabilitiesBeforeConnecting() throws Exception {
        Map<String, Object> capabilities = invoke(
                "capabilities", Map.of("requestedDriver", "sqlite"));

        assertEquals(200, code(capabilities));
        assertEquals("php", capabilities.get("runtime"));
        assertEquals("pdo", capabilities.get("provider"));
        assertEquals(true, capabilities.get("available"));
        assertEquals(true, ((Map<?, ?>) capabilities.get("requestedDriver")).get("available"));
        assertTrue(((List<?>) capabilities.get("drivers")).stream()
                .anyMatch(item -> "sqlite".equals(((Map<?, ?>) item).get("id"))));
    }

    @Test
    void limitsLargeResultSetsAndReportsTheBoundary() throws Exception {
        Map<String, Object> selected = execute(
                "WITH RECURSIVE numbers(value) AS (SELECT 1 UNION ALL "
                        + "SELECT value + 1 FROM numbers WHERE value < 5) SELECT value FROM numbers",
                Map.of("maxRows", 2));

        assertSuccess(selected);
        assertEquals(2, selected.get("rowCount"));
        assertEquals(true, selected.get("truncated"));
        assertEquals("MAX_ROWS", selected.get("truncationReason"));
        assertTrue(((Number) selected.get("resultBytes")).intValue() > 0);
    }

    private Map<String, Object> execute(String sql) throws Exception {
        return execute(sql, Map.of());
    }

    private Map<String, Object> execute(String sql, Map<String, Object> options) throws Exception {
        Map<String, Object> connection = new LinkedHashMap<>();
        connection.put("dialect", "sqlite");
        connection.put("connectionMode", "standard");
        connection.put("variant", "file");
        connection.put("file", database.toAbsolutePath().toString());
        Map<String, Object> params = new LinkedHashMap<>(new PhpDatabaseConnectionAdapter()
                .adapt(DatabaseConnectionSpec.fromMap(connection)));
        params.put("sql", sql);
        params.putAll(options);
        return invoke("exec", params);
    }

    private Map<String, Object> pdoConnection(String sql) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("provider", "pdo");
        params.put("pdoDriver", "sqlite");
        params.put("dsn", "sqlite:" + database.toAbsolutePath());
        params.put("username", "");
        params.put("password", "");
        params.put("sql", sql);
        return params;
    }

    private Map<String, Object> invoke(String action, Map<String, Object> params) throws Exception {
        String encoded = Base64.getEncoder().encodeToString(PortableJsonCodec.encode(params));
        String script = "function leo_binary($value){return array('$leoBinary'=>base64_encode($value));}"
                + "$component=require $argv[1];"
                + "$params=json_decode(base64_decode($argv[2]),true);"
                + "echo json_encode(call_user_func($component['handle'],$argv[3],$params));";
        return runJson(15, "php", "-r", script, component.toString(), encoded, action);
    }

    private void assertSuccess(Map<String, Object> response) {
        assertEquals(200, code(response), String.valueOf(response));
        assertTrue(response.get("columns") instanceof List<?>);
        assertTrue(response.get("rows") instanceof List<?>);
        assertTrue(response.get("rowCount") instanceof Number);
        assertTrue(response.get("affectedRows") instanceof Number);
        assertEquals("pdo", ((Map<?, ?>) response.get("runtimeMetadata")).get("provider"));
    }

    private boolean pdoSqliteAvailable() {
        return commandSucceeds("php", "-r", "exit(in_array('sqlite',PDO::getAvailableDrivers(),true)?0:1);");
    }
}
