package org.leo.core.puppet.service;

import org.leo.core.net.Communication;
import org.leo.core.net.layer.RequestLayer;
import org.leo.core.net.layer.ResponseLayer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 操作系统防火墙管理服务（服务器端解析版）。
 * 自动适配 Windows / macOS / Linux（firewalld / ufw / nftables / iptables）。
 */
public class FirewallService extends ComponentService {

    public FirewallService(Communication communication,
                           List<RequestLayer> requestLayers,
                           List<ResponseLayer> responseLayers) {
        super(communication, requestLayers, responseLayers);
    }

    // ── 公共接口 ───────────────────────────────────────────────────────────────

    public Map<String, Object> status() throws Exception {
        String osOutput = execFast("uname -s 2>/dev/null || echo Windows");
        boolean isWindows = isWindows(osOutput);
        boolean isMac = !isWindows && osOutput.toLowerCase().contains("darwin");

        Map<String, Object> detail = new HashMap<>();
        List<String> diagnostics = new ArrayList<>();

        if (isWindows) {
            statusWindows(detail, diagnostics);
        } else if (isMac) {
            statusMacOS(detail, diagnostics);
        } else {
            statusLinux(detail, diagnostics);
        }

        Map<String, Object> data = new HashMap<>();
        data.put("action", "status");
        data.put("os", isWindows ? "windows" : isMac ? "macos" : "linux");
        data.put("detail", detail);
        if (!diagnostics.isEmpty()) data.put("diagnostics", diagnostics);

        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("data", data);
        return result;
    }

    public Map<String, Object> listRules(String direction, String profile) throws Exception {
        String osOutput = execFast("uname -s 2>/dev/null || echo Windows");
        boolean isWindows = isWindows(osOutput);
        boolean isMac = !isWindows && osOutput.toLowerCase().contains("darwin");

        List<Map<String, Object>> rules = new ArrayList<>();
        List<String> diagnostics = new ArrayList<>();
        Map<String, Object> extra = new HashMap<>();

        if (isWindows) {
            listRulesWindows(rules, diagnostics, direction, profile);
        } else if (isMac) {
            listRulesMacOS(rules, diagnostics, extra);
        } else {
            listRulesLinux(rules, diagnostics, direction);
        }

        Map<String, Object> data = new HashMap<>();
        data.put("action", "listRules");
        data.put("os", isWindows ? "windows" : isMac ? "macos" : "linux");
        data.put("total", rules.size());
        data.put("rules", rules);
        data.putAll(extra);
        if (!diagnostics.isEmpty()) data.put("diagnostics", diagnostics);

        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("data", data);
        return result;
    }

    public Map<String, Object> addRule(String ruleName, String direction, String action,
                                       String protocol, String localPort, String remotePort,
                                       String remoteAddress, String rawRule) throws Exception {
        String osOutput = execFast("uname -s 2>/dev/null || echo Windows");
        boolean isWindows = isWindows(osOutput);
        boolean isMac = !isWindows && osOutput.toLowerCase().contains("darwin");

        List<String> diagnostics = new ArrayList<>();
        String output;

        if (isWindows) {
            output = addRuleWindows(ruleName, direction, action, protocol,
                    localPort, remotePort, remoteAddress, diagnostics);
        } else if (isMac) {
            output = "macOS pf rules must be edited via /etc/pf.conf. "
                    + "Use file tools to edit the config, then run: pfctl -f /etc/pf.conf";
            diagnostics.add("macOS pf requires config file editing");
        } else {
            output = addRuleLinux(rawRule, direction, action, protocol,
                    localPort, remoteAddress, diagnostics);
        }

        Map<String, Object> data = new HashMap<>();
        data.put("action", "addRule");
        data.put("os", isWindows ? "windows" : isMac ? "macos" : "linux");
        data.put("output", output != null ? output.trim() : "");
        if (!diagnostics.isEmpty()) data.put("diagnostics", diagnostics);

        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("data", data);
        return result;
    }

