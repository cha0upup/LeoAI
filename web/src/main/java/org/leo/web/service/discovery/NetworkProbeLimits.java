package org.leo.web.service.discovery;

import java.util.List;

/** Central limits shared by target planning, preview and workflow validation. */
public final class NetworkProbeLimits {

    public static final int MAX_TARGET_ITEMS = 256;
    public static final int MAX_EXCLUDE_ITEMS = 128;
    public static final int MAX_RESOLVED_TARGETS = 100_000;
    public static final int MAX_RESOLVED_HOSTS = 10_000;
    public static final int MAX_REACHABILITY_PROBES_PER_HOST = 32;
    public static final int MAX_REACHABILITY_PROBES = 100_000;
    public static final int MAX_DNS_ADDRESSES = 64;
    public static final long MAX_ENDPOINT_COMBINATIONS = 200_000L;
    public static final int MAX_FINGERPRINT_PROBES = 50_000;
    public static final List<Integer> DEFAULT_REACHABILITY_PORTS = List.of(80, 443, 22, 8080, 8443);

    private NetworkProbeLimits() {
    }
}
