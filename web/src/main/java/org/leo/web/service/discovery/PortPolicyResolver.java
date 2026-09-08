package org.leo.web.service.discovery;

import org.leo.web.dto.puppetnode.scan.NetworkDiscoveryDtos.PortPolicy;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 端口策略解析器 - 将端口策略展开为具体端口列表。
 *
 * <p>预定义端口策略：
 * <ul>
 *   <li>quick: 常用端口，约 100 个</li>
 *   <li>standard: 常见服务端口，约 1000 个</li>
 *   <li>extended: 扩展端口集，约 5000 个</li>
 *   <li>custom: 自定义端口和范围</li>
 * </ul>
 */
@Service
public class PortPolicyResolver {

    private static final Pattern RANGE_PATTERN = Pattern.compile("^(\\d+)-(\\d+)$");

    // Quick: Top 100 常用端口
    private static final int[] QUICK_PORTS = {
            21, 22, 23, 25, 53, 80, 81, 110, 111, 123, 135, 139, 143, 161, 443, 445, 465, 514, 515, 587,
            993, 995, 1080, 1433, 1521, 2049, 2181, 2375, 2376, 3000, 3001, 3306, 3389, 4369, 4848, 5000,
            5432, 5601, 5672, 5900, 6379, 7001, 7002, 8000, 8001, 8008, 8009, 8080, 8081, 8082, 8083, 8084,
            8085, 8086, 8087, 8088, 8089, 8090, 8091, 8161, 8443, 8888, 9000, 9001, 9042, 9090, 9092, 9093,
            9100, 9200, 9300, 9443, 9999, 10000, 11211, 15672, 27017, 27018, 50000, 50070
    };

    // Standard: 常见服务端口（包含 Quick + 其他常见端口）
    private static final int[] STANDARD_PORTS = expandStandard();

    // Extended: 1-10000 范围内的重要端口
    private static final int[] EXTENDED_PORTS = expandExtended();

    /**
     * 解析端口策略为端口列表。
     *
     * @param policy 端口策略
     * @return 端口列表（已排序去重）
     */
    public List<Integer> resolve(PortPolicy policy) {
        if (policy == null) {
            return toList(STANDARD_PORTS);
        }

        Set<Integer> ports = new LinkedHashSet<>();

        // 根据 profile 添加预定义端口
        String profile = policy.profile() != null ? policy.profile().toLowerCase() : "standard";
        switch (profile) {
            case "quick":
                addPorts(ports, QUICK_PORTS);
                break;
            case "standard":
                addPorts(ports, STANDARD_PORTS);
                break;
            case "extended":
                addPorts(ports, EXTENDED_PORTS);
                break;
            case "custom":
                // custom 不添加预定义端口
                break;
            default:
                throw new IllegalArgumentException("不支持的端口策略: " + profile);
        }

        // 解析自定义范围
        if (policy.ranges() != null) {
            for (String range : policy.ranges()) {
                addRange(ports, range.trim());
            }
        }

        // 添加额外端口
        if (policy.include() != null) {
            for (Integer port : policy.include()) {
                if (port != null && port >= 1 && port <= 65535) {
                    ports.add(port);
                }
            }
        }

        // 移除排除端口
        if (policy.exclude() != null) {
            for (Integer port : policy.exclude()) {
                ports.remove(port);
            }
        }

        if (ports.isEmpty()) {
            throw new IllegalArgumentException("端口策略展开后为空");
        }

        List<Integer> result = new ArrayList<>(ports);
        Collections.sort(result);
        return result;
    }

    private void addPorts(Set<Integer> target, int[] ports) {
        for (int port : ports) {
            target.add(port);
        }
    }

    private void addRange(Set<Integer> target, String range) {
        Matcher matcher = RANGE_PATTERN.matcher(range);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("无效的端口范围: " + range);
        }

        int start = Integer.parseInt(matcher.group(1));
        int end = Integer.parseInt(matcher.group(2));

        if (start < 1 || end > 65535 || start > end) {
            throw new IllegalArgumentException("端口范围必须在 1-65535 之间: " + range);
        }

