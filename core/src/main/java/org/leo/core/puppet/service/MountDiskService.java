package org.leo.core.puppet.service;

import org.leo.core.net.Communication;
import org.leo.core.net.layer.RequestLayer;
import org.leo.core.net.layer.ResponseLayer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 磁盘与卷枚举服务（服务器端解析版）。
 * 自动适配 Windows / macOS / Linux。
 */
public class MountDiskService extends ComponentService {

    public MountDiskService(Communication communication,
                            List<RequestLayer> requestLayers,
                            List<ResponseLayer> responseLayers) {
        super(communication, requestLayers, responseLayers);
    }

    public Map<String, Object> list() throws Exception {
        String osOutput = execFast("uname -s 2>/dev/null || echo Windows");
        boolean isWindows = isWindows(osOutput);
        boolean isMac = !isWindows && osOutput.toLowerCase().contains("darwin");
        String osType = isWindows ? "windows" : (isMac ? "macos" : "linux");

        List<Map<String, Object>> disks = new ArrayList<>();

        if (isWindows) {
            collectWindows(disks);
        } else {
            collectUnix(isMac, disks);
        }

        Map<String, Object> summary = buildSummary(disks);

        Map<String, Object> data = new HashMap<>();
        data.put("os", osType);
        data.put("total", disks.size());
        data.put("disks", disks);
        data.put("summary", summary);

        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("data", data);
        return result;
    }

    // ── Windows ──────────────────────────────────────────────────────────────

    private void collectWindows(List<Map<String, Object>> disks) throws Exception {
        String out = execFast("cmd /c \"chcp 65001 > nul & wmic logicaldisk get Caption,DriveType,FileSystem,FreeSpace,Size,VolumeName /format:csv\"");
        if (out != null && !out.trim().isEmpty()) {
            parseWmicCsv(out, disks);
            if (!disks.isEmpty()) return;
        }

        out = execFast("cmd /c \"chcp 65001 > nul & powershell -Command \"\"Get-PSDrive -PSProvider FileSystem | Select-Object Name,Used,Free,Root | Format-List\"\"\"");
        if (out != null && !out.trim().isEmpty()) {
            parsePowerShellDrive(out, disks);
            if (!disks.isEmpty()) return;
        }

        out = execFast("cmd /c \"chcp 65001 > nul & fsutil fsinfo drives\"");
        if (out != null && !out.trim().isEmpty()) {
            parseFsutil(out, disks);
        }
    }

    private void parseWmicCsv(String output, List<Map<String, Object>> disks) {
        for (String line : lines(output)) {
            if (line.trim().isEmpty()) continue;
            String[] parts = line.split(",");
            if (parts.length < 6) continue;
            if ("Caption".equals(parts[1].trim())) continue;

            long freeSpace = parseLong(parts[4].trim(), 0);
            long totalSize = parseLong(parts[5].trim(), 0);
            int driveType = parseInt(parts[2].trim(), -1);

            Map<String, Object> disk = new HashMap<>();
            disk.put("mount", parts[1].trim());
            disk.put("driveType", driveTypeLabel(driveType));
            disk.put("fsType", parts[3].trim());
            disk.put("totalBytes", totalSize);
            disk.put("freeBytes", freeSpace);
            disk.put("usedBytes", totalSize - freeSpace);
            disk.put("total", humanSize(totalSize));
            disk.put("free", humanSize(freeSpace));
            disk.put("used", humanSize(totalSize - freeSpace));
            disk.put("usedPercent", totalSize > 0 ? Math.round((double)(totalSize - freeSpace) / totalSize * 1000) / 10.0 : 0.0);
            if (parts.length >= 7) disk.put("volumeName", parts[6].trim());
            disks.add(disk);
        }
    }

    private String driveTypeLabel(int type) {
        switch (type) {
            case 2: return "Removable";
            case 3: return "Local";
            case 4: return "Network";
            case 5: return "CD-ROM";
            case 6: return "RAM";
            default: return type == 0 ? "Unknown" : String.valueOf(type);
        }
    }

