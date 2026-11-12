package org.leo.service.sql;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DatabaseConnectionViewPolicyTest {
    @ParameterizedTest
    @ValueSource(strings = {"en", "tr"})
    void secretClassificationAndEditRestorationAreIndependentOfServerLocale(String language) {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag(language));
            var stored = Map.<String, Object>of("API_KEY", "api-secret", "PRIVATE_KEY", "key-secret",
                    "options", Map.of("ACCESS_KEY", "access-secret", "region", "cn"), "host", "db.internal");
            var view = DatabaseConnectionViewPolicy.publicValues(stored);
            assertFalse(view.containsKey("API_KEY"));
            assertFalse(view.containsKey("PRIVATE_KEY"));
            assertEquals(Map.of("region", "cn"), view.get("options"));
            assertEquals(stored, DatabaseConnectionViewPolicy.restoreProtectedValues(stored, view));
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void nestedPublicContainersHideSecretsWithoutMutatingTheStoredConfiguration() {
        var nested = new LinkedHashMap<String, Object>(Map.of("token", "hidden", "label", "original"));
        var stored = new LinkedHashMap<String, Object>(Map.of("host", "db.internal", "options", nested,
                "items", List.of(nested), "jdbcUrl", "jdbc:vendor://app:secret@db.internal/db?password=query-secret&charset=UTF-8"));
        var view = DatabaseConnectionViewPolicy.publicValues(stored);
        assertEquals("jdbc:vendor://app:***@db.internal/db?password=***&charset=UTF-8", view.get("jdbcUrl"));
        ((Map<String, Object>) view.get("options")).put("label", "changed");
        ((Map<String, Object>) ((List<?>) view.get("items")).get(0)).put("label", "changed-list");
        view.put("host", "changed");
        assertEquals("original", nested.get("label"));
        assertEquals("hidden", nested.get("token"));
        assertEquals("db.internal", stored.get("host"));
        assertFalse(((Map<?, ?>) view.get("options")).containsKey("token"));
    }

    @Test
    void unchangedMaskedUrlsAndNestedOmittedSecretsRoundTripThroughImmutableMaps() {
        var stored = Map.<String, Object>of("options", Map.of("apiToken", "stored-token", "region", "cn"),
                "runtimeOptions", Map.of("java", Map.of(
                        "jdbcUrl", "jdbc:vendor://app:url-secret@db.internal/db?token=query-secret",
                        "connectionProperties", Map.of("privateKey", "stored-key", "charset", "UTF-8"))));
        var view = DatabaseConnectionViewPolicy.publicValues(stored);
        var restored = DatabaseConnectionViewPolicy.restoreProtectedValues(stored, view);
        assertEquals(stored, restored);
        assertFalse(view.toString().contains("stored-token"));
        assertFalse(view.toString().contains("stored-key"));
        assertFalse(view.toString().contains("url-secret"));
        assertFalse(view.toString().contains("query-secret"));
        assertNotSame(view, restored);
    }

    @Test
    void explicitClearsReplacementsAndChangedTargetsNeverRecoverOldCredentials() {
        var stored = Map.<String, Object>of("apiToken", "old-token", "password", "old-password",
                "jdbcUrl", "jdbc:vendor://app:old-secret@old-host/db?token=old-token",
                "options", Map.of("privateKey", "old-key", "region", "cn"));
        var patch = new LinkedHashMap<String, Object>();
        patch.put("apiToken", null);
        patch.put("password", "");
        patch.put("jdbcUrl", "jdbc:vendor://app:***@new-host/db?token=***");
        patch.put("options", Map.of("privateKey", "new-key", "region", "eu"));
        var restored = DatabaseConnectionViewPolicy.restoreProtectedValues(stored, patch);
        assertTrue(restored.containsKey("apiToken"));
        assertNull(restored.get("apiToken"));
        assertEquals("", restored.get("password"));
        assertEquals(patch.get("jdbcUrl"), restored.get("jdbcUrl"));
        assertEquals(patch.get("options"), restored.get("options"));
        assertFalse(restored.toString().contains("old-secret"));
    }

    @Test
    void creatingAProfileRetainsTheSuppliedValuesWithoutInventingStoredCredentials() {
        var incoming = Map.<String, Object>of("username", "app", "password", "new-password",
                "runtimeOptions", Map.of("java", Map.of("jdbcUrl", "jdbc:vendor://host/db?token=***")));
        var restored = DatabaseConnectionViewPolicy.restoreProtectedValues(Map.of(), incoming);
        assertEquals(incoming, restored);
        assertNotSame(incoming, restored);
    }
}
