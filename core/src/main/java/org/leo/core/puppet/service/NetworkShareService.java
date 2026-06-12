package org.leo.core.puppet.service;

import org.leo.core.net.Communication;
import org.leo.core.net.layer.RequestLayer;
import org.leo.core.net.layer.ResponseLayer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 操作系统网络共享管理服务（服务器端解析版）。
 * 自动适配 Windows / macOS / Linux。
 */
public class NetworkShareService extends ComponentService {

    private static final int OS_WINDOWS = 0;
    private static final int OS_MACOS   = 1;
    private static final int OS_LINUX   = 2;

    public NetworkShareService(Communication communication,
                               List<RequestLayer> requestLayers,
                               List<ResponseLayer> responseLayers) {
        super(communication, requestLayers, responseLayers);
    }

    // ── OS detection ──────────────────────────────────────────────────────────

    private int detectOS() throws Exception {
        String out = execFast("uname -s 2>/dev/null || echo Windows");
        if (out == null || out.trim().isEmpty() || out.toLowerCase().contains("windows")) return OS_WINDOWS;
        String s = out.trim().toLowerCase();
        if (s.contains("darwin")) return OS_MACOS;
        return OS_LINUX;
    }

    // ── Public interface ──────────────────────────────────────────────────────

    public Map<String, Object> listShares() throws Exception {
        int osType = detectOS();
        List<Map<String, Object>> shareList = new ArrayList<Map<String, Object>>();

        Map<String, Object> result = new HashMap<String, Object>();
        if (osType == OS_WINDOWS) {
            listSharesWindows(shareList);
        } else if (osType == OS_MACOS) {
            listSharesMacOS(shareList, result);
        } else {
            listSharesLinux(shareList, result);
        }

        result.put("code",   200);
        result.put("shares", shareList);
        result.put("total",  shareList.size());
        return result;
    }

    public Map<String, Object> listMounts() throws Exception {
        int osType = detectOS();
        List<Map<String, Object>> mountList = new ArrayList<Map<String, Object>>();

        if (osType == OS_WINDOWS) {
            listMountsWindows(mountList);
        } else {
            listMountsUnix(mountList);
        }

        Map<String, Object> result = new HashMap<String, Object>();
        result.put("code",   200);
        result.put("mounts", mountList);
        result.put("total",  mountList.size());
        return result;
    }

    public Map<String, Object> queryShare(String shareName) throws Exception {
        if (shareName == null || shareName.trim().isEmpty()) return error(400, "shareName is required");
        int osType = detectOS();

        Map<String, Object> detail = new HashMap<String, Object>();
        if (osType == OS_WINDOWS) {
            String output = execFast(winCmd("net share \"" + escapeCmd(shareName) + "\""));
            if (output != null && !output.trim().isEmpty()) parseNetShareDetail(output, detail);
        } else if (osType == OS_MACOS) {
            String output = execFast("sharing -l 2>/dev/null");
            if (output != null) parseShareDetailFromSharing(output, shareName, detail);
        } else {
            String smbConf = execFast("cat /etc/samba/smb.conf 2>/dev/null");
            if (smbConf != null && !smbConf.trim().isEmpty())
                parseShareDetailFromSambaConf(smbConf, shareName, detail);
            String usOutput = execFast("net usershare info '" + shareName.replace("'", "'\\''") + "' 2>/dev/null");
            if (usOutput != null && !usOutput.trim().isEmpty()) detail.put("usershareInfo", usOutput.trim());
        }

        if (detail.isEmpty()) return error(404, "share not found: " + shareName);
        detail.put("name", shareName);

        Map<String, Object> result = new HashMap<String, Object>();
        result.put("code",   200);
        result.put("detail", detail);
        return result;
    }

