package org.leo.core.rpc;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Converts execution envelopes to their runtime-neutral wire maps. */
public final class PuppetRpcEnvelopeMapper {

    private PuppetRpcEnvelopeMapper() {
    }

    public static Map<String, Object> toMap(PuppetRpcRequest request) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("requestId", request.requestId());
        result.put("operation", request.operation().name());
        putIfNotNull(result, "hostId", request.hostId());
        putIfNotNull(result, "component", request.component());
        putIfNotNull(result, "action", request.action());
        result.put("params", new LinkedHashMap<>(request.params()));
        return result;
    }

    public static PuppetRpcRequest requestFromMap(Map<String, Object> envelope) {
        if (envelope == null) throw new IllegalArgumentException("envelope不能为空");
        Object operation = envelope.get("operation");
        if (operation == null) throw new IllegalArgumentException("operation不能为空");
        return new PuppetRpcRequest(
                string(envelope.get("requestId")),
                PuppetOperation.valueOf(String.valueOf(operation).trim()),
                string(envelope.get("hostId")),
                string(envelope.get("component")),
                string(envelope.get("action")),
                map(envelope.get("params")));
    }

    public static Map<String, Object> toMap(PuppetRpcResponse response) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("requestId", response.requestId());
        result.put("code", response.code());
        if (response.isSuccess()) {
            result.put("data", response.data());
        } else {
            result.put("error", new LinkedHashMap<>(response.error()));
        }
        return result;
    }

    public static PuppetRpcResponse responseFromMap(Map<String, Object> envelope) {
        if (envelope == null) throw new IllegalArgumentException("envelope不能为空");
        Object code = envelope.get("code");
        if (!(code instanceof Number)) throw new IllegalArgumentException("code必须为数字");
        int status = ((Number) code).intValue();
        boolean success = status >= 200 && status < 300;
        Object payload = envelope.get(success ? "data" : "error");
        if (!(payload instanceof Map<?, ?>)) {
            throw new IllegalArgumentException(success ? "data必须为Map" : "error必须为Map");
        }
        Map<String, Object> content = map(payload);
        return new PuppetRpcResponse(
                string(envelope.get("requestId")),
                status,
                success ? content : null,
                success ? Map.of() : content);
    }

    /** Wraps the result produced by a component or core operation. */
    public static PuppetRpcResponse responseFromResult(
            String requestId, Map<String, Object> operationResult) {
        if (operationResult == null) throw new IllegalArgumentException("operationResult不能为空");
        Map<String, Object> source = new LinkedHashMap<>(operationResult);
        Object codeValue = source.remove("code");
        if (!(codeValue instanceof Number)) throw new IllegalArgumentException("code必须为数字");
        int code = ((Number) codeValue).intValue();
        if (code >= 200 && code < 300) {
            return new PuppetRpcResponse(requestId, code, source, Map.of());
        }
        Map<String, Object> error = new LinkedHashMap<>(source);
        Object message = error.remove("msg");
        if (message != null) error.put("message", message);
        return new PuppetRpcResponse(requestId, code, null, error);
    }

    /** Restores the result shape exposed by existing component service APIs. */
    public static Map<String, Object> toResultMap(PuppetRpcResponse response) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("code", response.code());
        if (response.isSuccess()) {
            copyEntries(response.data(), result);
        } else {
            copyEntries(response.error(), result);
            Object message = result.remove("message");
            if (message != null) result.put("msg", message);
        }
        return result;
    }

    public static boolean isEnvelopeResponse(Map<String, Object> response, String requestId) {
        return response != null && requestId != null && requestId.equals(response.get("requestId"));
    }

    public static boolean isCurrentPingResult(Map<String, Object> result) {
        if (result == null || !(result.get("code") instanceof Number code)
                || code.intValue() != 200
                || !(result.get("hostId") instanceof String hostId) || hostId.isBlank()
                || !(result.get("components") instanceof List<?> components)) {
            return false;
        }
        for (Object component : components) {
            if (!(component instanceof String name) || name.isBlank()) return false;
        }
        return true;
    }

    private static void copyEntries(Map<?, ?> source, Map<String, Object> target) {
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (entry.getKey() != null) target.put(String.valueOf(entry.getKey()), entry.getValue());
        }
    }

    private static Map<String, Object> map(Object value) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (value instanceof Map<?, ?> source) copyEntries(source, result);
        return result;
    }

    private static String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static void putIfNotNull(Map<String, Object> target, String key, Object value) {
        if (value != null) target.put(key, value);
    }
}
