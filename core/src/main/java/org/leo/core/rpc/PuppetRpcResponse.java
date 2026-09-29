package org.leo.core.rpc;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Minimal result envelope shared by runtime adapters and upper layers. */
public record PuppetRpcResponse(
        String requestId,
        int code,
        Map<String, Object> data,
        Map<String, Object> error) {

    public PuppetRpcResponse {
        if (requestId == null || requestId.isBlank()) {
            throw new IllegalArgumentException("requestId不能为空");
        }
        if (code >= 200 && code < 300 && data == null) {
            throw new IllegalArgumentException("成功响应必须包含data");
        }
        requestId = requestId.trim();
        data = data == null
                ? null
                : Collections.unmodifiableMap(new LinkedHashMap<>(data));
        error = error == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(error));
    }

    public boolean isSuccess() {
        return code >= 200 && code < 300;
    }

}
