package org.leo.service.audit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.leo.core.util.json.JsonUtil;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AuditParameterSanitizerTest {

    @ParameterizedTest
    @ValueSource(strings = {"password", "pwd", "passwd", "secret", "token", "key", "credential",
            "content", "data", "filedata", "base64", "access_token", "api_key", " PASSWORD "})
    void masksSensitiveFields(String field) {
        assertEquals("***", AuditParameterSanitizer.sanitize(Map.of(field, "secret-value")).get(field));
    }

    @Test
    void redactsNestedParametersWithoutChangingTheCallersData() {
        Map<String, Object> connection = new LinkedHashMap<>();
        connection.put("password", "original-password");
        connection.put("port", 3306);
        connection.put("enabled", true);
        connection.put("optional", null);
        Map<String, Object> source = Map.of("connections", List.of(connection, connection));

        Map<String, Object> sanitized = AuditParameterSanitizer.sanitize(source);
        Map<?, ?> nested = (Map<?, ?>) ((List<?>) sanitized.get("connections")).get(0);
        assertEquals(nested, ((List<?>) sanitized.get("connections")).get(1));
        assertEquals("***", nested.get("password"));
        assertEquals(3306, nested.get("port"));
        assertEquals(true, nested.get("enabled"));
        assertTrue(nested.containsKey("optional"));
        assertNull(nested.get("optional"));
        assertEquals("original-password", connection.get("password"));
    }

    @Test
    void redactsInlineCredentialsAndPreservesUsefulUrlFields() {
        String url = "jdbc:mysql://alice:authority-secret@localhost/db?password=query-secret&port=3306;api_key=key-secret";
        assertEquals("jdbc:mysql://alice:***@localhost/db?password=***&port=3306;api_key=***",
                AuditParameterSanitizer.sanitize(Map.of("url", url)).get("url"));
        assertEquals("token=***", AuditParameterSanitizer.sanitize(Map.of("value", "token=secret")).get("value"));
    }

    @Test
    void handlesNullFieldsAndCyclicParameters() {
        assertTrue(AuditParameterSanitizer.sanitize(null).isEmpty());
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("password", null);
        params.put(null, "ordinary value");
        params.put("self", params);
        params.put("again", params);
        Map<String, Object> sanitized = AuditParameterSanitizer.sanitize(params);
        assertNull(sanitized.get("password"));
        assertEquals("ordinary value", sanitized.get(null));
        assertEquals("[truncated]", sanitized.get("self"));
        assertEquals("[truncated]", sanitized.get("again"));
        assertTrue(JsonUtil.toJsonString(sanitized).contains("[truncated]"));
    }

    @Test
    @ResourceLock("java.util.Locale.default")
    void redactionDoesNotDependOnTheServerLocale() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertEquals("***", AuditParameterSanitizer.sanitize(Map.of("FILEDATA", "sensitive")).get("FILEDATA"));
        } finally {
            Locale.setDefault(previous);
        }
    }
}
