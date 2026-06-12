package org.leo.core.puppet.service;

import org.leo.core.net.Communication;
import org.leo.core.net.layer.RequestLayer;
import org.leo.core.net.layer.ResponseLayer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 操作系统计划任务管理服务（服务器端解析版）。
 * 自动适配 Windows / Linux。
 * Windows: schtasks.exe
 * Linux: crontab / systemd timer / at
 */
public class ScheduledTaskService extends ComponentService {

    public ScheduledTaskService(Communication communication,
                                List<RequestLayer> requestLayers,
                                List<ResponseLayer> responseLayers) {
        super(communication, requestLayers, responseLayers);
    }

    // ── 公共接口 ───────────────────────────────────────────────────────────────

    public Map<String, Object> list() throws Exception {
        String osOutput = execFast("uname -s 2>/dev/null || echo Windows");
        boolean isWindows = isWindows(osOutput);

        List<String> diagnostics = new ArrayList<String>();
        List<Map<String, Object>> tasks;
        if (isWindows) {
            tasks = listWindows(diagnostics);
        } else {
            tasks = listLinux(diagnostics);
        }

        Map<String, Object> data = new HashMap<String, Object>();
        data.put("os", isWindows ? "windows" : "linux");
        data.put("total", tasks.size());
        data.put("tasks", tasks);
        if (!diagnostics.isEmpty()) {
            data.put("diagnostics", diagnostics);
        }

        Map<String, Object> result = new HashMap<String, Object>();
        result.put("code", 200);
        result.put("data", data);
        return result;
    }

    public Map<String, Object> query(String taskName) throws Exception {
        if (taskName == null || taskName.trim().isEmpty()) {
            return error(400, "taskName is required");
        }

        String osOutput = execFast("uname -s 2>/dev/null || echo Windows");
        boolean isWindows = isWindows(osOutput);

        if (isWindows) {
            return queryWindows(taskName);
        } else {
            return queryLinux(taskName);
        }
    }

    public Map<String, Object> createWindows(String taskName, String command, String schedule,
                                              String modifier, String startTime, String startDate,
                                              String runAs, boolean force) throws Exception {
        if (taskName == null || taskName.trim().isEmpty()) {
            return error(400, "taskName is required");
        }
        if (command == null || command.trim().isEmpty()) {
            return error(400, "command is required");
        }
        if (schedule == null || schedule.trim().isEmpty()) {
            return error(400, "schedule is required (MINUTE, HOURLY, DAILY, WEEKLY, MONTHLY, ONCE, ONSTART, ONLOGON, ONIDLE)");
        }

        StringBuilder sb = new StringBuilder();
        sb.append("schtasks /Create");
        sb.append(" /TN \"").append(escapeCmd(taskName)).append("\"");
        sb.append(" /TR \"").append(escapeCmd(command)).append("\"");
        sb.append(" /SC ").append(schedule.toUpperCase());

        if (modifier != null && !modifier.isEmpty()) {
            sb.append(" /MO ").append(modifier);
        }
        if (startTime != null && !startTime.isEmpty()) {
            sb.append(" /ST ").append(startTime);
        }
        if (startDate != null && !startDate.isEmpty()) {
            sb.append(" /SD ").append(startDate);
        }
        if (runAs != null && !runAs.isEmpty()) {
            sb.append(" /RU \"").append(escapeCmd(runAs)).append("\"");
        }
        if (force) {
            sb.append(" /F");
        }

        String output = execFast(winCmd(sb.toString()));

        Map<String, Object> data = new HashMap<String, Object>();
        data.put("taskName", taskName);
        data.put("command", command);
        data.put("schedule", schedule);
        data.put("output", output != null ? output.trim() : "");

        Map<String, Object> result = new HashMap<String, Object>();
        result.put("code", 200);
        result.put("action", "create");
        result.put("data", data);
        return result;
    }

    public Map<String, Object> createLinux(String cronExpression, String command) throws Exception {
        if (command == null || command.trim().isEmpty()) {
            return error(400, "command is required");
        }
        if (cronExpression == null || cronExpression.trim().isEmpty()) {
            return error(400, "cronExpression is required (e.g. '*/5 * * * *')");
        }

        // Append to current user crontab:
        // (crontab -l 2>/dev/null; echo "cronExpr command") | crontab -
        String escapedCmd = command.replace("\"", "\\\"");
        String escapedCron = cronExpression.replace("\"", "\\\"");
        String addCmd = "(crontab -l 2>/dev/null; echo \"" + escapedCron + " " + escapedCmd + "\") | crontab -";
        String output = execFast(addCmd);

        Map<String, Object> data = new HashMap<String, Object>();
        data.put("cronExpression", cronExpression);
        data.put("command", command);
        data.put("output", output != null ? output.trim() : "added to crontab");

        Map<String, Object> result = new HashMap<String, Object>();
        result.put("code", 200);
        result.put("action", "create");
        result.put("data", data);
        return result;
    }

