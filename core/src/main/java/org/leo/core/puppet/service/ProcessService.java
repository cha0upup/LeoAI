package org.leo.core.puppet.service;

import org.leo.core.net.Communication;
import org.leo.core.net.layer.RequestLayer;
import org.leo.core.net.layer.ResponseLayer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 进程管理服务
 * <p>
 * 内联采集和管理操作系统进程，跨平台支持 Linux / macOS / Windows。
 * Linux 优先使用 ps 命令，回退到 /proc 文件系统扫描。
 * Windows 使用 wmic / tasklist。
 * 端口→PID 映射：Linux 使用 ss/netstat，Windows 使用 netstat -ano。
 */
public class ProcessService extends ComponentService {

    private static final int MAX_PROCS = 2000;

    private static final int OS_WINDOWS = 0;
    private static final int OS_MACOS   = 1;
    private static final int OS_LINUX   = 2;

    public ProcessService(Communication communication,
                          List<RequestLayer> requestLayers,
                          List<ResponseLayer> responseLayers) {
        super(communication, requestLayers, responseLayers);
    }

    // ==================== public API ====================

    /** 列举所有进程 */
    public Map<String, Object> listProcesses() throws Exception {
        return doList();
    }

    /** 按名称查找进程（模糊匹配） */
    public Map<String, Object> findByName(String name) throws Exception {
        return doFind(name, -1, -1);
    }

    /** 按 PID 查找进程 */
    public Map<String, Object> findByPid(int pid) throws Exception {
        return doFind(null, pid, -1);
    }

    /** 按监听端口查找进程 */
    public Map<String, Object> findByPort(int port) throws Exception {
        return doFind(null, -1, port);
    }

    /** 组合条件查找（任意参数可为 null / -1 表示不过滤） */
    public Map<String, Object> find(String name, int pid, int port) throws Exception {
        return doFind(
                (name != null && !name.isEmpty()) ? name : null,
                pid >= 0 ? pid : -1,
                port > 0 ? port : -1);
    }

    /**
     * 终止进程
     *
     * @param pid   要终止的进程 ID
     * @param force 是否强制终止（Linux: kill -9, Windows: taskkill /F）
     */
    public Map<String, Object> killProcess(int pid, boolean force) throws Exception {
        return doKill(pid, force);
    }

    // ==================== OS detection ====================

    private int detectOS() throws Exception {
        String out = execFast("uname -s 2>/dev/null || echo Windows").trim();
        if (out.startsWith("Windows") || out.isEmpty()) return OS_WINDOWS;
        if ("Darwin".equals(out)) return OS_MACOS;
        return OS_LINUX;
    }

    // ==================== list ====================

    private Map<String, Object> doList() throws Exception {
        int osType = detectOS();
        List<String>              diagnostics = new ArrayList<String>();
        List<Map<String, Object>> processes;

        if (osType == OS_WINDOWS) {
            processes = listWindows(diagnostics);
        } else {
            processes = listLinux(diagnostics);   // macOS also uses ps
        }

        Map<String, Object> result = new HashMap<String, Object>();
        result.put("code",      200);
        result.put("action",    "list");
        result.put("total",     processes.size());
        result.put("processes", processes);
        result.put("os", osType == OS_WINDOWS ? "Windows" : osType == OS_MACOS ? "macOS" : "Linux");
        if (!diagnostics.isEmpty()) {
            result.put("diagnostics", diagnostics);
        }
        return result;
    }

    // ==================== find ====================

