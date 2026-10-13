package org.leo.core.agent.service;

import org.junit.jupiter.api.Test;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BasicInfoServiceTest {
    private static final long MB = 1048576L;

    @Test
    void expandsCompactAgentDiskSnapshotOnServer() {
        Map<String, Object> basic = map("FileSystemInfo", List.of(map("mount", "/data", "name", "disk0",
                "fsType", "xfs", "totalBytes", 100 * MB, "freeBytes", 25 * MB)));
        BasicInfoService.normalizeBasicInfoResponse(map("BasicInfo", basic));
        Map<?, ?> disk = (Map<?, ?>) ((List<?>) basic.get("FileSystemInfo")).get(0);
        assertEquals("/data", disk.get("Root"));
        assertEquals("xfs", disk.get("Type"));
        assertEquals(100L, disk.get("TotalSpaceMB"));
        assertEquals(75L, disk.get("UsedSpaceMB"));
        assertEquals(25L, disk.get("UsableSpaceMB"));
        assertEquals(75.0, disk.get("UsagePercent"));
    }

    @Test
    void derivesMemoryLoadAndUptimeEvenWithoutDisks() {
        Map<String, Object> hardware = map("TotalPhysicalMemoryBytes", 100 * MB, "FreePhysicalMemoryBytes", 25 * MB,
                "TotalSwapSpaceBytes", 4 * MB, "FreeSwapSpaceBytes", 3 * MB, "SystemLoadAverage", 1.25);
        Map<String, Object> runtime = map("TotalMemoryBytes", 100 * MB, "FreeMemoryBytes", 40 * MB,
                "MaxMemoryBytes", 200 * MB, "HeapUsedBytes", 25 * MB, "HeapMaxBytes", 100 * MB);
        Map<String, Object> os = map("UptimeMs", 183845000L), process = map("UptimeMs", 12345L);
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.GERMANY);
            BasicInfoService.normalizeBasicInfoResponse(map("BasicInfo", map("HardwareInfo", hardware,
                    "JavaRuntimeInfo", runtime, "OSInfo", os, "ProcessInfo", process)));
        } finally { Locale.setDefault(previous); }
        assertEquals(75L, hardware.get("UsedPhysicalMemoryMB"));
        assertEquals(75.0, hardware.get("PhysicalMemoryUsagePercent"));
        assertEquals(1L, hardware.get("UsedSwapSpaceMB"));
        assertEquals(25.0, hardware.get("SwapUsagePercent"));
        assertEquals("1.25", hardware.get("SystemLoadAverage"));
        assertEquals(60L, runtime.get("UsedMemoryMB"));
        assertEquals(200L, runtime.get("MaxMemoryMB"));
        assertEquals(60.0, runtime.get("MemoryUsagePercent"));
        assertEquals(25.0, runtime.get("HeapUsagePercent"));
        assertEquals("2d 03:04:05", os.get("SystemUptime"));
        assertEquals("0d 00:00:12", process.get("Uptime"));
        for (Map<String, Object> section : List.of(hardware, runtime, os, process)) {
            assertFalse(section.keySet().stream().anyMatch(key -> key.endsWith("Bytes") || key.equals("UptimeMs")));
        }
    }

    @Test
    void keepsUnavailableMetricsAbsentAndHandlesUnboundedHeap() {
        Map<String, Object> hardware = map("TotalPhysicalMemoryBytes", -1L, "FreePhysicalMemoryBytes", -1L,
                "TotalSwapSpaceBytes", 0L, "FreeSwapSpaceBytes", 0L, "SystemLoadAverage", -1.0);
        Map<String, Object> runtime = map("HeapUsedBytes", MB, "HeapMaxBytes", -1L);
        Map<String, Object> os = map("UptimeMs", -1L);
        BasicInfoService.normalizeBasicInfoResponse(map("BasicInfo", map("HardwareInfo", hardware,
                "JavaRuntimeInfo", runtime, "OSInfo", os)));
        assertFalse(hardware.containsKey("TotalPhysicalMemoryMB"));
        assertFalse(hardware.containsKey("TotalSwapSpaceMB"));
        assertEquals("N/A", hardware.get("SystemLoadAverage"));
        assertEquals(0L, runtime.get("HeapMaxMB"));
        assertEquals(0.0, runtime.get("HeapUsagePercent"));
        assertEquals("0d 00:00:00", os.get("SystemUptime"));
    }

    private static Map<String, Object> map(Object... values) {
        Map<String, Object> result = new HashMap<>();
        for (int i = 0; i < values.length; i += 2) result.put((String) values[i], values[i + 1]);
        return result;
    }
}
