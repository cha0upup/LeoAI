package org.leo.core.puppet.service;

import org.leo.core.net.Communication;
import org.leo.core.net.layer.RequestLayer;
import org.leo.core.net.layer.ResponseLayer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 操作系统服务管理服务（服务器端解析版）。
 * 自动适配 Windows / macOS / Linux。
 */
public class ServiceManagerService extends ComponentService {

    public ServiceManagerService(Communication communication,
                                 List<RequestLayer> requestLayers,
                                 List<ResponseLayer> responseLayers) {
        super(communication, requestLayers, responseLayers);
    }

    // ── 公共接口 ───────────────────────────────────────────────────────────────

    public Map<String, Object> list() throws Exception {
        String osOutput = execFast("uname -s 2>/dev/null || echo Windows");
        boolean isWindows = isWindows(osOutput);
        boolean isMac = !isWindows && osOutput.toLowerCase().contains("darwin");

        List<Map<String, Object>> services;
        List<String> diagnostics = new ArrayList<>();

        if (isWindows) {
            services = listWindows(diagnostics);
        } else if (isMac) {
            services = listMacOS(diagnostics);
        } else {
            services = listLinux(diagnostics);
        }

        Map<String, Object> data = new HashMap<>();
        data.put("action", "list");
        data.put("os", isWindows ? "windows" : isMac ? "macos" : "linux");
        data.put("total", services.size());
        data.put("services", services);
        if (!diagnostics.isEmpty()) data.put("diagnostics", diagnostics);

        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("data", data);
        return result;
    }

    public Map<String, Object> query(String serviceName) throws Exception {
        if (serviceName == null || serviceName.trim().isEmpty()) {
            return error(400, "serviceName is required");
        }
        String osOutput = execFast("uname -s 2>/dev/null || echo Windows");
        boolean isWindows = isWindows(osOutput);
        boolean isMac = !isWindows && osOutput.toLowerCase().contains("darwin");

        if (isWindows) return queryWindows(serviceName);
        if (isMac)     return queryMacOS(serviceName);
        return queryLinux(serviceName);
    }

    public Map<String, Object> start(String serviceName) throws Exception {
        return control(serviceName, "start");
    }

    public Map<String, Object> stop(String serviceName) throws Exception {
        return control(serviceName, "stop");
    }

    public Map<String, Object> restart(String serviceName) throws Exception {
        return control(serviceName, "restart");
    }

    public Map<String, Object> enable(String serviceName) throws Exception {
        return autoStart(serviceName, true);
    }

    public Map<String, Object> disable(String serviceName) throws Exception {
        return autoStart(serviceName, false);
    }