        for (int port = start; port <= end; port++) {
            target.add(port);
        }
    }

    private List<Integer> toList(int[] ports) {
        List<Integer> result = new ArrayList<>(ports.length);
        for (int port : ports) {
            result.add(port);
        }
        return result;
    }

    private static int[] expandStandard() {
        Set<Integer> ports = new LinkedHashSet<>();

        // 包含所有 quick 端口
        for (int port : QUICK_PORTS) {
            ports.add(port);
        }

        // 常见服务端口
        int[] additional = {
            20, 21, 22, 23, 25, 37, 42, 43, 49, 53, 70, 79, 80, 81, 82, 83, 84, 85, 88, 89, 90, 99, 100,
            106, 109, 110, 111, 113, 119, 125, 135, 139, 143, 144, 146, 161, 163, 179, 199, 211, 212, 222,
            254, 255, 256, 259, 264, 280, 301, 306, 311, 340, 366, 389, 406, 407, 416, 417, 425, 427, 443,
            444, 445, 458, 464, 465, 481, 497, 500, 512, 513, 514, 515, 524, 541, 543, 544, 545, 548, 554,
            555, 563, 587, 593, 616, 617, 625, 631, 636, 646, 648, 666, 667, 668, 683, 687, 691, 700, 705,
            711, 714, 720, 722, 726, 749, 765, 777, 783, 787, 800, 801, 808, 843, 873, 880, 888, 898, 900,
            901, 902, 903, 911, 912, 981, 987, 990, 992, 993, 995, 999, 1000, 1001, 1002, 1007, 1009, 1010,
            1011, 1021, 1022, 1023, 1024, 1025, 1026, 1027, 1028, 1029, 1030, 1080, 1099, 1100, 1194, 1214,
            1234, 1311, 1337, 1433, 1434, 1521, 1589, 1604, 1723, 1741, 1777, 1883, 1900, 1935, 2000, 2001,
            2002, 2003, 2004, 2005, 2006, 2007, 2008, 2009, 2010, 2049, 2100, 2103, 2121, 2181, 2222, 2323,
            2375, 2376, 2379, 2380, 2601, 2604, 2869, 3000, 3001, 3003, 3128, 3268, 3269, 3306, 3322, 3389,
            3690, 3780, 4000, 4001, 4002, 4003, 4004, 4005, 4006, 4040, 4045, 4369, 4443, 4444, 4567, 4848,
            5000, 5001, 5002, 5003, 5004, 5009, 5030, 5050, 5051, 5060, 5061, 5080, 5087, 5100, 5101, 5190,
            5357, 5432, 5555, 5601, 5631, 5666, 5672, 5800, 5801, 5900, 5901, 5902, 5903, 5904, 5906, 5907,
            6000, 6001, 6002, 6003, 6004, 6005, 6006, 6007, 6009, 6025, 6059, 6100, 6101, 6106, 6379, 6646,
            7000, 7001, 7002, 7004, 7007, 7019, 7025, 7070, 7100, 7103, 7106, 7200, 7402, 7435, 7443, 7496,
            7512, 7625, 7627, 7676, 7741, 7777, 7778, 7800, 7911, 7920, 7921, 7937, 7938, 7999, 8000, 8001,
            8002, 8007, 8008, 8009, 8010, 8011, 8021, 8022, 8031, 8042, 8045, 8080, 8081, 8082, 8083, 8084,
            8085, 8086, 8087, 8088, 8089, 8090, 8091, 8092, 8093, 8099, 8100, 8180, 8181, 8192, 8193, 8194,
            8200, 8222, 8254, 8290, 8291, 8292, 8300, 8333, 8383, 8400, 8402, 8443, 8500, 8600, 8649, 8651,
            8652, 8654, 8701, 8800, 8873, 8888, 8899, 8994, 9000, 9001, 9002, 9003, 9009, 9010, 9011, 9040,
            9050, 9071, 9080, 9081, 9090, 9091, 9092, 9093, 9100, 9101, 9102, 9103, 9110, 9111, 9200, 9201,
            9207, 9220, 9290, 9300, 9443, 9448, 9500, 9502, 9503, 9535, 9800, 9801, 9876, 9877, 9943, 9944,
            9968, 9998, 9999, 10000, 10001, 10002, 10003, 10004, 10009, 10010, 10012, 10024, 10025, 10082,
            10180, 10215, 10243, 10566, 10616, 10617, 10621, 10626, 10628, 10629, 10778, 11110, 11111, 11967,
            12000, 12174, 12265, 12345, 13456, 13722, 13782, 13783, 14000, 15000, 15002, 15003, 15004, 15660,
            15672, 16000, 16001, 16012, 16016, 16018, 16080, 16113, 16992, 16993, 17877, 17988, 18040, 18101,
            18988, 19101, 19283, 19315, 19350, 19780, 19801, 19842, 20000, 20005, 20031, 20221, 20222, 20828,
            21571, 22939, 23502, 24444, 24800, 25734, 25735, 26214, 27000, 27352, 27353, 27355, 27356, 27715,
            28201, 30000, 30718, 30951, 31038, 31337, 32768, 32769, 32770, 32771, 32772, 32773, 32774, 32775,
            32776, 32777, 32778, 32779, 32780, 32781, 32782, 32783, 32784, 32785, 33354, 33899, 34571, 34572,
            34573, 35500, 38292, 40193, 40911, 41511, 42510, 44176, 44442, 44443, 44501, 45100, 48080, 49152,
            49153, 49154, 49155, 49156, 49157, 49158, 49159, 49160, 49161, 49163, 49165, 49167, 49175, 49176,
            49400, 49999, 50000, 50001, 50002, 50003, 50006, 50300, 50389, 50500, 50636, 50800, 51103, 51493,
            52673, 52822, 52848, 52869, 54045, 54328, 55055, 55056, 55555, 55600, 56737, 56738, 57294, 57797,
            58080, 60020, 60443, 61532, 61900, 62078, 63331, 64623, 64680, 65000, 65129, 65389
        };

        for (int port : additional) {
            ports.add(port);
        }

        int[] result = new int[ports.size()];
        int i = 0;
        for (Integer port : ports) {
            result[i++] = port;
        }
        Arrays.sort(result);
        return result;
    }

    private static int[] expandExtended() {
        Set<Integer> ports = new LinkedHashSet<>();

        // 包含所有 standard 端口
        for (int port : STANDARD_PORTS) {
            ports.add(port);
        }

        // 1-10000 范围内每隔一定间隔的端口
        for (int port = 1; port <= 10000; port++) {
            if (port <= 1024 || port % 10 == 0 || port % 100 == 0) {
                ports.add(port);
            }
        }

        int[] result = new int[ports.size()];
        int i = 0;
        for (Integer port : ports) {
            result[i++] = port;
        }
        Arrays.sort(result);
        return result;
    }
}
