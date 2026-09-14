package org.leo.web.service.discovery;

import java.util.List;

/** Central limits shared by target planning, preview and workflow validation. */
public final class NetworkProbeLimits {

    /** Fixed contract of the bundled Java/PHP network probe components. */
    /** Keep a whole /24 (and the usual quick scan) in one node request. */
    public static final int NODE_BATCH_SIZE = 4096;
    /** Port discovery is connection-bound; allow enough workers to hide connect timeouts. */
    public static final int NODE_MAX_THREADS = 256;
    public static final int NODE_DEFAULT_THREADS = 256;
    public static final int NODE_DEFAULT_TIMEOUT_MS = 1000;
    public static final int NODE_MIN_TIMEOUT_MS = 100;
    public static final int NODE_MAX_TIMEOUT_MS = 300_000;
    public static final int NODE_MAX_READ_BYTES = 8192;

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