    public Map<String, Object> deleteRule(String ruleName, String ruleIndex, String rawRule) throws Exception {
        String osOutput = execFast("uname -s 2>/dev/null || echo Windows");
        boolean isWindows = isWindows(osOutput);
        boolean isMac = !isWindows && osOutput.toLowerCase().contains("darwin");

        List<String> diagnostics = new ArrayList<>();
        String output;

        if (isWindows) {
            if (ruleName == null || ruleName.trim().isEmpty()) {
                return error(400, "ruleName is required for Windows");
            }
            String cmd = "netsh advfirewall firewall delete rule name=\"" + escapeCmd(ruleName) + "\"";
            diagnostics.add("cmd=" + cmd);
            output = execFast(winCmd(cmd));
        } else if (isMac) {
            output = "macOS pf rules must be edited via /etc/pf.conf. "
                    + "Remove the rule line, then run: pfctl -f /etc/pf.conf";
            diagnostics.add("macOS pf requires config file editing");
        } else {
            output = deleteRuleLinux(ruleIndex, rawRule, diagnostics);
        }

        Map<String, Object> data = new HashMap<>();
        data.put("action", "deleteRule");
        data.put("os", isWindows ? "windows" : isMac ? "macos" : "linux");
        data.put("output", output != null ? output.trim() : "");
        if (!diagnostics.isEmpty()) data.put("diagnostics", diagnostics);

        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("data", data);
        return result;
    }

    public Map<String, Object> toggleFirewall(boolean enable) throws Exception {
        String osOutput = execFast("uname -s 2>/dev/null || echo Windows");
        boolean isWindows = isWindows(osOutput);
        boolean isMac = !isWindows && osOutput.toLowerCase().contains("darwin");

        List<String> diagnostics = new ArrayList<>();
        String output;

        if (isWindows) {
            String state = enable ? "on" : "off";
            output = execFast(winCmd("netsh advfirewall set allprofiles state " + state));
            diagnostics.add("netsh advfirewall set allprofiles state " + state);
        } else if (isMac) {
            output = execFast("pfctl " + (enable ? "-e" : "-d") + " 2>&1");
            diagnostics.add("pfctl " + (enable ? "-e" : "-d"));
        } else {
            String tool = detectLinuxFirewallTool(diagnostics);
            if ("ufw".equals(tool)) {
                output = execFast("echo y | ufw " + (enable ? "enable" : "disable") + " 2>&1");
            } else if ("firewalld".equals(tool)) {
                output = execFast("systemctl " + (enable ? "start" : "stop") + " firewalld 2>&1");
            } else {
                output = "iptables does not have a global enable/disable. "
                        + "Use iptables -F to flush all rules (WARNING: removes all rules).";
            }
        }

        Map<String, Object> data = new HashMap<>();
        data.put("action", "toggleFirewall");
        data.put("os", isWindows ? "windows" : isMac ? "macos" : "linux");
        data.put("enable", enable);
        data.put("output", output != null ? output.trim() : "");
        if (!diagnostics.isEmpty()) data.put("diagnostics", diagnostics);

        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("data", data);
        return result;
    }

    // ── status helpers ────────────────────────────────────────────────────────

    private void statusWindows(Map<String, Object> detail, List<String> diagnostics) throws Exception {
        diagnostics.add("source=netsh advfirewall");
        String output = execWithTimeout(winCmd("netsh advfirewall show allprofiles"), 20);
        if (output != null && !output.trim().isEmpty()) {
            detail.put("profiles", truncate(output.trim(), 8192));
            String[] lines = output.split("\n");
            String currentProfile = "";
            for (String line : lines) {
                line = line.trim();
                if (line.endsWith("Profile Settings:") || line.contains("配置文件设置")) {
                    currentProfile = line.replace("Profile Settings:", "").replace("配置文件设置", "").trim();
                }
                if ((line.startsWith("State") || line.startsWith("状态"))) {
                    String key = currentProfile.isEmpty() ? "enabled" : currentProfile + "_enabled";
                    if (line.contains("ON"))  detail.put(key, true);
                    else if (line.contains("OFF")) detail.put(key, false);
                }
            }
        } else {
            diagnostics.add("netsh advfirewall returned empty");
        }
    }

