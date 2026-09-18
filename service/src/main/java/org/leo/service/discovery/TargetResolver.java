package org.leo.service.discovery;

import org.leo.service.discovery.NetworkDiscoveryDtos.ResolvedTarget;
import org.leo.service.discovery.NetworkDiscoveryDtos.TargetInput;
import org.springframework.stereotype.Service;

import java.net.InetAddress;
import java.net.URL;
import java.net.UnknownHostException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 目标解析器 - 将用户输入的各种格式解析为统一的 ResolvedTarget。
 *
 * <p>支持的输入格式：
 * <ul>
 *   <li>IPv4: 192.168.1.1</li>
 *   <li>IPv6: 2001:db8::1</li>
 *   <li>CIDR: 192.168.1.0/24</li>
 *   <li>IP范围: 192.168.1.1-192.168.1.254</li>
 *   <li>域名: example.com</li>
 *   <li>host:port: example.com:8080</li>
 *   <li>URL: https://example.com:8443/path</li>
 * </ul>
 */
@Service
public class TargetResolver {

    private static final Pattern CIDR_PATTERN = Pattern.compile("^([^/]+)/([0-9]{1,3})$");
    private static final Pattern IP_RANGE_PATTERN = Pattern.compile("^([0-9.]+)-([0-9.]+)$");
    private static final Pattern HOST_PORT_PATTERN = Pattern.compile("^(.+):(\\d+)$");
    private static final Pattern BRACKETED_HOST_PORT_PATTERN = Pattern.compile("^\\[([^]]+)]:(\\d+)$");
    private static final Pattern IPV4_PATTERN = Pattern.compile(
            "^((25[0-5]|(2[0-4]|1\\d|[1-9]|)\\d)\\.?\\b){4}$");

    /**
     * 解析目标输入。
     *
     * @param input 目标输入
     * @return 解析后的目标列表
     * @throws IllegalArgumentException 解析失败
     */
    public List<ResolvedTarget> resolve(TargetInput input) {
        if (input == null || input.items() == null || input.items().isEmpty()) {
            throw new IllegalArgumentException("目标列表不能为空");
        }
        if (input.items().size() > NetworkProbeLimits.MAX_TARGET_ITEMS) {
            throw new IllegalArgumentException("目标条目不能超过" + NetworkProbeLimits.MAX_TARGET_ITEMS + "个");
        }
        if (input.exclude() != null && input.exclude().size() > NetworkProbeLimits.MAX_EXCLUDE_ITEMS) {
            throw new IllegalArgumentException("排除条目不能超过" + NetworkProbeLimits.MAX_EXCLUDE_ITEMS + "个");
        }

        Set<String> excludeSet = new HashSet<>();
        if (input.exclude() != null) {
            for (String exclude : input.exclude()) {
                if (exclude != null && !exclude.isBlank()) {
                    excludeSet.addAll(expandToIps(exclude.trim()));
                }
            }
        }

        List<ResolvedTarget> results = new ArrayList<>();
        Set<String> dedup = new HashSet<>();  // Preserve virtual hosts and application paths; ports are deduplicated by the planner.

        for (String item : input.items()) {
            if (item == null) continue;
            String trimmed = item.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }

            try {
                List<ResolvedTarget> expanded = parseTarget(trimmed);
                for (ResolvedTarget target : expanded) {
                    if (excludeSet.contains(target.ip())) {
                        continue;
                    }
                    String key = target.ip() + "\u0000" + target.port() + "\u0000" + target.protocol()
                            + "\u0000" + target.host() + "\u0000" + ("url".equals(target.source()) ? target.rawTarget() : "");
                    if (dedup.add(key)) {
                        if (results.size() >= NetworkProbeLimits.MAX_RESOLVED_TARGETS) {
                            throw new IllegalArgumentException("目标展开后不能超过"
                                    + NetworkProbeLimits.MAX_RESOLVED_TARGETS + "个");
                        }
                        results.add(target);
                    }
                }
            } catch (Exception e) {
                throw new IllegalArgumentException("解析目标失败: " + trimmed + " - " + e.getMessage(), e);
            }

        }

        if (results.isEmpty()) {
            throw new IllegalArgumentException("没有有效的目标");
        }

