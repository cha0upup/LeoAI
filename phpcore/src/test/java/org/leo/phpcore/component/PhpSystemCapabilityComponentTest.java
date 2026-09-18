package org.leo.phpcore.component;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import static org.leo.phpcore.PhpTestSupport.code;
import static org.leo.phpcore.PhpTestSupport.invokeComponent;
import static org.leo.phpcore.PhpTestSupport.phpAvailable;

class PhpSystemCapabilityComponentTest {

    @BeforeAll
    static void requirePhp() {
        Assumptions.assumeTrue(phpAvailable(), "PHP CLI is not installed");
    }

    @Test
    void listsAndFindsProcessesWithTheSharedResponseShape() throws Exception {
        Map<String, Object> listed = invokeComponent("ProcessComponent.php", "list", "array()");
        assertEquals(200, code(listed));
        List<?> processes = assertInstanceOf(List.class, listed.get("processes"));
        assertFalse(processes.isEmpty());
        Map<?, ?> process = assertInstanceOf(Map.class, processes.get(0));
        assertTrue(process.containsKey("pid"));
        assertTrue(process.containsKey("name"));

        Map<String, Object> found = invokeComponent("ProcessComponent.php", "find", "array('pid'=>getmypid())");
        assertEquals(200, code(found));
        assertTrue(((Number) found.get("total")).intValue() >= 1, found.toString());
    }

    @Test
    void collectsNetworkTopologySections() throws Exception {
        Map<String, Object> response = invokeComponent("NetworkInfoComponent.php", "collect", "array()");
        assertEquals(200, code(response));
        Map<?, ?> network = assertInstanceOf(Map.class, response.get("networkInfo"));
        for (String key : List.of("interfaces", "arp", "routes", "dnsConfig", "hosts", "os")) {
            assertTrue(network.containsKey(key), key);
        }
        assertFalse(assertInstanceOf(List.class, network.get("interfaces")).isEmpty());
    }
}