    public Map<String, Object> delete(String taskName) throws Exception {
        if (taskName == null || taskName.trim().isEmpty()) {
            return error(400, "taskName is required");
        }

        String osOutput = execFast("uname -s 2>/dev/null || echo Windows");
        boolean isWindows = isWindows(osOutput);

        String output;
        if (isWindows) {
            output = execFast(winCmd("schtasks /Delete /TN \"" + escapeCmd(taskName) + "\" /F"));
        } else {
            String escapedName = taskName.replace("'", "'\\''");
            output = execFast("crontab -l 2>/dev/null | grep -v '" + escapedName + "' | crontab -");
            if (output == null || output.trim().isEmpty()) {
                output = "removed matching lines from crontab";
            }
        }

        Map<String, Object> data = new HashMap<String, Object>();
        data.put("taskName", taskName);
        data.put("output", output != null ? output.trim() : "");

        Map<String, Object> result = new HashMap<String, Object>();
        result.put("code", 200);
        result.put("action", "delete");
        result.put("data", data);
        return result;
    }

    public Map<String, Object> run(String taskName) throws Exception {
        if (taskName == null || taskName.trim().isEmpty()) {
            return error(400, "taskName is required");
        }

        String osOutput = execFast("uname -s 2>/dev/null || echo Windows");
        boolean isWindows = isWindows(osOutput);

        if (!isWindows) {
            return error(400, "Linux 不支持直接运行 cron 任务，请使用 command 工具手动执行");
        }

        String output = execFast(winCmd("schtasks /Run /TN \"" + escapeCmd(taskName) + "\""));

        Map<String, Object> data = new HashMap<String, Object>();
        data.put("taskName", taskName);
        data.put("output", output != null ? output.trim() : "");

        Map<String, Object> result = new HashMap<String, Object>();
        result.put("code", 200);
        result.put("action", "run");
        result.put("data", data);
        return result;
    }

    public Map<String, Object> enable(String taskName) throws Exception {
        return toggleWindows(taskName, true);
    }

    public Map<String, Object> disable(String taskName) throws Exception {
        return toggleWindows(taskName, false);
    }

    // ── Windows ───────────────────────────────────────────────────────────────

    private Map<String, Object> toggleWindows(String taskName, boolean enable) throws Exception {
        if (taskName == null || taskName.trim().isEmpty()) {
            return error(400, "taskName is required");
        }

        String osOutput = execFast("uname -s 2>/dev/null || echo Windows");
        if (!isWindows(osOutput)) {
            return error(400, "enable/disable 仅在 Windows 上支持；Linux 请直接编辑 crontab 注释行");
        }

        String flag = enable ? "/Enable" : "/Disable";
        String output = execFast(winCmd("schtasks /Change /TN \"" + escapeCmd(taskName) + "\" " + flag));

        Map<String, Object> data = new HashMap<String, Object>();
        data.put("taskName", taskName);
        data.put("output", output != null ? output.trim() : "");

        Map<String, Object> result = new HashMap<String, Object>();
        result.put("code", 200);
        result.put("action", enable ? "enable" : "disable");
        result.put("data", data);
        return result;
    }

