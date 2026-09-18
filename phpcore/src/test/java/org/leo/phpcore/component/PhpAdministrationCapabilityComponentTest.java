package org.leo.phpcore.component;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import static org.leo.phpcore.PhpTestSupport.code;
import static org.leo.phpcore.PhpTestSupport.invokeComponent;
import static org.leo.phpcore.PhpTestSupport.phpAvailable;
import static org.leo.phpcore.PhpTestSupport.phpString;

class PhpAdministrationCapabilityComponentTest {

    @BeforeAll
    static void requirePhp() {
        Assumptions.assumeTrue(phpAvailable(), "PHP CLI is not installed");
    }

    @Test
    void queriesAndAggregatesFileEventLogs(@TempDir Path directory) throws Exception {
        Path log = directory.resolve("access.log");
        Files.writeString(log,
                "10.0.0.1 - - [18/Jul/2026:10:00:00 +0800] \"GET /ok HTTP/1.1\" 200 12 \"-\" \"curl\"\n"
                        + "10.0.0.2 - - [18/Jul/2026:10:01:00 +0800] \"POST /bad HTTP/1.1\" 503 9 \"-\" \"agent\"\n",
                StandardCharsets.UTF_8);
        String source = phpString(log.toString());

        Map<String, Object> queried = invokeComponent("EventLogComponent.php", "query",
                "array('source'=>" + source + ",'maxEntries'=>20,'minStatus'=>500)");
        assertEquals(200, code(queried));
        List<?> entries = assertInstanceOf(List.class, queried.get("entries"));
        assertEquals(1, entries.size());
        assertEquals(503, ((Number) assertInstanceOf(Map.class, entries.get(0)).get("status")).intValue());
        assertTrue(assertInstanceOf(Map.class, queried.get("meta")).containsKey("endByte"));

        Map<String, Object> metadata = invokeComponent("EventLogComponent.php", "meta",
                "array('source'=>" + source + ",'lines'=>2,'fromTail'=>true)");
        assertEquals(200, code(metadata));
        assertEquals(2, assertInstanceOf(List.class, metadata.get("lines")).size());

        Map<String, Object> aggregate = invokeComponent("EventLogComponent.php", "aggregate",
                "array('source'=>" + source + ",'groupBy'=>'status','topN'=>5,'maxScan'=>100)");
        assertEquals(200, code(aggregate));
        assertEquals(2, ((Number) aggregate.get("unique")).intValue());
        assertInstanceOf(List.class, aggregate.get("groups"));
    }

    @Test
    void listsAccountsAndReportsCurrentIdentity() throws Exception {
        Map<String, Object> users = invokeComponent("UserAccountComponent.php", "listUsers", "array()");
        assertEquals(200, code(users));
        Map<?, ?> userData = assertInstanceOf(Map.class, users.get("data"));
        assertInstanceOf(List.class, userData.get("users"));

        Map<String, Object> groups = invokeComponent("UserAccountComponent.php", "listGroups", "array()");
        assertEquals(200, code(groups));
        assertInstanceOf(List.class, assertInstanceOf(Map.class, groups.get("data")).get("groups"));

        Map<String, Object> identity = invokeComponent("UserAccountComponent.php", "whoami", "array()");
        assertEquals(200, code(identity));
        assertInstanceOf(Map.class, assertInstanceOf(Map.class, identity.get("data")).get("detail"));
    }

    @Test
    void inspectsFirewallAndKeepsRegistryResponsePortable() throws Exception {
        Map<String, Object> firewall = invokeComponent("FirewallComponent.php", "status", "array()");
        assertEquals(200, code(firewall));
        Map<?, ?> detail = assertInstanceOf(Map.class,
                assertInstanceOf(Map.class, firewall.get("data")).get("detail"));
        assertTrue(detail.containsKey("tool"));

        Map<String, Object> registry = invokeComponent("RegistryComponent.php", "query",
                "array('keyPath'=>'HKCU\\\\Software','recursive'=>false)");
        assertTrue(code(registry) == 200 || code(registry) == 400);
    }
}
