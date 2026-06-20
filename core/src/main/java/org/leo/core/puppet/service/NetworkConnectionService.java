package org.leo.core.puppet.service;

import org.leo.core.net.Communication;
import org.leo.core.net.layer.RequestLayer;
import org.leo.core.net.layer.ResponseLayer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 网络连接实时采集服务（服务器端解析版）。
 * Windows: netstat -ano + tasklist
 * macOS: lsof -i / netstat -an
 * Linux: ss -tulnp / netstat -tulnp / lsof -i
 */
public class NetworkConnectionService extends ComponentService {

    public NetworkConnectionService(Communication communication,
                                    List<RequestLayer> requestLayers,
                                    List<ResponseLayer> responseLayers) {
        super(communication, requestLayers, responseLayers);
    }

    // ── 公共接口 ───────────────────────────────────────────────────────────────

    public Map<String, Object> list() throws Exception {
        return list(null, null, null, null, null, null, false, 2000);
    }

    public Map<String, Object> list(String state, String protocol, String port,
                                    String pid, String process, String remoteIp,
                                    boolean listeningOnly, int maxEntries) throws Exception {
        String osOutput = execFast("uname -s 2>/dev/null || echo Windows");
        boolean isWindows = isWindows(osOutput);
        boolean isMac = !isWindows && osOutput.toLowerCase().contains("darwin");

        List<Map<String, Object>> connections = new ArrayList<>();
        List<String> diagnostics = new ArrayList<>();

        if (isWindows)    collectWindows(connections, diagnostics);
        else if (isMac)   collectMacOS(connections, diagnostics);
        else              collectLinux(connections, diagnostics);

        // apply filters
        List<Map<String, Object>> filtered = new ArrayList<>();
        for (Map<String, Object> conn : connections) {
            if (listeningOnly && !"LISTEN".equals(conn.get("state"))) continue;
            if (state    != null && !strVal(conn.get("state")).toUpperCase().contains(state.toUpperCase())) continue;
            if (protocol != null && !strVal(conn.get("protocol")).toUpperCase().contains(protocol.toUpperCase())) continue;
            if (port     != null && !port.equals(strVal(conn.get("localPort"))) && !port.equals(strVal(conn.get("remotePort")))) continue;
            if (pid      != null && !pid.equals(strVal(conn.get("pid")))) continue;
            if (process  != null && !strVal(conn.get("process")).toLowerCase().contains(process.toLowerCase())) continue;
            if (remoteIp != null && !strVal(conn.get("remoteAddr")).startsWith(remoteIp)) continue;
            filtered.add(conn);
        }

        if (maxEntries <= 0) maxEntries = 2000;
        if (filtered.size() > maxEntries) {
            filtered = filtered.subList(0, maxEntries);
            diagnostics.add("truncated to " + maxEntries + " entries");
        }

        Map<String, Object> data = new HashMap<>();
        data.put("action",      "list");
        data.put("os",          isWindows ? "windows" : isMac ? "macos" : "linux");
        data.put("total",       connections.size());
        data.put("filtered",    filtered.size());
        data.put("connections", filtered);
        if (!diagnostics.isEmpty()) data.put("diagnostics", diagnostics);

        // ControllerUtil.handlePuppetCall 会把 service 返回值视作 data 部分，
        // 看到 code=200 就再调 ApiResponse.success(result) 包一层，
        // 因此 service 层只需要返回 {code, ...payload}（payload 字段直接平铺），
        // 不要再嵌一层 data，否则前端拿到的是 res.data.data.connections。
        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.putAll(data);
        return result;
    }

