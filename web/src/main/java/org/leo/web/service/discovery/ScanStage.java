package org.leo.web.service.discovery;

import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;

/** User-selectable stages, always executed in dependency order. */
public enum ScanStage {
    REACHABILITY, PORT_SCAN, SERVICE_PROBE;

    public static List<ScanStage> resolve(Object value) {
        if (value == null) return List.of(values());
        if (!(value instanceof Collection<?> stages) || stages.isEmpty()) {
            throw new IllegalArgumentException("请至少选择一个扫描阶段");
        }
        EnumSet<ScanStage> selected = EnumSet.noneOf(ScanStage.class);
        for (Object stage : stages) {
            try {
                selected.add(valueOf(String.valueOf(stage).trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException error) {
                throw new IllegalArgumentException("不支持的扫描阶段: " + stage);
            }
        }
        if (selected.contains(SERVICE_PROBE) && !selected.contains(PORT_SCAN)) {
            throw new IllegalArgumentException("服务识别需要同时启用端口扫描");
        }
        return Arrays.stream(values()).filter(selected::contains).toList();
    }
}