    private void statusMacOS(Map<String, Object> detail, List<String> diagnostics) throws Exception {
        diagnostics.add("source=pfctl");
        String output = execFast("pfctl -s info 2>&1");
        if (output != null && !output.trim().isEmpty()) {
            detail.put("pfInfo", truncate(output.trim(), 4096));
            if (output.contains("Status: Enabled"))  detail.put("enabled", true);
            else if (output.contains("Status: Disabled")) detail.put("enabled", false);
        }
        String socketFilter = execFast(
                "/usr/libexec/ApplicationFirewall/socketfilterfw --getglobalstate 2>/dev/null");
        if (socketFilter != null && !socketFilter.trim().isEmpty()) {
            detail.put("appFirewall", socketFilter.trim());
            diagnostics.add("source+=/usr/libexec/ApplicationFirewall");
        }
    }

    private void statusLinux(Map<String, Object> detail, List<String> diagnostics) throws Exception {
        String tool = detectLinuxFirewallTool(diagnostics);
        detail.put("tool", tool);

        if ("firewalld".equals(tool)) {
            String output = execFast("firewall-cmd --state 2>&1");
            if (output != null) {
                detail.put("state", output.trim());
                detail.put("enabled", "running".equals(output.trim()));
            }
            String zones = execFast("firewall-cmd --get-active-zones 2>/dev/null");
            if (zones != null) detail.put("activeZones", truncate(zones.trim(), 2048));
        } else if ("ufw".equals(tool)) {
            String output = execFast("ufw status verbose 2>/dev/null");
            if (output != null) {
                detail.put("status", truncate(output.trim(), 4096));
                detail.put("enabled", output.contains("Status: active"));
            }
        } else if ("nftables".equals(tool)) {
            String output = execFast("nft list ruleset 2>/dev/null | head -20");
            if (output != null) {
                detail.put("nftStatus", truncate(output.trim(), 4096));
                detail.put("enabled", !output.trim().isEmpty());
            }
        } else {
            String output = execFast("iptables -L -n --line-numbers 2>/dev/null | head -30");
            if (output != null && !output.trim().isEmpty()) {
                detail.put("iptablesStatus", truncate(output.trim(), 4096));
                String count = execFast(
                        "iptables -L -n 2>/dev/null | grep -c '^[0-9]\\|ACCEPT\\|DROP\\|REJECT'");
                if (count != null) detail.put("ruleCount", count.trim());
            } else {
                detail.put("iptablesStatus", "iptables not available or no permission");
            }
        }
    }

    // ── listRules helpers ─────────────────────────────────────────────────────