    public Map<String, Object> create(String serviceName, String binPath,
                                      String displayName, String startType) throws Exception {
        if (serviceName == null || serviceName.trim().isEmpty()) return error(400, "serviceName is required");
        if (binPath == null || binPath.trim().isEmpty())         return error(400, "binPath is required");
        if (startType == null || startType.trim().isEmpty())     startType = "demand";

        String osOutput = execFast("uname -s 2>/dev/null || echo Windows");
        boolean isWindows = isWindows(osOutput);
        boolean isMac = !isWindows && osOutput.toLowerCase().contains("darwin");

        Map<String, Object> data = new HashMap<>();
        data.put("action", "create");
        data.put("serviceName", serviceName);
        data.put("binPath", binPath);
        data.put("os", isWindows ? "windows" : isMac ? "macos" : "linux");

        if (isWindows) {
            StringBuilder sb = new StringBuilder("sc create \"")
                    .append(escapeCmd(serviceName)).append("\"")
                    .append(" binPath= \"").append(escapeCmd(binPath)).append("\"")
                    .append(" start= ").append(startType);
            if (displayName != null && !displayName.isEmpty()) {
                sb.append(" DisplayName= \"").append(escapeCmd(displayName)).append("\"");
            }
            String output = execFast(winCmd(sb.toString()));
            data.put("output", output != null ? output.trim() : "");
        } else if (isMac) {
            data.put("msg", "macOS 服务创建需手动编写 launchd plist 文件");
            data.put("plistTemplate", buildLaunchdPlistTemplate(serviceName, binPath));
            data.put("plistPath", "/Library/LaunchDaemons/" + serviceName + ".plist");
            data.put("hint", "将 plist 内容写入上述路径后执行: sudo launchctl load /Library/LaunchDaemons/" + serviceName + ".plist");
        } else {
            data.put("msg", "Linux 服务创建需手动编写 systemd unit 文件");
            data.put("unitTemplate", buildSystemdUnitTemplate(serviceName, binPath));
            data.put("unitPath", "/etc/systemd/system/" + serviceName + ".service");
            data.put("hint", "将 unit 内容写入上述路径后执行: systemctl daemon-reload && systemctl enable " + serviceName);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("data", data);
        return result;
    }

    public Map<String, Object> delete(String serviceName) throws Exception {
        if (serviceName == null || serviceName.trim().isEmpty()) return error(400, "serviceName is required");

        String osOutput = execFast("uname -s 2>/dev/null || echo Windows");
        boolean isWindows = isWindows(osOutput);
        boolean isMac = !isWindows && osOutput.toLowerCase().contains("darwin");

        String output;
        if (isWindows) {
            execFast(winCmd("sc stop \"" + escapeCmd(serviceName) + "\""));
            output = execFast(winCmd("sc delete \"" + escapeCmd(serviceName) + "\""));
        } else if (isMac) {
            output = deleteMacOS(serviceName);
        } else {
            execFast("systemctl stop " + escapeShell(serviceName) + " 2>&1");
            execFast("systemctl disable " + escapeShell(serviceName) + " 2>&1");
            output = "Service stopped and disabled. To fully remove, delete the unit file: "
                    + "/etc/systemd/system/" + serviceName + ".service and run: systemctl daemon-reload";
        }

        Map<String, Object> data = new HashMap<>();
        data.put("action", "delete");
        data.put("serviceName", serviceName);
        data.put("os", isWindows ? "windows" : isMac ? "macos" : "linux");
        data.put("output", output != null ? output.trim() : "");

        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("data", data);
        return result;
    }

    // ── start / stop / restart ─────────────────────────────────────────────────

    private Map<String, Object> control(String serviceName, String op) throws Exception {
        if (serviceName == null || serviceName.trim().isEmpty()) return error(400, "serviceName is required");

        String osOutput = execFast("uname -s 2>/dev/null || echo Windows");
        boolean isWindows = isWindows(osOutput);
        boolean isMac = !isWindows && osOutput.toLowerCase().contains("darwin");

        String output;
        if (isWindows) {
            if ("restart".equals(op)) {
                execFast(winCmd("sc stop \"" + escapeCmd(serviceName) + "\""));
                Thread.sleep(2000);
                output = execFast(winCmd("sc start \"" + escapeCmd(serviceName) + "\""));
            } else {
                output = execFast(winCmd("sc " + op + " \"" + escapeCmd(serviceName) + "\""));
            }
        } else if (isMac) {
            output = controlMacOS(serviceName, op);
        } else {
            output = execFast("systemctl " + op + " " + escapeShell(serviceName) + " 2>&1");
            if (output == null || output.contains("command not found")) {
                if ("restart".equals(op)) {
                    execFast("service " + escapeShell(serviceName) + " stop 2>&1");
                    output = execFast("service " + escapeShell(serviceName) + " start 2>&1");
                } else {
                    output = execFast("service " + escapeShell(serviceName) + " " + op + " 2>&1");
                }
            }
        }

        Map<String, Object> data = new HashMap<>();
        data.put("action", op);
        data.put("serviceName", serviceName);
        data.put("os", isWindows ? "windows" : isMac ? "macos" : "linux");
        data.put("output", output != null ? output.trim() : "");

        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("data", data);
        return result;
    }

    // ── enable / disable ──────────────────────────────────────────────────────

    private Map<String, Object> autoStart(String serviceName, boolean enable) throws Exception {
        if (serviceName == null || serviceName.trim().isEmpty()) return error(400, "serviceName is required");

        String osOutput = execFast("uname -s 2>/dev/null || echo Windows");
        boolean isWindows = isWindows(osOutput);
        boolean isMac = !isWindows && osOutput.toLowerCase().contains("darwin");

        String output;
        if (isWindows) {
            String startType = enable ? "auto" : "disabled";
            output = execFast(winCmd("sc config \"" + escapeCmd(serviceName) + "\" start= " + startType));
        } else if (isMac) {
            output = toggleAutoStartMacOS(serviceName, enable);
        } else {
            String op = enable ? "enable" : "disable";
            output = execFast("systemctl " + op + " " + escapeShell(serviceName) + " 2>&1");
            if (output == null || output.contains("command not found")) {
                if (enable) {
                    output = execFast("update-rc.d " + escapeShell(serviceName) + " defaults 2>&1");
                } else {
                    output = execFast("update-rc.d " + escapeShell(serviceName) + " remove 2>&1");
                }
            }
        }

        Map<String, Object> data = new HashMap<>();
        data.put("action", enable ? "enable" : "disable");
        data.put("serviceName", serviceName);
        data.put("os", isWindows ? "windows" : isMac ? "macos" : "linux");
        data.put("output", output != null ? output.trim() : "");

        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("data", data);
        return result;
    }

    // ── Windows list ──────────────────────────────────────────────────────────

    private List<Map<String, Object>> listWindows(List<String> diagnostics) throws Exception {
        List<Map<String, Object>> services = new ArrayList<>();

        String output = execWithTimeout(winCmd("sc query state= all"), 30);
        if (output == null || output.trim().isEmpty()) {
            diagnostics.add("sc query returned empty");
        } else {
            diagnostics.add("source=sc query");
            String[] blocks = output.split("SERVICE_NAME:");
            for (int i = 1; i < blocks.length && services.size() < 3000; i++) {
                Map<String, Object> svc = parseScQueryBlock(blocks[i]);
                if (svc != null) services.add(svc);
            }
        }

        if (services.isEmpty()) {
            diagnostics.add("sc query parsed 0 services, trying wmic");
            return listWindowsWmic(diagnostics);
        }
        return services;
    }

    private Map<String, Object> parseScQueryBlock(String block) {
        String[] lines = block.split("\n");
        if (lines.length == 0) return null;

        Map<String, Object> svc = new HashMap<>();
        svc.put("serviceName", lines[0].trim());

        for (int i = 1; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.startsWith("DISPLAY_NAME") || line.startsWith("显示名称")) {
                int ci = line.indexOf(':');
                if (ci > 0) svc.put("displayName", line.substring(ci + 1).trim());
            } else if (line.startsWith("STATE") || line.startsWith("状态")) {
                int ci = line.indexOf(':');
                if (ci > 0) {
                    String raw = line.substring(ci + 1).trim();
                    svc.put("stateRaw", raw);
                    svc.put("state", parseWindowsState(raw));
                }
            } else if (line.startsWith("TYPE") || line.startsWith("类型")) {
                int ci = line.indexOf(':');
                if (ci > 0) svc.put("type", line.substring(ci + 1).trim());
            } else if (line.startsWith("START_TYPE") || line.startsWith("启动类型")) {
                int ci = line.indexOf(':');
                if (ci > 0) svc.put("startType", line.substring(ci + 1).trim());
            }
        }
        String name = strVal(svc.get("serviceName"));
        return name.isEmpty() ? null : svc;
    }