    private List<Map<String, Object>> listWindows(List<String> diagnostics) throws Exception {
        List<Map<String, Object>> tasks = new ArrayList<Map<String, Object>>();

        String output = execWithTimeout(winCmd("schtasks /Query /FO CSV /V"), 30);
        if (output == null || output.trim().isEmpty()) {
            diagnostics.add("schtasks /Query returned empty");
            return tasks;
        }

        diagnostics.add("source=schtasks");
        String[] lines = output.split("\n");

        // First non-empty quoted line is the header
        String[] headers = null;
        int startLine = 0;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.length() > 0 && line.startsWith("\"")) {
                headers = parseCsvLine(line);
                startLine = i + 1;
                break;
            }
        }

        if (headers == null) {
            diagnostics.add("no CSV header found");
            return tasks;
        }

        // Locate key columns
        int colTaskName = -1, colNextRun = -1, colStatus = -1;
        int colLogonMode = -1, colLastRun = -1, colLastResult = -1;
        int colAuthor = -1, colTaskToRun = -1, colScheduleType = -1;
        for (int i = 0; i < headers.length; i++) {
            String h = headers[i].trim().toLowerCase();
            if (h.contains("taskname") || h.contains("任务名"))         colTaskName    = i;
            else if (h.contains("next run") || h.contains("下次运行"))   colNextRun     = i;
            else if (h.contains("status") || h.contains("状态"))         colStatus      = i;
            else if (h.contains("logon mode") || h.contains("登录模式")) colLogonMode   = i;
            else if (h.contains("last run time") || h.contains("上次运行")) colLastRun  = i;
            else if (h.contains("last result") || h.contains("上次结果")) colLastResult = i;
            else if (h.contains("author") || h.contains("创建者"))       colAuthor      = i;
            else if (h.contains("task to run") || h.contains("要运行的任务")) colTaskToRun = i;
            else if (h.contains("schedule type") || h.contains("计划类型")) colScheduleType = i;
        }

        for (int i = startLine; i < lines.length && tasks.size() < 2000; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) continue;
            String[] cols = parseCsvLine(line);
            if (cols.length <= 1) continue;

            Map<String, Object> task = new HashMap<String, Object>();
            if (colTaskName >= 0 && colTaskName < cols.length)
                task.put("taskName",     cols[colTaskName].trim());
            if (colNextRun >= 0 && colNextRun < cols.length)
                task.put("nextRun",      cols[colNextRun].trim());
            if (colStatus >= 0 && colStatus < cols.length)
                task.put("status",       cols[colStatus].trim());
            if (colLastRun >= 0 && colLastRun < cols.length)
                task.put("lastRun",      cols[colLastRun].trim());
            if (colLastResult >= 0 && colLastResult < cols.length)
                task.put("lastResult",   cols[colLastResult].trim());
            if (colAuthor >= 0 && colAuthor < cols.length)
                task.put("author",       cols[colAuthor].trim());
            if (colTaskToRun >= 0 && colTaskToRun < cols.length)
                task.put("command",      cols[colTaskToRun].trim());
            if (colScheduleType >= 0 && colScheduleType < cols.length)
                task.put("scheduleType", cols[colScheduleType].trim());
            if (colLogonMode >= 0 && colLogonMode < cols.length)
                task.put("logonMode",    cols[colLogonMode].trim());

            // Skip blank task names
            Object tn = task.get("taskName");
            if (tn == null || tn.toString().trim().isEmpty()) continue;
            tasks.add(task);
        }

        return tasks;
    }

    private Map<String, Object> queryWindows(String taskName) throws Exception {
        String output = execWithTimeout(
                winCmd("schtasks /Query /TN \"" + escapeCmd(taskName) + "\" /FO LIST /V"), 30);
        if (output == null || output.trim().isEmpty()) {
            return error(404, "task not found: " + taskName);
        }

        // Parse LIST format: "Property:     Value"
        Map<String, Object> detail = new HashMap<String, Object>();
        for (String line : output.split("\n")) {
            line = line.trim();
            int colonIdx = line.indexOf(':');
            if (colonIdx > 0 && colonIdx < line.length() - 1) {
                String key = line.substring(0, colonIdx).trim();
                String val = line.substring(colonIdx + 1).trim();
                if (!key.isEmpty() && !val.isEmpty()) {
                    detail.put(key, val);
                }
            }
        }

        Map<String, Object> data = new HashMap<String, Object>();
        data.put("taskName", taskName);
        data.put("detail",   detail);
        data.put("rawOutput", truncate(output, 8192));

        Map<String, Object> result = new HashMap<String, Object>();
        result.put("code",   200);
        result.put("action", "query");
        result.put("data",   data);
        return result;
    }

    // ── Linux ─────────────────────────────────────────────────────────────────

    private List<Map<String, Object>> listLinux(List<String> diagnostics) throws Exception {
        List<Map<String, Object>> tasks = new ArrayList<Map<String, Object>>();

        // 1. Current user crontab
        String crontab = execFast("crontab -l 2>/dev/null");
        if (crontab != null && !crontab.trim().isEmpty()) {
            diagnostics.add("source=crontab -l");
            parseCrontabEntries(crontab, "user-crontab", tasks);
        } else {
            diagnostics.add("crontab -l: empty or no crontab");
        }

        // 2. System cron (/etc/crontab)
        String etcCrontab = execFast("cat /etc/crontab 2>/dev/null");
        if (etcCrontab != null && !etcCrontab.trim().isEmpty()) {
            diagnostics.add("source=/etc/crontab");
            parseCrontabEntries(etcCrontab, "/etc/crontab", tasks);
        }

        // 3. /etc/cron.d/ directory
        String cronDList = execFast("ls -1 /etc/cron.d/ 2>/dev/null");
        if (cronDList != null && !cronDList.trim().isEmpty()) {
            String[] cronFiles = cronDList.trim().split("\n");
            diagnostics.add("source=/etc/cron.d (" + cronFiles.length + " files)");
            for (String fname : cronFiles) {
                fname = fname.trim();
                if (fname.isEmpty()) continue;
                String content = execFast("cat /etc/cron.d/" + fname + " 2>/dev/null");
                if (content != null && !content.trim().isEmpty()) {
                    parseCrontabEntries(content, "/etc/cron.d/" + fname, tasks);
                }
            }
        }

        // 4. systemd timers (if available)
        String timers = execFast("systemctl list-timers --all --no-pager 2>/dev/null");
        if (timers != null && !timers.trim().isEmpty()) {
            diagnostics.add("source=systemctl list-timers");
            parseSystemdTimers(timers, tasks);
        }

        // 5. at queue
        String atq = execFast("atq 2>/dev/null");
        if (atq != null && !atq.trim().isEmpty()) {
            diagnostics.add("source=atq");
            parseAtQueue(atq, tasks);
        }

        return tasks;
    }

    private Map<String, Object> queryLinux(String taskName) throws Exception {
        List<String> diagnostics = new ArrayList<String>();
        List<Map<String, Object>> allTasks = listLinux(diagnostics);
        String lowerName = taskName.toLowerCase();

        List<Map<String, Object>> matched = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> task : allTasks) {
            String name = strVal(task.get("taskName"));
            String cmd  = strVal(task.get("command"));
            if (name.toLowerCase().contains(lowerName) || cmd.toLowerCase().contains(lowerName)) {
                matched.add(task);
            }
        }

        Map<String, Object> data = new HashMap<String, Object>();
        data.put("taskName", taskName);
        data.put("total",    matched.size());
        data.put("tasks",    matched);

        Map<String, Object> result = new HashMap<String, Object>();
        result.put("code",   200);
        result.put("action", "query");
        result.put("data",   data);
        return result;
    }

    // ── Crontab / systemd / at parsers ────────────────────────────────────────

    private void parseCrontabEntries(String content, String source,
                                     List<Map<String, Object>> tasks) {
        String[] lines = content.split("\n");
        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;

            // Skip variable assignment lines (e.g. SHELL=/bin/bash, PATH=...)
            if ((line.contains("=") && !line.contains(" "))
                    || (line.indexOf('=') < line.indexOf(' ')
                        && line.indexOf('=') > 0
                        && line.indexOf('=') < 30)) {
                int eqIdx = line.indexOf('=');
                if (eqIdx > 0 && eqIdx < line.indexOf(' ')) {
                    continue;
                }
            }

            // cron line: min hour dom month dow [user] command
            String[] parts = line.split("\\s+", 7);
            if (parts.length < 6) continue;

            // Validate first 5 fields look like cron fields
            boolean looksCron = true;
            for (int j = 0; j < 5 && j < parts.length; j++) {
                if (!looksLikeCronField(parts[j])) {
                    looksCron = false;
                    break;
                }
            }
            if (!looksCron) continue;

            String cronExpr = parts[0] + " " + parts[1] + " " + parts[2] + " " + parts[3] + " " + parts[4];
            String cmdPart;
            String user = null;

            // /etc/crontab and /etc/cron.d/ have an extra "user" field
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

            Map<String, Object> task = new HashMap<String, Object>();
            task.put("taskName",       extractTaskName(cmdPart));
            task.put("cronExpression", cronExpr);
            task.put("command",        cmdPart);
            task.put("source",         source);
            task.put("type",           "cron");
            task.put("schedule",       describeCron(cronExpr));
            if (user != null) {
                task.put("user", user);
            }
            tasks.add(task);
        }
    }

    private void parseSystemdTimers(String output, List<Map<String, Object>> tasks) {
        for (String line : output.split("\n")) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("NEXT") || line.contains("timers listed")) continue;

            int timerIdx = line.indexOf(".timer");
            if (timerIdx < 0) continue;

            String beforeTimer = line.substring(0, timerIdx + 6);
            String[] beforeParts = beforeTimer.trim().split("\\s+");
            String timerUnit = beforeParts[beforeParts.length - 1];

            String serviceUnit = "";
            int serviceIdx = line.indexOf(".service");
            if (serviceIdx > 0) {
                String afterTimer = line.substring(timerIdx + 6).trim();
                for (String part : afterTimer.split("\\s+")) {
                    if (part.endsWith(".service")) {
                        serviceUnit = part;
                        break;
                    }
                }
            }

            Map<String, Object> task = new HashMap<String, Object>();
            task.put("taskName", timerUnit);
            task.put("type",     "systemd-timer");
            task.put("source",   "systemd");
            task.put("command",  serviceUnit);
            tasks.add(task);
        }
    }

    private void parseAtQueue(String output, List<Map<String, Object>> tasks) {
        for (String line : output.split("\n")) {
            line = line.trim();
            if (line.isEmpty()) continue;
            String[] parts = line.split("\\s+");
            if (parts.length < 2) continue;

            Map<String, Object> task = new HashMap<String, Object>();
            task.put("taskName", "at-job-" + parts[0]);
            task.put("type",     "at");
            task.put("source",   "atq");
            task.put("schedule", line);
            tasks.add(task);
        }
    }

    // ── Parse helpers ─────────────────────────────────────────────────────────

    private String[] parseCsvLine(String line) {
        List<String> fields = new ArrayList<String>();
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

    private boolean looksLikeCronField(String s) {
        if (s == null || s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') continue;
            if (c == '*' || c == '/' || c == '-' || c == ',') continue;
            if (c >= 'A' && c <= 'Z') continue;  // MON, TUE, etc.
            if (c >= 'a' && c <= 'z') continue;
            return false;
        }
        return true;
    }

    private String extractTaskName(String cmd) {
        if (cmd == null || cmd.isEmpty()) return "(unknown)";
        String first = cmd;
        int sp = cmd.indexOf(' ');
        if (sp > 0) first = cmd.substring(0, sp);
        int slash = first.lastIndexOf('/');
        if (slash >= 0) first = first.substring(slash + 1);
        if (first.length() > 60) first = first.substring(0, 60) + "...";
        return first;
    }

    private String describeCron(String cronExpr) {
        if (cronExpr == null) return "";
        String[] parts = cronExpr.split("\\s+");
        if (parts.length < 5) return cronExpr;

        String min = parts[0], hour = parts[1], dom = parts[2], mon = parts[3], dow = parts[4];

        if ("*".equals(min) && "*".equals(hour) && "*".equals(dom) && "*".equals(mon) && "*".equals(dow)) {
            return "Every minute";
        }
        if (!"*".equals(min) && "*".equals(hour) && "*".equals(dom) && "*".equals(mon) && "*".equals(dow)) {
            if (min.startsWith("*/")) return "Every " + min.substring(2) + " minutes";
        }
        if (!"*".equals(min) && !"*".equals(hour) && "*".equals(dom) && "*".equals(mon) && "*".equals(dow)) {
            return "Daily at " + hour + ":" + padZero(min);
        }
        if (!"*".equals(min) && !"*".equals(hour) && "*".equals(dom) && "*".equals(mon) && !"*".equals(dow)) {
            return "Weekly (dow=" + dow + ") at " + hour + ":" + padZero(min);
        }
        if (!"*".equals(min) && !"*".equals(hour) && !"*".equals(dom) && "*".equals(mon) && "*".equals(dow)) {
            return "Monthly on day " + dom + " at " + hour + ":" + padZero(min);
        }
        if ("@reboot".equals(cronExpr)) return "On reboot";
        return cronExpr;
    }

    private String padZero(String s) {
        if (s != null && s.length() == 1) return "0" + s;
        return s != null ? s : "00";
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    private boolean isWindows(String osOutput) {
        return osOutput == null || osOutput.trim().isEmpty()
                || osOutput.toLowerCase().contains("windows");
    }

    private String escapeCmd(String s) {
        if (s == null) return "";
        return s.replace("\"", "\\\"");
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return null;
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...(truncated)";
    }

    private String strVal(Object obj) {
        return obj != null ? obj.toString() : "";
    }

    private Map<String, Object> error(int code, String msg) {
        Map<String, Object> result = new HashMap<String, Object>();
        result.put("code", code);
        result.put("msg", msg);
        return result;
    }
}