    public Map<String, Object> connectShare(String remotePath, String localDrive,
                                             String mountPoint, String username,
                                             String password) throws Exception {
        if (remotePath == null || remotePath.trim().isEmpty())
            return error(400, "remotePath is required (e.g. \\\\server\\share or //server/share)");

        int osType = detectOS();
        String output;

        if (osType == OS_WINDOWS) {
            StringBuilder cmd = new StringBuilder("net use");
            if (localDrive != null && !localDrive.isEmpty()) cmd.append(" ").append(localDrive);
            cmd.append(" \"").append(escapeCmd(remotePath)).append("\"");
            if (password != null && !password.isEmpty()) cmd.append(" \"").append(escapeCmd(password)).append("\"");
            if (username != null && !username.isEmpty()) cmd.append(" /user:\"").append(escapeCmd(username)).append("\"");
            cmd.append(" /persistent:no");
            output = execFast(winCmd(cmd.toString()));
        } else {
            if (mountPoint == null || mountPoint.trim().isEmpty())
                return error(400, "mountPoint is required on Unix (e.g. /mnt/share)");

            boolean isNfs = remotePath.contains(":");
            StringBuilder cmd = new StringBuilder("mount");
            cmd.append(" -t ").append(isNfs ? "nfs" : "cifs");
            if (!isNfs && username != null && !username.isEmpty()) {
                String opts = "username=" + username;
                if (password != null && !password.isEmpty()) opts += ",password=" + password;
                cmd.append(" -o '").append(opts).append("'");
            }
            cmd.append(" '").append(remotePath.replace("'", "'\\''")).append("'");
            cmd.append(" '").append(mountPoint.replace("'", "'\\''")).append("'");
            output = execFast(cmd.toString());
        }

        Map<String, Object> result = new HashMap<String, Object>();
        result.put("code", 200);
        result.put("msg",  "connect command executed");
        if (output != null && !output.trim().isEmpty()) result.put("output", output.trim());
        return result;
    }

    public Map<String, Object> disconnectShare(String target) throws Exception {
        if (target == null || target.trim().isEmpty())
            return error(400, "target is required (Windows: drive letter or \\\\server\\share, Unix: mount point)");

        int osType = detectOS();
        String output;
        if (osType == OS_WINDOWS) {
            output = execFast(winCmd("net use \"" + escapeCmd(target) + "\" /delete /yes"));
        } else {
            output = execFast("umount '" + target.replace("'", "'\\''") + "' 2>&1");
        }

        Map<String, Object> result = new HashMap<String, Object>();
        result.put("code", 200);
        result.put("msg",  "disconnect command executed");
        if (output != null && !output.trim().isEmpty()) result.put("output", output.trim());
        return result;
    }

    // ── listShares implementations ────────────────────────────────────────────

    private void listSharesWindows(List<Map<String, Object>> shareList) throws Exception {
        // wmic share get .../format:csv
        String output = execFast(winCmd("wmic share get Name,Path,Description,Type,Status /format:csv"));
        if (output != null && !output.trim().isEmpty()) {
            for (String line : output.split("\\r?\\n")) {
                line = line.trim();
                if (line.isEmpty()) continue;
                String[] parts = line.split(",", -1);
                if (parts.length < 6) continue;
                if ("Node".equalsIgnoreCase(parts[0].trim())) continue;

                Map<String, Object> share = new HashMap<String, Object>();
                share.put("description", parts[1].trim());
                share.put("name",        parts[2].trim());
                share.put("path",        parts[3].trim());
                share.put("status",      parts[4].trim());
                share.put("type",        parseWindowsShareType(parts[5].trim()));
                shareList.add(share);
            }
        }

        // fallback: net share
        if (shareList.isEmpty()) {
            String netOutput = execFast(winCmd("net share"));
            if (netOutput != null) parseNetShareOutput(netOutput, shareList);
        }
    }

    private String parseWindowsShareType(String typeCode) {
        try {
            long t = Long.parseLong(typeCode);
            long base = t & 0xFFFFFFF;
            if (base == 0) return "Disk";
            if (base == 1) return "Printer";
            if (base == 2) return "Device";
            if (base == 3) return "IPC";
            return "Type(" + typeCode + ")";
        } catch (NumberFormatException e) {
            return typeCode;
        }
    }