        return results;
    }

    /**
     * 解析单个目标字符串。
     */
    private List<ResolvedTarget> parseTarget(String target) {
        // URL
        String lowerTarget = target.toLowerCase(Locale.ROOT);
        if (lowerTarget.startsWith("http://") || lowerTarget.startsWith("https://")) {
            return parseUrl(target);
        }

        // CIDR
        Matcher cidrMatcher = CIDR_PATTERN.matcher(target);
        if (cidrMatcher.matches()) {
            return parseCidr(cidrMatcher.group(1), Integer.parseInt(cidrMatcher.group(2)));
        }

        // IP范围
        Matcher rangeMatcher = IP_RANGE_PATTERN.matcher(target);
        if (rangeMatcher.matches()) {
            return parseIpRange(rangeMatcher.group(1), rangeMatcher.group(2));
        }

        // [IPv6]:port
        Matcher bracketedHostPortMatcher = BRACKETED_HOST_PORT_PATTERN.matcher(target);
        if (bracketedHostPortMatcher.matches()) {
            return parseHostPort(bracketedHostPortMatcher.group(1),
                    Integer.parseInt(bracketedHostPortMatcher.group(2)));
        }

        // host:port (an unbracketed IPv6 address is treated as a host)
        Matcher hostPortMatcher = HOST_PORT_PATTERN.matcher(target);
        if (hostPortMatcher.matches() && target.indexOf(':') == target.lastIndexOf(':')) {
            String host = hostPortMatcher.group(1);
            int port = Integer.parseInt(hostPortMatcher.group(2));
            return parseHostPort(host, port);
        }

        // 纯IP或域名（无端口，需要结合端口策略）
        return parseHost(target);
    }

    private List<ResolvedTarget> parseUrl(String urlString) {
        try {
            URL url = new URL(urlString);
            String protocol = url.getProtocol().toLowerCase();
            if (!protocol.equals("http") && !protocol.equals("https")) {
                throw new IllegalArgumentException("仅支持 http/https 协议");
            }

            String host = url.getHost();
            int port = url.getPort();
            if (port == -1) {
                port = protocol.equals("https") ? 443 : 80;
            }

            List<String> ips = resolveHost(host);
            List<ResolvedTarget> results = new ArrayList<>();
            for (String ip : ips) {
                results.add(new ResolvedTarget(
                        generateTargetId(urlString),
                        host,
                        ip,
                        port,
                        protocol,
                        "url",
                        urlString
                ));
            }
            return results;
        } catch (Exception e) {
            throw new IllegalArgumentException("URL 解析失败: " + e.getMessage(), e);
        }
    }

    private List<ResolvedTarget> parseCidr(String network, int prefixLength) {
        if (network.contains(":")) {
            return parseIpv6Cidr(network, prefixLength);
        }
        if (prefixLength < 0 || prefixLength > 32) {
            throw new IllegalArgumentException("IPv4 CIDR 前缀长度必须在 0-32 之间");
        }

        long networkLong = ipToLong(network);
        long mask = prefixLength == 0 ? 0L : (0xFFFFFFFFL << (32 - prefixLength)) & 0xFFFFFFFFL;
        networkLong &= mask;
        long hostCount = 1L << (32 - prefixLength);
        ensureExpansionCapacity(hostCount);

        List<ResolvedTarget> results = new ArrayList<>((int) hostCount);
        for (long i = 0; i < hostCount; i++) {
            String ip = longToIp(networkLong + i);
            // CIDR 不指定端口，返回 null，由端口策略决定
            results.add(new ResolvedTarget(
                    network + "/" + prefixLength,
                    ip,
                    ip,
                    null,
                    "tcp",
                    "cidr",
                    network + "/" + prefixLength
            ));
        }
        return results;
    }

    private List<ResolvedTarget> parseIpv6Cidr(String network, int prefixLength) {
        if (prefixLength < 0 || prefixLength > 128) {
            throw new IllegalArgumentException("IPv6 CIDR 前缀长度必须在 0-128 之间");
        }
        try {
            byte[] address = InetAddress.getByName(network).getAddress();
            if (address.length != 16) throw new IllegalArgumentException("无效的 IPv6 地址: " + network);
            BigInteger value = new BigInteger(1, address);
            BigInteger mask = prefixLength == 0
                    ? BigInteger.ZERO
                    : BigInteger.ONE.shiftLeft(128 - prefixLength).subtract(BigInteger.ONE)
                    .shiftLeft(prefixLength);
            BigInteger base = value.and(mask);
            BigInteger hostCount = BigInteger.ONE.shiftLeft(128 - prefixLength);
            if (hostCount.compareTo(BigInteger.valueOf(NetworkProbeLimits.MAX_RESOLVED_TARGETS)) > 0) {
                throw new IllegalArgumentException("IPv6 网段展开后不能超过"
                        + NetworkProbeLimits.MAX_RESOLVED_TARGETS + "个地址");
            }
            int count = hostCount.intValue();
            List<ResolvedTarget> results = new ArrayList<>(count);
            for (int index = 0; index < count; index++) {
                byte[] bytes = toFixedBytes(base.add(BigInteger.valueOf(index)), 16);
                String ip = InetAddress.getByAddress(bytes).getHostAddress();
                results.add(new ResolvedTarget(network + "/" + prefixLength, ip, ip,
                        null, "tcp", "cidr", network + "/" + prefixLength));
            }
            return results;
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("无效的 IPv6 地址: " + network, e);
        }
    }

    private List<ResolvedTarget> parseIpRange(String startIp, String endIp) {
        long start = ipToLong(startIp);
        long end = ipToLong(endIp);

        if (end < start) {
            throw new IllegalArgumentException("IP 范围结束地址小于起始地址");
        }
        ensureExpansionCapacity(end - start + 1L);

        List<ResolvedTarget> results = new ArrayList<>((int) (end - start + 1L));
        for (long i = start; i <= end; i++) {
            String ip = longToIp(i);
            results.add(new ResolvedTarget(
                    startIp + "-" + endIp,
                    ip,
                    ip,
                    null,
                    "tcp",
                    "range",
                    startIp + "-" + endIp
            ));
        }
        return results;
    }

    private List<ResolvedTarget> parseHostPort(String host, int port) {
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("端口必须在 1-65535 之间");
        }

        List<String> ips = resolveHost(host);
        List<ResolvedTarget> results = new ArrayList<>();
        for (String ip : ips) {
            results.add(new ResolvedTarget(
                    generateTargetId(host + ":" + port),
                    host,
                    ip,
                    port,
                    "tcp",
                    "manual",
                    host + ":" + port
            ));
        }
        return results;
    }

    private List<ResolvedTarget> parseHost(String host) {
        List<String> ips = resolveHost(host);
        List<ResolvedTarget> results = new ArrayList<>();
        for (String ip : ips) {
            results.add(new ResolvedTarget(
                    generateTargetId(host),
                    host,
                    ip,
                    null,  // 端口由端口策略决定
                    "tcp",
                    "manual",
                    host
            ));
        }
        return results;
    }

    /**
     * 解析主机名为IP列表。
     */
    private List<String> resolveHost(String host) {
        String normalized = host;
        if (normalized.startsWith("[") && normalized.endsWith("]")) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        // 如果已经是IP，直接返回
        if (IPV4_PATTERN.matcher(normalized).matches()) {
            ipToLong(normalized);
            return List.of(normalized);
        }

        // DNS 解析
        try {
            InetAddress[] addresses = InetAddress.getAllByName(normalized);
            if (addresses.length > NetworkProbeLimits.MAX_DNS_ADDRESSES) {
                throw new IllegalArgumentException("主机解析结果不能超过"
                        + NetworkProbeLimits.MAX_DNS_ADDRESSES + "个地址: " + host);
            }
            List<String> ips = new ArrayList<>();
            for (InetAddress addr : addresses) {
                ips.add(addr.getHostAddress());
            }
            return ips;
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("无法解析主机: " + host, e);
        }
    }

    /**
     * 展开目标为IP列表（用于排除列表）。
     */
    private Set<String> expandToIps(String target) {
        Set<String> ips = new HashSet<>();
        List<ResolvedTarget> resolved = parseTarget(target);
        for (ResolvedTarget t : resolved) {
            ips.add(t.ip());
        }
        return ips;
    }

    private void ensureExpansionCapacity(long count) {
        if (count > NetworkProbeLimits.MAX_RESOLVED_TARGETS) {
            throw new IllegalArgumentException("目标展开后不能超过"
                    + NetworkProbeLimits.MAX_RESOLVED_TARGETS + "个地址");
        }
    }

    private long ipToLong(String ip) {
        String[] parts = ip.split("\\.");
        if (parts.length != 4) {
            throw new IllegalArgumentException("无效的 IPv4 地址: " + ip);
        }
        long result = 0;
        for (int i = 0; i < 4; i++) {
            long part = Long.parseLong(parts[i]);
            if (part < 0 || part > 255) {
                throw new IllegalArgumentException("无效的 IPv4 地址: " + ip);
            }
            result = (result << 8) | part;
        }
        return result;
    }

    private String longToIp(long ip) {
        return ((ip >> 24) & 0xFF) + "." +
               ((ip >> 16) & 0xFF) + "." +
               ((ip >> 8) & 0xFF) + "." +
               (ip & 0xFF);
    }

    private String generateTargetId(String raw) {
        return UUID.nameUUIDFromBytes(raw.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private byte[] toFixedBytes(BigInteger value, int length) {
        byte[] source = value.toByteArray();
        byte[] result = new byte[length];
        int copyLength = Math.min(source.length, length);
        System.arraycopy(source, source.length - copyLength, result, length - copyLength, copyLength);
        return result;
    }
}
