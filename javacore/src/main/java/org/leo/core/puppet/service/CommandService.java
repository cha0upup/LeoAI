package org.leo.core.puppet.service;

import org.leo.core.net.Communication;
import org.leo.core.net.layer.RequestLayer;
import org.leo.core.net.layer.ResponseLayer;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class CommandService extends ComponentService {

    public CommandService(Communication communication, List<RequestLayer> requestLayers, List<ResponseLayer> responseLayers) {
        super(communication, requestLayers, responseLayers);
    }

    public Map<String, Object> readTerminals(List<String> processIds) throws Exception {
        return invokeComponent(TerminalRequests.COMPONENT, TerminalRequests.batchRead(processIds));
    }

    public Map<String, Object> execTerminal(String type, String command, String processId,
                                           String terminalMode, boolean includeOutput) throws Exception {
        return invokeComponent(TerminalRequests.COMPONENT,
                TerminalRequests.create(type, command, processId, terminalMode, includeOutput));
    }

    public Map<String, Object> execSimpleCommand(String cmd) throws Exception {
        return execSimpleCommand(cmd, 0);
    }

    /**
     * 一次性命令执行：fork → 等待退出（受 timeout 限制）→ 收集输出。
     *
     * @param cmd            shell 命令
     * @param timeoutSeconds 超时秒数；&lt;=0 时使用组件默认（30s）
     */
    public Map<String, Object> execSimpleCommand(String cmd, int timeoutSeconds) throws Exception {
        Map<String, Object> params = new HashMap<>();
        params.put("cmd", cmd.getBytes(StandardCharsets.UTF_8));
        if (timeoutSeconds > 0) {
            params.put("timeout", Integer.valueOf(timeoutSeconds));
        }
        return invokeComponent("ExecCommandSimpleComponent", params);
    }
}