    private Map<String, Object> doFind(String nameFilter, int pidFilter, int portFilter) throws Exception {
        int osType = detectOS();
        List<String>              diagnostics = new ArrayList<String>();
        List<Map<String, Object>> allProcs;

        if (osType == OS_WINDOWS) {
            allProcs = listWindows(diagnostics);
        } else {
            allProcs = listLinux(diagnostics);
        }

        // 端口 → PID 映射（仅当 portFilter 有效时才解析）
        Map<String, List<Integer>> portToPids = null;
        if (portFilter > 0) {
            if (osType == OS_WINDOWS) {
                portToPids = parseWindowsListeningPorts();
            } else {
                portToPids = parseLinuxListeningPorts();
            }
        }

        List<Map<String, Object>> matched = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < allProcs.size(); i++) {
            Map<String, Object> proc = allProcs.get(i);
            boolean match = true;

            // 按 PID 过滤
            if (pidFilter >= 0) {
                Object pidObj = proc.get("pid");
                int pid = pidObj instanceof Number ? ((Number) pidObj).intValue() : -1;
                if (pid != pidFilter) match = false;
            }

            // 按名称过滤（包含匹配，不区分大小写）
            if (match && nameFilter != null) {
                String procName = strVal(proc.get("name"));
                String procCmd  = strVal(proc.get("cmd"));
                String lower    = nameFilter.toLowerCase();
                if (!procName.toLowerCase().contains(lower)
                        && !procCmd.toLowerCase().contains(lower)) {
                    match = false;
                }
            }

            // 按端口过滤
            if (match && portFilter > 0 && portToPids != null) {
                Object pidObj    = proc.get("pid");
                int    pid       = pidObj instanceof Number ? ((Number) pidObj).intValue() : -1;
                String portKey   = String.valueOf(portFilter);
                List<Integer> pidList = portToPids.get(portKey);
                boolean portMatch = false;
                if (pidList != null) {
                    for (int j = 0; j < pidList.size(); j++) {
                        if (pidList.get(j).intValue() == pid) { portMatch = true; break; }
                    }
                }
                if (!portMatch) match = false;
            }

            if (match) {
                if (portFilter > 0) {
                    proc.put("matchedPort", portFilter);
                }
                matched.add(proc);
            }
        }

