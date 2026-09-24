package org.leo.core.puppet.service;

import org.leo.core.net.Communication;
import org.leo.core.net.layer.RequestLayer;
import org.leo.core.net.layer.ResponseLayer;

import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class BasicInfoService extends ComponentService {

    public BasicInfoService(Communication communication, List<RequestLayer> requestLayers, List<ResponseLayer> responseLayers) {
        super(communication, requestLayers, responseLayers);
    }

    public Map<String, Object> basicInfo() throws Exception {
        HashMap<String, Object> params = new HashMap<String, Object>();
        return normalizeBasicInfoResponse(invokeComponent("BasicInfoComponent", params));
    }

    static Map<String, Object> normalizeBasicInfoResponse(Map<String, Object> response) {
        if (response == null) return null;
        Object basicValue = response.get("BasicInfo");
        if (!(basicValue instanceof Map)) return response;
        Map<String, Object> basic = (Map<String, Object>) basicValue;
        Object fileSystemsValue = basic.get("FileSystemInfo");
        if (!(fileSystemsValue instanceof List)) return response;

        List<Map<String, Object>> normalized = new ArrayList<Map<String, Object>>();
        for (Object value : (List<?>) fileSystemsValue) {
            if (!(value instanceof Map)) continue;
            Map<?, ?> source = (Map<?, ?>) value;
            long total = number(source.get("totalBytes"), 0L);
            long free = number(source.get("freeBytes"), 0L);
            long used = Math.max(0L, total - free);
            Map<String, Object> disk = new HashMap<String, Object>();
            disk.put("Name", text(source.get("name"), text(source.get("mount"), "-")));
            disk.put("Root", text(source.get("mount"), "-"));
            disk.put("Type", text(source.get("fsType"), "File System"));
            disk.put("TotalSpaceMB", Long.valueOf(total / 1024L / 1024L));
            disk.put("UsableSpaceMB", Long.valueOf(free / 1024L / 1024L));
            disk.put("UsedSpaceMB", Long.valueOf(used / 1024L / 1024L));
            disk.put("UsagePercent", Double.valueOf(total > 0L
                    ? Math.round((double) used / total * 1000.0) / 10.0 : 0.0));
            normalized.add(disk);
        }
        basic.put("FileSystemInfo", normalized);
        return response;
    }

    private static long number(Object value, long fallback) {
        if (value instanceof Number) return ((Number) value).longValue();
        try { return value == null ? fallback : Long.parseLong(String.valueOf(value)); }
        catch (RuntimeException ignored) { return fallback; }
    }

    private static String text(Object value, String fallback) {
        return value == null || String.valueOf(value).isEmpty() ? fallback : String.valueOf(value);
    }
}
