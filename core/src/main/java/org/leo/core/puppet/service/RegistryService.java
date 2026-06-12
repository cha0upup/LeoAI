package org.leo.core.puppet.service;

import org.leo.core.net.Communication;
import org.leo.core.net.layer.RequestLayer;
import org.leo.core.net.layer.ResponseLayer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Windows 注册表管理服务（服务器端解析版）。
 * 直接通过 execFast / execWithTimeout 执行 reg 命令，在服务端解析输出，
 * 不再依赖 RegistryComponent.payload。
 */
public class RegistryService extends ComponentService {

    public RegistryService(Communication communication,
                           List<RequestLayer> requestLayers,
                           List<ResponseLayer> responseLayers) {
        super(communication, requestLayers, responseLayers);
    }

    // ── 查询 ─────────────────────────────────────────────────────────────────

    public Map<String, Object> query(String keyPath, boolean recursive) throws Exception {
        String cmd = buildCmd("reg query \"" + escape(keyPath) + "\"" + (recursive ? " /s" : ""));
        String output = execFast(cmd);

        List<Map<String, Object>> entries = parseRegOutput(output);
        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("action", "query");
        result.put("keyPath", keyPath);
        result.put("recursive", recursive);
        result.put("total", entries.size());
        result.put("entries", entries);
        return result;
    }

    // ── 搜索 ─────────────────────────────────────────────────────────────────

    public Map<String, Object> search(String keyPath, String pattern, String searchTarget, int maxResults) throws Exception {
        if (keyPath == null || keyPath.isEmpty()) keyPath = "HKLM";
        if (searchTarget == null || searchTarget.isEmpty()) searchTarget = "d";
        if (maxResults <= 0) maxResults = 50;

        String scope = "k".equals(searchTarget) ? " /k" : "v".equals(searchTarget) ? " /v" : " /d";
        String cmd = buildCmd("reg query \"" + escape(keyPath) + "\" /s /f \"" + escape(pattern) + "\"" + scope);
        String output = execWithTimeout(cmd, 30);

        List<Map<String, Object>> entries = parseRegOutput(output);
        if (entries.size() > maxResults) entries = entries.subList(0, maxResults);

        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("action", "search");
        result.put("rootPath", keyPath);
        result.put("pattern", pattern);
        result.put("searchIn", searchTarget);
        result.put("total", entries.size());
        result.put("entries", entries);
        return result;
    }

    // ── 写入 ─────────────────────────────────────────────────────────────────

    public Map<String, Object> add(String keyPath, String valueName, String valueType, String valueData, boolean force) throws Exception {
        if (valueType == null || valueType.isEmpty()) valueType = "REG_SZ";
        if (valueData == null) valueData = "";

        StringBuilder sb = new StringBuilder("reg add \"").append(escape(keyPath)).append("\"");
        if (valueName != null && !valueName.isEmpty()) {
            sb.append(" /v \"").append(escape(valueName)).append("\"");
        } else {
            sb.append(" /ve");
        }
        sb.append(" /t ").append(valueType);
        sb.append(" /d \"").append(escape(valueData)).append("\"");
        if (force) sb.append(" /f");

        String output = execFast(buildCmd(sb.toString()));

        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("action", "set");
        result.put("keyPath", keyPath);
        result.put("valueName", valueName != null && !valueName.isEmpty() ? valueName : "(Default)");
        result.put("valueType", valueType);
        result.put("output", output.trim());
        return result;
    }

    // ── 删除 ─────────────────────────────────────────────────────────────────

    public Map<String, Object> delete(String keyPath, String valueName, boolean force) throws Exception {
        StringBuilder sb = new StringBuilder("reg delete \"").append(escape(keyPath)).append("\"");
        if (valueName != null && !valueName.isEmpty()) {
            sb.append(" /v \"").append(escape(valueName)).append("\"");
        } else {
            sb.append(" /va");
        }
        if (force) sb.append(" /f");

        String output = execFast(buildCmd(sb.toString()));

        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("action", "delete");
        result.put("keyPath", keyPath);
        result.put("valueName", valueName != null && !valueName.isEmpty() ? valueName : "(allValues)");
        result.put("output", output.trim());
        return result;
    }

    // ── 导出 ─────────────────────────────────────────────────────────────────

    public Map<String, Object> export(String keyPath) throws Exception {
        String output = execWithTimeout(buildCmd("reg export \"" + escape(keyPath) + "\" CON /y"), 15);
        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("action", "export");
        result.put("keyPath", keyPath);
        result.put("output", output);
        return result;
    }

    // ── 解析 ─────────────────────────────────────────────────────────────────

    private List<Map<String, Object>> parseRegOutput(String output) {
        List<Map<String, Object>> entries = new ArrayList<>();
        if (output == null || output.trim().isEmpty()) return entries;

        String currentKey = null;
        List<Map<String, Object>> currentValues = null;

        for (String line : output.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;

            if (!line.startsWith(" ") && !line.startsWith("\t")) {
                if (trimmed.startsWith("HKEY_") || trimmed.startsWith("HKLM") ||
                        trimmed.startsWith("HKCU") || trimmed.startsWith("HKCR") ||
                        trimmed.startsWith("HKU") || trimmed.startsWith("HKCC")) {
                    if (currentKey != null) {
                        Map<String, Object> entry = new HashMap<>();
                        entry.put("keyPath", currentKey);
                        entry.put("values", currentValues);
                        entries.add(entry);
                    }
                    currentKey = trimmed;
                    currentValues = new ArrayList<>();
                }
                continue;
            }

            if (currentKey != null) {
                Map<String, Object> val = parseValueLine(trimmed);
                if (val != null) currentValues.add(val);
            }
        }

        if (currentKey != null) {
            Map<String, Object> entry = new HashMap<>();
            entry.put("keyPath", currentKey);
            entry.put("values", currentValues);
            entries.add(entry);
        }
        return entries;
    }

    private Map<String, Object> parseValueLine(String line) {
        String[] REG_TYPES = {
                "REG_SZ", "REG_EXPAND_SZ", "REG_MULTI_SZ",
                "REG_DWORD", "REG_QWORD", "REG_BINARY", "REG_NONE"
        };
        for (String type : REG_TYPES) {
            int idx = line.indexOf(type);
            if (idx > 0) {
                String name = line.substring(0, idx).trim();
                String data = line.substring(idx + type.length()).trim();
                if (name.isEmpty()) name = "(Default)";
                Map<String, Object> entry = new HashMap<>();
                entry.put("name", name);
                entry.put("type", type);
                entry.put("data", data);
                return entry;
            }
        }
        return null;
    }

    // ── 工具方法 ─────────────────────────────────────────────────────────────

    private String buildCmd(String regCmd) {
        return "cmd /c \"chcp 65001 > nul & " + regCmd + "\"";
    }

    private String escape(String s) {
        if (s == null) return "";
        return s.replace("\"", "\\\"");
    }
}
