package org.leo.core.puppet.service;

import org.leo.core.net.Communication;
import org.leo.core.net.layer.RequestLayer;
import org.leo.core.net.layer.ResponseLayer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 操作系统持久化机制枚举服务
 * <p>
 * 内联枚举操作系统中的各种持久化机制（自启动项），跨平台支持：
 * - Windows: Run/RunOnce 注册表、Startup 文件夹、IFEO、WMI 事件订阅、自启动服务
 * - macOS: LaunchDaemons / LaunchAgents（系统+用户+第三方）、login items、cron
 * - Linux: systemd enabled units、cron（用户+系统）、/etc/rc.local、XDG autostart、init.d、profile.d
 */
public class PersistenceService extends ComponentService {

    private static final int OS_WINDOWS = 0;
    private static final int OS_MACOS   = 1;
    private static final int OS_LINUX   = 2;

    public PersistenceService(Communication communication,
                              List<RequestLayer> requestLayers,
                              List<ResponseLayer> responseLayers) {
        super(communication, requestLayers, responseLayers);
    }

    // ==================== public API ====================

    public Map<String, Object> list() throws Exception {
        int osType = detectOS();
        List<Map<String, Object>> entries     = new ArrayList<Map<String, Object>>();
        List<String>              diagnostics = new ArrayList<String>();

        if (osType == OS_WINDOWS) {
            collectWindowsPersistence(entries, diagnostics);
        } else if (osType == OS_MACOS) {
            collectMacOSPersistence(entries, diagnostics);
        } else {
            collectLinuxPersistence(entries, diagnostics);
        }

        Map<String, Object> result = new HashMap<String, Object>();
        result.put("code",   200);
        result.put("action", "list");
        result.put("total",  entries.size());
        result.put("entries", entries);
        result.put("os", osType == OS_WINDOWS ? "Windows" : osType == OS_MACOS ? "macOS" : "Linux");
        if (!diagnostics.isEmpty()) {
            result.put("diagnostics", diagnostics);
        }
        return result;
    }

    public Map<String, Object> query(String name, String type, String path) throws Exception {
        Map<String, Object> result = new HashMap<String, Object>();
        if (name == null && path == null) {
            result.put("code", 400);
            result.put("msg",  "name or path is required for query action");
            return result;
        }

        int osType = detectOS();
        Map<String, Object> detail = new HashMap<String, Object>();

        if (osType == OS_WINDOWS) {
            queryWindows(name, type, path, detail);
        } else if (osType == OS_MACOS) {
            queryMacOS(name, path, detail);
        } else {
            queryLinux(name, type, path, detail);
        }

        if (detail.isEmpty()) {
            result.put("code", 404);
            result.put("msg",  "entry not found");
        } else {
            result.put("code",   200);
            result.put("action", "query");
            result.put("detail", detail);
        }
        return result;
    }

    // ==================== OS detection ====================

    private int detectOS() throws Exception {
        String out = execFast("uname -s 2>/dev/null || echo Windows").trim();
        if (out.startsWith("Windows") || out.isEmpty()) return OS_WINDOWS;
        if ("Darwin".equals(out)) return OS_MACOS;
        return OS_LINUX;
    }

    // ==================== Windows ====================

    private void collectWindowsPersistence(List<Map<String, Object>> entries,
                                           List<String> diagnostics) throws Exception {
        // 1. Run/RunOnce 注册表键
        String[][] regPaths = {
            {"HKLM\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Run",       "registry-run",     "HKLM"},
            {"HKLM\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\RunOnce",   "registry-runonce", "HKLM"},
            {"HKCU\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Run",       "registry-run",     "HKCU"},
            {"HKCU\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\RunOnce",   "registry-runonce", "HKCU"},
            {"HKLM\\SOFTWARE\\Wow6432Node\\Microsoft\\Windows\\CurrentVersion\\Run",     "registry-run",     "HKLM-WOW64"},
            {"HKLM\\SOFTWARE\\Wow6432Node\\Microsoft\\Windows\\CurrentVersion\\RunOnce", "registry-runonce", "HKLM-WOW64"}
        };
        for (int i = 0; i < regPaths.length; i++) {
            collectRegistryRun(regPaths[i][0], regPaths[i][1], regPaths[i][2], entries, diagnostics);
        }

        // 2. Startup 文件夹
        collectStartupFolder(entries, diagnostics);

        // 3. Image File Execution Options (IFEO)
        collectIFEO(entries, diagnostics);

        // 4. WMI 事件订阅
        collectWmiSubscription(entries, diagnostics);

        // 5. 自启动服务
        collectAutoStartServices(entries, diagnostics);
    }

