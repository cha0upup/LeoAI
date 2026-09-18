package org.leo.phpcore.component;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import static org.leo.phpcore.PhpTestSupport.invokeComponent;
import static org.leo.phpcore.PhpTestSupport.phpAvailable;

class PhpBasicInfoComponentTest {

    @Test
    void collectsResourceFileSystemAndNetworkData() throws Exception {
        Assumptions.assumeTrue(phpAvailable(), "PHP CLI is not installed");
        Map<String, Object> response = invokeComponent("BasicInfoComponent.php", "get", "array()");
        Map<?, ?> basicInfo = assertInstanceOf(Map.class, response.get("BasicInfo"));
        Map<?, ?> hardware = assertInstanceOf(Map.class, basicInfo.get("HardwareInfo"));
        assertTrue(number(hardware.get("TotalPhysicalMemoryMB")) > 0, hardware.toString());
        assertTrue(hardware.containsKey("TotalSwapSpaceMB"), hardware.toString());

        Map<?, ?> environment = assertInstanceOf(Map.class, basicInfo.get("EnvironmentInfo"));
        assertFalse(environment.isEmpty());

        List<?> fileSystems = assertInstanceOf(List.class, basicInfo.get("FileSystemInfo"));
        assertFalse(fileSystems.isEmpty());
        Map<?, ?> fileSystem = assertInstanceOf(Map.class, fileSystems.get(0));
        assertTrue(fileSystem.containsKey("Root"));
        assertTrue(number(fileSystem.get("TotalSpaceMB")) >= 0);

        List<?> networks = assertInstanceOf(List.class, basicInfo.get("NetworkInfo"));
        assertFalse(networks.isEmpty());
        Map<?, ?> network = assertInstanceOf(Map.class, networks.get(0));
        assertTrue(network.containsKey("IsUp"));
        assertInstanceOf(List.class, network.get("IPAddresses"));
    }

    private double number(Object value) {
        return value instanceof Number ? ((Number) value).doubleValue() : -1;
    }
}
