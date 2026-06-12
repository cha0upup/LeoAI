package org.leo.core.puppet.service;

import org.leo.core.net.Communication;
import org.leo.core.net.layer.RequestLayer;
import org.leo.core.net.layer.ResponseLayer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 已安装软件枚举服务（服务器端解析版）。
 * 自动适配 Windows / macOS / Linux。
 */
public class InstalledSoftwareService extends ComponentService {

    public InstalledSoftwareService(Communication communication,
                                    List<RequestLayer> requestLayers,
                                    List<ResponseLayer> responseLayers) {
        super(communication, requestLayers, responseLayers);
    }

    // ── 公共接口 ───────────────────────────────────────────────────────────────

    public Map<String, Object> listAll() throws Exception {
        return collect(0, null);
    }

    public Map<String, Object> listSystem() throws Exception {
        return collect(1, null);
    }

    public Map<String, Object> listUser() throws Exception {
        return collect(2, null);
    }

    public Map<String, Object> searchSoftware(String keyword) throws Exception {
        if (keyword == null || keyword.trim().isEmpty()) {
            return error(400, "keyword is required");
        }
        return collect(3, keyword);
    }

    // ── core logic ────────────────────────────────────────────────────────────

    private Map<String, Object> collect(int op, String keyword) throws Exception {
        String osOutput = execFast("uname -s 2>/dev/null || echo Windows");
        boolean isWindows = isWindows(osOutput);
        boolean isMac = !isWindows && osOutput.toLowerCase().contains("darwin");

        Map<String, Object> data = new HashMap<>();
        data.put("os", isWindows ? "windows" : isMac ? "macos" : "linux");

        if (op == 3) {
            data.put("keyword", keyword);
            data.put("searchResults", searchSoftware(keyword, isWindows, isMac));
        } else {
            if (op == 0 || op == 1) {
                Map<String, Object> system = collectSystem(isWindows, isMac);
                if (!system.isEmpty()) data.put("system", system);
            }
            if (op == 0 || op == 2) {
                Map<String, Object> user = collectUser(isWindows, isMac);
                if (!user.isEmpty()) data.put("user", user);
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("data", data);
        return result;
    }

    // ── system package managers ───────────────────────────────────────────────

    private Map<String, Object> collectSystem(boolean isWindows, boolean isMac) throws Exception {
        Map<String, Object> group = new HashMap<>();
        if (isWindows) {
            Map<String, Object> wmic = collectWmic();
            if (wmic != null) group.put("wmic", wmic);
        } else if (isMac) {
            Map<String, Object> pkgutil = collectPkgutil();
            if (pkgutil != null) group.put("pkgutil", pkgutil);
            Map<String, Object> profiler = collectSystemProfiler();
            if (profiler != null) group.put("systemProfiler", profiler);
        } else {
            Map<String, Object> dpkg = collectDpkg();
            if (dpkg != null) group.put("dpkg", dpkg);
            Map<String, Object> rpm = collectRpm();
            if (rpm != null) group.put("rpm", rpm);
        }
        return group;
    }

    // ── user package managers ─────────────────────────────────────────────────

    private Map<String, Object> collectUser(boolean isWindows, boolean isMac) throws Exception {
        Map<String, Object> group = new HashMap<>();
        if (!isWindows) {
            Map<String, Object> snap = collectSnap();
            if (snap != null) group.put("snap", snap);
            Map<String, Object> flatpak = collectFlatpak();
            if (flatpak != null) group.put("flatpak", flatpak);
        }
        if (isMac) {
            Map<String, Object> brew = collectBrew();
            if (brew != null) group.put("brew", brew);
        }
        Map<String, Object> pip = collectPip();
        if (pip != null) group.put("pip", pip);
        Map<String, Object> npm = collectNpm();
        if (npm != null) group.put("npm", npm);
        Map<String, Object> gem = collectGem();
        if (gem != null) group.put("gem", gem);
        return group;
    }

    // ── Windows ───────────────────────────────────────────────────────────────

    private Map<String, Object> collectWmic() throws Exception {
        String output = execWithTimeout(
                winCmd("wmic product get Name,Version,Vendor /format:csv"), 60);
        if (output != null && !output.trim().isEmpty()) {
            List<Map<String, Object>> packages = new ArrayList<>();
            for (String line : output.split("\n")) {
                line = line.trim();
                if (line.isEmpty()) continue;
                String[] parts = line.split(",");
                // CSV: Node,Name,Vendor,Version
                if (parts.length >= 4 && !"Name".equals(parts[1].trim())) {
                    Map<String, Object> pkg = new HashMap<>();
                    pkg.put("name",    parts[1].trim());
                    pkg.put("vendor",  parts[2].trim());
                    pkg.put("version", parts[3].trim());
                    packages.add(pkg);
                }
            }
            if (!packages.isEmpty()) {
                Map<String, Object> result = new HashMap<>();
                result.put("source",   "wmic product");
                result.put("packages", packages);
                result.put("total",    packages.size());
                return result;
            }
        }

        // fallback: registry
        output = execWithTimeout(
                winCmd("reg query HKLM\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Uninstall /s /v DisplayName"), 30);
        if (output != null && !output.trim().isEmpty()) {
            List<Map<String, Object>> packages = parseRegUninstall(output);
            Map<String, Object> result = new HashMap<>();
            result.put("source",   "registry");
            result.put("raw",      truncate(output, 16384));
            result.put("packages", packages);
            result.put("total",    packages.size());
            return result;
        }
        return null;
    }

    private List<Map<String, Object>> parseRegUninstall(String output) {
        List<Map<String, Object>> packages = new ArrayList<>();
        for (String line : output.split("\n")) {
            line = line.trim();
            if (line.contains("DisplayName") && line.contains("REG_SZ")) {
                int idx = line.indexOf("REG_SZ");
                if (idx > 0) {
                    String name = line.substring(idx + 6).trim();
                    if (!name.isEmpty()) {
                        Map<String, Object> pkg = new HashMap<>();
                        pkg.put("name", name);
                        packages.add(pkg);
                    }
                }
            }
        }
        return packages;
    }

    // ── Linux ─────────────────────────────────────────────────────────────────

    private Map<String, Object> collectDpkg() throws Exception {
        String output = execWithTimeout(
                "dpkg-query -W -f='${Package}\\t${Version}\\t${Status}\\n' 2>/dev/null | head -500", 30);
        if (output == null || output.trim().isEmpty()) return null;
        return parseTabSeparated(output, "dpkg", new String[]{"name", "version", "status"});
    }

    private Map<String, Object> collectRpm() throws Exception {
        String output = execWithTimeout(
                "rpm -qa --queryformat '%{NAME}\\t%{VERSION}-%{RELEASE}\\t%{VENDOR}\\n' 2>/dev/null | head -500", 30);
        if (output == null || output.trim().isEmpty()) return null;
        return parseTabSeparated(output, "rpm", new String[]{"name", "version", "vendor"});
    }

    // ── macOS ─────────────────────────────────────────────────────────────────

    private Map<String, Object> collectPkgutil() throws Exception {
        String output = execFast("pkgutil --pkgs 2>/dev/null | head -200");
        if (output == null || output.trim().isEmpty()) return null;
        List<Map<String, Object>> packages = new ArrayList<>();
        for (String line : output.split("\n")) {
            line = line.trim();
            if (!line.isEmpty()) {
                Map<String, Object> pkg = new HashMap<>();
                pkg.put("name", line);
                packages.add(pkg);
            }
        }
        Map<String, Object> result = new HashMap<>();
        result.put("source",   "pkgutil");
        result.put("packages", packages);
        result.put("total",    packages.size());
        return result;
    }

    private Map<String, Object> collectSystemProfiler() throws Exception {
        String output = execWithTimeout(
                "system_profiler SPApplicationsDataType -detailLevel mini 2>/dev/null | head -200", 30);
        if (output == null || output.trim().isEmpty()) return null;
        Map<String, Object> result = new HashMap<>();
        result.put("source", "system_profiler");
        result.put("raw",    truncate(output, 16384));
        return result;
    }

    // ── user-space package managers ───────────────────────────────────────────

    private Map<String, Object> collectSnap() throws Exception {
        String output = execFast("snap list 2>/dev/null | tail -n +2 | head -100");
        if (output == null || output.trim().isEmpty()) return null;
        return parseSpaceSeparated(output, "snap",
                new String[]{"name", "version", "rev", "tracking", "publisher", "notes"});
    }

    private Map<String, Object> collectFlatpak() throws Exception {
        String output = execFast(
                "flatpak list --columns=application,version,origin 2>/dev/null | head -100");
        if (output == null || output.trim().isEmpty()) return null;
        return parseTabSeparated(output, "flatpak", new String[]{"name", "version", "origin"});
    }

    private Map<String, Object> collectBrew() throws Exception {
        String output = execFast("brew list --versions 2>/dev/null | head -200");
        if (output == null || output.trim().isEmpty()) return null;
        List<Map<String, Object>> packages = new ArrayList<>();
        for (String line : output.split("\n")) {
            line = line.trim();
            if (line.isEmpty()) continue;
            int sp = line.indexOf(' ');
            Map<String, Object> pkg = new HashMap<>();
            if (sp > 0) {
                pkg.put("name",    line.substring(0, sp));
                pkg.put("version", line.substring(sp + 1).trim());
            } else {
                pkg.put("name", line);
            }
            packages.add(pkg);
        }
        Map<String, Object> result = new HashMap<>();
        result.put("source",   "brew");
        result.put("packages", packages);
        result.put("total",    packages.size());
        return result;
    }

    private Map<String, Object> collectPip() throws Exception {
        String output = execFast("pip list --format=columns 2>/dev/null | tail -n +3 | head -100");
        if (output == null || output.trim().isEmpty()) {
            output = execFast("pip3 list --format=columns 2>/dev/null | tail -n +3 | head -100");
        }
        if (output == null || output.trim().isEmpty()) return null;
        return parseSpaceSeparated(output, "pip", new String[]{"name", "version"});
    }

    private Map<String, Object> collectNpm() throws Exception {
        String output = execFast("npm list -g --depth=0 2>/dev/null | tail -n +2 | head -100");
        if (output == null || output.trim().isEmpty()) return null;
        List<Map<String, Object>> packages = new ArrayList<>();
        for (String line : output.split("\n")) {
            line = line.trim();
            if (line.length() < 4) continue;
            String pkgStr = line;
            if (pkgStr.startsWith("+--") || pkgStr.startsWith("`--") || pkgStr.startsWith("\\--")) {
                pkgStr = pkgStr.substring(4).trim();
            }
            int at = pkgStr.lastIndexOf('@');
            Map<String, Object> pkg = new HashMap<>();
            if (at > 0) {
                pkg.put("name",    pkgStr.substring(0, at));
                pkg.put("version", pkgStr.substring(at + 1));
            } else {
                pkg.put("name", pkgStr);
            }
            packages.add(pkg);
        }
        Map<String, Object> result = new HashMap<>();
        result.put("source",   "npm-global");
        result.put("packages", packages);
        result.put("total",    packages.size());
        return result;
    }

    private Map<String, Object> collectGem() throws Exception {
        String output = execFast("gem list --local 2>/dev/null | head -100");
        if (output == null || output.trim().isEmpty()) return null;
        List<Map<String, Object>> packages = new ArrayList<>();
        for (String line : output.split("\n")) {
            line = line.trim();
            if (line.isEmpty()) continue;
            // format: name (version, version)
            int paren = line.indexOf('(');
            Map<String, Object> pkg = new HashMap<>();
            if (paren > 0) {
                pkg.put("name", line.substring(0, paren).trim());
                String ver = line.substring(paren + 1);
                if (ver.endsWith(")")) ver = ver.substring(0, ver.length() - 1);
                pkg.put("version", ver.trim());
            } else {
                pkg.put("name", line);
            }
            packages.add(pkg);
        }
        Map<String, Object> result = new HashMap<>();
        result.put("source",   "gem");
        result.put("packages", packages);
        result.put("total",    packages.size());
        return result;
    }

    // ── search ────────────────────────────────────────────────────────────────

    private Map<String, Object> searchSoftware(String keyword, boolean isWindows, boolean isMac)
            throws Exception {
        // Sanitize keyword for shell use
        String safe = keyword.replaceAll("[^a-zA-Z0-9 _\\-.+]", "");
        Map<String, Object> result = new HashMap<>();
        result.put("keyword", keyword);

        if (isWindows) {
            String output = execWithTimeout(
                    winCmd("wmic product where \"Name like '%" + safe + "%'\" get Name,Version,Vendor /format:csv"), 60);
            if (output != null && !output.trim().isEmpty()) {
                result.put("source", "wmic");
                result.put("raw",    truncate(output, 8192));
            }
        } else if (isMac) {
            String brew = execFast("brew list --versions 2>/dev/null | grep -i '" + safe + "' | head -50");
            if (brew != null && !brew.trim().isEmpty()) result.put("brew", truncate(brew, 4096));
            String pkg = execFast("pkgutil --pkgs 2>/dev/null | grep -i '" + safe + "' | head -50");
            if (pkg != null && !pkg.trim().isEmpty()) result.put("pkgutil", truncate(pkg, 4096));
        } else {
            String dpkg = execFast("dpkg -l 2>/dev/null | grep -i '" + safe + "' | head -50");
            if (dpkg != null && !dpkg.trim().isEmpty()) result.put("dpkg", truncate(dpkg, 4096));
            String rpm = execFast("rpm -qa 2>/dev/null | grep -i '" + safe + "' | head -50");
            if (rpm != null && !rpm.trim().isEmpty()) result.put("rpm", truncate(rpm, 4096));
        }
        return result;
    }

    // ── parse helpers ─────────────────────────────────────────────────────────

    private Map<String, Object> parseSpaceSeparated(String output, String source, String[] fields) {
        List<Map<String, Object>> packages = new ArrayList<>();
        for (String line : output.split("\n")) {
            line = line.trim();
            if (line.isEmpty()) continue;
            String[] parts = line.split("\\s+");
            Map<String, Object> pkg = new HashMap<>();
            for (int j = 0; j < fields.length && j < parts.length; j++) {
                pkg.put(fields[j], parts[j]);
            }
            packages.add(pkg);
        }
        Map<String, Object> result = new HashMap<>();
        result.put("source",   source);
        result.put("packages", packages);
        result.put("total",    packages.size());
        return result;
    }

    private Map<String, Object> parseTabSeparated(String output, String source, String[] fields) {
        List<Map<String, Object>> packages = new ArrayList<>();
        for (String line : output.split("\n")) {
            line = line.trim();
            if (line.isEmpty()) continue;
            String[] parts = line.split("\t");
            Map<String, Object> pkg = new HashMap<>();
            for (int j = 0; j < fields.length && j < parts.length; j++) {
                pkg.put(fields[j], parts[j].trim());
            }
            packages.add(pkg);
        }
        Map<String, Object> result = new HashMap<>();
        result.put("source",   source);
        result.put("packages", packages);
        result.put("total",    packages.size());
        return result;
    }

    // ── utilities ─────────────────────────────────────────────────────────────

    private boolean isWindows(String osOutput) {
        return osOutput == null || osOutput.trim().isEmpty()
                || osOutput.toLowerCase().contains("windows");
    }

    private String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s
                : s.substring(0, max) + "\n... (truncated, total " + s.length() + " chars)";
    }

    private Map<String, Object> error(int code, String msg) {
        Map<String, Object> result = new HashMap<>();
        result.put("code", code);
        result.put("msg", msg);
        return result;
    }
}
