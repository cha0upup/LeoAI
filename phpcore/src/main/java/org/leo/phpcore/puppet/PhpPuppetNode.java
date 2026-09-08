package org.leo.phpcore.puppet;

import org.leo.core.puppet.AbstractPuppetNode;
import org.leo.core.puppet.capability.BasicInfoCapable;
import org.leo.core.puppet.capability.ComponentInvokeCapable;
import org.leo.core.puppet.capability.ComponentManageCapable;
import org.leo.core.puppet.capability.EventLogCapable;
import org.leo.core.puppet.capability.FileCapable;
import org.leo.core.puppet.capability.FirewallCapable;
import org.leo.core.puppet.capability.HostScopedCapable;
import org.leo.core.puppet.capability.HttpProxyCapable;
import org.leo.core.puppet.capability.HttpSenderCapable;
import org.leo.core.puppet.capability.LoadedComponentCacheCapable;
import org.leo.core.puppet.capability.LocalForwardCapable;
import org.leo.core.puppet.capability.NetworkConnectionCapable;
import org.leo.core.puppet.capability.NetworkInfoCapable;
import org.leo.core.puppet.capability.NetworkProbeCapable;
import org.leo.core.puppet.capability.PluginCapable;
import org.leo.core.puppet.capability.ProcessCapable;
import org.leo.core.puppet.capability.RegistryCapable;
import org.leo.core.puppet.capability.ReverseTunnelCapable;
import org.leo.core.puppet.capability.ScheduledTaskCapable;
import org.leo.core.puppet.capability.ScriptCapable;
import org.leo.core.puppet.capability.ServiceCapable;
import org.leo.core.puppet.capability.Socks5ProxyCapable;
import org.leo.core.puppet.capability.SqlCapable;
import org.leo.core.puppet.capability.TerminalCapable;
import org.leo.core.puppet.capability.UserAccountCapable;
import org.leo.core.engine.proxy.NetworkProxyManager;
import org.leo.core.engine.socks5.Socks5ProxyStatistics;
import org.leo.core.runtime.PuppetRuntime;
import org.leo.core.runtime.RuntimeProfile;
import org.leo.core.puppet.http.HttpSenderEngine;
import org.leo.core.rpc.PuppetRpcErrorCodes;
import org.leo.phpcore.rpc.PhpRpcClient;
import org.leo.phpcore.component.PhpComponentArtifactRegistry;
import org.leo.phpcore.component.PhpComponentVariantBuilder;
import org.leo.phpcore.database.PhpDatabaseConnectionAdapter;
import org.leo.core.component.runtime.ComponentArtifact;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** PHP implementation of the shared Puppet node and core capabilities. */
public final class PhpPuppetNode extends AbstractPuppetNode implements
        BasicInfoCapable, TerminalCapable, FileCapable, NetworkInfoCapable,
        ProcessCapable, NetworkConnectionCapable, NetworkProbeCapable, ServiceCapable,
        ScheduledTaskCapable, RegistryCapable, EventLogCapable, FirewallCapable,
        UserAccountCapable, ScriptCapable, SqlCapable,
        ComponentInvokeCapable, ComponentManageCapable, PluginCapable,
        HttpSenderCapable, Socks5ProxyCapable, HttpProxyCapable,
        LocalForwardCapable, ReverseTunnelCapable,
        HostScopedCapable, LoadedComponentCacheCapable {

    private final PhpRpcClient rpcClient;
    private final PhpDatabaseConnectionAdapter databaseConnectionAdapter = new PhpDatabaseConnectionAdapter();
    private final PhpComponentArtifactRegistry componentRegistry;
    private final PhpComponentVariantBuilder componentVariantBuilder = new PhpComponentVariantBuilder();
    private final Set<String> loadedComponents = ConcurrentHashMap.newKeySet();
    private final NetworkProxyManager networkProxyManager = new NetworkProxyManager(this);
    private final HttpSenderEngine httpSenderEngine = new HttpSenderEngine() {
        @Override
        protected Map<String, Object> executeRequest(
                String method, String url, Map<String, String> headers, String body,
                int connectTimeout, int readTimeout, boolean followRedirects) throws Exception {
            return PhpPuppetNode.this.httpRequest(method, url, headers, body,
                    connectTimeout, readTimeout, followRedirects);
        }
    };
    private volatile String hostId;
    private volatile Consumer<String> hostIdChangeListener = ignored -> { };

    public PhpPuppetNode(PhpRpcClient rpcClient, PhpComponentArtifactRegistry componentRegistry) {
        this.rpcClient = rpcClient;
        this.componentRegistry = componentRegistry;
        this.rpcClient.setHostIdMismatchRecovery(this::recoverHostAffinity);
        setRuntimeProfile(RuntimeProfile.minimal(PuppetRuntime.PHP));
    }

    @Override
    public String getHostId() {
        return hostId;
    }

    @Override
    public void setHostId(String hostId) {
        String previous = this.hostId;
        this.hostId = hostId;
        if (!Objects.equals(previous, hostId)) rpcClient.resetTransportAffinity();
        rpcClient.setHostId(hostId);
        if (!Objects.equals(previous, hostId)) hostIdChangeListener.accept(hostId);
    }

    @Override
    public void setHostIdChangeListener(Consumer<String> listener) {
        this.hostIdChangeListener = listener == null ? ignored -> { } : listener;
    }

    @Override
    public void addLoadedComponent(String hostId, Set<String> components) {
        if (hostId != null && this.hostId == null) setHostId(hostId);
        if (components != null) loadedComponents.addAll(components);
    }

    @Override
    public Set<String> getLoadedComponents() {
        return Set.copyOf(loadedComponents);
    }

    @Override
    public Set<String> getAvailableComponents() {
        return componentRegistry.getComponentIds();
    }

    @Override
    public synchronized Map<String, Object> loadComponent(String componentId) throws Exception {
        ComponentArtifact artifact = componentArtifact(componentId);
        Map<String, Object> result = rpcClient.putComponent(artifact);
        if (success(result)) loadedComponents.add(componentId);
        return result;
    }

    @Override
    public synchronized Map<String, Object> reloadComponent(String componentId) throws Exception {
        loadedComponents.remove(componentId);
        Map<String, Object> result = loadComponent(componentId);
        if (success(result)) {
            result.put("cached", Boolean.FALSE);
            result.put("reloaded", Boolean.TRUE);
            result.put("msg", "组件重新加载成功");
        }
        return result;
    }

    @Override
    public Map<String, Object> invokeComponent(String componentId, Map<String, Object> params) throws Exception {
        ComponentArtifact artifact = componentArtifact(componentId);
        Map<String, Object> result = rpcClient.invokeComponent(artifact.getComponentId(), artifact.getDigest(),
                params == null ? new LinkedHashMap<>() : params);
        if (missingComponent(result)) {
            synchronized (this) {
                loadedComponents.remove(componentId);
                Map<String, Object> loadResult = loadComponent(componentId);
                if (!success(loadResult)) return loadResult;
            }
            result = rpcClient.invokeComponent(artifact.getComponentId(), artifact.getDigest(),
                    params == null ? new LinkedHashMap<>() : params);
        }
        if (success(result)) loadedComponents.add(componentId);
        return result;
    }

    @Override
    public void unloadComponent(String componentId) {
        loadedComponents.remove(componentId);
    }

    @Override
    public Map<String, Object> testConnection() throws Exception {
        Map<String, Object> result = rpcClient.ping();
        Object reportedHostId = result.get("hostId");
        if (reportedHostId != null) setHostId(String.valueOf(reportedHostId));
        loadedComponents.clear();
        addReportedComponents(result.get("components"));
        Map<String, Object> normalized = new LinkedHashMap<>(result);
        List<String> componentIds = new ArrayList<>(loadedComponents); componentIds.sort(String::compareTo);
        normalized.put("components", componentIds);
        return normalized;
    }

    private synchronized Map<String, Object> recoverHostAffinity(String expectedHostId) {
        if (!Objects.equals(expectedHostId, hostId)) {
            return reboundResult(expectedHostId, hostId);
        }
        try {
            Map<String, Object> ping = rpcClient.ping();
            Object reported = ping == null ? null : ping.get("hostId");
            String newHostId = reported == null ? null : String.valueOf(reported).trim();
            if (!success(ping) || newHostId == null || newHostId.isBlank()) {
                return unavailableResult(expectedHostId);
            }
            loadedComponents.clear();
            setHostId(newHostId);
            addReportedComponents(ping.get("components"));
            return reboundResult(expectedHostId, newHostId);
        } catch (Exception e) {
            Map<String, Object> unavailable = unavailableResult(expectedHostId);
            unavailable.put("msg", "目标实例已变化，但重新握手失败: "
                    + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
            return unavailable;
        }
    }

    private Map<String, Object> reboundResult(String previousHostId, String currentHostId) {
        Map<String, Object> rebound = new LinkedHashMap<>();
        rebound.put("code", Integer.valueOf(409));
        rebound.put("errorCode", PuppetRpcErrorCodes.HOST_ID_REBOUND);
        rebound.put("previousHostId", previousHostId);
        rebound.put("hostId", currentHostId);
        rebound.put("msg", "目标实例已变化，会话已重新绑定，请重试当前操作");
        return rebound;
    }

    private Map<String, Object> unavailableResult(String expectedHostId) {
        Map<String, Object> unavailable = new LinkedHashMap<>();
        unavailable.put("code", Integer.valueOf(503));
        unavailable.put("errorCode", PuppetRpcErrorCodes.HOST_ID_UNAVAILABLE);
        unavailable.put("expectedHostId", expectedHostId);
        unavailable.put("msg", "目标实例已变化，但重新握手未能获得有效 HostId，请稍后重试");
        return unavailable;
    }

    @Override
    public Map<String, Object> getBasicInfo() throws Exception {
        return invoke("BasicInfoComponent", "get", Map.of());
    }

    @Override
    public Map<String, Object> collectNetworkInfo() throws Exception {
        return invoke("NetworkInfoComponent", "collect", Map.of());
    }

    @Override
    public Map<String, Object> listProcesses() throws Exception {
        return invoke("ProcessComponent", "list", Map.of());
    }

    @Override
    public Map<String, Object> findProcesses(String name, int pid, int port) throws Exception {
        Map<String, Object> params = new LinkedHashMap<>();
        if (name != null && !name.isBlank()) params.put("name", name);
        if (pid >= 0) params.put("pid", pid);
        if (port > 0) params.put("port", port);
        return invoke("ProcessComponent", "find", params);
    }

    @Override
    public Map<String, Object> killProcess(int pid, boolean force) throws Exception {
        return invoke("ProcessComponent", "kill", Map.of("pid", pid, "force", force));
    }

    @Override
    public Map<String, Object> listNetworkConnections(String state, String protocol, String port,
                                                       String pid, String process, String remoteIp,
                                                       boolean listeningOnly, int maxEntries) throws Exception {
        Map<String, Object> params = new LinkedHashMap<>();
        if (state != null && !state.isBlank()) params.put("state", state);
        if (protocol != null && !protocol.isBlank()) params.put("protocol", protocol);
        if (port != null && !port.isBlank()) params.put("port", port);
        if (pid != null && !pid.isBlank()) params.put("pid", pid);
        if (process != null && !process.isBlank()) params.put("process", process);
        if (remoteIp != null && !remoteIp.isBlank()) params.put("remoteIp", remoteIp);
        params.put("listeningOnly", listeningOnly);
        params.put("maxEntries", maxEntries);
        return invoke("NetworkConnectionComponent", "list", params);
    }

    @Override
    public Map<String, Object> listNetworkConnections() throws Exception {
        return listNetworkConnections(null, null, null, null, null, null, false, 2000);
    }

    @Override
    public Map<String, Object> networkConnectionSummary() throws Exception {
        return invoke("NetworkConnectionComponent", "summary", Map.of());
    }

    @Override
    public Map<String, Object> networkProbeCapabilities() throws Exception {
        return invoke("NetworkProbeComponent", "capabilities", Map.of());
    }

    @Override
    public Map<String, Object> startNetworkProbe(Map<String, Object> plan) throws Exception {
        return invoke("NetworkProbeComponent", "startTask", Map.of("plan", plan));
    }

    @Override
    public Map<String, Object> queryNetworkProbe(String taskId, long cursor,
                                                  int maxItems, int maxBytes,
                                                  boolean includeEvidence) throws Exception {
        return invoke("NetworkProbeComponent", "queryTask", Map.of(
                "taskId", taskId,
                "cursor", Long.valueOf(cursor),
                "maxItems", Integer.valueOf(maxItems),
                "maxBytes", Integer.valueOf(maxBytes),
                "includeEvidence", Boolean.valueOf(includeEvidence)));
    }

    @Override
    public Map<String, Object> ackNetworkProbe(String taskId, long cursor) throws Exception {
        return invoke("NetworkProbeComponent", "ackTask", Map.of(
                "taskId", taskId, "cursor", Long.valueOf(cursor)));
    }

    @Override
    public Map<String, Object> pauseNetworkProbe(String taskId) throws Exception {
        return invoke("NetworkProbeComponent", "pauseTask", Map.of("taskId", taskId));
    }

    @Override
    public Map<String, Object> resumeNetworkProbe(String taskId) throws Exception {
        return invoke("NetworkProbeComponent", "resumeTask", Map.of("taskId", taskId));
    }

    @Override
    public Map<String, Object> stopNetworkProbe(String taskId) throws Exception {
        return invoke("NetworkProbeComponent", "stopTask", Map.of("taskId", taskId));
    }

    @Override
    public Map<String, Object> releaseNetworkProbe(String taskId) throws Exception {
        return invoke("NetworkProbeComponent", "releaseTask", Map.of("taskId", taskId));
    }

    @Override
    public Map<String, Object> listServices() throws Exception {
        return invoke("ServiceComponent", "list", Map.of());
    }

    @Override
    public Map<String, Object> queryService(String serviceName) throws Exception {
        return invoke("ServiceComponent", "query", Map.of("serviceName", serviceName));
    }

    @Override
    public Map<String, Object> startService(String serviceName) throws Exception {
        return invoke("ServiceComponent", "start", Map.of("serviceName", serviceName));
    }

    @Override
    public Map<String, Object> stopService(String serviceName) throws Exception {
        return invoke("ServiceComponent", "stop", Map.of("serviceName", serviceName));
    }

    @Override
    public Map<String, Object> restartService(String serviceName) throws Exception {
        return invoke("ServiceComponent", "restart", Map.of("serviceName", serviceName));
    }

    @Override
    public Map<String, Object> enableService(String serviceName) throws Exception {
        return invoke("ServiceComponent", "enable", Map.of("serviceName", serviceName));
    }

    @Override
    public Map<String, Object> disableService(String serviceName) throws Exception {
        return invoke("ServiceComponent", "disable", Map.of("serviceName", serviceName));
    }

    @Override
    public Map<String, Object> createService(String serviceName, String binPath,
                                              String displayName, String startType) throws Exception {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("serviceName", serviceName); params.put("binPath", binPath);
        if (displayName != null && !displayName.isBlank()) params.put("displayName", displayName);
        if (startType != null && !startType.isBlank()) params.put("startType", startType);
        return invoke("ServiceComponent", "create", params);
    }

    @Override
    public Map<String, Object> deleteService(String serviceName) throws Exception {
        return invoke("ServiceComponent", "delete", Map.of("serviceName", serviceName));
    }

    @Override
    public Map<String, Object> listScheduledTasks() throws Exception {
        return invoke("ScheduledTaskComponent", "list", Map.of());
    }

    @Override
    public Map<String, Object> queryScheduledTask(String taskName) throws Exception {
        return invoke("ScheduledTaskComponent", "query", Map.of("taskName", taskName));
    }

    @Override
    public Map<String, Object> createScheduledTaskWindows(String taskName, String command,
                                                           String schedule, String modifier,
                                                           String startTime, String startDate,
                                                           String runAs, boolean force) throws Exception {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("taskName", taskName); params.put("command", command);
        params.put("schedule", schedule); params.put("force", force);
        if (modifier != null && !modifier.isBlank()) params.put("modifier", modifier);
        if (startTime != null && !startTime.isBlank()) params.put("startTime", startTime);
        if (startDate != null && !startDate.isBlank()) params.put("startDate", startDate);
        if (runAs != null && !runAs.isBlank()) params.put("runAs", runAs);
        return invoke("ScheduledTaskComponent", "createWindows", params);
    }

    @Override
    public Map<String, Object> createScheduledTaskLinux(String cronExpression,
                                                         String command) throws Exception {
        return invoke("ScheduledTaskComponent", "createLinux",
                Map.of("cronExpression", cronExpression, "command", command));
    }

    @Override
    public Map<String, Object> deleteScheduledTask(String taskName) throws Exception {
        return invoke("ScheduledTaskComponent", "delete", Map.of("taskName", taskName));
    }

    @Override
    public Map<String, Object> runScheduledTask(String taskName) throws Exception {
        return invoke("ScheduledTaskComponent", "run", Map.of("taskName", taskName));
    }

    @Override
    public Map<String, Object> enableScheduledTask(String taskName) throws Exception {
        return invoke("ScheduledTaskComponent", "enable", Map.of("taskName", taskName));
    }

    @Override
    public Map<String, Object> disableScheduledTask(String taskName) throws Exception {
        return invoke("ScheduledTaskComponent", "disable", Map.of("taskName", taskName));
    }

    @Override
    public Map<String, Object> queryRegistry(String keyPath, boolean recursive) throws Exception {
        return invoke("RegistryComponent", "query", Map.of("keyPath", keyPath, "recursive", recursive));
    }

    @Override
    public Map<String, Object> searchRegistry(String keyPath, String pattern,
                                               String searchTarget, int maxResults) throws Exception {
        return invoke("RegistryComponent", "search", Map.of("keyPath", keyPath, "pattern", pattern,
                "searchTarget", searchTarget, "maxResults", maxResults));
    }

    @Override
    public Map<String, Object> addRegistry(String keyPath, String valueName, String valueType,
                                            String valueData, boolean force) throws Exception {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("keyPath", keyPath); params.put("valueName", valueName == null ? "" : valueName);
        params.put("valueType", valueType == null ? "REG_SZ" : valueType);
        params.put("valueData", valueData == null ? "" : valueData); params.put("force", force);
        return invoke("RegistryComponent", "set", params);
    }

    @Override
    public Map<String, Object> deleteRegistry(String keyPath, String valueName, boolean force) throws Exception {
        return invoke("RegistryComponent", "delete", Map.of("keyPath", keyPath,
                "valueName", valueName == null ? "" : valueName, "force", force));
    }

    @Override
    public Map<String, Object> exportRegistry(String keyPath) throws Exception {
        return invoke("RegistryComponent", "export", Map.of("keyPath", keyPath));
    }

    @Override
    public Map<String, Object> listEventLogSources() throws Exception {
        return invoke("EventLogComponent", "listSources", Map.of());
    }

    @Override
    public Map<String, Object> queryEventLog(String source, int maxEntries, String keyword,
                                              String level, String since, String until,
                                              String eventId) throws Exception {
        return queryEventLog(source, maxEntries, keyword, level, since, until, eventId, null);
    }

    @Override
    public Map<String, Object> queryEventLog(String source, int maxEntries, String keyword,
                                              String level, String since, String until,
                                              String eventId, String format) throws Exception {
        return queryEventLog(source, maxEntries, keyword, level, since, until, eventId, format,
                2 * 1024 * 1024);
    }

    @Override
    public Map<String, Object> queryEventLog(String source, int maxEntries, String keyword,
                                              String level, String since, String until,
                                              String eventId, String format, int maxBytes) throws Exception {
        return queryEventLog(source, maxEntries, keyword, level, since, until, eventId, format,
                maxBytes, null, null, null, null, null, null);
    }

    @Override
    public Map<String, Object> queryEventLog(String source, int maxEntries, String keyword,
                                              String level, String since, String until,
                                              String eventId, String format, int maxBytes,
                                              Long cursor, String direction,
                                              Integer minStatus, Integer maxStatus,
                                              String ipPrefix, String pathPrefix) throws Exception {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("source", source); params.put("maxEntries", maxEntries); params.put("maxBytes", maxBytes);
        putIfText(params, "keyword", keyword); putIfText(params, "level", level);
        putIfText(params, "since", since); putIfText(params, "until", until);
        putIfText(params, "eventId", eventId); putIfText(params, "format", format);
        if (cursor != null) params.put("cursor", cursor); putIfText(params, "direction", direction);
        if (minStatus != null) params.put("minStatus", minStatus);
        if (maxStatus != null) params.put("maxStatus", maxStatus);
        putIfText(params, "ipPrefix", ipPrefix); putIfText(params, "pathPrefix", pathPrefix);
        return invoke("EventLogComponent", "query", params);
    }

    @Override
    public Map<String, Object> getEventLogStats(String source) throws Exception {
        return invoke("EventLogComponent", "stats", Map.of("source", source));
    }

    @Override
    public Map<String, Object> clearEventLog(String source) throws Exception {
        return invoke("EventLogComponent", "clear", Map.of("source", source));
    }

    @Override
    public Map<String, Object> aggregateEventLog(String source, String format, String groupBy,
                                                  int topN, int maxScan, String keyword,
                                                  Integer minStatus, Integer maxStatus,
                                                  String ipPrefix, String pathPrefix) throws Exception {
        return aggregateEventLog(source, format, groupBy, topN, maxScan, 2 * 1024 * 1024,
                keyword, minStatus, maxStatus, ipPrefix, pathPrefix, false);
    }

    @Override
    public Map<String, Object> aggregateEventLog(String source, String format, String groupBy,
                                                  int topN, int maxScan, int maxBytes, String keyword,
                                                  Integer minStatus, Integer maxStatus,
                                                  String ipPrefix, String pathPrefix, boolean slow) throws Exception {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("source", source); params.put("topN", topN); params.put("maxScan", maxScan);
        params.put("maxBytes", maxBytes); params.put("slow", slow);
        putIfText(params, "format", format); putIfText(params, "groupBy", groupBy);
        putIfText(params, "keyword", keyword); putIfText(params, "ipPrefix", ipPrefix);
        putIfText(params, "pathPrefix", pathPrefix);
        if (minStatus != null) params.put("minStatus", minStatus);
        if (maxStatus != null) params.put("maxStatus", maxStatus);
        return invoke("EventLogComponent", "aggregate", params);
    }

    @Override
    public Map<String, Object> previewEventLog(String source, int lines, boolean fromTail) throws Exception {
        return metaEventLog(source, null, lines, fromTail);
    }

    @Override
    public Map<String, Object> metaEventLog(String source, String format) throws Exception {
        return metaEventLog(source, format, 0, true);
    }

    @Override
    public Map<String, Object> metaEventLog(String source, String format,
                                             int lines, boolean fromTail) throws Exception {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("source", source); params.put("lines", lines); params.put("fromTail", fromTail);
        putIfText(params, "format", format);
        return invoke("EventLogComponent", "meta", params);
    }

    @Override
    public Map<String, Object> getFirewallStatus() throws Exception {
        return invoke("FirewallComponent", "status", Map.of());
    }

    @Override
    public Map<String, Object> listFirewallRules(String direction, String profile) throws Exception {
        Map<String, Object> params = new LinkedHashMap<>();
        putIfText(params, "direction", direction); putIfText(params, "profile", profile);
        return invoke("FirewallComponent", "list", params);
    }

    @Override
    public Map<String, Object> addFirewallRule(String ruleName, String direction, String action,
                                                String protocol, String localPort, String remotePort,
                                                String remoteAddress, String rawRule) throws Exception {
        Map<String, Object> params = new LinkedHashMap<>();
        putIfText(params, "ruleName", ruleName); putIfText(params, "direction", direction);
        putIfText(params, "effect", action); putIfText(params, "protocol", protocol);
        putIfText(params, "localPort", localPort); putIfText(params, "remotePort", remotePort);
        putIfText(params, "remoteAddress", remoteAddress); putIfText(params, "rawRule", rawRule);
        return invoke("FirewallComponent", "add", params);
    }

    @Override
    public Map<String, Object> deleteFirewallRule(String ruleName, String ruleIndex,
                                                   String rawRule) throws Exception {
        Map<String, Object> params = new LinkedHashMap<>();
        putIfText(params, "ruleName", ruleName); putIfText(params, "ruleIndex", ruleIndex);
        putIfText(params, "rawRule", rawRule);
        return invoke("FirewallComponent", "delete", params);
    }

    @Override
    public Map<String, Object> toggleFirewall(boolean enable) throws Exception {
        return invoke("FirewallComponent", "toggle", Map.of("enable", enable));
    }

    @Override
    public Map<String, Object> listUsers() throws Exception {
        return invoke("UserAccountComponent", "listUsers", Map.of());
    }

    @Override
    public Map<String, Object> listGroups() throws Exception {
        return invoke("UserAccountComponent", "listGroups", Map.of());
    }

    @Override
    public Map<String, Object> queryUser(String username) throws Exception {
        return invoke("UserAccountComponent", "queryUser", Map.of("username", username));
    }

    @Override
    public Map<String, Object> queryGroup(String groupName) throws Exception {
        return invoke("UserAccountComponent", "queryGroup", Map.of("groupName", groupName));
    }

    @Override
    public Map<String, Object> whoami() throws Exception {
        return invoke("UserAccountComponent", "whoami", Map.of());
    }

    @Override
    public Map<String, Object> execCommand(String type, String cmd, String processId) throws Exception {
        String action = type == null ? "" : type.trim().toLowerCase();
        if (!Set.of("write", "read", "resize", "stop").contains(action)) {
            return error(400, "PHP 虚拟终端操作不受支持: " + action);
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("processId", processId);
        params.put("cmd", cmd == null ? "" : cmd);
        return invoke("ExecCommandComponent", action, params);
    }

    @Override
    public Map<String, Object> execSimpleCommand(String cmd) throws Exception {
        return execSimpleCommand(cmd, 30);
    }

    @Override
    public Map<String, Object> execSimpleCommand(String cmd, int timeoutSeconds) throws Exception {
        return invoke("ExecCommandSimpleComponent", "exec",
                Map.of("cmd", cmd, "timeoutSeconds", Math.max(1, Math.min(timeoutSeconds, 120))));
    }

    @Override
    public Map<String, Object> getFileSystemProfile() throws Exception {
        return invoke("FileComponent", "profile", Map.of());
    }

    @Override
    public Map<String, Object> getFileList(String path) throws Exception {
        return invoke("FileComponent", "list", Map.of("path", path));
    }

    @Override
    public Map<String, Object> fileDownloadChunk(String path, long size, long offset) throws Exception {
        return invoke("FileDownloadComponent", "download",
                Map.of("path", path, "size", size, "offset", offset));
    }

    @Override
    public Map<String, Object> fileUploadChunk(String path, long offset, byte[] data) throws Exception {
        return invoke("FileUploadComponent", "upload",
                Map.of("path", path, "offset", offset, "data", data));
    }

    @Override
    public Map<String, Object> getFileMD5(String path) throws Exception {
        return invoke("FileComponent", "checksum", Map.of("path", path));
    }

    @Override
    public Map<String, Object> createDir(String dirName) throws Exception {
        return invoke("FileComponent", "createDirectory", Map.of("path", dirName));
    }

    @Override
    public Map<String, Object> deleteFile(String path) throws Exception {
        return invoke("FileComponent", "delete", Map.of("path", path));
    }

    @Override
    public Map<String, Object> copyFile(String srcPath, String destPath, String conflictStrategy) throws Exception {
        return invoke("FileComponent", "copy", optionalConflict(srcPath, destPath, conflictStrategy, "destPath"));
    }

    @Override
    public Map<String, Object> moveFile(String srcPath, String newPath, String conflictStrategy) throws Exception {
        return invoke("FileComponent", "move", optionalConflict(srcPath, newPath, conflictStrategy, "newPath"));
    }

    @Override
    public Map<String, Object> createFile(String path, String content) throws Exception {
        return invoke("FileComponent", "createFile", Map.of("path", path, "content", content));
    }

    @Override
    public Map<String, Object> compressFile(String src, String des, String excludePattern) throws Exception {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("src", src); params.put("des", des);
        if (excludePattern != null) params.put("exclude", excludePattern);
        return invoke("CompressComponent", "compress", params);
    }

    @Override
    public Map<String, Object> editFile(String path, String content) throws Exception {
        return invoke("FileComponent", "edit", Map.of("path", path, "content", content));
    }

    @Override
    public Map<String, Object> decompressFile(String src, String des, String format) throws Exception {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("src", src); params.put("des", des);
        if (format != null) params.put("format", format);
        return invoke("DecompressComponent", "decompress", params);
    }

    @Override
    public Map<String, Object> execScript(String language, String script) throws Exception {
        return invoke("ExecScriptComponent", "exec", Map.of("language", language, "script", script));
    }

    @Override
    public Map<String, Object> httpRequest(String method, String url, Map<String, String> headers,
                                           String body, int connectTimeout, int readTimeout,
                                           boolean followRedirects) throws Exception {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("method", method == null ? "GET" : method);
        params.put("url", url);
        if (headers != null && !headers.isEmpty()) params.put("headers", new LinkedHashMap<>(headers));
        if (body != null) params.put("body", body);
        if (connectTimeout > 0) params.put("connectTimeout", connectTimeout);
        if (readTimeout > 0) params.put("readTimeout", readTimeout);
        params.put("followRedirects", followRedirects);
        return invoke("HttpRequestComponent", "send", params);
    }

    @Override
    public Map<String, Object> sendRawHttp(String rawHttp, String targetHost, int targetPort,
                                           boolean useTls, boolean followRedirects,
                                           int connectTimeout, int readTimeout) throws Exception {
        return httpSenderEngine.sendRawHttp(rawHttp, targetHost, targetPort, useTls,
                followRedirects, connectTimeout, readTimeout);
    }

    @Override
    public Map<String, Object> startFuzz(String rawHttp, Map<String, List<String>> payloads,
                                         String targetHost, int targetPort, boolean useTls,
                                         int threads, int delayMs,
                                         Map<String, Object> matchRules) throws Exception {
        return httpSenderEngine.startFuzz(rawHttp, payloads, targetHost, targetPort, useTls,
                threads, delayMs, matchRules);
    }

    @Override
    public Map<String, Object> queryFuzz(String taskId) {
        return httpSenderEngine.queryFuzz(taskId);
    }

    @Override
    public Map<String, Object> stopFuzz(String taskId) {
        return httpSenderEngine.stopFuzz(taskId);
    }

    @Override
    public Map<String, Object> startSocks5Proxy(int port) throws Exception {
        return networkProxyManager.startSocks5Proxy(port);
    }

    @Override
    public Map<String, Object> stopSocks5Proxy() {
        return networkProxyManager.stopSocks5Proxy();
    }

    @Override
    public Map<String, Object> getSocks5ProxyStatus() {
        return networkProxyManager.getSocks5ProxyStatus();
    }

    @Override
    public Socks5ProxyStatistics.StatisticsSnapshot getSocks5ProxyStatistics() {
        return networkProxyManager.getSocks5ProxyStatistics();
    }

    @Override
    public Map<String, Object> startHttpProxy(int port) throws Exception {
        return networkProxyManager.startHttpProxy(port);
    }

    @Override
    public Map<String, Object> stopHttpProxy() {
        return networkProxyManager.stopHttpProxy();
    }

    @Override
    public Map<String, Object> getHttpProxyStatus() {
        return networkProxyManager.getHttpProxyStatus();
    }

    @Override
    public Socks5ProxyStatistics.StatisticsSnapshot getHttpProxyStatistics() {
        return networkProxyManager.getHttpProxyStatistics();
    }

    @Override
    public Map<String, Object> startLocalForward(int localPort, String targetHost, int targetPort) throws Exception {
        return networkProxyManager.startLocalForward(localPort, targetHost, targetPort);
    }

    @Override
    public Map<String, Object> stopLocalForward(int localPort) {
        return networkProxyManager.stopLocalForward(localPort);
    }

    @Override
    public Map<String, Object> stopAllLocalForwards() {
        return networkProxyManager.stopAllLocalForwards();
    }

    @Override
    public List<Map<String, Object>> listLocalForwards() {
        return networkProxyManager.listLocalForwards();
    }

    @Override
    public Socks5ProxyStatistics.StatisticsSnapshot getLocalForwardStatistics(int localPort) {
        return networkProxyManager.getLocalForwardStatistics(localPort);
    }

    @Override
    public Map<String, Object> startReverseTunnel(int remoteListenPort, String bindAddr,
                                                   String forwardHost, int forwardPort) throws Exception {
        return networkProxyManager.startReverseTunnel(remoteListenPort, bindAddr, forwardHost, forwardPort);
    }

    @Override
    public Map<String, Object> stopReverseTunnel(String listenId) {
        return networkProxyManager.stopReverseTunnel(listenId);
    }

    @Override
    public Map<String, Object> stopAllReverseTunnels() {
        return networkProxyManager.stopAllReverseTunnels();
    }

    @Override
    public List<Map<String, Object>> listReverseTunnels() {
        return networkProxyManager.listReverseTunnels();
    }

    @Override
    public Socks5ProxyStatistics.StatisticsSnapshot getReverseTunnelStatistics(String listenId) {
        return networkProxyManager.getReverseTunnelStatistics(listenId);
    }

    @Override
    public Map<String, Object> executeSql(org.leo.core.puppet.database.DatabaseConnectionSpec connection,
                                          String sqlScript) throws Exception {
        return executeSql(connection, org.leo.core.puppet.database.SqlCommand.raw(sqlScript));
    }

    @Override
    public Map<String, Object> executeSql(org.leo.core.puppet.database.DatabaseConnectionSpec connection,
                                          org.leo.core.puppet.database.SqlCommand command) throws Exception {
        Map<String, Object> params = new LinkedHashMap<>(databaseConnectionAdapter.adapt(connection));
        params.put("sql", command.sql());
        if (command.hasParameters()) params.put("parameters", command.parameters());
        return invoke("DatabaseComponent", "exec", params);
    }

    @Override
    public Map<String, Object> inspectDatabaseRuntime(Map<String, Object> connection) throws Exception {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("requestedDriver", requestedPdoDriver(connection));
        return invoke("DatabaseComponent", "capabilities", params);
    }

    private String requestedPdoDriver(Map<String, Object> connection) {
        if (connection == null) return "";
        Object runtimeOptions = connection.get("runtimeOptions");
        if (runtimeOptions instanceof Map<?, ?> runtimes) {
            Object phpOptions = runtimes.get("php");
            if (phpOptions instanceof Map<?, ?> php) {
                Object configured = php.get("pdoDriver");
                if (configured != null && !String.valueOf(configured).isBlank()) {
                    return String.valueOf(configured).trim().toLowerCase();
                }
            }
        }
        String dialect = String.valueOf(connection.getOrDefault("dialect", "")).trim().toLowerCase();
        try {
            return databaseConnectionAdapter.defaultDriver(dialect);
        } catch (IllegalArgumentException ignored) {
            return "";
        }
    }

    @Override
    public Map<String, Object> invokePlugin(String pluginId, String source,
                                            Map<String, Object> params) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("pluginId", pluginId);
        payload.put("source", source);
        payload.put("pluginParams", params == null ? Map.of() : params);
        payload.put("action", "invoke");
        return invokeComponent("PluginComponent", payload);
    }

    private boolean success(Map<String, Object> result) {
        return result != null && result.get("code") instanceof Number number
                && number.intValue() >= 200 && number.intValue() < 300;
    }

    private boolean missingComponent(Map<String, Object> result) {
        return result != null && result.get("code") instanceof Number number && number.intValue() == 424;
    }

    @Override
    public void close() throws Exception {
        networkProxyManager.close();
        httpSenderEngine.close();
        rpcClient.close();
    }

    private Map<String, Object> invoke(String component, String action,
                                       Map<String, Object> params) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>(params);
        payload.put("action", action);
        return invokeComponent(component, payload);
    }

    private ComponentArtifact componentArtifact(String componentId) {
        ComponentArtifact base = componentRegistry.getRequired(componentId);
        return hostId == null || hostId.isBlank() ? base : componentVariantBuilder.variant(base, hostId);
    }

    private Map<String, Object> optionalConflict(String src, String target,
                                                  String strategy, String targetKey) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("path", src);
        params.put(targetKey, target);
        if (strategy != null && !strategy.isBlank()) params.put("conflictStrategy", strategy);
        return params;
    }

    private void putIfText(Map<String, Object> target, String key, String value) {
        if (value != null && !value.isBlank()) target.put(key, value);
    }

    private void addStringValues(Object raw, Set<String> target) {
        if (raw instanceof Iterable<?> iterable) {
            for (Object item : iterable) if (item != null) target.add(String.valueOf(item));
        } else if (raw instanceof Object[] array) {
            for (Object item : array) if (item != null) target.add(String.valueOf(item));
        }
    }

    private void addReportedComponents(Object raw) {
        Set<String> reported = new java.util.LinkedHashSet<>();
        addStringValues(raw, reported);
        for (String value : reported) {
            String componentId = componentVariantBuilder.originalId(value, hostId, componentRegistry.getComponentIds());
            if (componentId != null) loadedComponents.add(componentId);
        }
    }

    private Map<String, Object> error(int code, String message) {
        return new LinkedHashMap<>(Map.of("code", code, "msg", message));
    }
}
