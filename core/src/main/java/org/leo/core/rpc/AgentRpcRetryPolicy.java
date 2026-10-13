package org.leo.core.rpc;

/** Server-side replay rules; unknown operations are sent once, not guessed from SQL or command text. */
public final class AgentRpcRetryPolicy {
    private AgentRpcRetryPolicy() {}

    public static boolean isReplayable(AgentRpcRequest request) {
        if (request.operation() == AgentOperation.PING) return true;
        if (request.operation() != AgentOperation.COMPONENT_INVOKE || request.component() == null) return false;
        String action = request.action() == null ? "" : request.action();
        return switch (request.component()) {
            case "BasicInfoComponent" -> switch (action) {
                case "", "basic", "get", "processes", "disks", "network", "resolveDns" -> true;
                default -> false;
            };
            case "FileComponent" -> switch (action) {
                case "list", "profile", "checksum" -> true;
                default -> false;
            };
            case "FileDownloadComponent" -> action.isEmpty() || "download".equals(action);
            case "ResourceComponent", "ScreenComponent" -> action.isEmpty();
            case "DatabaseComponent" -> "capabilities".equals(action);
            // ACK is cumulative, and queries retain unacknowledged pages for replay.
            case "NetworkProbeComponent" -> "queryTask".equals(action) || "ackTask".equals(action);
            case "TomcatContainerManageComponent", "WeblogicContainerManageComponent",
                 "GenericServletContainerManageComponent" -> "inspectRuntime".equals(action);
            case "SpringFrameworkManageComponent", "JavaWebFrameworkManageComponent" -> "getFrameworkInfo".equals(action);
            // Terminal reads consume output; proxy batches have their own offset/ACK retry owner.
            default -> false;
        };
    }

    public static long delayMillis(String requestId, int failure, long base, long maximum) {
        if (base <= 0L || maximum <= 0L) return 0L;
        int shift = Math.min(20, Math.max(0, failure - 1));
        long exponential = base > Long.MAX_VALUE >> shift ? Long.MAX_VALUE : base << shift;
        long hash = 1125899906842597L;
        String material = requestId + '|' + failure;
        for (int index = 0; index < material.length(); index++) hash = 31L * hash + material.charAt(index);
        double jitter = 0.75d + Math.floorMod(hash, 501L) / 1000.0d;
        return Math.max(1L, Math.min(maximum, Math.round(Math.min(maximum, exponential) * jitter)));
    }
}