        Map<String, Object> result = new HashMap<String, Object>();
        result.put("code",      200);
        result.put("action",    "find");
        result.put("total",     matched.size());
        result.put("processes", matched);
        return result;
    }

    // ==================== kill ====================

    private Map<String, Object> doKill(int pid, boolean force) throws Exception {
        Map<String, Object> result = new HashMap<String, Object>();
        if (pid <= 0) {
            result.put("code", 400);
            result.put("msg",  "pid is required for kill action");
            return result;
        }

        int    osType = detectOS();
        String output;
        if (osType == OS_WINDOWS) {
            String cmd = force
                    ? "taskkill /PID " + pid + " /F"
                    : "taskkill /PID " + pid;
            output = execFast(winCmd(cmd));
        } else {
            String signal = force ? "-9" : "-15";
            output = execFast("kill " + signal + " " + pid + " 2>&1");
        }

        result.put("code",   200);
        result.put("action", "kill");
        result.put("pid",    pid);
        result.put("force",  force);
        result.put("output", output != null ? output.trim() : "");
        return result;
    }

    // ==================== Linux / macOS: ps-based listing ====================

    private List<Map<String, Object>> listLinux(List<String> diagnostics) throws Exception {
        // 1. Try ps with extended fields (Linux & modern macOS)
        String output = execFast("ps -eo pid,ppid,user,rss,comm,args --no-headers 2>/dev/null");
        if (output != null && !output.trim().isEmpty()) {
            List<Map<String, Object>> procs = parsePsEoOutput(output);
            if (!procs.isEmpty()) {
                diagnostics.add("source=ps -eo");
                return procs;
            }
        }

        // 2. ps aux
        output = execFast("ps aux 2>/dev/null");
        if (output != null && !output.trim().isEmpty()) {
            List<Map<String, Object>> procs = parsePsAuxOutput(output);
            if (!procs.isEmpty()) {
                diagnostics.add("source=ps aux");
                return procs;
            }
        }

        // 3. ps -ef
        output = execFast("ps -ef 2>/dev/null");
        if (output != null && !output.trim().isEmpty()) {
            List<Map<String, Object>> procs = parsePsEfOutput(output);
            if (!procs.isEmpty()) {
                diagnostics.add("source=ps -ef");
                return procs;
            }
        }

        // 4. Last resort: /proc scan (Linux only)
        diagnostics.add("ps commands failed, fallback to /proc");
        return listLinuxProc(diagnostics);
    }

    /**
     * ps -eo pid,ppid,user,rss,comm,args --no-headers
     * Columns: PID PPID USER RSS COMM ARGS...
     */
    private List<Map<String, Object>> parsePsEoOutput(String output) {
        List<Map<String, Object>> processes = new ArrayList<Map<String, Object>>();
        String[] lines = output.split("\n");
        for (int i = 0; i < lines.length && processes.size() < MAX_PROCS; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) continue;
            // Split into at most 6 parts; last part is full ARGS
            String[] parts = line.split("\\s+", 6);
            if (parts.length < 5) continue;
            Map<String, Object> proc = new HashMap<String, Object>();
            try {
                proc.put("pid", Integer.valueOf(Integer.parseInt(parts[0])));
            } catch (NumberFormatException e) {
                continue;
            }
            try {
                proc.put("ppid", Integer.valueOf(Integer.parseInt(parts[1])));
            } catch (NumberFormatException ignored) {}
            proc.put("user",  parts[2]);
            try {
                proc.put("memKb", Integer.valueOf(Integer.parseInt(parts[3])));
            } catch (NumberFormatException ignored) {}
            proc.put("name", parts[4]);
            proc.put("cmd",  parts.length >= 6 ? parts[5] : parts[4]);
            processes.add(proc);
        }
        return processes;
    }

    /**
     * ps aux: USER PID %CPU %MEM VSZ RSS TTY STAT START TIME COMMAND
     */
    private List<Map<String, Object>> parsePsAuxOutput(String output) {
        List<Map<String, Object>> processes = new ArrayList<Map<String, Object>>();
        String[] lines = output.split("\n");
        int start = 0;
        if (lines.length > 0 && lines[0].toUpperCase().contains("PID")) start = 1;
        for (int i = start; i < lines.length && processes.size() < MAX_PROCS; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) continue;
            String[] parts = line.split("\\s+", 11);
            if (parts.length < 11) continue;
            Map<String, Object> proc = new HashMap<String, Object>();
            proc.put("user", parts[0]);
            try {
                proc.put("pid", Integer.valueOf(Integer.parseInt(parts[1])));
            } catch (NumberFormatException e) {
                continue;
            }
            try {
                proc.put("memKb", Integer.valueOf(Integer.parseInt(parts[5])));
            } catch (NumberFormatException ignored) {}
            proc.put("name", extractName(parts[10]));
            proc.put("cmd",  parts[10]);
            processes.add(proc);
        }
        return processes;
    }

    /**
     * ps -ef: UID PID PPID C STIME TTY TIME CMD
     */
    private List<Map<String, Object>> parsePsEfOutput(String output) {
        List<Map<String, Object>> processes = new ArrayList<Map<String, Object>>();
        String[] lines = output.split("\n");
        int start = 0;
        if (lines.length > 0 && lines[0].toUpperCase().contains("PID")) start = 1;
        for (int i = start; i < lines.length && processes.size() < MAX_PROCS; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) continue;
            String[] parts = line.split("\\s+", 8);
            if (parts.length < 8) continue;
            Map<String, Object> proc = new HashMap<String, Object>();
            proc.put("user", parts[0]);
            try {
                proc.put("pid", Integer.valueOf(Integer.parseInt(parts[1])));
            } catch (NumberFormatException e) {
                continue;
            }
            try {
                proc.put("ppid", Integer.valueOf(Integer.parseInt(parts[2])));
            } catch (NumberFormatException ignored) {}
            proc.put("name", extractName(parts[7]));
            proc.put("cmd",  parts[7]);
            processes.add(proc);
        }
        return processes;
    }

    // ==================== Linux: /proc fallback ====================

    private List<Map<String, Object>> listLinuxProc(List<String> diagnostics) throws Exception {
        List<Map<String, Object>> processes = new ArrayList<Map<String, Object>>();

        // Enumerate numeric entries under /proc
        String listing = execFast("ls -1 /proc 2>/dev/null");
        if (listing == null || listing.trim().isEmpty()) {
            diagnostics.add("/proc not accessible");
            return processes;
        }

        // Build user cache from /etc/passwd
        Map<String, String> userCache = buildUserCache();

        String[] entries = listing.trim().split("\n");
        diagnostics.add("source=/proc, entries=" + entries.length);

        int count   = 0;
        int skipped = 0;
        for (int i = 0; i < entries.length && count < MAX_PROCS; i++) {
            String name = entries[i].trim();
            if (!isNumeric(name)) continue;
            int pid = Integer.parseInt(name);

            Map<String, Object> proc = readLinuxProc(pid, userCache);
            if (proc != null) {
                processes.add(proc);
                count++;
            } else {
                skipped++;
            }
        }

        diagnostics.add("parsed=" + count + ", skipped=" + skipped);
        return processes;
    }

    private Map<String, Object> readLinuxProc(int pid, Map<String, String> userCache) throws Exception {
        String status = execFast("cat /proc/" + pid + "/status 2>/dev/null");
        if (status == null || status.trim().isEmpty()) return null;

        Map<String, Object> proc = new HashMap<String, Object>();
        proc.put("pid", pid);

        String[] lines = status.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.startsWith("Name:")) {
                proc.put("name", line.substring(5).trim());
            } else if (line.startsWith("PPid:")) {
                try { proc.put("ppid", Integer.valueOf(Integer.parseInt(line.substring(5).trim()))); }
                catch (NumberFormatException ignored) {}
            } else if (line.startsWith("Uid:")) {
                String[] parts = line.substring(4).trim().split("\\s+");
                if (parts.length > 0) {
                    proc.put("uid",  parts[0]);
                    String uname = userCache.get(parts[0]);
                    proc.put("user", uname != null ? uname : parts[0]);
                }
            } else if (line.startsWith("VmRSS:")) {
                String val = line.substring(6).trim();
                String[] parts = val.split("\\s+");
                if (parts.length > 0) {
                    try { proc.put("memKb", Integer.valueOf(Integer.parseInt(parts[0]))); }
                    catch (NumberFormatException ignored) {}
                }
            }
        }

        // cmdline: args separated by \0; cat converts \0 to spaces on some systems
        String cmdline = execFast("cat /proc/" + pid + "/cmdline 2>/dev/null | tr '\\0' ' '");
        if (cmdline != null && !cmdline.trim().isEmpty()) {
            proc.put("cmd", cmdline.trim());
        } else {
            Object procName = proc.get("name");
            if (procName != null) proc.put("cmd", "[" + procName + "]");
        }

        return proc;
    }

    private Map<String, String> buildUserCache() throws Exception {
        Map<String, String> cache = new HashMap<String, String>();
        String passwd = execFast("cat /etc/passwd 2>/dev/null");
        if (passwd == null) return cache;
        String[] lines = passwd.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String[] parts = lines[i].split(":");
            if (parts.length >= 3) {
                cache.put(parts[2], parts[0]); // uid → username
            }
        }
        return cache;
    }

    // ==================== Windows: wmic / tasklist ====================

    private List<Map<String, Object>> listWindows(List<String> diagnostics) throws Exception {
        String output = execFast(winCmd(
                "wmic process get ProcessId,Name,CommandLine,ParentProcessId,WorkingSetSize /FORMAT:CSV 2>nul"));

        if (output != null && !output.trim().isEmpty()) {
            String[] lines = output.split("\n");
            // find header line
            int headerIdx = -1;
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i].trim();
                if (line.startsWith("Node,") || line.contains("CommandLine,") || line.contains("Name,")) {
                    headerIdx = i;
                    break;
                }
            }
            if (headerIdx >= 0) {
                String[] headers = lines[headerIdx].split(",");
                int colCmd = -1, colName = -1, colPpid = -1, colPid = -1, colMem = -1;
                for (int i = 0; i < headers.length; i++) {
                    String h = headers[i].trim();
                    if ("CommandLine".equalsIgnoreCase(h))     colCmd  = i;
                    else if ("Name".equalsIgnoreCase(h))       colName = i;
                    else if ("ParentProcessId".equalsIgnoreCase(h)) colPpid = i;
                    else if ("ProcessId".equalsIgnoreCase(h))  colPid  = i;
                    else if ("WorkingSetSize".equalsIgnoreCase(h))  colMem  = i;
                }

                List<Map<String, Object>> processes = new ArrayList<Map<String, Object>>();
                for (int i = headerIdx + 1; i < lines.length && processes.size() < MAX_PROCS; i++) {
                    String line = lines[i].trim();
                    if (line.isEmpty()) continue;
                    String[] cols = line.split(",");
                    Map<String, Object> proc = new HashMap<String, Object>();

                    if (colPid >= 0 && colPid < cols.length) {
                        try { proc.put("pid", Integer.valueOf(Integer.parseInt(cols[colPid].trim()))); }
                        catch (NumberFormatException e) { continue; }
                    }
                    if (colName >= 0 && colName < cols.length) proc.put("name", cols[colName].trim());
                    if (colCmd  >= 0 && colCmd  < cols.length) proc.put("cmd",  cols[colCmd].trim());
                    if (colPpid >= 0 && colPpid < cols.length) {
                        try { proc.put("ppid", Integer.valueOf(Integer.parseInt(cols[colPpid].trim()))); }
                        catch (NumberFormatException ignored) {}
                    }
                    if (colMem  >= 0 && colMem  < cols.length) {
                        try {
                            long bytes = Long.parseLong(cols[colMem].trim());
                            proc.put("memKb", Integer.valueOf((int)(bytes / 1024)));
                        } catch (NumberFormatException ignored) {}
                    }
                    processes.add(proc);
                }

                if (!processes.isEmpty()) {
                    diagnostics.add("source=wmic");
                    return processes;
                }
                diagnostics.add("wmic parsed 0 processes, fallback to tasklist");
            } else {
                diagnostics.add("wmic output has no header, fallback to tasklist");
            }
        } else {
            diagnostics.add("wmic returned empty, fallback to tasklist");
        }

        return listWindowsTasklist(diagnostics);
    }

    private List<Map<String, Object>> listWindowsTasklist(List<String> diagnostics) throws Exception {
        String[] commands = {
            "tasklist /FO CSV /NH",
            "tasklist /V /FO CSV /NH",
            "tasklist"
        };
        String output   = null;
        String usedCmd  = null;
        for (int c = 0; c < commands.length; c++) {
            output = execFast(winCmd(commands[c] + " 2>nul"));
            if (output != null && !output.trim().isEmpty()) {
                usedCmd = commands[c];
                break;
            }
        }
        List<Map<String, Object>> processes = new ArrayList<Map<String, Object>>();
        if (output == null || output.trim().isEmpty()) {
            diagnostics.add("all tasklist commands failed");
            return processes;
        }

        diagnostics.add("source=" + usedCmd);
        String[] lines = output.split("\n");
        for (int i = 0; i < lines.length && i < MAX_PROCS; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) continue;
            // "Image Name","PID","Session Name","Session#","Mem Usage",...
            String[] parts = parseCsvLine(line);
            if (parts.length < 5) continue;
            Map<String, Object> proc = new HashMap<String, Object>();
            proc.put("name", parts[0]);
            try { proc.put("pid", Integer.valueOf(Integer.parseInt(parts[1].trim()))); }
            catch (NumberFormatException e) { continue; }
            String memStr = parts[4].replaceAll("[^0-9]", "");
            if (!memStr.isEmpty()) {
                try { proc.put("memKb", Integer.valueOf(Integer.parseInt(memStr))); }
                catch (NumberFormatException ignored) {}
            }
            processes.add(proc);
        }
        return processes;
    }

    // ==================== port → PID mapping ====================

    /**
     * Linux / macOS: use ss or netstat to get port → PID mapping.
     * Returns Map<portString, List<pid>>.
     */
    private Map<String, List<Integer>> parseLinuxListeningPorts() throws Exception {
        // Try ss first
        String output = execFast("ss -tlnp 2>/dev/null");
        if (output != null && !output.trim().isEmpty() && output.contains("LISTEN")) {
            return parseSsOutput(output);
        }
        // Fallback: netstat
        output = execFast("netstat -tlnp 2>/dev/null");
        if (output != null && !output.trim().isEmpty()) {
            return parseNetstatLinuxOutput(output);
        }
        return new HashMap<String, List<Integer>>();
    }

    /**
     * ss -tlnp output:
     * State   Recv-Q Send-Q Local Address:Port  Peer Address:Port  Process
     * LISTEN  0      128    0.0.0.0:8080        0.0.0.0:*          users:(("java",pid=1234,fd=5))
     */
    private Map<String, List<Integer>> parseSsOutput(String output) {
        Map<String, List<Integer>> portToPids = new HashMap<String, List<Integer>>();
        String[] lines = output.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (!line.toUpperCase().contains("LISTEN")) continue;
            String[] parts = line.split("\\s+");
            if (parts.length < 4) continue;
            String local = parts[3];
            int port = extractPort(local);
            if (port <= 0) continue;
            // Extract pid= from users field
            for (int j = 4; j < parts.length; j++) {
                String part = parts[j];
                int pidIdx = part.indexOf("pid=");
                if (pidIdx >= 0) {
                    String pidStr = part.substring(pidIdx + 4);
                    int end = pidStr.indexOf(',');
                    if (end < 0) end = pidStr.indexOf(')');
                    if (end > 0) pidStr = pidStr.substring(0, end);
                    addPortPid(portToPids, port, pidStr.trim());
                }
            }
        }
        return portToPids;
    }

    /**
     * netstat -tlnp output (Linux):
     * Proto Recv-Q Send-Q Local Address  Foreign Address  State  PID/Program
     */
    private Map<String, List<Integer>> parseNetstatLinuxOutput(String output) {
        Map<String, List<Integer>> portToPids = new HashMap<String, List<Integer>>();
        String[] lines = output.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (!line.toUpperCase().contains("LISTEN")) continue;
            String[] parts = line.split("\\s+");
            if (parts.length < 7) continue;
            int port = extractPort(parts[3]);
            if (port <= 0) continue;
            // PID/Program field: "1234/java"
            String pidProgram = parts[6];
            int slash = pidProgram.indexOf('/');
            String pidStr = slash >= 0 ? pidProgram.substring(0, slash) : pidProgram;
            addPortPid(portToPids, port, pidStr.trim());
        }
        return portToPids;
    }

    /**
     * Windows: netstat -ano
     * TCP  0.0.0.0:8080  0.0.0.0:0  LISTENING  1234
     */
    private Map<String, List<Integer>> parseWindowsListeningPorts() throws Exception {
        Map<String, List<Integer>> portToPids = new HashMap<String, List<Integer>>();
        String output = execFast(winCmd("netstat -ano 2>nul"));
        if (output == null) return portToPids;
        String[] lines = output.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String line  = lines[i].trim();
            String upper = line.toUpperCase();
            if (!upper.contains("LISTEN")) continue;
            String[] parts = line.split("\\s+");
            if (parts.length < 5) continue;
            int port = extractPort(parts[1]);
            if (port <= 0) continue;
            addPortPid(portToPids, port, parts[parts.length - 1].trim());
        }
        return portToPids;
    }

    // ==================== utilities ====================

    private int extractPort(String address) {
        int lastColon = address.lastIndexOf(':');
        if (lastColon < 0) return -1;
        try {
            return Integer.parseInt(address.substring(lastColon + 1));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private void addPortPid(Map<String, List<Integer>> portToPids, int port, String pidStr) {
        try {
            int pid = Integer.parseInt(pidStr);
            String portKey = String.valueOf(port);
            List<Integer> pidList = portToPids.get(portKey);
            if (pidList == null) {
                pidList = new ArrayList<Integer>();
                portToPids.put(portKey, pidList);
            }
            pidList.add(Integer.valueOf(pid));
        } catch (NumberFormatException ignored) {}
    }

    private String extractName(String cmd) {
        if (cmd == null || cmd.isEmpty()) return "";
        String first = cmd;
        int sp = cmd.indexOf(' ');
        if (sp > 0) first = cmd.substring(0, sp);
        int slash = first.lastIndexOf('/');
        if (slash >= 0) first = first.substring(slash + 1);
        return first;
    }

    private boolean isNumeric(String s) {
        if (s == null || s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') return false;
        }
        return true;
    }

    /** Minimal CSV parser that handles double-quoted fields. */
    private String[] parseCsvLine(String line) {
        List<String>   fields  = new ArrayList<String>();
        boolean        inQuote = false;
        StringBuilder  sb      = new StringBuilder();
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                inQuote = !inQuote;
            } else if (c == ',' && !inQuote) {
                fields.add(sb.toString());
                sb.setLength(0);
            } else {
                sb.append(c);
            }
        }
        fields.add(sb.toString());
        return fields.toArray(new String[0]);
    }

    private String strVal(Object obj) {
        return obj != null ? obj.toString() : "";
    }
}