    private void parsePowerShellDrive(String output, List<Map<String, Object>> disks) {
        Map<String, Object> current = null;
        for (String line : lines(output)) {
            if (line.trim().isEmpty()) {
                if (current != null && current.containsKey("mount")) {
                    computePsTotals(current);
                    disks.add(current);
                }
                current = null;
                continue;
            }
            int sep = line.indexOf(':');
            if (sep <= 0) continue;
            String key = line.substring(0, sep).trim();
            String val = line.substring(sep + 1).trim();
            if (current == null) current = new HashMap<>();
            if ("Root".equals(key)) {
                current.put("mount", val);
            } else if ("Used".equals(key)) {
                long used = parseLong(val, 0);
                current.put("usedBytes", used);
                current.put("used", humanSize(used));
            } else if ("Free".equals(key)) {
                long free = parseLong(val, 0);
                current.put("freeBytes", free);
                current.put("free", humanSize(free));
            }
        }
        if (current != null && current.containsKey("mount")) {
            computePsTotals(current);
            disks.add(current);
        }
    }

    private void computePsTotals(Map<String, Object> disk) {
        long used = disk.containsKey("usedBytes") ? ((Number) disk.get("usedBytes")).longValue() : 0;
        long free = disk.containsKey("freeBytes") ? ((Number) disk.get("freeBytes")).longValue() : 0;
        long total = used + free;
        disk.put("totalBytes", total);
        disk.put("total", humanSize(total));
        disk.put("usedPercent", total > 0 ? Math.round((double) used / total * 1000) / 10.0 : 0.0);
    }

    private void parseFsutil(String drives, List<Map<String, Object>> disks) throws Exception {
        for (String part : drives.split("\\s+")) {
            part = part.trim();
            if (part.length() >= 2 && part.charAt(1) == ':') {
                String letter = part.substring(0, 2);
                String info = execFast("cmd /c \"chcp 65001 > nul & fsutil volume diskfree " + letter + "\"");
                if (info != null && !info.trim().isEmpty()) {
                    Map<String, Object> disk = new HashMap<>();
                    disk.put("mount", letter);
                    parseFsutilDiskFree(info, disk);
                    disks.add(disk);
                }
            }
        }
    }

    private void parseFsutilDiskFree(String info, Map<String, Object> disk) {
        long totalFree = 0, totalBytes = 0;
        for (String line : lines(info)) {
            int colon = line.lastIndexOf(':');
            if (colon < 0) continue;
            long num = parseLong(line.substring(colon + 1).trim(), -1);
            if (num < 0) continue;
            String lower = line.toLowerCase();
            if (lower.contains("total free") || lower.contains("可用")) totalFree = num;
            else if (lower.contains("total bytes") || lower.contains("总字节")) totalBytes = num;
        }
        long used = totalBytes - totalFree;
        disk.put("totalBytes", totalBytes);
        disk.put("freeBytes", totalFree);
        disk.put("usedBytes", used);
        disk.put("total", humanSize(totalBytes));
        disk.put("free", humanSize(totalFree));
        disk.put("used", humanSize(used));
        disk.put("usedPercent", totalBytes > 0 ? Math.round((double) used / totalBytes * 1000) / 10.0 : 0.0);
    }

    // ── Unix ─────────────────────────────────────────────────────────────────

    private void collectUnix(boolean isMac, List<Map<String, Object>> disks) throws Exception {
        String dfOut = execFast("df -hP 2>/dev/null");
        if (dfOut != null && !dfOut.trim().isEmpty()) parseDf(dfOut, disks);

        String mountOut = execFast("mount 2>/dev/null");
        if (mountOut != null && !mountOut.trim().isEmpty()) enrichFromMount(mountOut, disks);

        String dfBytesOut = execFast("df -P 2>/dev/null");
        if (dfBytesOut != null && !dfBytesOut.trim().isEmpty()) enrichWithExactBytes(dfBytesOut, disks);

        if (!isMac) {
            String lsblkOut = execFast("lsblk -o NAME,TYPE,SIZE,FSTYPE,MOUNTPOINT -n 2>/dev/null");
            if (lsblkOut != null && !lsblkOut.trim().isEmpty()) enrichFromLsblk(lsblkOut, disks);
        }
    }

