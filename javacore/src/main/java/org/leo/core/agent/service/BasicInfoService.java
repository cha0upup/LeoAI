package org.leo.core.agent.service;

import org.leo.core.net.Communication;
import org.leo.core.net.layer.RequestLayer;
import org.leo.core.net.layer.ResponseLayer;

import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Locale;

public class BasicInfoService extends ComponentService {

    public BasicInfoService(Communication communication, List<RequestLayer> requestLayers, List<ResponseLayer> responseLayers) {
        super(communication, requestLayers, responseLayers);
    }

    public Map<String, Object> basicInfo() throws Exception {
        return normalizeBasicInfoResponse(invokeComponent("BasicInfoComponent", Map.of()));
    }

    static Map<String, Object> normalizeBasicInfoResponse(Map<String, Object> response) {
        if (response == null) return null;
        Object basicValue = response.get("BasicInfo");
        if (!(basicValue instanceof Map)) return response;
        Map<String, Object> basic = (Map<String, Object>) basicValue;
        Map<String, Object> hardware = section(basic, "HardwareInfo");
        if (hardware != null) {
            memory(hardware, "PhysicalMemory", "PhysicalMemoryUsagePercent");
            memory(hardware, "SwapSpace", "SwapUsagePercent");
            Object load = hardware.get("SystemLoadAverage");
            if (load instanceof Number number) hardware.put("SystemLoadAverage",
                    number.doubleValue() < 0 ? "N/A" : String.format(Locale.ROOT, "%.2f", number.doubleValue()));
        }
        uptime(section(basic, "OSInfo"), "SystemUptime");
        uptime(section(basic, "ProcessInfo"), "Uptime");
        Map<String, Object> runtime = section(basic, "JavaRuntimeInfo");
        if (runtime != null) {
            memory(runtime, "Memory", "MemoryUsagePercent");
            megabytes(runtime, "MaxMemory");
            long heapUsed = number(runtime.get("HeapUsedBytes"), 0L);
            long heapMax = number(runtime.get("HeapMaxBytes"), 0L);
            if (runtime.containsKey("HeapUsedBytes")) runtime.put("HeapUsagePercent", percent(heapUsed, heapMax));
            megabytes(runtime, "HeapUsed");
            megabytes(runtime, "HeapMax");
        }
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
            disk.put("UsagePercent", percent(used, total));
            normalized.add(disk);
        }
        basic.put("FileSystemInfo", normalized);
        return response;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> section(Map<String, Object> basic, String name) {
        Object value = basic.get(name);
        return value instanceof Map<?, ?> ? (Map<String, Object>) value : null;
    }

    private static void memory(Map<String, Object> info, String name, String usageKey) {
        long total = number(info.remove("Total" + name + "Bytes"), -1L);
        long free = number(info.remove("Free" + name + "Bytes"), -1L);
        if (total <= 0L || free < 0L) return;
        long used = Math.max(0L, total - free);
        info.put("Total" + name + "MB", total / 1048576L);
        info.put("Free" + name + "MB", free / 1048576L);
        info.put("Used" + name + "MB", used / 1048576L);
        info.put(usageKey, percent(used, total));
    }

    private static void megabytes(Map<String, Object> info, String name) {
        if (info.containsKey(name + "Bytes")) info.put(name + "MB", number(info.remove(name + "Bytes"), 0L) / 1048576L);
    }

    private static void uptime(Map<String, Object> info, String key) {
        if (info == null || !info.containsKey("UptimeMs")) return;
        long seconds = Math.max(0L, number(info.remove("UptimeMs"), 0L) / 1000L);
        info.put(key, String.format(Locale.ROOT, "%dd %02d:%02d:%02d",
                seconds / 86400L, seconds / 3600L % 24L, seconds / 60L % 60L, seconds % 60L));
    }

    private static double percent(long used, long total) {
        return total > 0L ? Math.round((double) used / total * 1000.0) / 10.0 : 0.0;
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