    private void parseNetShareOutput(String output, List<Map<String, Object>> shareList) {
        boolean headerPassed = false;
        for (String line : output.split("\\r?\\n")) {
            if (line.startsWith("---")) { headerPassed = true; continue; }
            if (!headerPassed) continue;
            if (line.trim().isEmpty()) continue;
            if (line.startsWith("The command completed")) break;

            String shareName = line.length() > 0  ? line.substring(0, Math.min(13, line.length())).trim() : "";
            String resource  = line.length() > 13 ? line.substring(13, Math.min(39, line.length())).trim() : "";
            String remark    = line.length() > 39 ? line.substring(39).trim() : "";

            if (!shareName.isEmpty()) {
                Map<String, Object> share = new HashMap<String, Object>();
                share.put("name",        shareName);
                share.put("path",        resource);
                share.put("description", remark);
                shareList.add(share);
            }
        }
    }

    private void listSharesMacOS(List<Map<String, Object>> shareList,
                                  Map<String, Object> result) throws Exception {
        String output = execFast("sharing -l 2>/dev/null");
        if (output != null && !output.trim().isEmpty()) parseSharingOutput(output, shareList);

        // Check for smb.conf
        String check = execFast("test -f /etc/smb.conf && echo /etc/smb.conf || (test -f /usr/local/etc/smb.conf && echo /usr/local/etc/smb.conf) 2>/dev/null");
        if (check != null && !check.trim().isEmpty()) result.put("smbConfPath", check.trim());
    }

