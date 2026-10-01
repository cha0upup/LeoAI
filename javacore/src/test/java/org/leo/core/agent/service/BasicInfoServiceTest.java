package org.leo.core.agent.service;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BasicInfoServiceTest {

    @Test
    void expandsCompactAgentDiskSnapshotOnServer() {
        Map<String, Object> compact = new HashMap<String, Object>();
        compact.put("mount", "/data");
        compact.put("name", "disk0");
        compact.put("fsType", "xfs");
        compact.put("totalBytes", Long.valueOf(100L * 1024L * 1024L));
        compact.put("freeBytes", Long.valueOf(25L * 1024L * 1024L));
        List<Map<String, Object>> fileSystems = new ArrayList<Map<String, Object>>();
        fileSystems.add(compact);
        Map<String, Object> basic = new HashMap<String, Object>();
        basic.put("FileSystemInfo", fileSystems);
        Map<String, Object> response = new HashMap<String, Object>();
        response.put("BasicInfo", basic);

        BasicInfoService.normalizeBasicInfoResponse(response);

        Map<?, ?> disk = (Map<?, ?>) ((List<?>) basic.get("FileSystemInfo")).get(0);
        assertEquals("/data", disk.get("Root"));
        assertEquals("xfs", disk.get("Type"));
        assertEquals(100L, ((Number) disk.get("TotalSpaceMB")).longValue());
        assertEquals(75L, ((Number) disk.get("UsedSpaceMB")).longValue());
        assertEquals(25L, ((Number) disk.get("UsableSpaceMB")).longValue());
        assertEquals(75.0, ((Number) disk.get("UsagePercent")).doubleValue());
    }
}
