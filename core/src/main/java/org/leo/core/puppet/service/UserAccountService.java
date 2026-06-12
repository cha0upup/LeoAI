package org.leo.core.puppet.service;

import org.leo.core.net.Communication;
import org.leo.core.net.layer.RequestLayer;
import org.leo.core.net.layer.ResponseLayer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 操作系统用户与组管理服务（服务器端解析版）。
 * 自动适配 Windows / macOS / Linux。
 */
public class UserAccountService extends ComponentService {

    public UserAccountService(Communication communication,
                              List<RequestLayer> requestLayers,
                              List<ResponseLayer> responseLayers) {
        super(communication, requestLayers, responseLayers);
    }

    // ── 公共接口 ───────────────────────────────────────────────────────────────

    public Map<String, Object> listUsers() throws Exception {
        String osOutput = execFast("uname -s 2>/dev/null || echo Windows");
        boolean isWindows = isWindows(osOutput);
        boolean isMac = !isWindows && osOutput.toLowerCase().contains("darwin");

        List<Map<String, Object>> users = new ArrayList<>();
        List<String> diagnostics = new ArrayList<>();

        if (isWindows)    listUsersWindows(users, diagnostics);
        else if (isMac)   listUsersMacOS(users, diagnostics);
        else              listUsersLinux(users, diagnostics);

        Map<String, Object> data = new HashMap<>();
        data.put("action", "listUsers");
        data.put("os", isWindows ? "windows" : isMac ? "macos" : "linux");
        data.put("total", users.size());
        data.put("users", users);
        if (!diagnostics.isEmpty()) data.put("diagnostics", diagnostics);

        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("data", data);
        return result;
    }

    public Map<String, Object> listGroups() throws Exception {
        String osOutput = execFast("uname -s 2>/dev/null || echo Windows");
        boolean isWindows = isWindows(osOutput);
        boolean isMac = !isWindows && osOutput.toLowerCase().contains("darwin");

        List<Map<String, Object>> groups = new ArrayList<>();
        List<String> diagnostics = new ArrayList<>();

        if (isWindows)    listGroupsWindows(groups, diagnostics);
        else if (isMac)   listGroupsMacOS(groups, diagnostics);
        else              listGroupsLinux(groups, diagnostics);

        Map<String, Object> data = new HashMap<>();
        data.put("action", "listGroups");
        data.put("os", isWindows ? "windows" : isMac ? "macos" : "linux");
        data.put("total", groups.size());
        data.put("groups", groups);
        if (!diagnostics.isEmpty()) data.put("diagnostics", diagnostics);

        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("data", data);
        return result;
    }

    public Map<String, Object> queryUser(String username) throws Exception {
        if (username == null || username.trim().isEmpty()) return error(400, "username is required");

        String osOutput = execFast("uname -s 2>/dev/null || echo Windows");
        boolean isWindows = isWindows(osOutput);
        boolean isMac = !isWindows && osOutput.toLowerCase().contains("darwin");

        Map<String, Object> detail = new HashMap<>();
        List<String> diagnostics = new ArrayList<>();

        if (isWindows)    queryUserWindows(username, detail, diagnostics);
        else if (isMac)   queryUserMacOS(username, detail, diagnostics);
        else              queryUserLinux(username, detail, diagnostics);

        Map<String, Object> data = new HashMap<>();
        data.put("action", "queryUser");
        data.put("os", isWindows ? "windows" : isMac ? "macos" : "linux");
        data.put("username", username);
        data.put("detail", detail);
        if (!diagnostics.isEmpty()) data.put("diagnostics", diagnostics);

        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("data", data);
        return result;
    }

    public Map<String, Object> queryGroup(String groupName) throws Exception {
        if (groupName == null || groupName.trim().isEmpty()) return error(400, "groupName is required");

        String osOutput = execFast("uname -s 2>/dev/null || echo Windows");
        boolean isWindows = isWindows(osOutput);
        boolean isMac = !isWindows && osOutput.toLowerCase().contains("darwin");

        Map<String, Object> detail = new HashMap<>();
        List<String> diagnostics = new ArrayList<>();

        if (isWindows)    queryGroupWindows(groupName, detail, diagnostics);
        else if (isMac)   queryGroupMacOS(groupName, detail, diagnostics);
        else              queryGroupLinux(groupName, detail, diagnostics);

        Map<String, Object> data = new HashMap<>();
        data.put("action", "queryGroup");
        data.put("os", isWindows ? "windows" : isMac ? "macos" : "linux");
        data.put("groupName", groupName);
        data.put("detail", detail);
        if (!diagnostics.isEmpty()) data.put("diagnostics", diagnostics);

        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("data", data);
        return result;
    }

