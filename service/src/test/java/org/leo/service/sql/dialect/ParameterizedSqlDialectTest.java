package org.leo.service.sql.dialect;

import org.junit.jupiter.api.Test;
import org.leo.core.agent.database.SqlCommand;
import org.leo.service.sql.SqlObjectRef;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ParameterizedSqlDialectTest {

    private final PostgreSqlDialect dialect = new PostgreSqlDialect();
    private final SqlObjectRef table = SqlObjectRef.table(null, "sales", "orders");

    @Test
    void queryFiltersUsePlaceholdersAndPreserveParameterOrder() {
        SqlCommand command = dialect.buildQueryTableCommand(
                table, 2, 25, List.of("id", "customer"),
                List.of(Map.of("field", "id", "direction", "DESC")),
                List.of(
                        Map.of("field", "customer", "operator", "like", "value", "%O'Reilly%"),
                        Map.of("field", "status", "operator", "in", "value", List.of("new", "paid"))));

        assertTrue(command.sql().contains("\"customer\" LIKE ?"));
        assertTrue(command.sql().contains("\"status\" IN (?, ?)"));
        assertTrue(command.sql().endsWith("ORDER BY \"id\" DESC LIMIT 25 OFFSET 25"));
        assertFalse(command.sql().contains("O'Reilly"));
        assertEquals(List.of("%O'Reilly%", "new", "paid"), command.parameters());
    }

    @Test
    void writesBindValuesAndUseNullAwarePrimaryKeys() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", "O'Reilly");
        row.put("note", null);
        SqlCommand insert = dialect.buildInsertCommand(table, row);
        assertEquals("INSERT INTO \"sales\".\"orders\" (\"name\", \"note\") VALUES (?, ?)", insert.sql());
        assertEquals(2, insert.parameters().size());
        assertEquals("O'Reilly", insert.parameters().get(0));
        assertEquals(null, insert.parameters().get(1));

        SqlCommand update = dialect.buildUpdateCommand(
                table,
                Map.of("type", "pk", "values", Map.of("external_id", "A-7")),
                Map.of("name", "changed"));
        assertEquals("UPDATE \"sales\".\"orders\" SET \"name\" = ? WHERE \"external_id\" = ?", update.sql());
        assertEquals(List.of("changed", "A-7"), update.parameters());

        Map<String, Object> nullKey = new LinkedHashMap<>();
        nullKey.put("nullable_id", null);
        SqlCommand delete = dialect.buildDeleteCommand(
                table, Map.of("type", "pk", "values", nullKey));
        assertEquals("DELETE FROM \"sales\".\"orders\" WHERE \"nullable_id\" IS NULL", delete.sql());
        assertTrue(delete.parameters().isEmpty());
    }

    @Test
    void emptyInHasExplicitSemanticsAndUnknownOperatorsAreRejected() {
        SqlCommand emptyIn = dialect.buildQueryTableCommand(
                table, 1, 20, List.of(), List.of(),
                List.of(Map.of("field", "id", "operator", "in", "value", List.of())));
        assertTrue(emptyIn.sql().contains("WHERE 1 = 0"));
        assertTrue(emptyIn.parameters().isEmpty());

        assertThrows(IllegalArgumentException.class, () -> dialect.buildCountCommand(
                table, List.of(Map.of("field", "id", "operator", "contains", "value", "1"))));

        List<Map<String, Object>> excessiveFilters = java.util.stream.IntStream.range(0, 257)
                .mapToObj(index -> Map.<String, Object>of(
                        "field", "id", "operator", "eq", "value", index))
                .toList();
        assertThrows(IllegalArgumentException.class,
                () -> dialect.buildCountCommand(table, excessiveFilters));
    }
}