    private void collectRegistryRun(String regPath, String type, String scope,
                                    List<Map<String, Object>> entries,
                                    List<String> diagnostics) throws Exception {
        String output = execFast(winCmd("reg query \"" + regPath + "\" 2>nul"));
        if (output == null || output.trim().isEmpty()) return;

        diagnostics.add("source=reg:" + regPath);
        String[] lines = output.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty() || line.startsWith("HKEY_") || line.startsWith("HKLM") || line.startsWith("HKCU")) {
                continue;
            }
            int regSzIdx = line.indexOf("REG_SZ");
            if (regSzIdx < 0) regSzIdx = line.indexOf("REG_EXPAND_SZ");
            if (regSzIdx < 0) continue;

            String valName = line.substring(0, regSzIdx).trim();
            String command = "";
            String afterType = line.substring(regSzIdx);
            String[] parts = afterType.split("\\s+", 2);
            if (parts.length >= 2) {
                command = parts[1].trim();
            }

            Map<String, Object> entry = new HashMap<String, Object>();
            entry.put("type",      type);
            entry.put("name",      valName);
            entry.put("command",   command);
            entry.put("path",      regPath);
            entry.put("source",    scope);
            entry.put("enabled",   Boolean.TRUE);
            entry.put("suspicious", isSuspiciousPath(command));
            entries.add(entry);
        }
    }

    private void collectStartupFolder(List<Map<String, Object>> entries,
                                      List<String> diagnostics) throws Exception {
        String programData = execFast(winCmd("echo %ProgramData%")).trim();
        if (programData.isEmpty() || programData.contains("%")) programData = "C:\\ProgramData";
        String appData = execFast(winCmd("echo %APPDATA%")).trim();
        if (appData.contains("%")) appData = "";

        String[] folders = {
            programData + "\\Microsoft\\Windows\\Start Menu\\Programs\\Startup",
            appData.isEmpty() ? null : appData + "\\Microsoft\\Windows\\Start Menu\\Programs\\Startup"
        };
        String[] folderScopes = {"all-users", "current-user"};

        for (int i = 0; i < folders.length; i++) {
            if (folders[i] == null) continue;
            String listing = execFast(winCmd("dir /b \"" + folders[i] + "\" 2>nul"));
            if (listing == null || listing.trim().isEmpty()) continue;
            diagnostics.add("source=startup:" + folders[i]);
            String[] files = listing.trim().split("\n");
            for (int j = 0; j < files.length; j++) {
                String fname = files[j].trim();
                if (fname.isEmpty() || "desktop.ini".equalsIgnoreCase(fname)) continue;
                String fullPath = folders[i] + "\\" + fname;
                Map<String, Object> entry = new HashMap<String, Object>();
                entry.put("type",      "startup-folder");
                entry.put("name",      fname);
                entry.put("path",      fullPath);
                entry.put("command",   fullPath);
                entry.put("source",    folderScopes[i]);
                entry.put("enabled",   Boolean.TRUE);
                entry.put("suspicious", isSuspiciousStartupFile(fname));
                entries.add(entry);
            }
        }
    }

    private void collectIFEO(List<Map<String, Object>> entries,
                             List<String> diagnostics) throws Exception {
        String regPath = "HKLM\\SOFTWARE\\Microsoft\\Windows NT\\CurrentVersion\\Image File Execution Options";
        String output = execFast(winCmd("reg query \"" + regPath + "\" /s /v Debugger 2>nul"));
        if (output == null || output.trim().isEmpty()) return;

        diagnostics.add("source=IFEO");
        String[] lines = output.split("\n");
        String currentKey = "";
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.startsWith("HKEY_")) {
                currentKey = line;
            } else if (line.contains("Debugger") && (line.contains("REG_SZ") || line.contains("REG_EXPAND_SZ"))) {
                String debugger = "";
                String[] parts = line.split("\\s+", 3);
                if (parts.length >= 3) {
                    debugger = parts[2].trim();
                }
                if (debugger.isEmpty()) continue;
                String exeName = "";
                int lastSlash = currentKey.lastIndexOf('\\');
                if (lastSlash >= 0) exeName = currentKey.substring(lastSlash + 1);
                Map<String, Object> entry = new HashMap<String, Object>();
                entry.put("type",      "ifeo");
                entry.put("name",      exeName);
                entry.put("command",   debugger);
                entry.put("path",      currentKey);
                entry.put("source",    "IFEO");
                entry.put("enabled",   Boolean.TRUE);
                entry.put("suspicious", Boolean.TRUE);
                entries.add(entry);
            }
        }
    }

    private void collectWmiSubscription(List<Map<String, Object>> entries,
                                        List<String> diagnostics) throws Exception {
        String output = execFast(winCmd(
            "wmic /namespace:\\\\root\\subscription path __EventConsumer get Name,CommandLineTemplate /format:list 2>nul"));
        if (output == null || output.trim().isEmpty()) return;

        diagnostics.add("source=WMI-subscription");
        String name = "";
        String command = "";
        String[] lines = output.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.startsWith("Name=")) {
                name = line.substring(5).trim();
            } else if (line.startsWith("CommandLineTemplate=")) {
                command = line.substring(20).trim();
            }
            if (!name.isEmpty() && !command.isEmpty()) {
                Map<String, Object> entry = new HashMap<String, Object>();
                entry.put("type",      "wmi-subscription");
                entry.put("name",      name);
                entry.put("command",   command);
                entry.put("source",    "WMI");
                entry.put("enabled",   Boolean.TRUE);
                entry.put("suspicious", Boolean.TRUE);
                entries.add(entry);
                name    = "";
                command = "";
            }
        }
    }

    private void collectAutoStartServices(List<Map<String, Object>> entries,
                                          List<String> diagnostics) throws Exception {
        String configOutput = execFast(winCmd(
            "wmic service where \"StartMode='Auto'\" get Name,PathName,StartMode /format:csv 2>nul"));
        if (configOutput == null || configOutput.trim().isEmpty()) return;

        diagnostics.add("source=auto-start-services");
        String[] lines = configOutput.split("\n");
        String[] headers  = null;
        int      startLine = 0;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.contains("Name") && line.contains("PathName")) {
                headers   = line.split(",");
                startLine = i + 1;
                break;
            }
        }
        if (headers == null) return;

        int colName = -1, colPath = -1;
        for (int i = 0; i < headers.length; i++) {
            String h = headers[i].trim();
            if ("Name".equalsIgnoreCase(h))     colName = i;
            else if ("PathName".equalsIgnoreCase(h)) colPath = i;
        }

        for (int i = startLine; i < lines.length && entries.size() < 2000; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) continue;
            String[] cols    = line.split(",");
            String   svcName = (colName >= 0 && colName < cols.length) ? cols[colName].trim() : "";
            String   pathName = (colPath >= 0 && colPath < cols.length) ? cols[colPath].trim() : "";
            if (svcName.isEmpty()) continue;

            Map<String, Object> entry = new HashMap<String, Object>();
            entry.put("type",      "service-autostart");
            entry.put("name",      svcName);
            entry.put("command",   pathName);
            entry.put("source",    "service");
            entry.put("enabled",   Boolean.TRUE);
            entry.put("suspicious", isSuspiciousPath(pathName));
            entries.add(entry);
        }
    }

    // ==================== macOS ====================

    private void collectMacOSPersistence(List<Map<String, Object>> entries,
                                         List<String> diagnostics) throws Exception {
        String homeDir = execFast("echo $HOME").trim();

        // 1. LaunchDaemons / LaunchAgents
        String[][] launchDirs = {
            {"/Library/LaunchDaemons",        "system-daemon"},
            {"/Library/LaunchAgents",         "system-agent"},
            {"/System/Library/LaunchDaemons", "apple-daemon"},
            {"/System/Library/LaunchAgents",  "apple-agent"}
        };
        for (int i = 0; i < launchDirs.length; i++) {
            collectLaunchDir(launchDirs[i][0], launchDirs[i][1], entries, diagnostics);
        }
        if (!homeDir.isEmpty()) {
            collectLaunchDir(homeDir + "/Library/LaunchAgents", "user-agent", entries, diagnostics);
        }

        // 2. Login Items
        collectMacOSLoginItems(entries, diagnostics);

        // 3. cron
        collectCron(entries, diagnostics);
    }

    private void collectLaunchDir(String dirPath, String source,
                                  List<Map<String, Object>> entries,
                                  List<String> diagnostics) throws Exception {
        String listing = execFast("ls -1 \"" + dirPath + "\" 2>/dev/null");
        if (listing == null || listing.trim().isEmpty()) return;

        String[] files      = listing.trim().split("\n");
        int      plistCount = 0;
        for (int i = 0; i < files.length; i++) {
            if (files[i].trim().endsWith(".plist")) plistCount++;
        }
        if (plistCount == 0) return;

        diagnostics.add("source=launchd:" + dirPath + " (" + plistCount + " files)");

        for (int i = 0; i < files.length; i++) {
            String fname = files[i].trim();
            if (!fname.endsWith(".plist")) continue;
            String label    = fname.substring(0, fname.length() - 6);
            String filePath = dirPath + "/" + fname;

            Map<String, Object> entry = new HashMap<String, Object>();
            entry.put("type",   "launchd");
            entry.put("name",   label);
            entry.put("path",   filePath);
            entry.put("source", source);

            String content = execFast("cat \"" + filePath + "\" 2>/dev/null");
            if (content != null && !content.isEmpty()) {
                String program     = extractPlistValue(content, "Program");
                String programArgs = extractPlistArray(content, "ProgramArguments");
                String disabled    = extractPlistBool(content, "Disabled");
                String runAtLoad   = extractPlistBool(content, "RunAtLoad");

                if (programArgs != null && !programArgs.isEmpty()) {
                    entry.put("command", programArgs);
                } else if (program != null) {
                    entry.put("command", program);
                }
                entry.put("enabled", !"true".equals(disabled));
                if (runAtLoad != null) entry.put("runAtLoad", runAtLoad);
            }

            boolean isApple = label.startsWith("com.apple.");
            entry.put("suspicious", !isApple && isSuspiciousLaunchd(label));
            entries.add(entry);
        }
    }

    private void collectMacOSLoginItems(List<Map<String, Object>> entries,
                                        List<String> diagnostics) throws Exception {
        String output = execFast(
            "osascript -e 'tell application \"System Events\" to get the name of every login item' 2>/dev/null");
        if (output == null || output.trim().isEmpty()) return;

        diagnostics.add("source=login-items");
        String[] items = output.trim().split(",");
        for (int i = 0; i < items.length; i++) {
            String name = items[i].trim();
            if (name.isEmpty()) continue;
            Map<String, Object> entry = new HashMap<String, Object>();
            entry.put("type",      "login-item");
            entry.put("name",      name);
            entry.put("source",    "login-items");
            entry.put("enabled",   Boolean.TRUE);
            entry.put("suspicious", Boolean.FALSE);
            entries.add(entry);
        }
    }

    // ==================== Linux ====================

    private void collectLinuxPersistence(List<Map<String, Object>> entries,
                                         List<String> diagnostics) throws Exception {
        collectSystemdEnabled(entries, diagnostics);
        collectCron(entries, diagnostics);
        collectRcLocal(entries, diagnostics);
        collectXdgAutostart(entries, diagnostics);
        collectInitd(entries, diagnostics);
        collectProfiled(entries, diagnostics);
    }

    private void collectSystemdEnabled(List<Map<String, Object>> entries,
                                       List<String> diagnostics) throws Exception {
        String output = execFast(
            "systemctl list-unit-files --type=service --state=enabled --no-pager --no-legend 2>/dev/null");
        if (output == null || output.trim().isEmpty()) return;

        diagnostics.add("source=systemd-enabled");
        String[] lines = output.split("\n");
        for (int i = 0; i < lines.length && entries.size() < 2000; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) continue;
            String[] parts = line.split("\\s+");
            if (parts.length < 2) continue;

            String unitName = parts[0];

            Map<String, Object> entry = new HashMap<String, Object>();
            entry.put("type",   "systemd-enabled");
            entry.put("name",   unitName);
            entry.put("source", "systemd");
            entry.put("enabled", Boolean.TRUE);

            // 尝试获取 ExecStart
            String showOutput = execFast(
                "systemctl show " + escapeShell(unitName) + " --property=ExecStart --no-pager 2>/dev/null");
            if (showOutput != null) {
                int eqIdx = showOutput.indexOf('=');
                if (eqIdx > 0 && eqIdx < showOutput.length() - 1) {
                    String execStart = showOutput.substring(eqIdx + 1).trim();
                    int pathIdx = execStart.indexOf("path=");
                    if (pathIdx >= 0) {
                        int semiIdx = execStart.indexOf(';', pathIdx);
                        if (semiIdx > pathIdx) {
                            execStart = execStart.substring(pathIdx + 5, semiIdx).trim();
                        }
                    }
                    if (!execStart.isEmpty() && !execStart.startsWith("{")) {
                        entry.put("command", execStart);
                    }
                }
            }

            entry.put("suspicious", Boolean.FALSE);
            entries.add(entry);
        }
    }

    private void collectCron(List<Map<String, Object>> entries,
                              List<String> diagnostics) throws Exception {
        // 当前用户 crontab
        String crontab = execFast("crontab -l 2>/dev/null");
        if (crontab != null && !crontab.trim().isEmpty()) {
            diagnostics.add("source=crontab");
            parseCronEntries(crontab, "user-crontab", entries);
        }

        // /etc/crontab
        String etcCrontab = execFast("cat /etc/crontab 2>/dev/null");
        if (etcCrontab != null && !etcCrontab.trim().isEmpty()) {
            diagnostics.add("source=/etc/crontab");
            parseCronEntries(etcCrontab, "/etc/crontab", entries);
        }

        // /etc/cron.d/
        String cronDList = execFast("ls -1 /etc/cron.d/ 2>/dev/null");
        if (cronDList != null && !cronDList.trim().isEmpty()) {
            String[] cronFiles = cronDList.trim().split("\n");
            diagnostics.add("source=/etc/cron.d (" + cronFiles.length + " files)");
            for (int i = 0; i < cronFiles.length; i++) {
                String fname = cronFiles[i].trim();
                if (fname.isEmpty()) continue;
                String content = execFast("cat /etc/cron.d/" + escapeShell(fname) + " 2>/dev/null");
                if (content != null) {
                    parseCronEntries(content, "/etc/cron.d/" + fname, entries);
                }
            }
        }
    }

    private void parseCronEntries(String content, String source,
                                  List<Map<String, Object>> entries) {
        String[] lines = content.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            // 跳过变量赋值
            int eqIdx = line.indexOf('=');
            int spIdx = line.indexOf(' ');
            if (eqIdx > 0 && (spIdx < 0 || eqIdx < spIdx)) continue;

            String[] parts = line.split("\\s+", 7);
            if (parts.length < 6) continue;

            boolean looksCron = true;
            for (int j = 0; j < 5; j++) {
                if (!looksLikeCronField(parts[j])) { looksCron = false; break; }
            }
            if (!looksCron) continue;

            String cronExpr = parts[0] + " " + parts[1] + " " + parts[2] + " " + parts[3] + " " + parts[4];
            String cmdPart;
            String user = null;

            if ("/etc/crontab".equals(source) || source.startsWith("/etc/cron.d/")) {
                if (parts.length >= 7) {
                    user    = parts[5];
                    cmdPart = parts[6];
                } else {
                    cmdPart = parts[5];
                }
            } else {
                StringBuilder sb = new StringBuilder();
                for (int j = 5; j < parts.length; j++) {
                    if (sb.length() > 0) sb.append(" ");
                    sb.append(parts[j]);
                }
                cmdPart = sb.toString();
            }

            Map<String, Object> entry = new HashMap<String, Object>();
            entry.put("type",      "cron");
            entry.put("name",      extractCmdName(cmdPart));
            entry.put("command",   cmdPart);
            entry.put("path",      source);
            entry.put("source",    source);
            entry.put("enabled",   Boolean.TRUE);
            if (user != null) entry.put("user", user);
            entry.put("suspicious", isSuspiciousCronCmd(cmdPart));
            entries.add(entry);
        }
    }

    private void collectRcLocal(List<Map<String, Object>> entries,
                                List<String> diagnostics) throws Exception {
        String content = execFast("cat /etc/rc.local 2>/dev/null");
        if (content == null || content.trim().isEmpty()) return;

        diagnostics.add("source=/etc/rc.local");
        String[] lines = content.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("exit") || line.startsWith("#!/")) continue;

            Map<String, Object> entry = new HashMap<String, Object>();
            entry.put("type",      "rc-local");
            entry.put("name",      extractCmdName(line));
            entry.put("command",   line);
            entry.put("path",      "/etc/rc.local");
            entry.put("source",    "rc.local");
            entry.put("enabled",   Boolean.TRUE);
            entry.put("suspicious", isSuspiciousCronCmd(line));
            entries.add(entry);
        }
    }

    private void collectXdgAutostart(List<Map<String, Object>> entries,
                                     List<String> diagnostics) throws Exception {
        collectDesktopDir("/etc/xdg/autostart", "system-xdg", entries, diagnostics);
        String homeDir = execFast("echo $HOME").trim();
        if (!homeDir.isEmpty()) {
            collectDesktopDir(homeDir + "/.config/autostart", "user-xdg", entries, diagnostics);
        }
    }

    private void collectDesktopDir(String dirPath, String source,
                                   List<Map<String, Object>> entries,
                                   List<String> diagnostics) throws Exception {
        String listing = execFast("ls -1 \"" + dirPath + "\" 2>/dev/null");
        if (listing == null || listing.trim().isEmpty()) return;

        String[] files       = listing.trim().split("\n");
        int      desktopCount = 0;
        for (int i = 0; i < files.length; i++) {
            if (files[i].trim().endsWith(".desktop")) desktopCount++;
        }
        if (desktopCount == 0) return;

        diagnostics.add("source=xdg:" + dirPath + " (" + desktopCount + " files)");

        for (int i = 0; i < files.length; i++) {
            String fname = files[i].trim();
            if (!fname.endsWith(".desktop")) continue;
            String filePath = dirPath + "/" + fname;
            String content  = execFast("cat \"" + filePath + "\" 2>/dev/null");
            if (content == null) continue;

            String name      = extractDesktopEntry(content, "Name");
            String exec      = extractDesktopEntry(content, "Exec");
            String hidden    = extractDesktopEntry(content, "Hidden");

            Map<String, Object> entry = new HashMap<String, Object>();
            entry.put("type",      "xdg-autostart");
            entry.put("name",      name != null ? name : fname);
            entry.put("command",   exec != null ? exec : "");
            entry.put("path",      filePath);
            entry.put("source",    source);
            entry.put("enabled",   !"true".equalsIgnoreCase(hidden));
            entry.put("suspicious", Boolean.FALSE);
            entries.add(entry);
        }
    }

    private void collectInitd(List<Map<String, Object>> entries,
                              List<String> diagnostics) throws Exception {
        String initdListing = execFast("ls -1 /etc/init.d/ 2>/dev/null");
        if (initdListing == null || initdListing.trim().isEmpty()) return;

        // 只收集通过 update-rc.d / chkconfig 启用的
        String rcOutput = execFast("ls /etc/rc2.d/S* 2>/dev/null; ls /etc/rc3.d/S* 2>/dev/null");
        Map<String, Boolean> enabledSet = new HashMap<String, Boolean>();
        if (rcOutput != null) {
            String[] rcFiles = rcOutput.trim().split("\n");
            for (int i = 0; i < rcFiles.length; i++) {
                String name = rcFiles[i].trim();
                int lastSlash = name.lastIndexOf('/');
                if (lastSlash >= 0) name = name.substring(lastSlash + 1);
                // S01xxx -> 去掉前缀 S + 数字
                int startIdx = 1;
                while (startIdx < name.length() && name.charAt(startIdx) >= '0' && name.charAt(startIdx) <= '9') {
                    startIdx++;
                }
                if (startIdx < name.length()) {
                    enabledSet.put(name.substring(startIdx), Boolean.TRUE);
                }
            }
        }
        if (enabledSet.isEmpty()) return;

        diagnostics.add("source=init.d (enabled: " + enabledSet.size() + ")");
        String[] initdFiles = initdListing.trim().split("\n");
        for (int i = 0; i < initdFiles.length; i++) {
            String fname = initdFiles[i].trim();
            if (!enabledSet.containsKey(fname)) continue;

            Map<String, Object> entry = new HashMap<String, Object>();
            entry.put("type",      "init.d");
            entry.put("name",      fname);
            entry.put("path",      "/etc/init.d/" + fname);
            entry.put("source",    "init.d");
            entry.put("enabled",   Boolean.TRUE);
            entry.put("suspicious", Boolean.FALSE);
            entries.add(entry);
        }
    }

    private void collectProfiled(List<Map<String, Object>> entries,
                                 List<String> diagnostics) throws Exception {
        String listing = execFast("ls -1 /etc/profile.d/ 2>/dev/null");
        if (listing == null || listing.trim().isEmpty()) return;

        String[] files     = listing.trim().split("\n");
        int      shCount   = 0;
        for (int i = 0; i < files.length; i++) {
            if (files[i].trim().endsWith(".sh")) shCount++;
        }
        if (shCount == 0) return;

        diagnostics.add("source=/etc/profile.d (" + shCount + " files)");
        for (int i = 0; i < files.length; i++) {
            String fname = files[i].trim();
            if (!fname.endsWith(".sh")) continue;
            String filePath   = "/etc/profile.d/" + fname;
            String execCheck  = execFast("test -x \"" + filePath + "\" && echo 1 || echo 0").trim();
            boolean executable = "1".equals(execCheck);

            Map<String, Object> entry = new HashMap<String, Object>();
            entry.put("type",      "profile.d");
            entry.put("name",      fname);
            entry.put("path",      filePath);
            entry.put("source",    "profile.d");
            entry.put("enabled",   executable);
            entry.put("suspicious", Boolean.FALSE);
            entries.add(entry);
        }
    }

    // ==================== query ====================

    private void queryWindows(String name, String type, String path,
                              Map<String, Object> detail) throws Exception {
        if ("ifeo".equals(type) && path != null) {
            String output = execFast(winCmd("reg query \"" + path + "\" 2>nul"));
            if (output != null && !output.trim().isEmpty()) {
                detail.put("registryOutput", truncate(output, 8192));
            }
        } else if (path != null && path.startsWith("HK")) {
            String output = execFast(winCmd("reg query \"" + path + "\" /v \"" + escapeCmd(name) + "\" 2>nul"));
            if (output != null && !output.trim().isEmpty()) {
                detail.put("registryOutput", truncate(output, 4096));
            }
        } else if ("service-autostart".equals(type) && name != null) {
            String qc = execFast(winCmd("sc qc \"" + escapeCmd(name) + "\" 2>nul"));
            if (qc != null) detail.put("serviceConfig", truncate(qc, 4096));
            String status = execFast(winCmd("sc query \"" + escapeCmd(name) + "\" 2>nul"));
            if (status != null) detail.put("serviceStatus", truncate(status, 4096));
        }
        if (name != null) detail.put("name", name);
        if (type != null) detail.put("type", type);
    }

    private void queryMacOS(String name, String path,
                            Map<String, Object> detail) throws Exception {
        if (path != null && path.endsWith(".plist")) {
            String content = execFast("cat \"" + path + "\" 2>/dev/null");
            if (content != null && !content.isEmpty()) {
                detail.put("plistContent", truncate(content, 8192));
            }
            String label = name;
            if (label == null) {
                int lastSlash = path.lastIndexOf('/');
                label = path.substring(lastSlash + 1);
                if (label.endsWith(".plist")) label = label.substring(0, label.length() - 6);
            }
            String printOut = execFast("launchctl print system/" + escapeShell(label) + " 2>&1");
            if (printOut != null && !printOut.contains("Could not find service")) {
                detail.put("launchctlPrint", truncate(printOut, 8192));
            }
        }
        if (name != null) detail.put("name", name);
    }

    private void queryLinux(String name, String type, String path,
                            Map<String, Object> detail) throws Exception {
        if ("systemd-enabled".equals(type) && name != null) {
            String show = execFast("systemctl show " + escapeShell(name) + " --no-pager 2>/dev/null");
            if (show != null) detail.put("systemctlShow", truncate(show, 8192));
            String status = execFast("systemctl status " + escapeShell(name) + " --no-pager 2>&1");
            if (status != null) detail.put("systemctlStatus", truncate(status, 4096));
        } else if (path != null) {
            String content = execFast("cat \"" + path + "\" 2>/dev/null");
            if (content != null && !content.isEmpty()) {
                detail.put("fileContent", truncate(content, 8192));
            }
        }
        if (name != null) detail.put("name", name);
        if (type != null) detail.put("type", type);
    }

    // ==================== suspicion heuristics ====================

    private boolean isSuspiciousPath(String command) {
        if (command == null) return false;
        String lower = command.toLowerCase();
        return lower.contains("\\temp\\") || lower.contains("\\tmp\\")
                || lower.contains("\\appdata\\local\\temp")
                || lower.contains("\\users\\public\\")
                || (lower.contains("powershell") && lower.contains("-enc"))
                || (lower.contains("cmd") && lower.contains("/c") && lower.contains("http"));
    }

    private boolean isSuspiciousStartupFile(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase();
        return lower.endsWith(".vbs") || lower.endsWith(".bat") || lower.endsWith(".cmd")
                || lower.endsWith(".ps1") || lower.endsWith(".hta") || lower.endsWith(".js")
                || lower.endsWith(".wsh") || lower.endsWith(".wsf");
    }

    private boolean isSuspiciousLaunchd(String label) {
        if (label == null) return false;
        String lower = label.toLowerCase();
        return lower.contains("..") || lower.contains("temp") || lower.contains("tmp")
                || lower.startsWith(".") || lower.length() < 5;
    }

    private boolean isSuspiciousCronCmd(String cmd) {
        if (cmd == null) return false;
        String lower = cmd.toLowerCase();
        return (lower.contains("curl")  && lower.contains("|") && lower.contains("sh"))
                || (lower.contains("wget") && lower.contains("|") && lower.contains("sh"))
                || lower.contains("/tmp/") || lower.contains("/dev/tcp/")
                || (lower.contains("base64") && lower.contains("decode"));
    }

    // ==================== plist helpers ====================

    private String extractPlistValue(String content, String key) {
        String keyTag = "<key>" + key + "</key>";
        int idx = content.indexOf(keyTag);
        if (idx < 0) return null;
        int start = content.indexOf("<string>", idx + keyTag.length());
        if (start < 0) return null;
        start += 8;
        int end = content.indexOf("</string>", start);
        if (end < 0) return null;
        return content.substring(start, end);
    }

    private String extractPlistArray(String content, String key) {
        String keyTag  = "<key>" + key + "</key>";
        int    idx     = content.indexOf(keyTag);
        if (idx < 0) return null;
        int arrStart = content.indexOf("<array>", idx + keyTag.length());
        if (arrStart < 0) return null;
        int arrEnd = content.indexOf("</array>", arrStart);
        if (arrEnd < 0) return null;
        String       arrContent = content.substring(arrStart + 7, arrEnd);
        StringBuilder sb        = new StringBuilder();
        int pos = 0;
        while (pos < arrContent.length()) {
            int sStart = arrContent.indexOf("<string>", pos);
            if (sStart < 0) break;
            sStart += 8;
            int sEnd = arrContent.indexOf("</string>", sStart);
            if (sEnd < 0) break;
            if (sb.length() > 0) sb.append(" ");
            sb.append(arrContent, sStart, sEnd);
            pos = sEnd + 9;
        }
        return sb.toString();
    }

    private String extractPlistBool(String content, String key) {
        String keyTag  = "<key>" + key + "</key>";
        int    idx     = content.indexOf(keyTag);
        if (idx < 0) return null;
        int after    = idx + keyTag.length();
        int trueIdx  = content.indexOf("<true/>",  after);
        int falseIdx = content.indexOf("<false/>", after);
        if (trueIdx >= 0 && (falseIdx < 0 || trueIdx < falseIdx) && trueIdx - after < 50) return "true";
        if (falseIdx >= 0 && falseIdx - after < 50) return "false";
        return null;
    }

    // ==================== .desktop helper ====================

    private String extractDesktopEntry(String content, String key) {
        String[] lines = content.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.startsWith(key + "=")) {
                return line.substring(key.length() + 1).trim();
            }
        }
        return null;
    }

    // ==================== cron helpers ====================

    private boolean looksLikeCronField(String s) {
        if (s == null || s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') continue;
            if (c == '*' || c == '/' || c == '-' || c == ',') continue;
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')) continue;
            return false;
        }
        return true;
    }

    private String extractCmdName(String cmd) {
        if (cmd == null || cmd.isEmpty()) return "(unknown)";
        String first = cmd;
        int sp = cmd.indexOf(' ');
        if (sp > 0) first = cmd.substring(0, sp);
        int slash = first.lastIndexOf('/');
        if (slash >= 0) first = first.substring(slash + 1);
        if (first.length() > 60) first = first.substring(0, 60) + "...";
        return first;
    }

    // ==================== general helpers ====================

    private String escapeCmd(String s) {
        if (s == null) return "";
        return s.replace("\"", "\\\"");
    }

    private String escapeShell(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '_' || c == '.' || c == '-' || c == '@') {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return null;
        if (s.length() <= maxLen) return s;
        return s.substring(0, maxLen) + "...(truncated)";
    }
}
