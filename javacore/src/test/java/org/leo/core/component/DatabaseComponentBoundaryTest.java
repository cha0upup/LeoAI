package org.leo.core.component;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.leo.core.util.javassist.CloneWithJavassist;
import org.sqlite.JDBC;

import java.io.StringReader;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DatabaseComponentBoundaryTest {

    @ParameterizedTest
    @ValueSource(strings = {"source", "payload", "transformed"})
    void exactRowLimitKeepsAllDuplicateColumnsWithoutTruncation(String variant) throws Exception {
        Map<String, Object> result = execute(variant, Map.of(
                "sql", "SELECT 1 AS id, 2 AS id, 3 AS id_2", "maxRows", 1));

        assertEquals(200, result.get("code"));
        assertEquals(List.of(Map.of("id", 1, "id_2", 2, "id_2_2", 3)), result.get("rows"));
        List<?> columns = (List<?>) result.get("columns");
        assertEquals(List.of("id", "id_2", "id_2_2"), columns.stream()
                .map(column -> ((Map<?, ?>) column).get("name")).toList());
        assertEquals(false, result.get("truncated"));
        assertNull(result.get("truncationReason"));
        assertEquals(0, result.get("affectedRows"));
        assertNull(result.get("generatedKey"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"source", "payload", "transformed"})
    void resultByteLimitExcludesTheEntireOverflowRow(String variant) throws Exception {
        String first = "a".repeat(500);
        Map<String, Object> result = execute(variant, Map.of(
                "sql", "SELECT ? AS value UNION ALL SELECT ?",
                "parameters", List.of(first, "b".repeat(600)), "maxResultBytes", 1024));

        assertEquals(200, result.get("code"));
        assertEquals(List.of(Map.of("value", first)), result.get("rows"));
        assertEquals(1, result.get("rowCount"));
        assertEquals(505, result.get("resultBytes"));
        assertEquals(true, result.get("truncated"));
        assertEquals("MAX_RESULT_BYTES", result.get("truncationReason"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"source", "payload", "transformed"})
    void stringTruncationPreservesUnicodeAndExactByteLimits(String variant) throws Exception {
        String[] inputs = {"x".repeat(255) + "😀", "中".repeat(84) + "😀", "中".repeat(85) + "😀"};
        String[] expected = {"x".repeat(255), inputs[1], "中".repeat(85)};
        for (int index = 0; index < inputs.length; index++) {
            Map<String, Object> result = execute(variant, Map.of(
                    "sql", "SELECT ? AS value", "parameters", List.of(inputs[index]), "maxCellBytes", 256));

            assertEquals(200, result.get("code"));
            assertEquals(List.of(Map.of("value", expected[index])), result.get("rows"));
            assertEquals(!inputs[index].equals(expected[index]), result.get("truncated"));
            assertEquals(5 + expected[index].getBytes(StandardCharsets.UTF_8).length, result.get("resultBytes"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"source", "payload", "transformed"})
    void capabilityProbeReportsTrimmedUnavailableDriver(String variant) throws Exception {
        Map<String, Object> result = invoke(component(variant), Map.of(
                "operation", "capabilities", "requestedDriver", "  missing.jdbc.Driver  "));

        assertEquals(200, result.get("code"));
        Map<?, ?> requested = (Map<?, ?>) result.get("requestedDriver");
        assertEquals("missing.jdbc.Driver", requested.get("id"));
        assertEquals(false, requested.get("available"));
        assertTrue(((List<?>) result.get("drivers")).stream().map(Map.class::cast)
                .anyMatch(driver -> "missing.jdbc.Driver".equals(driver.get("id"))
                        && Boolean.FALSE.equals(driver.get("available"))));
        assertFalse(result.containsKey("rows"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"source", "payload", "transformed"})
    void driverFailuresKeepDistinctCategoriesAndEmptyResults(String variant) throws Exception {
        String[] drivers = {"missing.jdbc.Driver", "java.lang.String", "org.sqlite.JDBC"};
        String[] categories = {"DRIVER_NOT_FOUND", "EXECUTION_ERROR", "URL_MISMATCH"};
        int[] codes = {503, 500, 400};
        for (int index = 0; index < drivers.length; index++) {
            Map<String, Object> result = execute(variant, Map.of(
                    "driverClass", drivers[index], "jdbcUrl", "jdbc:unknown:test?password=secret-value",
                    "password", "secret-value", "sql", "SELECT 1"));

            assertEquals(codes[index], result.get("code"));
            assertEquals(categories[index], result.get("errorCategory"));
            assertEquals(List.of(), result.get("rows"));
            assertEquals(List.of(), result.get("columns"));
            assertEquals(0, result.get("rowCount"));
            assertFalse(String.valueOf(result.get("msg")).contains("secret-value"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"source", "payload", "transformed"})
    void clobReadIsBoundedAndClosesResourcesAcrossUnicodeChunks(String variant) throws Exception {
        String prefix = "x".repeat(2047) + "😀";
        TrackingReader reader = new TrackingReader(prefix + "中".repeat(1000));
        Clob clob = mock(Clob.class);
        when(clob.getCharacterStream()).thenReturn(reader);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet rows = mock(ResultSet.class);
        ResultSetMetaData metadata = mock(ResultSetMetaData.class);
        when(connection.prepareStatement(anyString(), eq(Statement.RETURN_GENERATED_KEYS))).thenReturn(statement);
        when(statement.execute()).thenReturn(true);
        when(statement.getResultSet()).thenReturn(rows);
        when(rows.getMetaData()).thenReturn(metadata);
        when(metadata.getColumnCount()).thenReturn(1);
        when(metadata.getColumnLabel(1)).thenReturn("value");
        when(metadata.getColumnName(1)).thenReturn("value");
        when(rows.next()).thenReturn(true, false);
        when(rows.getObject(1)).thenReturn(clob);
        ClobDriver.CONNECTION.set(connection);
        try {
            Map<String, Object> result = execute(variant, Map.of(
                    "driverClass", ClobDriver.class.getName(), "sql", "SELECT value", "maxCellBytes", 2304));

            assertEquals(200, result.get("code"));
            assertEquals(List.of(Map.of("value", prefix + "中".repeat(84))), result.get("rows"));
            assertEquals("MAX_CELL_BYTES", result.get("truncationReason"));
            assertTrue(reader.charactersRead <= 2305);
            assertTrue(reader.closed);
            verify(rows).close();
            verify(statement).close();
            verify(connection).close();
        } finally {
            ClobDriver.CONNECTION.remove();
        }
    }

    private Map<String, Object> execute(String variant, Map<String, Object> options) throws Exception {
        Map<String, Object> params = new HashMap<>();
        params.put("driverClass", "org.sqlite.JDBC");
        params.put("jdbcUrl", "jdbc:sqlite::memory:");
        params.putAll(options);
        return invoke(component(variant), params);
    }

    private Runnable component(String variant) throws Exception {
        if ("source".equals(variant)) return new DatabaseComponent();
        byte[] bytes;
        if ("transformed".equals(variant)) {
            bytes = CloneWithJavassist.cloneClass("DatabaseComponent", "org.leo.generated.Database" + System.nanoTime());
        } else {
            try (var input = getClass().getResourceAsStream("/component/DatabaseComponent.payload")) {
                assertNotNull(input);
                bytes = input.readAllBytes();
            }
        }
        return (Runnable) new BytecodeLoader().define(bytes).getDeclaredConstructor().newInstance();
    }

    private Map<String, Object> invoke(Runnable component, Map<String, Object> params) {
        Thread thread = Thread.currentThread();
        ClassLoader original = thread.getContextClassLoader();
        ComponentBridge bridge = new ComponentBridge(original, params);
        thread.setContextClassLoader(bridge);
        try {
            component.run();
            assertNotNull(bridge.result);
            return bridge.result;
        } finally {
            thread.setContextClassLoader(original);
        }
    }

    public static class ClobDriver extends JDBC {
        private static final ThreadLocal<Connection> CONNECTION = new ThreadLocal<>();

        @Override
        public Connection connect(String url, Properties properties) throws SQLException {
            return CONNECTION.get();
        }
    }

    private static class TrackingReader extends StringReader {
        private int charactersRead;
        private boolean closed;

        private TrackingReader(String text) { super(text); }

        @Override
        public int read(char[] buffer, int offset, int length) throws java.io.IOException {
            int count = super.read(buffer, offset, length);
            if (count > 0) charactersRead += count;
            return count;
        }

        @Override
        public void close() { closed = true; super.close(); }
    }

    private static class BytecodeLoader extends ClassLoader {
        private Class<?> define(byte[] bytes) { return defineClass(null, bytes, 0, bytes.length); }
    }

    private static class ComponentBridge extends ClassLoader implements InvocationHandler {
        private final Map<String, Object> params;
        private Map<String, Object> result;

        private ComponentBridge(ClassLoader parent, Map<String, Object> params) {
            super(parent);
            this.params = params;
        }

        @Override
        @SuppressWarnings("unchecked")
        public Object invoke(Object proxy, Method method, Object[] args) {
            if (args == null) return params;
            result = (Map<String, Object>) args[0];
            return null;
        }
    }
}
