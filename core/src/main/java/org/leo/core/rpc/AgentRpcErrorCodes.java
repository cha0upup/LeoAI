package org.leo.core.rpc;

/** Stable machine-readable error codes shared by Agent runtimes and platform clients. */
public final class AgentRpcErrorCodes {

    public static final String HOST_ID_MISMATCH = "HOST_ID_MISMATCH";
    public static final String HOST_ID_REBOUND = "HOST_ID_REBOUND";
    public static final String HOST_ID_UNAVAILABLE = "HOST_ID_UNAVAILABLE";
    public static final String RPC_RESULT_UNKNOWN = "RPC_RESULT_UNKNOWN";

    private AgentRpcErrorCodes() {
    }

    public static boolean isHostIdMismatch(AgentRpcResponse response) {
        if (response == null || response.isSuccess()) return false;
        return HOST_ID_MISMATCH.equals(String.valueOf(response.error().get("errorCode")));
    }
}