    private void listRulesWindows(List<Map<String, Object>> rules, List<String> diagnostics,
                                  String direction, String profile) throws Exception {
        StringBuilder cmd = new StringBuilder("netsh advfirewall firewall show rule name=all");
        if ("in".equals(direction))       cmd.append(" dir=in");
        else if ("out".equals(direction)) cmd.append(" dir=out");
        if (profile != null && !profile.isEmpty()) cmd.append(" profile=").append(escapeShell(profile));

        diagnostics.add("source=netsh advfirewall firewall show rule");
        String output = execWithTimeout(winCmd(cmd.toString()), 30);
        if (output == null || output.trim().isEmpty()) {
            diagnostics.add("no rules found or command failed");
            return;
        }

        String[] lines = output.split("\n");
        Map<String, Object> current = null;
        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) {
                if (current != null && !current.isEmpty()) rules.add(current);
                current = null;
                continue;
            }
            if (line.startsWith("---")) continue;
            if (current == null) current = new HashMap<>();
            int si = line.indexOf(':');
            if (si > 0) {
                String key = line.substring(0, si).trim();
                String val = line.substring(si + 1).trim();
                current.put(key, val);
            }
        }
        if (current != null && !current.isEmpty()) rules.add(current);

        if (rules.size() > 500) {
            diagnostics.add("truncated to 500 rules (total=" + rules.size() + ")");
            while (rules.size() > 500) rules.remove(rules.size() - 1);
        }
    }

    private void listRulesMacOS(List<Map<String, Object>> rules, List<String> diagnostics,
                                Map<String, Object> extra) throws Exception {
        diagnostics.add("source=pfctl -s rules");
        String output = execFast("pfctl -s rules 2>/dev/null");
        if (output != null && !output.trim().isEmpty()) {
            int idx = 0;
            for (String line : output.split("\n")) {
                if (rules.size() >= 500) break;
                line = line.trim();
                if (line.isEmpty()) continue;
                idx++;
                Map<String, Object> rule = new HashMap<>();
                rule.put("index", idx);
                rule.put("raw", line);
                if (line.startsWith("pass"))        rule.put("action", "pass");
                else if (line.startsWith("block"))  rule.put("action", "block");
                if (line.contains(" in "))          rule.put("direction", "in");
                else if (line.contains(" out "))    rule.put("direction", "out");
                rules.add(rule);
            }
        }
        String natOutput = execFast("pfctl -s nat 2>/dev/null");
        if (natOutput != null && !natOutput.trim().isEmpty()) {
            diagnostics.add("nat rules available");
            extra.put("natRules", truncate(natOutput.trim(), 4096));
        }
    }

    private void listRulesLinux(List<Map<String, Object>> rules, List<String> diagnostics,
                                String direction) throws Exception {
        String tool = detectLinuxFirewallTool(diagnostics);

        if ("firewalld".equals(tool)) {
            String output = execFast("firewall-cmd --list-all 2>/dev/null");
            if (output != null) {
                diagnostics.add("source=firewall-cmd --list-all");
                Map<String, Object> rule = new HashMap<>();
                rule.put("raw", truncate(output.trim(), 8192));
                rules.add(rule);
            }
            String richOutput = execFast("firewall-cmd --list-rich-rules 2>/dev/null");
            if (richOutput != null && !richOutput.trim().isEmpty()) {
                for (String line : richOutput.split("\n")) {
                    line = line.trim();
                    if (!line.isEmpty()) {
                        Map<String, Object> r = new HashMap<>();
                        r.put("type", "rich-rule");
                        r.put("raw", line);
                        rules.add(r);
                    }
                }
            }
        } else if ("ufw".equals(tool)) {
            String output = execFast("ufw status numbered 2>/dev/null");
            if (output != null) {
                diagnostics.add("source=ufw status numbered");
                for (String line : output.split("\n")) {
                    if (rules.size() >= 500) break;
                    line = line.trim();
                    if (!line.startsWith("[")) continue;
                    Map<String, Object> rule = new HashMap<>();
                    rule.put("raw", line);
                    int bracket = line.indexOf(']');
                    if (bracket > 1) {
                        rule.put("index", line.substring(1, bracket).trim());
                        rule.put("rule",  line.substring(bracket + 1).trim());
                    }
                    rules.add(rule);
                }
            }
        } else if ("nftables".equals(tool)) {
            String output = execFast("nft list ruleset 2>/dev/null");
            if (output != null) {
                diagnostics.add("source=nft list ruleset");
                Map<String, Object> rule = new HashMap<>();
                rule.put("raw", truncate(output.trim(), 16384));
                rules.add(rule);
            }
        } else {
            // iptables
            String chain = "in".equals(direction) ? "INPUT" : "out".equals(direction) ? "OUTPUT" : null;
            String cmd = "iptables -L -n -v --line-numbers"
                    + (chain != null ? " " + chain : "") + " 2>/dev/null";
            diagnostics.add("source=iptables");
            String output = execWithTimeout(cmd, 20);
            if (output != null && !output.trim().isEmpty()) {
                parseIptablesOutput(output, rules);
            }
        }
    }

    private void parseIptablesOutput(String output, List<Map<String, Object>> rules) {
        String currentChain = "";
        for (String line : output.split("\n")) {
            if (rules.size() >= 500) break;
            line = line.trim();
            if (line.startsWith("Chain ")) { currentChain = line; continue; }
            if (line.startsWith("num") || line.startsWith("pkts") || line.isEmpty()) continue;
            String[] cols = line.split("\\s+");
            if (cols.length >= 9) {
                Map<String, Object> rule = new HashMap<>();
                rule.put("chain", currentChain);
                rule.put("num",         cols[0]);
                rule.put("pkts",        cols[1]);
                rule.put("bytes",       cols[2]);
                rule.put("target",      cols[3]);
                rule.put("prot",        cols[4]);
                rule.put("opt",         cols[5]);
                rule.put("in",          cols[6]);
                rule.put("out",         cols[7]);
                rule.put("source",      cols[8]);
                if (cols.length >= 10) rule.put("destination", cols[9]);
                if (cols.length > 10) {
                    StringBuilder extra = new StringBuilder();
                    for (int j = 10; j < cols.length; j++) {
                        if (extra.length() > 0) extra.append(" ");
                        extra.append(cols[j]);
                    }
                    rule.put("extra", extra.toString());
                }
                rules.add(rule);
            }
        }
    }

    // ── addRule helpers ───────────────────────────────────────────────────────

    private String addRuleWindows(String ruleName, String direction, String action, String protocol,
                                   String localPort, String remotePort, String remoteAddress,
                                   List<String> diagnostics) throws Exception {
        if (ruleName == null || ruleName.isEmpty()) ruleName = "LeoRule-" + System.currentTimeMillis();
        if (direction == null) direction = "in";
        if (action == null)    action    = "allow";
        if (protocol == null)  protocol  = "tcp";

        StringBuilder cmd = new StringBuilder("netsh advfirewall firewall add rule");
        cmd.append(" name=\"").append(escapeCmd(ruleName)).append("\"");
        cmd.append(" dir=").append("in".equals(direction) ? "in" : "out");
        cmd.append(" action=").append("allow".equals(action) ? "allow" : "block");
        cmd.append(" protocol=").append(escapeShell(protocol));
        if (localPort  != null && !localPort.isEmpty())   cmd.append(" localport=").append(escapeShell(localPort));
        if (remotePort != null && !remotePort.isEmpty())  cmd.append(" remoteport=").append(escapeShell(remotePort));
        if (remoteAddress != null && !remoteAddress.isEmpty()) cmd.append(" remoteip=").append(escapeShell(remoteAddress));

        diagnostics.add("cmd=" + cmd);
        return execFast(winCmd(cmd.toString()));
    }

    private String addRuleLinux(String rawRule, String direction, String action, String protocol,
                                 String localPort, String remoteAddress,
                                 List<String> diagnostics) throws Exception {
        String tool = detectLinuxFirewallTool(diagnostics);

        if (rawRule != null && !rawRule.isEmpty()) {
            diagnostics.add("using raw rule");
            if ("ufw".equals(tool))      return execFast("ufw " + rawRule + " 2>&1");
            if ("firewalld".equals(tool)) return execFast("firewall-cmd " + rawRule + " 2>&1");
            return execFast("iptables " + rawRule + " 2>&1");
        }

        if ("ufw".equals(tool)) {
            StringBuilder cmd = new StringBuilder("ufw ");
            cmd.append("allow".equals(action) ? "allow" : "deny");
            if ("in".equals(direction))       cmd.append(" in");
            else if ("out".equals(direction)) cmd.append(" out");
            if (protocol != null && localPort != null) {
                cmd.append(" ").append(escapeShell(localPort)).append("/").append(escapeShell(protocol));
            }
            if (remoteAddress != null && !remoteAddress.isEmpty()) {
                cmd.append(" from ").append(escapeShell(remoteAddress));
            }
            diagnostics.add("cmd=ufw ...");
            return execFast(cmd + " 2>&1");
        } else if ("firewalld".equals(tool)) {
            StringBuilder cmd = new StringBuilder("firewall-cmd --permanent");
            if ("allow".equals(action) && localPort != null) {
                cmd.append(" --add-port=").append(escapeShell(localPort))
                   .append("/").append(protocol != null ? escapeShell(protocol) : "tcp");
            } else if ("block".equals(action) && remoteAddress != null) {
                cmd.append(" --add-rich-rule='rule family=ipv4 source address=")
                   .append(escapeShell(remoteAddress)).append(" drop'");
            }
            diagnostics.add("cmd=firewall-cmd ...");
            String out = execFast(cmd + " 2>&1");
            execFast("firewall-cmd --reload 2>/dev/null");
            return out;
        } else {
            StringBuilder cmd = new StringBuilder("iptables -A ");
            cmd.append("out".equals(direction) ? "OUTPUT" : "INPUT");
            if (protocol != null)     cmd.append(" -p ").append(escapeShell(protocol));
            if (localPort != null)    cmd.append(" --dport ").append(escapeShell(localPort));
            if (remoteAddress != null && !remoteAddress.isEmpty()) {
                cmd.append(" -s ").append(escapeShell(remoteAddress));
            }
            cmd.append(" -j ").append("allow".equals(action) ? "ACCEPT" : "DROP");
            diagnostics.add("cmd=iptables ...");
            return execFast(cmd + " 2>&1");
        }
    }

    // ── deleteRule helpers ────────────────────────────────────────────────────

    private String deleteRuleLinux(String ruleIndex, String rawRule, List<String> diagnostics) throws Exception {
        String tool = detectLinuxFirewallTool(diagnostics);

        if (rawRule != null && !rawRule.isEmpty()) {
            if ("ufw".equals(tool))       return execFast("ufw delete " + rawRule + " 2>&1");
            if ("firewalld".equals(tool)) return execFast("firewall-cmd --permanent " + rawRule + " 2>&1");
            return execFast("iptables " + rawRule + " 2>&1");
        }

        if ("ufw".equals(tool) && ruleIndex != null) {
            diagnostics.add("ufw delete by index");
            return execFast("echo y | ufw delete " + escapeShell(ruleIndex) + " 2>&1");
        }

        return "specify ruleIndex (ufw) or rawRule (iptables -D ...) for deletion";
    }

    // ── Linux firewall tool detection ─────────────────────────────────────────

    private String detectLinuxFirewallTool(List<String> diagnostics) throws Exception {
        String check = execFast("which firewall-cmd 2>/dev/null && firewall-cmd --state 2>/dev/null");
        if (check != null && check.contains("running")) {
            if (diagnostics != null) diagnostics.add("detected=firewalld (running)");
            return "firewalld";
        }
        check = execFast("which ufw 2>/dev/null && ufw status 2>/dev/null | head -1");
        if (check != null && (check.contains("Status:") || check.contains("active"))) {
            if (diagnostics != null) diagnostics.add("detected=ufw");
            return "ufw";
        }
        check = execFast("which nft 2>/dev/null");
        if (check != null && check.contains("/nft")) {
            if (diagnostics != null) diagnostics.add("detected=nftables");
            return "nftables";
        }
        if (diagnostics != null) diagnostics.add("detected=iptables (fallback)");
        return "iptables";
    }

    // ── utilities ─────────────────────────────────────────────────────────────

    private boolean isWindows(String osOutput) {
        return osOutput == null || osOutput.trim().isEmpty()
                || osOutput.toLowerCase().contains("windows");
    }

    private String escapeCmd(String s) {
        return s == null ? "" : s.replace("\"", "\\\"");
    }

    /** Allows letters, digits, and common characters used in ports/IPs/protocols. */
    private String escapeShell(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '_' || c == '.' || c == '-' || c == '@'
                    || c == '/' || c == ':' || c == ',' || c == '*') {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max) + "...(truncated)";
    }

    private Map<String, Object> error(int code, String msg) {
        Map<String, Object> result = new HashMap<>();
        result.put("code", code);
        result.put("msg", msg);
        return result;
    }
}