    public Map<String, Object> whoami() throws Exception {
        String osOutput = execFast("uname -s 2>/dev/null || echo Windows");
        boolean isWindows = isWindows(osOutput);
        boolean isMac = !isWindows && osOutput.toLowerCase().contains("darwin");

        Map<String, Object> detail = new HashMap<>();
        List<String> diagnostics = new ArrayList<>();

        // Basic identity via shell (more reliable than Java system properties)
        String whoOutput = execFast("whoami 2>/dev/null");
        if (whoOutput != null) detail.put("user", whoOutput.trim());

        if (isWindows)    whoamiWindows(detail, diagnostics);
        else if (isMac)   whoamiUnix(detail, diagnostics, true);
        else              whoamiUnix(detail, diagnostics, false);

        Map<String, Object> data = new HashMap<>();
        data.put("action", "whoami");
        data.put("os", isWindows ? "windows" : isMac ? "macos" : "linux");
        data.put("detail", detail);
        if (!diagnostics.isEmpty()) data.put("diagnostics", diagnostics);

        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("data", data);
        return result;
    }

    // ── listUsers ─────────────────────────────────────────────────────────────

    private void listUsersWindows(List<Map<String, Object>> users, List<String> diagnostics)
            throws Exception {
        String output = execWithTimeout(
                winCmd("wmic useraccount get Name,FullName,Disabled,Status,SID /format:csv"), 30);
        if (output != null && !output.trim().isEmpty() && !output.contains("not recognized")) {
            diagnostics.add("source=wmic useraccount");
            for (String line : output.split("\n")) {
                line = line.trim();
                if (line.isEmpty()) continue;
                // CSV: Node,Disabled,FullName,Name,SID,Status
                String[] cols = line.split(",");
                if (cols.length < 4) continue;
                if ("Node".equalsIgnoreCase(cols[0].trim()) || "Disabled".equalsIgnoreCase(cols[1].trim())) continue;
                Map<String, Object> user = new HashMap<>();
                if (cols.length >= 6) {
                    user.put("disabled", cols[1].trim());
                    user.put("fullName", cols[2].trim());
                    user.put("name",     cols[3].trim());
                    user.put("sid",      cols[4].trim());
                    user.put("status",   cols[5].trim());
                } else {
                    user.put("name", cols[cols.length >= 4 ? 3 : 0].trim());
                }
                if (!strVal(user.get("name")).isEmpty()) users.add(user);
            }
            if (!users.isEmpty()) return;
        }

        // fallback: net user
        diagnostics.add("source=net user");
        output = execFast(winCmd("net user"));
        if (output == null || output.trim().isEmpty()) {
            diagnostics.add("net user returned empty");
            return;
        }
        boolean inList = false;
        for (String line : output.split("\n")) {
            if (line.startsWith("---")) { inList = true; continue; }
            if (!inList) continue;
            if (line.trim().isEmpty() || line.contains("The command")) break;
            for (String name : line.trim().split("\\s{2,}")) {
                name = name.trim();
                if (!name.isEmpty()) {
                    Map<String, Object> u = new HashMap<>();
                    u.put("name", name);
                    users.add(u);
                }
            }
        }
    }

    private void listUsersMacOS(List<Map<String, Object>> users, List<String> diagnostics)
            throws Exception {
        diagnostics.add("source=dscl");
        String output = execFast("dscl . list /Users 2>/dev/null");
        if (output == null || output.trim().isEmpty()) {
            diagnostics.add("dscl list /Users returned empty");
            return;
        }

        for (String name : output.split("\n")) {
            name = name.trim();
            if (name.isEmpty()) continue;
            Map<String, Object> user = new HashMap<>();
            user.put("name", name);
            if (name.startsWith("_")) user.put("system", true);
            users.add(user);
        }

        // supplement with UIDs
        String uidOutput = execFast("dscl . list /Users UniqueID 2>/dev/null");
        if (uidOutput != null) {
            Map<String, String> uidMap = new HashMap<>();
            for (String line : uidOutput.split("\n")) {
                line = line.trim();
                int sp = line.lastIndexOf(' ');
                if (sp > 0) uidMap.put(line.substring(0, sp).trim(), line.substring(sp + 1).trim());
            }
            for (Map<String, Object> u : users) {
                String uid = uidMap.get(strVal(u.get("name")));
                if (uid != null) u.put("uid", uid);
            }
        }
    }