    private void parseSharingOutput(String output, List<Map<String, Object>> shareList) {
        Map<String, Object> current = null;
        for (String line : output.split("\\r?\\n")) {
            line = line.trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("name:")) {
                if (current != null) shareList.add(current);
                current = new HashMap<String, Object>();
                current.put("name", line.substring(5).trim());
            } else if (current != null) {
                int colonIdx = line.indexOf(':');
                if (colonIdx > 0) {
                    String key = line.substring(0, colonIdx).trim().toLowerCase();
                    String val = line.substring(colonIdx + 1).trim();
                    if ("path".equals(key))          current.put("path", val);
                    else if ("afp".equals(key))      current.put("afp", val);
                    else if ("smb".equals(key))      current.put("smb", val);
                    else if ("ftp".equals(key))      current.put("ftp", val);
                    else if ("guest access".equals(key)) current.put("guestAccess", val);
                }
            }
        }
        if (current != null) shareList.add(current);
    }

    private void listSharesLinux(List<Map<String, Object>> shareList,
                                  Map<String, Object> result) throws Exception {
        // 1. Samba config
        String smbConf = execFast("cat /etc/samba/smb.conf 2>/dev/null");
        if (smbConf != null && !smbConf.trim().isEmpty()) {
            parseSambaConfig(smbConf, shareList);
            result.put("smbConfPath", "/etc/samba/smb.conf");
        }

        // 2. NFS exports
        String exports = execFast("cat /etc/exports 2>/dev/null");
        if (exports != null && !exports.trim().isEmpty()) {
            parseNfsExports(exports, shareList);
            result.put("nfsExportsPath", "/etc/exports");
        }

        // 3. usershare
        String usershare = execFast("net usershare list 2>/dev/null");
        if (usershare != null && !usershare.trim().isEmpty()) {
            for (String name : usershare.trim().split("\\r?\\n")) {
                name = name.trim();
                if (!name.isEmpty()) {
                    Map<String, Object> share = new HashMap<String, Object>();
                    share.put("name",   name);
                    share.put("source", "usershare");
                    shareList.add(share);
                }
            }
        }
    }

    private void parseSambaConfig(String content, List<Map<String, Object>> shareList) {
        Map<String, Object> current = null;
        String currentName = null;
        for (String line : content.split("\\r?\\n")) {
            line = line.trim();
            if (line.isEmpty() || line.charAt(0) == '#' || line.charAt(0) == ';') continue;

            if (line.charAt(0) == '[' && line.indexOf(']') > 0) {
                if (current != null && currentName != null
                        && !"global".equalsIgnoreCase(currentName)
                        && !"printers".equalsIgnoreCase(currentName)
                        && !"print$".equalsIgnoreCase(currentName)) {
                    current.put("source", "samba");
                    shareList.add(current);
                }
                currentName = line.substring(1, line.indexOf(']')).trim();
                current = new HashMap<String, Object>();
                current.put("name", currentName);
                continue;
            }

            if (current != null) {
                int eqIdx = line.indexOf('=');
                if (eqIdx > 0) {
                    String key = line.substring(0, eqIdx).trim().toLowerCase();
                    String val = line.substring(eqIdx + 1).trim();
                    if ("path".equals(key))                                   current.put("path", val);
                    else if ("comment".equals(key))                           current.put("description", val);
                    else if ("browseable".equals(key) || "browsable".equals(key)) current.put("browseable", val);
                    else if ("read only".equals(key))                         current.put("readOnly", val);
                    else if ("guest ok".equals(key))                          current.put("guestOk", val);
                    else if ("valid users".equals(key))                       current.put("validUsers", val);
                    else if ("writable".equals(key) || "writeable".equals(key)) current.put("writable", val);
                }
            }
        }
        if (current != null && currentName != null
                && !"global".equalsIgnoreCase(currentName)
                && !"printers".equalsIgnoreCase(currentName)
                && !"print$".equalsIgnoreCase(currentName)) {
            current.put("source", "samba");
            shareList.add(current);
        }
    }

    private void parseNfsExports(String content, List<Map<String, Object>> shareList) {
        for (String line : content.split("\\r?\\n")) {
            line = line.trim();
            if (line.isEmpty() || line.charAt(0) == '#') continue;
            String[] parts = line.split("\\s+");
            if (parts.length > 0) {
                Map<String, Object> share = new HashMap<String, Object>();
                share.put("name",   parts[0]);
                share.put("path",   parts[0]);
                share.put("source", "nfs");
                if (parts.length > 1) {
                    List<String> clients = new ArrayList<String>();
                    for (int i = 1; i < parts.length; i++) clients.add(parts[i]);
                    share.put("clients", clients);
                }
                shareList.add(share);
            }
        }
    }

    // ── listMounts implementations ────────────────────────────────────────────

    private void listMountsWindows(List<Map<String, Object>> mountList) throws Exception {
        String output = execFast(winCmd("net use"));
        if (output == null || output.trim().isEmpty()) return;

        boolean headerPassed = false;
        for (String line : output.split("\\r?\\n")) {
            if (line.startsWith("---")) { headerPassed = true; continue; }
            if (!headerPassed) continue;
            if (line.trim().isEmpty()) continue;
            if (line.startsWith("The command completed")) break;

            String[] parts = line.trim().split("\\s+");
            if (parts.length >= 3) {
                Map<String, Object> mount = new HashMap<String, Object>();
                mount.put("status", parts[0]);
                if (parts[1].contains(":") || parts[1].contains("*")) {
                    mount.put("local",   parts[1]);
                    mount.put("remote",  parts[2]);
                    if (parts.length >= 4) mount.put("network", parts[3]);
                } else {
                    mount.put("remote", parts[1]);
                    if (parts.length >= 3) mount.put("network", parts[2]);
                }
                mountList.add(mount);
            }
        }
    }

    private void listMountsUnix(List<Map<String, Object>> mountList) throws Exception {
        String output = execFast("mount 2>/dev/null");
        if (output != null && !output.trim().isEmpty()) {
            for (String line : output.split("\\r?\\n")) {
                line = line.trim();
                if (line.isEmpty()) continue;
                String lower = line.toLowerCase();
                if (lower.contains("cifs") || lower.contains("smb") || lower.contains("nfs")
                        || lower.contains("smbfs") || lower.contains("afpfs")) {
                    int onIdx   = line.indexOf(" on ");
                    int typeIdx = line.indexOf(" type ");
                    if (onIdx > 0) {
                        Map<String, Object> mount = new HashMap<String, Object>();
                        mount.put("remote", line.substring(0, onIdx).trim());
                        if (typeIdx > onIdx) {
                            mount.put("mountPoint", line.substring(onIdx + 4, typeIdx).trim());
                            String rest = line.substring(typeIdx + 6).trim();
                            int spaceIdx = rest.indexOf(' ');
                            if (spaceIdx > 0) {
                                mount.put("fsType", rest.substring(0, spaceIdx));
                                int parenStart = rest.indexOf('('), parenEnd = rest.lastIndexOf(')');
                                if (parenStart >= 0 && parenEnd > parenStart)
                                    mount.put("options", rest.substring(parenStart + 1, parenEnd));
                            } else {
                                mount.put("fsType", rest);
                            }
                        } else {
                            mount.put("mountPoint", line.substring(onIdx + 4).trim());
                        }
                        mountList.add(mount);
                    }
                }
            }
        }

        // Supplement with /etc/fstab network mounts
        String fstab = execFast("cat /etc/fstab 2>/dev/null");
        if (fstab != null && !fstab.trim().isEmpty()) {
            for (String line : fstab.split("\\r?\\n")) {
                line = line.trim();
                if (line.isEmpty() || line.charAt(0) == '#') continue;
                String lower = line.toLowerCase();
                if (lower.contains("cifs") || lower.contains("nfs") || lower.contains("smbfs")) {
                    String[] parts = line.split("\\s+");
                    if (parts.length >= 3) {
                        Map<String, Object> mount = new HashMap<String, Object>();
                        mount.put("remote",     parts[0]);
                        mount.put("mountPoint", parts[1]);
                        mount.put("fsType",     parts[2]);
                        mount.put("source",     "fstab");
                        if (parts.length >= 4) mount.put("options", parts[3]);
                        mountList.add(mount);
                    }
                }
            }
        }
    }

    // ── queryShare helpers ────────────────────────────────────────────────────

    private void parseNetShareDetail(String output, Map<String, Object> detail) {
        for (String line : output.split("\\r?\\n")) {
            line = line.trim();
            if (line.isEmpty()) continue;
            int firstSpace = line.indexOf(' ');
            if (firstSpace > 0) {
                String key = line.substring(0, firstSpace).trim();
                String val = line.substring(firstSpace).trim();
                if ("Share".equalsIgnoreCase(key) && val.startsWith("name")) {
                    detail.put("shareName", val.substring(4).trim());
                } else if ("Path".equalsIgnoreCase(key))       detail.put("path", val);
                else if ("Remark".equalsIgnoreCase(key))       detail.put("remark", val);
                else if ("Type".equalsIgnoreCase(key))         detail.put("type", val);
                else if ("Maximum".equalsIgnoreCase(key))      detail.put("maxUsers", val);
                else if ("Users".equalsIgnoreCase(key))        detail.put("currentUsers", val);
                else if ("Caching".equalsIgnoreCase(key))      detail.put("caching", val);
                else if ("Permission".equalsIgnoreCase(key))   detail.put("permission", val);
            }
        }
        detail.put("rawOutput", output);
    }

    private void parseShareDetailFromSharing(String output, String shareName,
                                              Map<String, Object> detail) {
        boolean found = false;
        for (String line : output.split("\\r?\\n")) {
            line = line.trim();
            if (line.startsWith("name:")) {
                String name = line.substring(5).trim();
                found = name.equalsIgnoreCase(shareName);
                if (found) detail.put("name", name);
            } else if (found) {
                if (line.isEmpty()) break;
                int colonIdx = line.indexOf(':');
                if (colonIdx > 0) {
                    detail.put(line.substring(0, colonIdx).trim().toLowerCase(),
                               line.substring(colonIdx + 1).trim());
                }
            }
        }
    }

    private void parseShareDetailFromSambaConf(String content, String shareName,
                                                Map<String, Object> detail) {
        boolean inTarget = false;
        for (String line : content.split("\\r?\\n")) {
            line = line.trim();
            if (line.isEmpty() || line.charAt(0) == '#' || line.charAt(0) == ';') continue;
            if (line.charAt(0) == '[' && line.indexOf(']') > 0) {
                String section = line.substring(1, line.indexOf(']')).trim();
                if (inTarget) break;
                inTarget = section.equalsIgnoreCase(shareName);
                continue;
            }
            if (inTarget) {
                int eqIdx = line.indexOf('=');
                if (eqIdx > 0) {
                    detail.put(line.substring(0, eqIdx).trim(), line.substring(eqIdx + 1).trim());
                }
            }
        }
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    private String escapeCmd(String s) {
        if (s == null) return "";
        return s.replace("\"", "\\\"");
    }

    private Map<String, Object> error(int code, String msg) {
        Map<String, Object> result = new HashMap<String, Object>();
        result.put("code", code);
        result.put("msg",  msg);
        return result;
    }
}