    private void parseDf(String output, List<Map<String, Object>> disks) {
        String[] lineArr = lines(output);
        for (int i = 1; i < lineArr.length; i++) {
            String line = lineArr[i].trim();
            if (line.isEmpty()) continue;
            String[] parts = line.split("\\s+");
            if (parts.length < 6) continue;
            if (isVirtualFs(parts[0], parts[5])) continue;

            Map<String, Object> disk = new HashMap<>();
            disk.put("device", parts[0]);
            disk.put("total", parts[1]);
            disk.put("used", parts[2]);
            disk.put("free", parts[3]);
            disk.put("usedPercent", parseDouble(parts[4].replace("%", ""), 0.0));
            disk.put("mount", parts[5]);
            disks.add(disk);
        }
    }

    private boolean isVirtualFs(String fs, String mp) {
        if ("tmpfs".equals(fs) || "devtmpfs".equals(fs) || "udev".equals(fs) ||
                "sysfs".equals(fs) || "proc".equals(fs) || "cgroup".equals(fs) ||
                "cgroup2".equals(fs) || "devfs".equals(fs) || "none".equals(fs) ||
                "overlay".equals(fs) || "shm".equals(fs) || "run".equals(fs)) return true;
        if (mp.startsWith("/sys") || mp.startsWith("/proc") ||
                mp.startsWith("/dev/shm") || "/dev".equals(mp)) return true;
        if (fs.startsWith("snap/")) return true;
        return false;
    }

    private void enrichFromMount(String mountOutput, List<Map<String, Object>> disks) {
        Map<String, String[]> mountMap = new HashMap<>();
        for (String line : lines(mountOutput)) {
            int onIdx = line.indexOf(" on ");
            int typeIdx = line.indexOf(" type ");
            if (onIdx < 0 || typeIdx < 0) continue;
            String mp = line.substring(onIdx + 4, typeIdx).trim();
            String rest = line.substring(typeIdx + 6).trim();
            int space = rest.indexOf(' ');
            String fsType = space > 0 ? rest.substring(0, space) : rest;
            String opts = space > 0 ? rest.substring(space).trim() : "";
            if (opts.startsWith("(") && opts.endsWith(")")) opts = opts.substring(1, opts.length() - 1);
            mountMap.put(mp, new String[]{fsType, opts});
        }
        for (Map<String, Object> disk : disks) {
            String mp = (String) disk.get("mount");
            if (mp != null && mountMap.containsKey(mp)) {
                String[] info = mountMap.get(mp);
                if (!disk.containsKey("fsType")) disk.put("fsType", info[0]);
                disk.put("mountOptions", info[1]);
            }
        }
    }

    private void enrichWithExactBytes(String dfBytesOutput, List<Map<String, Object>> disks) {
        Map<String, long[]> byteMap = new HashMap<>();
        String[] lineArr = lines(dfBytesOutput);
        for (int i = 1; i < lineArr.length; i++) {
            String line = lineArr[i].trim();
            if (line.isEmpty()) continue;
            String[] parts = line.split("\\s+");
            if (parts.length < 6) continue;
            byteMap.put(parts[5], new long[]{
                parseLong(parts[1], 0) * 1024,
                parseLong(parts[2], 0) * 1024,
                parseLong(parts[3], 0) * 1024
            });
        }
        for (Map<String, Object> disk : disks) {
            String mp = (String) disk.get("mount");
            if (mp != null && byteMap.containsKey(mp)) {
                long[] b = byteMap.get(mp);
                disk.put("totalBytes", b[0]);
                disk.put("usedBytes", b[1]);
                disk.put("freeBytes", b[2]);
            }
        }
    }