    private void listUsersLinux(List<Map<String, Object>> users, List<String> diagnostics)
            throws Exception {
        String output = execFast("cat /etc/passwd 2>/dev/null");
        if (output != null && !output.trim().isEmpty()) {
            diagnostics.add("source=/etc/passwd");
        } else {
            diagnostics.add("/etc/passwd not readable, trying getent");
            output = execFast("getent passwd 2>/dev/null");
            if (output != null && !output.trim().isEmpty()) diagnostics.add("source=getent passwd");
            else { diagnostics.add("getent passwd also failed"); return; }
        }

        for (String line : output.split("\n")) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            // name:x:uid:gid:gecos:home:shell
            String[] parts = line.split(":");
            if (parts.length < 7) continue;
            Map<String, Object> user = new HashMap<>();
            user.put("name",  parts[0]);
            user.put("uid",   parts[2]);
            user.put("gid",   parts[3]);
            user.put("gecos", parts[4]);
            user.put("home",  parts[5]);
            user.put("shell", parts[6]);
            try {
                int uid = Integer.parseInt(parts[2]);
                if (uid < 1000 && uid != 0) user.put("system", true);
            } catch (NumberFormatException ignored) {}
            users.add(user);
        }
    }

    // ── listGroups ────────────────────────────────────────────────────────────

    private void listGroupsWindows(List<Map<String, Object>> groups, List<String> diagnostics)
            throws Exception {
        diagnostics.add("source=net localgroup");
        String output = execFast(winCmd("net localgroup"));
        if (output == null || output.trim().isEmpty()) {
            diagnostics.add("net localgroup returned empty");
            return;
        }
        boolean inList = false;
        for (String line : output.split("\n")) {
            if (line.startsWith("---")) { inList = true; continue; }
            if (!inList) continue;
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("The command")) break;
            if (t.startsWith("*")) t = t.substring(1).trim();
            if (!t.isEmpty()) {
                Map<String, Object> g = new HashMap<>();
                g.put("name", t);
                groups.add(g);
            }
        }
    }

    private void listGroupsMacOS(List<Map<String, Object>> groups, List<String> diagnostics)
            throws Exception {
        diagnostics.add("source=dscl");
        String output = execFast("dscl . list /Groups PrimaryGroupID 2>/dev/null");
        if (output == null || output.trim().isEmpty()) {
            diagnostics.add("dscl list /Groups returned empty");
            return;
        }
        for (String line : output.split("\n")) {
            line = line.trim();
            if (line.isEmpty()) continue;
            int sp = line.lastIndexOf(' ');
            if (sp > 0) {
                String gname = line.substring(0, sp).trim();
                String gid   = line.substring(sp + 1).trim();
                Map<String, Object> g = new HashMap<>();
                g.put("name", gname);
                g.put("gid",  gid);
                if (gname.startsWith("_")) g.put("system", true);
                groups.add(g);
            }
        }
    }

    private void listGroupsLinux(List<Map<String, Object>> groups, List<String> diagnostics)
            throws Exception {
        String output = execFast("cat /etc/group 2>/dev/null");
        if (output == null || output.trim().isEmpty()) {
            diagnostics.add("/etc/group not readable");
            return;
        }
        diagnostics.add("source=/etc/group");
        for (String line : output.split("\n")) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            // name:x:gid:members
            String[] parts = line.split(":");
            if (parts.length < 3) continue;
            Map<String, Object> g = new HashMap<>();
            g.put("name", parts[0]);
            g.put("gid",  parts[2]);
            if (parts.length > 3 && !parts[3].trim().isEmpty()) g.put("members", parts[3].trim());
            groups.add(g);
        }
    }

    // ── queryUser ─────────────────────────────────────────────────────────────

    private void queryUserWindows(String username, Map<String, Object> detail,
                                  List<String> diagnostics) throws Exception {
        diagnostics.add("source=net user");
        String output = execFast(winCmd("net user \"" + escapeCmd(username) + "\""));
        if (output == null || output.trim().isEmpty()) {
            diagnostics.add("net user returned empty");
            return;
        }
        for (String line : output.split("\n")) {
            line = line.trim();
            if (line.isEmpty()) continue;
            int sep = line.indexOf("  ");
            if (sep > 0) {
                String key = line.substring(0, sep).trim();
                String val = line.substring(sep).trim();
                if (!key.isEmpty() && !val.isEmpty()) detail.put(key, val);
            }
        }
    }

    private void queryUserMacOS(String username, Map<String, Object> detail,
                                List<String> diagnostics) throws Exception {
        diagnostics.add("source=dscl");
        String output = execFast("dscl . read /Users/" + escapeShell(username) + " 2>/dev/null");
        if (output == null || output.trim().isEmpty()) {
            diagnostics.add("dscl read returned empty for " + username);
            return;
        }
        parseDsclRead(output, detail);
    }

    private void queryUserLinux(String username, Map<String, Object> detail,
                                List<String> diagnostics) throws Exception {
        String safe = escapeShell(username);
        String output = execFast("grep '^" + safe + ":' /etc/passwd 2>/dev/null");
        if (output != null && !output.trim().isEmpty()) {
            diagnostics.add("source=/etc/passwd + id");
            String[] parts = output.trim().split(":");
            if (parts.length >= 7) {
                detail.put("name",  parts[0]);
                detail.put("uid",   parts[2]);
                detail.put("gid",   parts[3]);
                detail.put("gecos", parts[4]);
                detail.put("home",  parts[5]);
                detail.put("shell", parts[6]);
            }
        }

        String idOut = execFast("id " + safe + " 2>/dev/null");
        if (idOut != null && !idOut.trim().isEmpty()) detail.put("idInfo", idOut.trim());

        String lastOut = execFast("last -1 " + safe + " 2>/dev/null | head -1");
        if (lastOut != null && !lastOut.trim().isEmpty() && !lastOut.contains("wtmp begins")) {
            detail.put("lastLogin", lastOut.trim());
        }

        // /etc/shadow (requires root)
        String shadowOut = execFast("grep '^" + safe + ":' /etc/shadow 2>/dev/null");
        if (shadowOut != null && !shadowOut.trim().isEmpty()) {
            diagnostics.add("shadow readable");
            String[] sp = shadowOut.trim().split(":");
            if (sp.length >= 2) {
                String hash = sp[1];
                if ("!".equals(hash) || "*".equals(hash) || "!!".equals(hash)) {
                    detail.put("passwordStatus", "locked/disabled");
                } else if (!hash.isEmpty()) {
                    detail.put("passwordStatus", "set (hash length=" + hash.length() + ")");
                    if (hash.startsWith("$6$"))      detail.put("hashType", "SHA-512");
                    else if (hash.startsWith("$5$")) detail.put("hashType", "SHA-256");
                    else if (hash.startsWith("$y$")) detail.put("hashType", "yescrypt");
                    else if (hash.startsWith("$1$")) detail.put("hashType", "MD5");
                    else if (hash.startsWith("$2"))  detail.put("hashType", "bcrypt");
                }
            }
            if (sp.length >= 5) {
                detail.put("lastPasswordChange", sp[2]);
                detail.put("minAge", sp[3]);
                detail.put("maxAge", sp[4]);
            }
        }
    }

    // ── queryGroup ────────────────────────────────────────────────────────────

    private void queryGroupWindows(String groupName, Map<String, Object> detail,
                                   List<String> diagnostics) throws Exception {
        diagnostics.add("source=net localgroup");
        String output = execFast(winCmd("net localgroup \"" + escapeCmd(groupName) + "\""));
        if (output == null || output.trim().isEmpty()) {
            diagnostics.add("net localgroup returned empty");
            return;
        }
        detail.put("name", groupName);
        List<String> members = new ArrayList<>();
        boolean inMembers = false;
        for (String line : output.split("\n")) {
            if (line.contains("Comment")) detail.put("comment", line.substring(line.indexOf("Comment") + 7).trim());
            if (line.startsWith("---")) { inMembers = true; continue; }
            if (!inMembers) continue;
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("The command")) break;
            members.add(t);
        }
        detail.put("members",     members);
        detail.put("memberCount", members.size());
    }

    private void queryGroupMacOS(String groupName, Map<String, Object> detail,
                                 List<String> diagnostics) throws Exception {
        diagnostics.add("source=dscl");
        String output = execFast("dscl . read /Groups/" + escapeShell(groupName) + " 2>/dev/null");
        if (output == null || output.trim().isEmpty()) {
            diagnostics.add("dscl read returned empty for group " + groupName);
            return;
        }
        parseDsclRead(output, detail);
    }

    private void queryGroupLinux(String groupName, Map<String, Object> detail,
                                 List<String> diagnostics) throws Exception {
        diagnostics.add("source=/etc/group");
        String safe = escapeShell(groupName);
        String output = execFast("grep '^" + safe + ":' /etc/group 2>/dev/null");
        if (output != null && !output.trim().isEmpty()) {
            String[] parts = output.trim().split(":");
            detail.put("name", parts[0]);
            if (parts.length >= 3) detail.put("gid", parts[2]);
            if (parts.length >= 4 && !parts[3].trim().isEmpty()) {
                detail.put("members", parts[3].trim());
            } else {
                detail.put("members", "");
            }
        }
        // users with this as primary group
        String gid = strVal(detail.get("gid"));
        if (!gid.isEmpty()) {
            String primaryOut = execFast(
                    "awk -F: '$4==\"" + escapeShell(gid) + "\" {print $1}' /etc/passwd 2>/dev/null");
            if (primaryOut != null && !primaryOut.trim().isEmpty()) {
                detail.put("primaryMembers", primaryOut.trim().replace("\n", ", "));
            }
        }
    }

    // ── whoami ────────────────────────────────────────────────────────────────

    private void whoamiWindows(Map<String, Object> detail, List<String> diagnostics)
            throws Exception {
        String output = execWithTimeout(winCmd("whoami /all"), 15);
        if (output != null && !output.trim().isEmpty()) {
            diagnostics.add("source=whoami /all");
            detail.put("whoamiAll", truncate(output.trim(), 8192));
        }
        // admin check
        String adminCheck = execFast(winCmd("net session 2>&1"));
        if (adminCheck != null) {
            boolean isAdmin = !adminCheck.contains("Access is denied")
                    && !adminCheck.contains("拒绝访问");
            detail.put("isAdmin", isAdmin);
        }
    }

    private void whoamiUnix(Map<String, Object> detail, List<String> diagnostics, boolean isMac)
            throws Exception {
        diagnostics.add("source=id + groups");
        String idOut = execFast("id 2>/dev/null");
        if (idOut != null) detail.put("idInfo", idOut.trim());

        String groupsOut = execFast("groups 2>/dev/null");
        if (groupsOut != null) detail.put("groups", groupsOut.trim());

        if (!isMac) {
            String uidOut = execFast("id -u 2>/dev/null");
            if (uidOut != null) {
                String uid = uidOut.trim();
                detail.put("uid", uid);
                if ("0".equals(uid)) detail.put("isRoot", true);
            }
        } else {
            // macOS admin group check
            if (groupsOut != null && groupsOut.contains("admin")) detail.put("isAdmin", true);
        }

        // sudo no-password check
        String sudoCheck = execFast("sudo -n true 2>&1");
        if (sudoCheck != null && sudoCheck.trim().isEmpty()) detail.put("sudoNoPassword", true);

        // sudo privileges list
        String sudoL = execFast("sudo -n -l 2>/dev/null");
        if (sudoL != null && !sudoL.trim().isEmpty() && !sudoL.contains("not allowed")) {
            detail.put("sudoPrivileges", truncate(sudoL.trim(), 4096));
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /** Parse multi-line dscl output (Key: value / continuation lines). */
    private void parseDsclRead(String output, Map<String, Object> detail) {
        String lastKey = null;
        for (String line : output.split("\n")) {
            if (line.length() > 0 && line.charAt(0) != ' ' && line.contains(":")) {
                int ci = line.indexOf(':');
                String key = line.substring(0, ci).trim();
                String val = line.substring(ci + 1).trim();
                detail.put(key, val);
                lastKey = key;
            } else if (lastKey != null && line.startsWith(" ")) {
                Object prev = detail.get(lastKey);
                if (prev != null) detail.put(lastKey, prev.toString() + " " + line.trim());
            }
        }
    }

    private boolean isWindows(String osOutput) {
        return osOutput == null || osOutput.trim().isEmpty()
                || osOutput.toLowerCase().contains("windows");
    }

    private String escapeCmd(String s) {
        return s == null ? "" : s.replace("\"", "\\\"");
    }

    private String escapeShell(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '_' || c == '.' || c == '-' || c == '@' || c == '/') {
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
