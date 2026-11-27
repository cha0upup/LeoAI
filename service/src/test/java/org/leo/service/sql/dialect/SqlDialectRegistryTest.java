package org.leo.service.sql.dialect;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlDialectRegistryTest {

    @Test
    void resolvesAliasesToCanonicalRegisteredDialects() {
        SqlDialectRegistry registry = new SqlDialectRegistry();

        assertEquals("mysql", registry.canonicalType("MariaDB"));
        assertEquals("postgresql", registry.canonicalType("postgres"));
        assertEquals("sqlserver", registry.canonicalType("MS"));
        assertEquals("dm", registry.canonicalType("Dameng"));
        assertEquals("kingbasees", registry.canonicalType("Kingbase"));
        assertEquals("kingbasees", registry.canonicalType("KES"));
        assertEquals("PostgreSQL", registry.require("postgres").getName());
    }

    @Test
    void exposesConnectionModesAliasesAndAnExplicitCapabilityMatrix() {
        SqlDialectRegistry registry = new SqlDialectRegistry();
        List<Map<String, Object>> catalog = registry.getDialectInfos();
        Map<String, Object> generic = catalog.stream()
                .filter(item -> "generic".equals(item.get("type")))
                .findFirst()
                .orElseThrow();
        Map<String, Object> mysql = catalog.stream()
                .filter(item -> "mysql".equals(item.get("type")))
                .findFirst()
                .orElseThrow();
        Map<String, Object> postgresql = catalog.stream()
                .filter(item -> "postgresql".equals(item.get("type")))
                .findFirst()
                .orElseThrow();
        Map<String, Object> sqlServer = catalog.stream()
                .filter(item -> "sqlserver".equals(item.get("type")))
                .findFirst()
                .orElseThrow();

        assertEquals(List.of("custom"), generic.get("connectionModes"));
        assertEquals(List.of(), generic.get("namespaceLevels"));
        assertEquals(List.of("catalog"), mysql.get("namespaceLevels"));
        assertEquals(List.of("schema"), postgresql.get("namespaceLevels"));
        assertEquals(List.of("catalog", "schema"), sqlServer.get("namespaceLevels"));
        assertTrue(((List<?>) mysql.get("aliases")).contains("mariadb"));
        assertEquals(List.of("standard", "custom"), mysql.get("connectionModes"));
        assertTrue(((List<?>) mysql.get("dataTypes")).stream()
                .map(Map.class::cast)
                .anyMatch(type -> "VARCHAR".equals(type.get("type"))));
        Map<?, ?> genericCapabilities = (Map<?, ?>) generic.get("capabilities");
        Map<?, ?> mysqlCapabilities = (Map<?, ?>) mysql.get("capabilities");
        assertEquals(true, genericCapabilities.get("rawSql"));
        assertEquals(false, genericCapabilities.get("structuredQuery"));
        assertEquals(false, genericCapabilities.get("exportDatabase"));
        assertEquals(true, mysqlCapabilities.get("structuredQuery"));
        assertEquals(false, mysqlCapabilities.get("stableOffsetPagination"));

        Map<String, Object> dm = catalog.stream()
                .filter(item -> "dm".equals(item.get("type")))
                .findFirst()
                .orElseThrow();
        Map<String, Object> kingbase = catalog.stream()
                .filter(item -> "kingbasees".equals(item.get("type")))
                .findFirst()
                .orElseThrow();
        assertEquals(5236, dm.get("defaultPort"));
        assertEquals(Map.of("java", true, "php", false), dm.get("runtimeSupport"));
        assertEquals(false, ((Map<?, ?>) dm.get("capabilities")).get("createDatabase"));
        assertEquals(54321, kingbase.get("defaultPort"));
        assertEquals(Map.of("java", true, "php", false), kingbase.get("runtimeSupport"));
        assertEquals(false, ((Map<?, ?>) kingbase.get("capabilities")).get("createDatabase"));
    }

    @Test
    void reusesAnImmutableCatalogIncludingNestedMetadata() {
        SqlDialectRegistry registry = new SqlDialectRegistry();
        List<Map<String, Object>> catalog = registry.getDialectInfos();

        assertReadOnly(catalog);
        assertSame(catalog, registry.getDialectInfos());
    }

    private static void assertReadOnly(Object value) {
        if (value instanceof Map<?, ?> map) {
            assertThrows(UnsupportedOperationException.class, map::clear);
            map.values().forEach(SqlDialectRegistryTest::assertReadOnly);
        } else if (value instanceof List<?> list) {
            assertThrows(UnsupportedOperationException.class, list::clear);
            list.forEach(SqlDialectRegistryTest::assertReadOnly);
        }
    }
}