    public Map<String, Object> summary() throws Exception {
        String osOutput = execFast("uname -s 2>/dev/null || echo Windows");
        boolean isWindows = isWindows(osOutput);
        boolean isMac = !isWindows && osOutput.toLowerCase().contains("darwin");

        List<Map<String, Object>> connections = new ArrayList<>();
        List<String> diagnostics = new ArrayList<>();

        if (isWindows)    collectWindows(connections, diagnostics);
        else if (isMac)   collectMacOS(connections, diagnostics);
        else              collectLinux(connections, diagnostics);

        Map<String, Integer> byState     = new HashMap<>();
        Map<String, Integer> byProtocol  = new HashMap<>();
        Map<String, Integer> byProcess   = new HashMap<>();
        Map<String, Integer> byRemoteIp  = new HashMap<>();
        List<Map<String, Object>> listeningPorts = new ArrayList<>();

        for (Map<String, Object> conn : connections) {
            String s   = strVal(conn.get("state"));
            String p   = strVal(conn.get("protocol"));
            String proc = strVal(conn.get("process"));
            String pid  = strVal(conn.get("pid"));
            String rem  = strVal(conn.get("remoteAddr"));

            increment(byState,    s.isEmpty()    ? "UNKNOWN" : s);
            increment(byProtocol, p.isEmpty()    ? "UNKNOWN" : p);

            String pk = !proc.isEmpty() ? proc : (!pid.isEmpty() ? "pid:" + pid : "unknown");
            increment(byProcess, pk);

            if (!rem.isEmpty() && !"*".equals(rem) && !"0.0.0.0".equals(rem)
                    && !"::".equals(rem) && !"127.0.0.1".equals(rem) && !"::1".equals(rem)) {
                increment(byRemoteIp, rem);
            }

            if ("LISTEN".equals(s)) {
                Map<String, Object> lp = new HashMap<>();
                lp.put("port",      conn.get("localPort"));
                lp.put("protocol",  p);
                lp.put("process",   pk);
                lp.put("localAddr", conn.get("localAddr"));
                listeningPorts.add(lp);
            }
        }

        Map<String, Object> data = new HashMap<>();
        data.put("action",           "summary");
        data.put("os",               isWindows ? "windows" : isMac ? "macos" : "linux");
        data.put("totalConnections", connections.size());
        data.put("byState",          byState);
        data.put("byProtocol",       byProtocol);
        data.put("byProcess",        topN(byProcess, 30));
        data.put("byRemoteIp",       topN(byRemoteIp, 30));
        data.put("listeningPorts",   listeningPorts);
        if (!diagnostics.isEmpty()) data.put("diagnostics", diagnostics);

        // 同 list()：handlePuppetCall 会把返回值再包一层 ApiResponse.success，所以这里平铺即可
        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.putAll(data);
        return result;
    }

    // ── Windows ───────────────────────────────────────────────────────────────

