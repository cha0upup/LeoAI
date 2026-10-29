package org.leo.service.discovery;

import java.util.List;

/** Node protocol limits and advisory thresholds shared by scan planning and preview. */
public final class NetworkProbeLimits {

    /** Fixed contract of the bundled Java/PHP network probe components. */
    /** Keep a whole /24 (and the usual quick scan) in one node request. */
    public static final int NODE_BATCH_SIZE = 4096;
    /** Scheduling quantum; a large task returns to the node queue after each child batch. */
    public static final int SERVER_BATCH_SIZE = 1024;
    /** Port discovery is connection-bound; allow enough workers to hide connect timeouts. */
    public static final int NODE_MAX_THREADS = 256;
    public static final int NODE_DEFAULT_THREADS = 256;
    public static final int NODE_DEFAULT_TIMEOUT_MS = 1000;
    public static final int NODE_MIN_TIMEOUT_MS = 100;
    public static final int NODE_MAX_TIMEOUT_MS = 300_000;
    public static final int NODE_MAX_READ_BYTES = 8192;

    /** Task size thresholds are warnings only, never admission limits. */
    public static final int WARN_TARGET_ITEMS = 256;
    public static final int WARN_EXCLUDE_ITEMS = 128;
    public static final int WARN_RESOLVED_HOSTS = 10_000;
    public static final int WARN_FINGERPRINT_PROBES = 50_000;
    /** Fixed node protocol limit for one host-discovery target, not a task quota. */
    public static final int MAX_REACHABILITY_PROBES_PER_HOST = 32;
    public static final List<Integer> DEFAULT_REACHABILITY_PORTS = List.of(443, 80, 22, 445, 3389, 135, 8080, 8443);

    private NetworkProbeLimits() {
    }
}