    private void enrichFromLsblk(String lsblkOutput, List<Map<String, Object>> disks) {
        Map<String, String[]> lsblkMap = new HashMap<>();
        for (String line : lines(lsblkOutput)) {
            if (line.trim().isEmpty()) continue;
            String[] parts = line.split("\\s+");
            if (parts.length < 5) continue;
            String mp = parts[4];
            if (mp.isEmpty() || "-".equals(mp)) continue;
            lsblkMap.put(mp, new String[]{
                parts[0].replaceAll("[^a-zA-Z0-9/]", ""),
                parts[1],
                ("-".equals(parts[3]) || parts[3].isEmpty()) ? null : parts[3]
            });
        }
        for (Map<String, Object> disk : disks) {
            String mp = (String) disk.get("mount");
            if (mp != null && lsblkMap.containsKey(mp)) {
                String[] info = lsblkMap.get(mp);
                disk.put("blockDevice", info[0]);
                disk.put("deviceType", info[1]);
                if (info[2] != null && !disk.containsKey("fsType")) disk.put("fsType", info[2]);
            }
        }
    }

    // ── Summary ───────────────────────────────────────────────────────────────

    private Map<String, Object> buildSummary(List<Map<String, Object>> disks) {
        long totalAll = 0, usedAll = 0, freeAll = 0;
        Map<String, Integer> byFsType = new HashMap<>();
        for (Map<String, Object> disk : disks) {
            if (disk.containsKey("totalBytes")) totalAll += ((Number) disk.get("totalBytes")).longValue();
            if (disk.containsKey("usedBytes"))  usedAll  += ((Number) disk.get("usedBytes")).longValue();
            if (disk.containsKey("freeBytes"))  freeAll  += ((Number) disk.get("freeBytes")).longValue();
            String fs = (String) disk.get("fsType");
            if (fs != null && !fs.isEmpty()) {
                Integer cnt = byFsType.get(fs);
                byFsType.put(fs, cnt == null ? 1 : cnt + 1);
            }
        }
        Map<String, Object> summary = new HashMap<>();
        summary.put("totalDisks", disks.size());
        summary.put("totalBytes", totalAll);
        summary.put("usedBytes", usedAll);
        summary.put("freeBytes", freeAll);
        summary.put("total", humanSize(totalAll));
        summary.put("used", humanSize(usedAll));
        summary.put("free", humanSize(freeAll));
        summary.put("usedPercent", totalAll > 0 ? Math.round((double) usedAll / totalAll * 1000) / 10.0 : 0.0);
        summary.put("byFsType", byFsType);
        return summary;
    }

    // ── 辅助方法 ─────────────────────────────────────────────────────────────

    private boolean isWindows(String osOutput) {
        return osOutput == null || osOutput.trim().isEmpty() || osOutput.toLowerCase().contains("windows");
    }

    private String[] lines(String s) {
        if (s == null || s.isEmpty()) return new String[0];
        return s.split("\n");
    }

    private long parseLong(String s, long def) {
        try { return Long.parseLong(s.trim()); } catch (Exception e) { return def; }
    }

    private int parseInt(String s, int def) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return def; }
    }

    private double parseDouble(String s, double def) {
        try { return Double.parseDouble(s.trim()); } catch (Exception e) { return def; }
    }

    private String humanSize(long bytes) {
        if (bytes <= 0) return "0 B";
        String[] units = {"B", "KB", "MB", "GB", "TB", "PB"};
        int idx = 0;
        double val = bytes;
        while (val >= 1024 && idx < units.length - 1) { val /= 1024; idx++; }
        return idx == 0 ? bytes + " B" : (Math.round(val * 100) / 100.0) + " " + units[idx];
    }
}