    private void collectWindows(List<Map<String, Object>> connections, List<String> diagnostics)
            throws Exception {
        // Build pid→process map from tasklist
        Map<String, String> pidToProcess = new HashMap<>();
        String taskOutput = execWithTimeout(winCmd("tasklist /FO CSV /NH"), 20);
        if (taskOutput != null) {
            for (String line : taskOutput.split("\n")) {
                line = line.trim();
                if (line.isEmpty() || !line.startsWith("\"")) continue;
                String[] fields = parseCsvLine(line);
                if (fields.length >= 2) pidToProcess.put(fields[1].trim(), fields[0].trim());
            }
        }

        String output = execWithTimeout(winCmd("netstat -ano"), 20);
        if (output == null || output.trim().isEmpty()) {
            diagnostics.add("netstat -ano returned empty");
            return;
        }
        diagnostics.add("source=netstat -ano");

        for (String line : output.split("\n")) {
            if (connections.size() >= 10000) break;
            line = line.trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("Active") || line.startsWith("Proto") || line.contains("协议")) continue;

            String[] parts = line.split("\\s+");
            if (parts.length < 4) continue;

            String proto = parts[0].toUpperCase();
            if (!proto.startsWith("TCP") && !proto.startsWith("UDP")) continue;

            Map<String, Object> conn = new HashMap<>();
            conn.put("protocol", proto);

            String state = "";
            String pid   = "";
            if (proto.startsWith("TCP")) {
                if (parts.length >= 5) { state = parts[3]; pid = parts[4]; }
                else if (parts.length >= 4) { state = parts[3]; }
            } else {
                if (parts.length >= 4) {
                    pid = parts[3];
                    if ("*:*".equals(pid) && parts.length >= 5) pid = parts[4];
                }
            }

            parseAddress(parts[1], conn, "local");
            parseAddress(parts[2], conn, "remote");
            if (!state.isEmpty()) conn.put("state", normalizeState(state));
            else if (proto.startsWith("UDP")) conn.put("state", "");

            if (!pid.isEmpty()) {
                conn.put("pid", pid);
                String pname = pidToProcess.get(pid);
                if (pname != null) conn.put("process", pname);
            }
            connections.add(conn);
        }
    }

    // ── macOS ─────────────────────────────────────────────────────────────────

    private void collectMacOS(List<Map<String, Object>> connections, List<String> diagnostics)
            throws Exception {
        // PATH 兜底：puppet shell 会话是非 login shell，macOS 默认 PATH 可能不含 /usr/sbin（lsof 在那里）
        // `command -v` 同时探到，跑哪个用哪个；2>&1 不再丢错误信息
        String lsofCmd = "PATH=$PATH:/usr/sbin:/sbin:/usr/local/sbin lsof -i -n -P 2>&1";
        int before = connections.size();
        String output = execWithTimeout(lsofCmd, 20);
        if (looksLikeRealOutput(output, "COMMAND")) {
            parseLsof(output, connections);
            if (connections.size() > before) {
                diagnostics.add("source=lsof -i -n -P");
                return;
            }
            diagnostics.add("lsof returned " + output.length() + " chars but parsed 0 entries");
            diagnostics.add("lsof preview=" + previewOutput(output));
        } else {
            diagnostics.add("lsof unavailable or empty (preview=" + previewOutput(output) + ")");
        }

        // fallback：netstat -an 始终存在
        output = execFast("netstat -an 2>/dev/null");
        if (output != null && !output.trim().isEmpty()) {
            int beforeNs = connections.size();
            parseNetstatUnix(output, connections);
            if (connections.size() > beforeNs) {
                diagnostics.add("source=netstat -an");
                return;
            }
            diagnostics.add("netstat -an parsed 0 entries (preview=" + previewOutput(output) + ")");
        } else {
            diagnostics.add("netstat -an returned empty");
        }
    }

    // ── Linux ─────────────────────────────────────────────────────────────────

    private void collectLinux(List<Map<String, Object>> connections, List<String> diagnostics)
            throws Exception {
        // 同样 PATH 兜底，sbin 下的工具最常见
        String pathPrefix = "PATH=$PATH:/usr/sbin:/sbin:/usr/local/sbin ";

        int before = connections.size();
        String output = execWithTimeout(pathPrefix + "ss -tulnp 2>&1", 20);
        if (looksLikeRealOutput(output, "Netid")) {
            parseSs(output, connections, true);
            String estOutput = execWithTimeout(pathPrefix + "ss -tnp state established 2>&1", 20);
            if (looksLikeRealOutput(estOutput, null)) {
                parseSs(estOutput, connections, false);
            }
            if (connections.size() > before) {
                diagnostics.add("source=ss -tulnp");
                return;
            }
            diagnostics.add("ss returned output but parsed 0 entries");
        }

        before = connections.size();
        output = execWithTimeout(pathPrefix + "netstat -tulnp 2>&1", 20);
        if (looksLikeRealOutput(output, "Proto")) {
            parseNetstatLinux(output, connections);
            String estOutput = execFast(pathPrefix + "netstat -tnp 2>/dev/null | grep ESTABLISHED");
            if (estOutput != null && !estOutput.trim().isEmpty()) {
                parseNetstatLinux(estOutput, connections);
            }
            if (connections.size() > before) {
                diagnostics.add("source=netstat -tulnp");
                return;
            }
            diagnostics.add("netstat returned output but parsed 0 entries");
        }

        before = connections.size();
        output = execWithTimeout(pathPrefix + "lsof -i -n -P 2>&1", 20);
        if (looksLikeRealOutput(output, "COMMAND")) {
            parseLsof(output, connections);
            if (connections.size() > before) {
                diagnostics.add("source=lsof -i -n -P (fallback)");
                return;
            }
        }
        diagnostics.add("ss, netstat, lsof all unavailable or parsed 0 entries");
    }

    /**
     * 判断 shell 回显是不是「真有命令输出」而不是只有 prompt + 命令回显。
     * 出现 expectedHeader 就肯定是真输出；找不到时退而求其次用「行数 ≥ 3 且非 shell-not-found 模式」判断。
     */
    private boolean looksLikeRealOutput(String output, String expectedHeader) {
        if (output == null) return false;
        String trimmed = output.trim();
        if (trimmed.isEmpty()) return false;
        if (expectedHeader != null && trimmed.contains(expectedHeader)) return true;
        // 常见错误模式：command not found / Permission denied / No such file
        String lower = trimmed.toLowerCase();
        if (lower.contains("command not found") || lower.contains("not found")
                || lower.contains("no such file") || lower.contains("permission denied")) {
            return false;
        }
        // 至少 3 行非空才算有内容（前两行通常是 prompt + 命令回显）
        int lines = 0;
        for (String l : trimmed.split("\n")) if (!l.trim().isEmpty()) lines++;
        return lines >= 3;
    }

    private String previewOutput(String output) {
        if (output == null) return "<null>";
        String trimmed = output.trim().replace('\n', '|').replace('\r', ' ');
        if (trimmed.isEmpty()) return "<empty>";
        return trimmed.length() > 160 ? trimmed.substring(0, 160) + "…" : trimmed;
    }

    // ── parse ss ──────────────────────────────────────────────────────────────

    private void parseSs(String output, List<Map<String, Object>> connections, boolean hasHeader) {
        String[] lines = output.split("\n");
        int start = 0;
        if (hasHeader) {
            // ss header 行包含 "Netid"/"State" + "Local Address"，先定位到 header 之后开始
            for (int i = 0; i < lines.length; i++) {
                String t = lines[i].trim();
                if ((t.startsWith("Netid") || t.startsWith("State")) && t.contains("Local")) {
                    start = i + 1;
                    break;
                }
            }
        }
        for (int i = start; i < lines.length && connections.size() < 10000; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) continue;

            String[] parts = line.split("\\s+");
            if (parts.length < 5) continue;

            String col0 = parts[0].toLowerCase();
            boolean hasNetid = "tcp".equals(col0) || "udp".equals(col0) || "raw".equals(col0)
                    || "tcp6".equals(col0) || "udp6".equals(col0) || "raw6".equals(col0);
            int offset  = hasNetid ? 1 : 0;
            String netid = hasNetid ? col0 : null;

            if (parts.length < 5 + offset) continue;

            String state = parts[offset];
            if (!isValidState(state)) continue;

            Map<String, Object> conn = new HashMap<>();
            conn.put("state", normalizeState(state));

            int localIdx   = 3 + offset;
            int remoteIdx  = 4 + offset;
            int processIdx = 5 + offset;

            if (localIdx  < parts.length) parseSsAddress(parts[localIdx],  conn, "local");
            if (remoteIdx < parts.length) parseSsAddress(parts[remoteIdx], conn, "remote");

            if (netid != null) {
                conn.put("protocol", netid.startsWith("udp") ? "UDP" : "TCP");
            } else {
                String rp = strVal(conn.get("remotePort"));
                conn.put("protocol", ("UNCONN".equals(state) || "0".equals(rp) || "*".equals(rp)) ? "UDP" : "TCP");
            }

            if (processIdx < parts.length) {
                StringBuilder sb = new StringBuilder();
                for (int j = processIdx; j < parts.length; j++) {
                    if (sb.length() > 0) sb.append(" ");
                    sb.append(parts[j]);
                }
                parseSsProcess(sb.toString(), conn);
            }
            connections.add(conn);
        }
    }

    private void parseSsAddress(String addrPort, Map<String, Object> conn, String prefix) {
        if (addrPort == null || addrPort.isEmpty()) return;
        int lc = addrPort.lastIndexOf(':');
        if (lc < 0) { conn.put(prefix + "Addr", addrPort); return; }
        String addr = addrPort.substring(0, lc);
        String port = addrPort.substring(lc + 1);
        if (addr.startsWith("[") && addr.endsWith("]")) addr = addr.substring(1, addr.length() - 1);
        conn.put(prefix + "Addr", addr);
        conn.put(prefix + "Port", port);
    }

    private void parseSsProcess(String s, Map<String, Object> conn) {
        if (s == null || !s.contains("pid=")) return;
        int pi = s.indexOf("pid=");
        if (pi >= 0) {
            int start = pi + 4, end = start;
            while (end < s.length() && s.charAt(end) >= '0' && s.charAt(end) <= '9') end++;
            if (end > start) conn.put("pid", s.substring(start, end));
        }
        int ns = s.indexOf("((\"");
        if (ns >= 0) {
            ns += 3;
            int ne = s.indexOf("\"", ns);
            if (ne > ns) conn.put("process", s.substring(ns, ne));
        }
    }

    // ── parse lsof ────────────────────────────────────────────────────────────

    private void parseLsof(String output, List<Map<String, Object>> connections) {
        String[] lines = output.split("\n");
        // execWithTimeout 的回显前面可能有：shell prompt、命令回显、PATH= 设置等噪声行。
        // 先找到 lsof 的 header 行（以 COMMAND 开头），从下一行开始解析。
        int start = 0;
        for (int i = 0; i < lines.length; i++) {
            String t = lines[i].trim();
            if (t.startsWith("COMMAND") && t.contains("PID") && t.contains("NAME")) {
                start = i + 1;
                break;
            }
        }
        for (int i = start; i < lines.length && connections.size() < 10000; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) continue;
            // 过滤再次出现的 prompt / 命令回显
            if (line.startsWith("$") || line.startsWith("#") || line.startsWith("%")) continue;
            String[] parts = line.split("\\s+", 10);
            if (parts.length < 9) continue;

            String command = parts[0];
            String pid     = parts[1];
            String user    = parts[2];
            String node    = parts[7];
            // node 列必须是 TCP/UDP/IPv4/IPv6 之类，否则是噪声行
            String nodeUp = node.toUpperCase();
            if (!nodeUp.startsWith("TCP") && !nodeUp.startsWith("UDP")
                    && !nodeUp.startsWith("IP")) continue;

            String name = parts[8];
            if (parts.length > 9) name = name + " " + parts[9];

            Map<String, Object> conn = new HashMap<>();
            conn.put("process",  command);
            conn.put("pid",      pid);
            conn.put("user",     user);
            // 协议：node 列若是 IPv4/IPv6，从 NAME 中的 TCP/UDP 推断；否则 node 自身就是协议
            if (nodeUp.startsWith("IP")) {
                conn.put("protocol", name.toUpperCase().contains("UDP") ? "UDP" : "TCP");
            } else {
                conn.put("protocol", nodeUp);
            }

            int ps = name.lastIndexOf('(');
            int pe = name.lastIndexOf(')');
            if (ps >= 0 && pe > ps) {
                conn.put("state", normalizeState(name.substring(ps + 1, pe)));
                name = name.substring(0, ps).trim();
            }

            int arrow = name.indexOf("->");
            if (arrow >= 0) {
                parseLsofAddress(name.substring(0, arrow), conn, "local");
                parseLsofAddress(name.substring(arrow + 2), conn, "remote");
            } else {
                parseLsofAddress(name, conn, "local");
            }
            connections.add(conn);
        }
    }

    private void parseLsofAddress(String addrPort, Map<String, Object> conn, String prefix) {
        if (addrPort == null || addrPort.isEmpty()) return;
        int lc = addrPort.lastIndexOf(':');
        if (lc < 0) { conn.put(prefix + "Addr", addrPort); return; }
        String addr = addrPort.substring(0, lc);
        String port = addrPort.substring(lc + 1);
        if ("*".equals(addr)) addr = "0.0.0.0";
        conn.put(prefix + "Addr", addr);
        conn.put(prefix + "Port", port);
    }

    // ── parse netstat (Unix/BSD) ──────────────────────────────────────────────

    private void parseNetstatUnix(String output, List<Map<String, Object>> connections) {
        for (String line : output.split("\n")) {
            if (connections.size() >= 10000) break;
            line = line.trim();
            if (line.isEmpty()) continue;
            String[] parts = line.split("\\s+");
            if (parts.length < 4) continue;
            String proto = parts[0].toLowerCase();
            if (!proto.startsWith("tcp") && !proto.startsWith("udp")) continue;

            Map<String, Object> conn = new HashMap<>();
            conn.put("protocol", proto.toUpperCase());
            if (parts.length > 3) parseNetstatBsdAddress(parts[3], conn, "local");
            if (parts.length > 4) parseNetstatBsdAddress(parts[4], conn, "remote");
            if (parts.length > 5) conn.put("state", normalizeState(parts[5]));
            connections.add(conn);
        }
    }

    private void parseNetstatBsdAddress(String addrPort, Map<String, Object> conn, String prefix) {
        if (addrPort == null || addrPort.isEmpty() || "*.*".equals(addrPort)) {
            conn.put(prefix + "Addr", "*");
            conn.put(prefix + "Port", "*");
            return;
        }
        int ld = addrPort.lastIndexOf('.');
        if (ld > 0) {
            conn.put(prefix + "Addr", addrPort.substring(0, ld));
            conn.put(prefix + "Port", addrPort.substring(ld + 1));
        } else {
            conn.put(prefix + "Addr", addrPort);
        }
    }

    // ── parse netstat (Linux) ─────────────────────────────────────────────────

    private void parseNetstatLinux(String output, List<Map<String, Object>> connections) {
        for (String line : output.split("\n")) {
            if (connections.size() >= 10000) break;
            line = line.trim();
            if (line.isEmpty()) continue;
            String[] parts = line.split("\\s+");
            if (parts.length < 4) continue;
            String proto = parts[0].toLowerCase();
            if (!proto.startsWith("tcp") && !proto.startsWith("udp")) continue;

            Map<String, Object> conn = new HashMap<>();
            conn.put("protocol", proto.toUpperCase());

            boolean isTcp = proto.startsWith("tcp");
            // Proto Recv-Q Send-Q Local Foreign [State] PID/Prog
            if (parts.length > 3) parseSsAddress(parts[3], conn, "local");
            if (parts.length > 4) parseSsAddress(parts[4], conn, "remote");
            int stateIdx   = isTcp ? 5 : -1;
            int processIdx = isTcp ? 6 :  5;

            if (stateIdx >= 0 && stateIdx < parts.length) conn.put("state", normalizeState(parts[stateIdx]));
            if (processIdx < parts.length) {
                String pp = parts[processIdx];
                if (!"-".equals(pp) && pp.contains("/")) {
                    int si = pp.indexOf('/');
                    conn.put("pid",     pp.substring(0, si));
                    conn.put("process", pp.substring(si + 1));
                }
            }
            connections.add(conn);
        }
    }

    // ── Windows address parse ─────────────────────────────────────────────────

    private void parseAddress(String addrPort, Map<String, Object> conn, String prefix) {
        if (addrPort == null || addrPort.isEmpty()) return;
        if (addrPort.startsWith("[")) {
            int be = addrPort.indexOf("]:");
            if (be >= 0) {
                conn.put(prefix + "Addr", addrPort.substring(1, be));
                conn.put(prefix + "Port", addrPort.substring(be + 2));
            } else {
                conn.put(prefix + "Addr", addrPort);
            }
        } else {
            int lc = addrPort.lastIndexOf(':');
            if (lc >= 0) {
                conn.put(prefix + "Addr", addrPort.substring(0, lc));
                conn.put(prefix + "Port", addrPort.substring(lc + 1));
            } else {
                conn.put(prefix + "Addr", addrPort);
            }
        }
    }

    // ── state normalization ───────────────────────────────────────────────────

    private String normalizeState(String state) {
        if (state == null || state.isEmpty()) return "";
        String u = state.toUpperCase();
        if (u.startsWith("ESTAB"))                                               return "ESTABLISHED";
        if (u.startsWith("LISTEN"))                                              return "LISTEN";
        if (u.startsWith("TIME_WAIT") || u.startsWith("TIME-WAIT") || u.startsWith("TIMEWAIT")) return "TIME_WAIT";
        if (u.startsWith("CLOSE_WAIT")|| u.startsWith("CLOSE-WAIT")|| u.startsWith("CLOSEWAIT")) return "CLOSE_WAIT";
        if (u.startsWith("SYN_SENT")  || u.startsWith("SYN-SENT"))             return "SYN_SENT";
        if (u.startsWith("SYN_RECV")  || u.startsWith("SYN-RECV"))             return "SYN_RECV";
        if (u.startsWith("FIN_WAIT")  || u.startsWith("FIN-WAIT")) {
            if (u.contains("1")) return "FIN_WAIT1";
            if (u.contains("2")) return "FIN_WAIT2";
            return "FIN_WAIT";
        }
        if (u.startsWith("LAST_ACK")  || u.startsWith("LAST-ACK"))             return "LAST_ACK";
        if (u.startsWith("CLOSING"))                                             return "CLOSING";
        if (u.startsWith("CLOSED"))                                              return "CLOSED";
        if (u.startsWith("UNCONN"))                                              return "UNCONN";
        return u;
    }

    private boolean isValidState(String s) {
        if (s == null || s.isEmpty()) return false;
        String u = s.toUpperCase();
        return u.startsWith("ESTAB") || u.startsWith("LISTEN") || u.startsWith("TIME")
                || u.startsWith("CLOSE") || u.startsWith("SYN") || u.startsWith("FIN")
                || u.startsWith("LAST") || u.startsWith("UNCONN");
    }

    // ── aggregation helpers ───────────────────────────────────────────────────

    private void increment(Map<String, Integer> map, String key) {
        Integer v = map.get(key);
        map.put(key, v == null ? 1 : v + 1);
    }

    private List<Map<String, Object>> topN(final Map<String, Integer> map, int n) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Map.Entry<String, Integer> e : map.entrySet()) {
            Map<String, Object> entry = new HashMap<>();
            entry.put("key",   e.getKey());
            entry.put("count", e.getValue());
            list.add(entry);
        }
        Collections.sort(list, new Comparator<Map<String, Object>>() {
            public int compare(Map<String, Object> a, Map<String, Object> b) {
                return ((Integer) b.get("count")).compareTo((Integer) a.get("count"));
            }
        });
        return list.size() > n ? list.subList(0, n) : list;
    }

    // ── CSV parse (Windows tasklist) ──────────────────────────────────────────

    private String[] parseCsvLine(String line) {
        List<String> fields = new ArrayList<>();
        boolean inQuote = false;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                inQuote = !inQuote;
            } else if (c == ',' && !inQuote) {
                fields.add(sb.toString());
                sb = new StringBuilder();
            } else {
                sb.append(c);
            }
        }
        fields.add(sb.toString());
        return fields.toArray(new String[0]);
    }

    // ── utilities ─────────────────────────────────────────────────────────────

    private boolean isWindows(String osOutput) {
        return osOutput == null || osOutput.trim().isEmpty()
                || osOutput.toLowerCase().contains("windows");
    }

    private String strVal(Object obj) {
        return obj != null ? obj.toString() : "";
    }
}
