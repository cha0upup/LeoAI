package org.leo.service.audit;

import org.leo.service.config.SystemConfigService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class AuditPolicyService {

    public static final String CONFIG_KEY_AUDIT_MODE = "audit.mode";
    public static final String MODE_ON = "on";
    public static final String MODE_WRITE = "write";
    public static final String MODE_OFF = "off";
    public static final String OPERATION_AUDIT_MODE_CHANGE = "AUDIT_MODE_CHANGE";
    public static final String OPERATION_AUDIT_LOG_DELETE = "AUDIT_LOG_DELETE";
    public static final String OPERATION_AUDIT_LOG_CLEANUP = "AUDIT_LOG_CLEANUP";

    private static final String MODE_DESCRIPTION =
            "审计日志模式：on=开启，write=关闭低风险读操作，off=完全关闭";
    private static final long MODE_CACHE_TTL_MS = 3000L;

    private static final Set<String> LOW_RISK_READ_TYPES = new LinkedHashSet<>(Arrays.asList(
            "FILE_LIST",
            "FILE_LIST_ROOT",
            "FILE_MD5",
            "PROCESS_LIST",
            "PROCESS_FIND",
            "SCHEDULED_TASK_LIST",
            "SCHEDULED_TASK_QUERY",
            "EVENT_LOG_SOURCES",
            "EVENT_LOG_QUERY",
            "EVENT_LOG_AGGREGATE",
            "EVENT_LOG_META",
            "EVENT_LOG_STATS",
            "SERVICE_LIST",
            "SERVICE_QUERY",
            "HTTP_SENDER_FUZZ_QUERY",
            "PORT_SCAN_QUERY",
            "FINGERPRINT_QUERY",
            "RECON_QUERY",
            "NETWORK_CONNECTION_LIST",
            "NETWORK_CONNECTION_SUMMARY",
            "INSTALLED_SOFTWARE_LIST_ALL",
            "INSTALLED_SOFTWARE_SYSTEM",
            "INSTALLED_SOFTWARE_USER",
            "INSTALLED_SOFTWARE_SEARCH",
            "DOCKER_CONTAINER_LIST",
            "DOCKER_IMAGE_LIST",
            "DOCKER_CONTAINER_INSPECT",
            "DOCKER_CONTAINER_LOGS",
            "DOCKER_NETWORK_LIST",
            "DOCKER_INFO"
    ));

    private static final Set<String> HIGH_RISK_PREFIXES = new LinkedHashSet<>(Arrays.asList(
            "AUDIT_",
            "COMMAND_",
            "COMPONENT_",
            "EXEC_",
            "FIREWALL_",
            "PLUGIN_",
            "PROXY_",
            "REGISTRY_",
            "RESOURCE_",
            "SCREENSHOT",
            "SQL_",
            "SUID_CAPABILITY_",
            "SUID_CAPS_"
    ));

    private static final Set<String> HIGH_RISK_ACTION_PARTS = new LinkedHashSet<>(Arrays.asList(
            "_ADD",
            "_CLEAR",
            "_COMPRESS",
            "_CONNECT",
            "_COPY",
            "_CREATE",
            "_DECOMPRESS",
            "_DELETE",
            "_DISCONNECT",
            "_DUMP",
            "_DOWNLOAD",
            "_EDIT",
            "_EXEC",
            "_EXPORT",
            "_IMPORT",
            "_INVOKE",
            "_KILL",
            "_MONITOR",
            "_MOVE",
            "_NEW",
            "_PAUSE",
            "_PASSWORD",
            "_REMOVE",
            "_RESTART",
            "_RESUME",
            "_RUN",
            "_SEND",
            "_START",
            "_STOP",
            "_TOGGLE",
            "_UNPAUSE",
            "_UPDATE",
            "_UPLOAD",
            "_WRITE"
    ));

    private static final Set<String> LOW_RISK_SUFFIXES = new LinkedHashSet<>(Arrays.asList(
            "_AGGREGATE",
            "_DETAIL",
            "_FIND",
            "_INFO",
            "_INSPECT",
            "_LIST",
            "_LIST_ALL",
            "_LOGS",
            "_META",
            "_POLL",
            "_PROGRESS",
            "_QUERY",
            "_SEARCH",
            "_SOURCES",
            "_STATS",
            "_STATUS",
            "_SUMMARY"
    ));

    private static final Set<String> KNOWN_OPERATION_TYPES = new LinkedHashSet<>(Arrays.asList(
            "FILE_LIST",
            "FILE_LIST_ROOT",
            "FILE_READ",
            "FILE_EDIT",
            "FILE_NEW",
            "FILE_MOVE",
            "FILE_COPY",
            "FILE_DELETE",
            "FILE_NEW_DIR",
            "FILE_COMPRESS",
            "FILE_DECOMPRESS",
            "FILE_SEARCH",
            "FILE_UPLOAD",
            "FILE_UPLOAD_CANCEL",
            "FILE_DOWNLOAD",
            "FILE_DOWNLOAD_LOCAL",
            "FILE_DOWNLOAD_CANCEL",
            "FILE_DOWNLOAD_RESUME",
            "FILE_MD5",
            "COMMAND_EXEC",
            "COMMAND_STOP",
            "COMPONENT_INVOKE",
            "SQL_EXEC",
            "SQL_QUERY",
            "SQL_QUERY_TABLE",
            "SQL_TABLE_CREATE",
            "SQL_DATABASE_CREATE",
            "SQL_ROW_INSERT",
            "SQL_ROW_UPDATE",
            "SQL_ROW_DELETE",
            "SQL_EXPORT_TABLE",
            "SQL_EXPORT_DATABASE",
            "SQL_EXPORT_PAUSE",
            "SQL_EXPORT_STOP",
            "SQL_EXPORT_RESUME",
            "DATABASE_CONNECTION_CREATE",
            "DATABASE_CONNECTION_UPDATE",
            "DATABASE_CONNECTION_DELETE",
            "SCREENSHOT",
            "PLUGIN_INVOKE",
            "PROXY_START",
            "PROXY_STOP",
            "RESOURCE_GET",
            "EXEC_CLASS",
            "EXEC_SCRIPT",
            "FINGERPRINT_START",
            "FINGERPRINT_QUERY",
            "FINGERPRINT_PAUSE",
            "FINGERPRINT_RESUME",
            "FINGERPRINT_STOP",
            "RECON_START",
            "RECON_QUERY",
            "HOST_REACHABLE_SCAN",
            "PROCESS_LIST",
            "PROCESS_FIND",
            "PROCESS_KILL",
            "SCHEDULED_TASK_LIST",
            "SCHEDULED_TASK_QUERY",
            "SCHEDULED_TASK_CREATE",
            "SCHEDULED_TASK_DELETE",
            "SCHEDULED_TASK_RUN",
            "SCHEDULED_TASK_TOGGLE",
            "EVENT_LOG_SOURCES",
            "EVENT_LOG_QUERY",
            "EVENT_LOG_AGGREGATE",
            "EVENT_LOG_META",
            "EVENT_LOG_STATS",
            "EVENT_LOG_CLEAR",
            "SERVICE_LIST",
            "SERVICE_QUERY",
            "SERVICE_START",
            "SERVICE_STOP",
            "SERVICE_RESTART",
            "SERVICE_TOGGLE_AUTO_START",
            "SERVICE_CREATE",
            "SERVICE_DELETE",
            "HTTP_SENDER_SEND",
            "HTTP_SENDER_FUZZ_START",
            "HTTP_SENDER_FUZZ_QUERY",
            "HTTP_SENDER_FUZZ_STOP",
            "REGISTRY_QUERY",
            "REGISTRY_SEARCH",
            "REGISTRY_ADD",
            "REGISTRY_DELETE",
            "REGISTRY_EXPORT",
            "PORT_SCAN_START",
            "PORT_SCAN_QUERY",
            "PORT_SCAN_PAUSE",
            "PORT_SCAN_RESUME",
            "PORT_SCAN_STOP",
            "PERSISTENCE_LIST",
            "PERSISTENCE_QUERY",
            "NETWORK_SHARE_LIST",
            "NETWORK_SHARE_MOUNT_LIST",
            "NETWORK_SHARE_DETAIL",
            "NETWORK_SHARE_CONNECT",
            "NETWORK_SHARE_DISCONNECT",
            "USER_ACCOUNT_LIST",
            "USER_ACCOUNT_GROUP_LIST",
            "USER_ACCOUNT_USER_QUERY",
            "USER_ACCOUNT_GROUP_QUERY",
            "USER_ACCOUNT_WHOAMI",
            "NETWORK_CONNECTION_LIST",
            "NETWORK_CONNECTION_SUMMARY",
            "FIREWALL_STATUS",
            "FIREWALL_RULE_LIST",
            "FIREWALL_ADD",
            "FIREWALL_DELETE",
            "FIREWALL_TOGGLE",
            "SUID_CAPABILITY_LIST_SUID",
            "SUID_CAPABILITY_LIST_SGID",
            "SUID_CAPABILITY_LIST_CAPABILITIES",
            "SUID_CAPABILITY_LIST_ALL",
            "INSTALLED_SOFTWARE_LIST_ALL",
            "INSTALLED_SOFTWARE_SYSTEM",
            "INSTALLED_SOFTWARE_USER",
            "INSTALLED_SOFTWARE_SEARCH",
            "DOCKER_CONTAINER_LIST",
            "DOCKER_IMAGE_LIST",
            "DOCKER_CONTAINER_INSPECT",
            "DOCKER_CONTAINER_LOGS",
            "DOCKER_NETWORK_LIST",
            "DOCKER_INFO",
            "DOCKER_CONTAINER_EXEC",
            "DOCKER_CONTAINER_START",
            "DOCKER_CONTAINER_STOP",
            "DOCKER_CONTAINER_RESTART",
            "DOCKER_CONTAINER_PAUSE",
            "DOCKER_CONTAINER_UNPAUSE",
            "DOCKER_CONTAINER_REMOVE",
            "DOCKER_IMAGE_REMOVE",
            OPERATION_AUDIT_MODE_CHANGE,
            OPERATION_AUDIT_LOG_DELETE,
            OPERATION_AUDIT_LOG_CLEANUP
    ));

    private final SystemConfigService systemConfigService;
    private volatile String cachedMode;
    private volatile long cachedModeAtMillis;

    public AuditPolicyService(SystemConfigService systemConfigService) {
        this.systemConfigService = systemConfigService;
    }

    public String getMode() {
        long now = System.currentTimeMillis();
        String mode = cachedMode;
        if (mode != null && now - cachedModeAtMillis <= MODE_CACHE_TTL_MS) {
            return mode;
        }
        String loadedMode = normalizeMode(systemConfigService.getString(CONFIG_KEY_AUDIT_MODE, MODE_ON));
        cachedMode = loadedMode;
        cachedModeAtMillis = now;
        return loadedMode;
    }

    public String updateMode(String mode) {
        String normalizedMode = requireValidMode(mode);
        systemConfigService.setString(CONFIG_KEY_AUDIT_MODE, normalizedMode, MODE_DESCRIPTION);
        cachedMode = normalizedMode;
        cachedModeAtMillis = System.currentTimeMillis();
        return normalizedMode;
    }

    public boolean shouldRecord(String operationType, boolean forceRecord) {
        if (forceRecord) {
            return true;
        }
        String mode = getMode();
        if (MODE_OFF.equals(mode)) {
            return false;
        }
        if (MODE_WRITE.equals(mode)) {
            return !isLowRiskReadOperation(operationType);
        }
        return true;
    }

    public boolean isLowRiskReadOperation(String operationType) {
        String normalizedType = normalizeOperationType(operationType);
        if (normalizedType == null) {
            return false;
        }
        if (LOW_RISK_READ_TYPES.contains(normalizedType)) {
            return true;
        }
        if (hasPrefix(normalizedType, HIGH_RISK_PREFIXES) || containsAny(normalizedType, HIGH_RISK_ACTION_PARTS)) {
            return false;
        }
        for (String suffix : LOW_RISK_SUFFIXES) {
            if (normalizedType.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }

    public List<Map<String, Object>> getModeOptions() {
        List<Map<String, Object>> options = new ArrayList<>();
        options.add(modeOption(MODE_ON, "开启"));
        options.add(modeOption(MODE_WRITE, "关闭低风险读操作"));
        options.add(modeOption(MODE_OFF, "完全关闭"));
        return options;
    }

    public String getModeLabel(String mode) {
        String normalizedMode = normalizeMode(mode);
        if (MODE_WRITE.equals(normalizedMode)) {
            return "关闭低风险读操作";
        }
        if (MODE_OFF.equals(normalizedMode)) {
            return "完全关闭";
        }
        return "开启";
    }

    public List<String> getKnownOperationTypes() {
        return new ArrayList<>(KNOWN_OPERATION_TYPES);
    }

    public String requireValidMode(String mode) {
        String normalizedMode = parseMode(mode);
        if (normalizedMode == null) {
            throw new IllegalArgumentException("不支持的审计模式: " + mode);
        }
        return normalizedMode;
    }

    private String normalizeMode(String mode) {
        String normalizedMode = parseMode(mode);
        return normalizedMode != null ? normalizedMode : MODE_ON;
    }

    private String parseMode(String mode) {
        if (mode == null || mode.isBlank()) {
            return null;
        }
        String normalizedMode = mode.trim().toLowerCase(Locale.ROOT);
        if (MODE_ON.equals(normalizedMode)
                || MODE_WRITE.equals(normalizedMode)
                || MODE_OFF.equals(normalizedMode)) {
            return normalizedMode;
        }
        return null;
    }

    private String normalizeOperationType(String operationType) {
        if (operationType == null || operationType.isBlank()) {
            return null;
        }
        return operationType.trim().toUpperCase(Locale.ROOT).replace('-', '_');
    }

    private boolean hasPrefix(String operationType, Set<String> prefixes) {
        for (String prefix : prefixes) {
            if (operationType.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private boolean containsAny(String operationType, Set<String> actionParts) {
        for (String actionPart : actionParts) {
            if (operationType.contains(actionPart)) {
                return true;
            }
        }
        return false;
    }

    private Map<String, Object> modeOption(String value, String label) {
        Map<String, Object> option = new HashMap<>();
        option.put("value", value);
        option.put("label", label);
        return option;
    }
}