    private String parseWindowsState(String s) {
        if (s == null) return "UNKNOWN";
        String u = s.toUpperCase();
        if (u.contains("RUNNING")      || u.contains("运行"))   return "RUNNING";
        if (u.contains("STOPPED")      || u.contains("停止"))   return "STOPPED";
        if (u.contains("PAUSED")       || u.contains("暂停"))   return "PAUSED";
        if (u.contains("START_PENDING")|| u.contains("正在启动")) return "START_PENDING";
        if (u.contains("STOP_PENDING") || u.contains("正在停止")) return "STOP_PENDING";
        return s;
    }

    private List<Map<String, Object>> listWindowsWmic(List<String> diagnostics) throws Exception {
        List<Map<String, Object>> services = new ArrayList<>();
        String output = execWithTimeout(
                winCmd("wmic service get Name,DisplayName,State,StartMode,PathName /FORMAT:CSV"), 30);
        if (output == null || output.trim().isEmpty()) {
            diagnostics.add("wmic service also returned empty");
            return services;
        }

        diagnostics.add("source=wmic service");
        String[] lines = output.split("\n");

        int headerIdx = -1;
        String[] headers = null;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.contains("Name") && line.contains("State")) {
                headers = line.split(",");
                headerIdx = i;
                break;
            }
        }
        if (headers == null) return services;

        int colName = -1, colDisplay = -1, colState = -1, colStart = -1, colPath = -1;
        for (int i = 0; i < headers.length; i++) {
            String h = headers[i].trim();
            if ("Name".equalsIgnoreCase(h))        colName    = i;
            else if ("DisplayName".equalsIgnoreCase(h)) colDisplay = i;
            else if ("State".equalsIgnoreCase(h))  colState   = i;
            else if ("StartMode".equalsIgnoreCase(h))  colStart = i;
            else if ("PathName".equalsIgnoreCase(h))   colPath  = i;
        }

        for (int i = headerIdx + 1; i < lines.length && services.size() < 3000; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) continue;
            String[] cols = line.split(",");
            Map<String, Object> svc = new HashMap<>();
            if (colName    >= 0 && colName    < cols.length) svc.put("serviceName", cols[colName].trim());
            if (colDisplay >= 0 && colDisplay < cols.length) svc.put("displayName", cols[colDisplay].trim());
            if (colState   >= 0 && colState   < cols.length) svc.put("state",       cols[colState].trim());
            if (colStart   >= 0 && colStart   < cols.length) svc.put("startType",   cols[colStart].trim());
            if (colPath    >= 0 && colPath    < cols.length) svc.put("binPath",      cols[colPath].trim());
            if (svc.get("serviceName") != null) services.add(svc);
        }
        return services;
    }

    // ── Windows query ─────────────────────────────────────────────────────────

    private Map<String, Object> queryWindows(String serviceName) throws Exception {
        String qcOutput    = execFast(winCmd("sc qc \""    + escapeCmd(serviceName) + "\""));
        String queryOutput = execFast(winCmd("sc query \"" + escapeCmd(serviceName) + "\""));

        Map<String, Object> detail = new HashMap<>();
        if (qcOutput    != null) parseScKeyValueBlock(qcOutput, detail);
        if (queryOutput != null) parseScKeyValueBlock(queryOutput, detail);

        if (detail.isEmpty()) return error(404, "service not found: " + serviceName);

        Map<String, Object> data = new HashMap<>();
        data.put("action", "query");
        data.put("serviceName", serviceName);
        data.put("detail", detail);
        data.put("rawOutput", truncate((qcOutput != null ? qcOutput : "") + "\n---\n"
                + (queryOutput != null ? queryOutput : ""), 8192));

        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("data", data);
        return result;
    }

    private void parseScKeyValueBlock(String output, Map<String, Object> detail) {
        for (String line : output.split("\n")) {
            line = line.trim();
            int ci = line.indexOf(':');
            if (ci > 0 && ci < line.length() - 1) {
                String key = line.substring(0, ci).trim();
                String val = line.substring(ci + 1).trim();
                if (!key.isEmpty() && !val.isEmpty()) detail.put(key, val);
            }
        }
    }

    // ── Linux list ────────────────────────────────────────────────────────────

    private List<Map<String, Object>> listLinux(List<String> diagnostics) throws Exception {
        List<Map<String, Object>> services = new ArrayList<>();

        String output = execWithTimeout(
                "systemctl list-units --type=service --all --no-pager --no-legend 2>/dev/null", 30);
        if (output != null && !output.trim().isEmpty()) {
            diagnostics.add("source=systemctl list-units");
            parseSystemctlListUnits(output, services);
        }

        if (!services.isEmpty()) {
            String filesOutput = execWithTimeout(
                    "systemctl list-unit-files --type=service --no-pager --no-legend 2>/dev/null", 30);
            if (filesOutput != null) {
                Map<String, String> enabledMap = parseUnitFilesEnabled(filesOutput);
                for (Map<String, Object> svc : services) {
                    String name = strVal(svc.get("serviceName"));
                    String enabled = enabledMap.get(name);
                    if (enabled != null) svc.put("enabled", enabled);
                }
            }
            return services;
        }

        // fallback: service --status-all
        diagnostics.add("systemctl unavailable, trying service --status-all");
        output = execFast("service --status-all 2>/dev/null");
        if (output != null && !output.trim().isEmpty()) {
            diagnostics.add("source=service --status-all");
            parseServiceStatusAll(output, services);
        }

        // fallback: /etc/init.d/
        if (services.isEmpty()) {
            output = execFast("ls -1 /etc/init.d/ 2>/dev/null");
            if (output != null && !output.trim().isEmpty()) {
                diagnostics.add("source=/etc/init.d/");
                for (String name : output.trim().split("\n")) {
                    name = name.trim();
                    if (!name.isEmpty()) {
                        Map<String, Object> svc = new HashMap<>();
                        svc.put("serviceName", name);
                        svc.put("type", "init.d");
                        services.add(svc);
                    }
                }
            }
        }

        return services;
    }

    private void parseSystemctlListUnits(String output, List<Map<String, Object>> services) {
        for (String line : output.split("\n")) {
            if (services.size() >= 3000) break;
            line = line.trim();
            if (line.isEmpty()) continue;

            // strip leading ● or * bullet
            if (line.charAt(0) > 127 || line.startsWith("●") || line.startsWith("*")) {
                line = line.substring(1).trim();
            }

            String[] parts = line.split("\\s+", 5);
            if (parts.length < 4) continue;
            if (!parts[0].endsWith(".service")) continue;

            Map<String, Object> svc = new HashMap<>();
            svc.put("serviceName", parts[0]);
            svc.put("load",   parts[1]);
            svc.put("active", parts[2]);
            svc.put("sub",    parts[3]);
            if (parts.length >= 5) svc.put("description", parts[4]);

            String active = parts[2];
            String sub    = parts[3];
            String state;
            if ("active".equals(active) && "running".equals(sub)) {
                state = "RUNNING";
            } else if ("active".equals(active)) {
                state = "ACTIVE";
            } else if ("failed".equals(active) || "failed".equals(sub)) {
                state = "FAILED";
            } else {
                state = "STOPPED";
            }
            svc.put("state", state);
            svc.put("type", "systemd");
            services.add(svc);
        }
    }

    private Map<String, String> parseUnitFilesEnabled(String output) {
        Map<String, String> map = new HashMap<>();
        for (String line : output.split("\n")) {
            String[] parts = line.trim().split("\\s+");
            if (parts.length >= 2) map.put(parts[0], parts[1]);
        }
        return map;
    }

    private void parseServiceStatusAll(String output, List<Map<String, Object>> services) {
        for (String line : output.split("\n")) {
            line = line.trim();
            if (line.isEmpty()) continue;
            String state = "UNKNOWN";
            if (line.contains("[ + ]"))      state = "RUNNING";
            else if (line.contains("[ - ]")) state = "STOPPED";
            int bi = line.lastIndexOf(']');
            if (bi < 0) continue;
            String name = line.substring(bi + 1).trim();
            if (name.isEmpty()) continue;
            Map<String, Object> svc = new HashMap<>();
            svc.put("serviceName", name);
            svc.put("state", state);
            svc.put("type", "sysv");
            services.add(svc);
        }
    }

    // ── Linux query ───────────────────────────────────────────────────────────

    private Map<String, Object> queryLinux(String serviceName) throws Exception {
        String output = execFast("systemctl show " + escapeShell(serviceName) + " --no-pager 2>/dev/null");

        Map<String, Object> data = new HashMap<>();
        data.put("action", "query");
        data.put("serviceName", serviceName);

        if (output == null || output.trim().isEmpty()) {
            // fallback to status
            output = execFast("systemctl status " + escapeShell(serviceName) + " --no-pager 2>&1");
            if (output == null || output.trim().isEmpty()) {
                output = execFast("service " + escapeShell(serviceName) + " status 2>&1");
            }
            data.put("rawOutput", output != null ? truncate(output, 8192) : "");
        } else {
            String[] interesting = {
                "Id", "Description", "LoadState", "ActiveState", "SubState",
                "MainPID", "ExecStart", "ExecMainStartTimestamp", "FragmentPath",
                "UnitFileState", "Type", "Restart", "User", "Group",
                "MemoryCurrent", "CPUUsageNSec", "TasksCurrent"
            };
            Map<String, Object> detail = new HashMap<>();
            for (String line : output.split("\n")) {
                int ei = line.indexOf('=');
                if (ei <= 0) continue;
                String key = line.substring(0, ei);
                String val = line.substring(ei + 1);
                for (String k : interesting) {
                    if (k.equals(key) && !val.isEmpty() && !"0".equals(val) && !"[not set]".equals(val)) {
                        detail.put(key, val);
                        break;
                    }
                }
            }
            data.put("detail", detail);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("data", data);
        return result;
    }

    // ── macOS list ────────────────────────────────────────────────────────────

    private List<Map<String, Object>> listMacOS(List<String> diagnostics) throws Exception {
        List<Map<String, Object>> services = new ArrayList<>();
        Map<String, Boolean> labelSet = new HashMap<>();

        String output = execWithTimeout("launchctl list 2>/dev/null", 20);
        if (output != null && !output.trim().isEmpty()) {
            diagnostics.add("source=launchctl list");
            for (String line : output.split("\n")) {
                if (services.size() >= 3000) break;
                line = line.trim();
                if (line.isEmpty()) continue;
                if (line.startsWith("PID") && line.contains("Label")) continue; // header

                String[] parts = line.split("\t");
                if (parts.length < 3) continue;
                String pid    = parts[0].trim();
                String status = parts[1].trim();
                String label  = parts[2].trim();
                if (label.isEmpty()) continue;

                Map<String, Object> svc = new HashMap<>();
                svc.put("serviceName", label);
                svc.put("type", "launchd");

                if (!"-".equals(pid) && !pid.isEmpty()) {
                    svc.put("pid", pid);
                    svc.put("status", "RUNNING");
                } else {
                    svc.put("status", "STOPPED");
                }
                if (!"-".equals(status) && !"0".equals(status) && !status.isEmpty()) {
                    svc.put("lastExitStatus", status);
                    if ("-".equals(pid)) svc.put("status", "FAILED");
                }
                svc.put("domain", label.startsWith("com.apple.") ? "apple" : "third-party");

                services.add(svc);
                labelSet.put(label, Boolean.TRUE);
            }
        }

        // Supplement with plist directories — use $HOME for user LaunchAgents
        String[] plistDirs = {
            "/Library/LaunchDaemons",
            "/Library/LaunchAgents",
            "/System/Library/LaunchDaemons",
            "/System/Library/LaunchAgents",
            "$HOME/Library/LaunchAgents"
        };
        StringBuilder allDirs = new StringBuilder();
        for (String d : plistDirs) allDirs.append(" ").append(d);
        String lsOutput = execFast("ls " + allDirs.toString().trim() + " 2>/dev/null");
        if (lsOutput != null) {
            for (String name : lsOutput.split("\n")) {
                name = name.trim();
                if (!name.endsWith(".plist")) continue;
                String label = name.substring(0, name.length() - 6);
                if (labelSet.containsKey(label)) continue;
                Map<String, Object> svc = new HashMap<>();
                svc.put("serviceName", label);
                svc.put("type", "launchd");
                svc.put("status", "UNLOADED");
                services.add(svc);
                labelSet.put(label, Boolean.TRUE);
            }
        }

        if (services.isEmpty()) diagnostics.add("launchctl list returned empty");
        return services;
    }

    // ── macOS query ───────────────────────────────────────────────────────────

    private Map<String, Object> queryMacOS(String serviceName) throws Exception {
        Map<String, Object> detail = new HashMap<>();
        String rawOutput = "";

        // try system domain
        String printOutput = execFast("launchctl print system/" + escapeShell(serviceName) + " 2>&1");
        if (printOutput != null && !printOutput.contains("Could not find service")
                && !printOutput.contains("No such process") && !printOutput.trim().isEmpty()) {
            rawOutput = printOutput;
            parseLaunchctlPrint(printOutput, detail);
        } else {
            // try gui domain
            String uid = execFast("id -u 2>/dev/null");
            if (uid != null) {
                uid = uid.trim();
                String guiOutput = execFast("launchctl print gui/" + uid + "/"
                        + escapeShell(serviceName) + " 2>&1");
                if (guiOutput != null && !guiOutput.contains("Could not find service")
                        && !guiOutput.contains("No such process") && !guiOutput.trim().isEmpty()) {
                    rawOutput = guiOutput;
                    parseLaunchctlPrint(guiOutput, detail);
                }
            }
        }

        // try reading plist
        String plistContent = findAndReadPlist(serviceName);
        if (plistContent != null) {
            detail.put("plistContent", truncate(plistContent, 4096));
            if (rawOutput.isEmpty()) rawOutput = plistContent;
        }

        // basic status from launchctl list
        String listOut = execFast("launchctl list 2>/dev/null | grep " + escapeShell(serviceName));
        if (listOut != null && !listOut.trim().isEmpty()) {
            String[] parts = listOut.trim().split("\t");
            if (parts.length >= 3) {
                detail.put("pid",            parts[0].trim());
                detail.put("lastExitStatus", parts[1].trim());
                detail.put("label",          parts[2].trim());
                detail.put("status", "-".equals(parts[0].trim()) ? "STOPPED" : "RUNNING");
            }
        }

        if (detail.isEmpty()) return error(404, "service not found: " + serviceName);

        Map<String, Object> data = new HashMap<>();
        data.put("action", "query");
        data.put("serviceName", serviceName);
        data.put("detail", detail);
        if (!rawOutput.isEmpty()) data.put("rawOutput", truncate(rawOutput, 8192));

        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("data", data);
        return result;
    }

    private void parseLaunchctlPrint(String output, Map<String, Object> detail) {
        for (String line : output.split("\n")) {
            line = line.trim();
            if      (line.startsWith("state ="))          detail.put("state",        line.substring(7).trim());
            else if (line.startsWith("program ="))        detail.put("program",       line.substring(9).trim());
            else if (line.startsWith("pid ="))            detail.put("pid",           line.substring(5).trim());
            else if (line.startsWith("last exit code =")) detail.put("lastExitCode",  line.substring(16).trim());
            else if (line.startsWith("timeout ="))        detail.put("timeout",       line.substring(9).trim());
            else if (line.startsWith("runs ="))           detail.put("runs",          line.substring(6).trim());
            else if (line.startsWith("path ="))           detail.put("path",          line.substring(6).trim());
            else if (line.startsWith("type ="))           detail.put("type",          line.substring(6).trim());
        }
    }

    // ── macOS control ─────────────────────────────────────────────────────────

    private String controlMacOS(String serviceName, String op) throws Exception {
        String safe = escapeShell(serviceName);
        if ("restart".equals(op)) {
            controlMacOS(serviceName, "stop");
            Thread.sleep(2000);
            return controlMacOS(serviceName, "start");
        }
        if ("start".equals(op)) {
            String out = execFast("launchctl kickstart -k system/" + safe + " 2>&1");
            if (out != null && !out.contains("Could not find service") && !out.contains("Usage:")) return out;
            return execFast("launchctl start " + safe + " 2>&1");
        }
        if ("stop".equals(op)) {
            String out = execFast("launchctl kill SIGTERM system/" + safe + " 2>&1");
            if (out != null && !out.contains("Could not find service") && !out.contains("Usage:")) return out;
            return execFast("launchctl stop " + safe + " 2>&1");
        }
        return "unsupported operation: " + op;
    }

    private String toggleAutoStartMacOS(String serviceName, boolean enable) throws Exception {
        String safe = escapeShell(serviceName);
        String output;
        if (enable) {
            output = execFast("launchctl enable system/" + safe + " 2>&1");
            if (output != null && output.contains("Usage:")) {
                String path = findPlistPath(serviceName);
                if (path != null) output = execFast("launchctl load -w '" + path.replace("'", "'\\''") + "' 2>&1");
            }
        } else {
            output = execFast("launchctl disable system/" + safe + " 2>&1");
            if (output != null && output.contains("Usage:")) {
                String path = findPlistPath(serviceName);
                if (path != null) output = execFast("launchctl unload -w '" + path.replace("'", "'\\''") + "' 2>&1");
            }
        }
        return output;
    }

    private String deleteMacOS(String serviceName) throws Exception {
        String safe = escapeShell(serviceName);
        String output = execFast("launchctl bootout system/" + safe + " 2>&1");
        String plistPath = findPlistPath(serviceName);
        if (plistPath != null) {
            execFast("launchctl unload '" + plistPath.replace("'", "'\\''") + "' 2>&1");
            return (output != null ? output.trim() + "\n" : "")
                    + "Service unloaded. To fully remove, delete the plist file: " + plistPath;
        }
        return output != null ? output.trim()
                : "Service bootout attempted. If a plist file exists, delete it manually.";
    }

    // ── macOS plist helpers ───────────────────────────────────────────────────

    private String findAndReadPlist(String label) throws Exception {
        String safe = escapeShell(label);
        String[] dirs = {
            "/Library/LaunchDaemons/",
            "/Library/LaunchAgents/",
            "/System/Library/LaunchDaemons/",
            "/System/Library/LaunchAgents/"
        };
        for (String dir : dirs) {
            String content = execFast("cat " + dir + safe + ".plist 2>/dev/null");
            if (content != null && !content.trim().isEmpty()) return content;
        }
        // user LaunchAgents via $HOME
        String content = execFast("cat \"$HOME/Library/LaunchAgents/" + safe + ".plist\" 2>/dev/null");
        if (content != null && !content.trim().isEmpty()) return content;
        return null;
    }

    private String findPlistPath(String label) throws Exception {
        String safe = escapeShell(label);
        String[] dirs = {
            "/Library/LaunchDaemons/",
            "/Library/LaunchAgents/",
            "/System/Library/LaunchDaemons/",
            "/System/Library/LaunchAgents/"
        };
        for (String dir : dirs) {
            String check = execFast("test -f " + dir + safe + ".plist && echo EXISTS 2>/dev/null");
            if (check != null && check.trim().contains("EXISTS")) return dir + label + ".plist";
        }
        String check = execFast("test -f \"$HOME/Library/LaunchAgents/" + safe + ".plist\" && echo EXISTS 2>/dev/null");
        if (check != null && check.trim().contains("EXISTS")) return "$HOME/Library/LaunchAgents/" + label + ".plist";
        return null;
    }

    // ── templates ─────────────────────────────────────────────────────────────

    private String buildLaunchdPlistTemplate(String label, String programPath) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\""
                + " \"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n"
                + "<plist version=\"1.0\">\n<dict>\n"
                + "    <key>Label</key>\n    <string>" + label + "</string>\n"
                + "    <key>ProgramArguments</key>\n    <array>\n"
                + "        <string>" + programPath + "</string>\n    </array>\n"
                + "    <key>RunAtLoad</key>\n    <true/>\n"
                + "    <key>KeepAlive</key>\n    <true/>\n"
                + "    <key>StandardOutPath</key>\n    <string>/var/log/" + label + ".out.log</string>\n"
                + "    <key>StandardErrorPath</key>\n    <string>/var/log/" + label + ".err.log</string>\n"
                + "</dict>\n</plist>\n";
    }

    private String buildSystemdUnitTemplate(String name, String execStart) {
        return "[Unit]\nDescription=" + name + "\nAfter=network.target\n\n"
                + "[Service]\nType=simple\nExecStart=" + execStart + "\n"
                + "Restart=on-failure\nRestartSec=5\n\n"
                + "[Install]\nWantedBy=multi-user.target\n";
    }

    // ── utilities ─────────────────────────────────────────────────────────────

    private boolean isWindows(String osOutput) {
        return osOutput == null || osOutput.trim().isEmpty()
                || osOutput.toLowerCase().contains("windows");
    }

    /** Wrap a command for Windows: chcp 65001 to force UTF-8 output. */

    private String escapeCmd(String s) {
        return s == null ? "" : s.replace("\"", "\\\"");
    }

    private String escapeShell(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '_' || c == '.' || c == '-' || c == '@') {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max) + "...(truncated)";
    }

    private String strVal(Object obj) {
        return obj != null ? obj.toString() : "";
    }

    private Map<String, Object> error(int code, String msg) {
        Map<String, Object> result = new HashMap<>();
        result.put("code", code);
        result.put("msg", msg);
        return result;
    }
}
