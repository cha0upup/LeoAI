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
                    for (ResolvedTarget target : parseTarget(exclude.trim())) excludeSet.add(target.ip());
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
            return parseHostPort(hostPortMatcher.group(1), Integer.parseInt(hostPortMatcher.group(2)));
        }

        // 纯IP或域名（无端口，需要结合端口策略）
        return resolveHostTargets(target, null, "tcp", "manual", target);
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

            return resolveHostTargets(host, port, protocol, "url", urlString);
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
        return expandIpv4(networkLong, hostCount, "cidr", network + "/" + prefixLength);
    }

    private List<ResolvedTarget> parseIpv6Cidr(String network, int prefixLength) {
        if (prefixLength < 0 || prefixLength > 128) {
            throw new IllegalArgumentException("IPv6 CIDR 前缀长度必须在 0-128 之间");
        }
        try {
            byte[] address = InetAddress.getByName(network).getAddress();
            if (address.length != 16) throw new IllegalArgumentException("无效的 IPv6 地址: " + network);
            int hostBits = 128 - prefixLength;
            BigInteger base = new BigInteger(1, address).shiftRight(hostBits).shiftLeft(hostBits);
            BigInteger hostCount = BigInteger.ONE.shiftLeft(hostBits);
            if (hostCount.compareTo(BigInteger.valueOf(NetworkProbeLimits.MAX_RESOLVED_TARGETS)) > 0) {
                throw new IllegalArgumentException("IPv6 网段展开后不能超过"
                        + NetworkProbeLimits.MAX_RESOLVED_TARGETS + "个地址");
            }
            int count = hostCount.intValue();
            String rawTarget = network + "/" + prefixLength;
            List<ResolvedTarget> results = new ArrayList<>(count);
            for (int index = 0; index < count; index++) {
                byte[] bytes = toFixedBytes(base.add(BigInteger.valueOf(index)), 16);
                String ip = InetAddress.getByAddress(bytes).getHostAddress();
                results.add(new ResolvedTarget(rawTarget, ip, ip, null, "tcp", "cidr", rawTarget));
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
        return expandIpv4(start, end - start + 1L, "range", startIp + "-" + endIp);
    }

    private List<ResolvedTarget> expandIpv4(long start, long count, String source, String rawTarget) {
        if (count > NetworkProbeLimits.MAX_RESOLVED_TARGETS) {
            throw new IllegalArgumentException("目标展开后不能超过"
                    + NetworkProbeLimits.MAX_RESOLVED_TARGETS + "个地址");
        }
        List<ResolvedTarget> results = new ArrayList<>((int) count);
        for (long offset = 0; offset < count; offset++) {
            String ip = longToIp(start + offset);
            results.add(new ResolvedTarget(rawTarget, ip, ip, null, "tcp", source, rawTarget));
        }
        return results;
    }

    private List<ResolvedTarget> parseHostPort(String host, int port) {
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("端口必须在 1-65535 之间");
        }

        return resolveHostTargets(host, port, "tcp", "manual", host + ":" + port);
    }

    private List<ResolvedTarget> resolveHostTargets(String host, Integer port, String protocol,
                                                   String source, String rawTarget) {
        List<String> ips = resolveHost(host);
        String targetId = UUID.nameUUIDFromBytes(rawTarget.getBytes(StandardCharsets.UTF_8)).toString();
        List<ResolvedTarget> results = new ArrayList<>(ips.size());
        for (String ip : ips) {
            results.add(new ResolvedTarget(targetId, host, ip, port, protocol, source, rawTarget));
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

    private byte[] toFixedBytes(BigInteger value, int length) {
        byte[] source = value.toByteArray();
        byte[] result = new byte[length];
        int copyLength = Math.min(source.length, length);
        System.arraycopy(source, source.length - copyLength, result, length - copyLength, copyLength);
        return result;
    }
}
