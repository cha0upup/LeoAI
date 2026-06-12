package org.leo.core.puppet.service;

import org.leo.core.net.Communication;
import org.leo.core.net.layer.RequestLayer;
import org.leo.core.net.layer.ResponseLayer;

import java.util.List;
import java.util.Map;

/**
 * 剪贴板操作服务
 * <p>
 * 封装 ClipboardComponent 的调用，读取/写入/监控远程主机剪贴板。
 */
public class ClipboardService extends ComponentService {

    private static final String COMPONENT_NAME = "ClipboardComponent";

    public ClipboardService(Communication communication,
                            List<RequestLayer> requestLayers,
                            List<ResponseLayer> responseLayers) {
        super(communication, requestLayers, responseLayers);
    }

    public Map<String, Object> read() throws Exception {
        return call(COMPONENT_NAME, "read");
    }

    public Map<String, Object> write(String content) throws Exception {
        return call(COMPONENT_NAME, "write", "content", content);
    }

    public Map<String, Object> monitor(int duration, int interval) throws Exception {
        return call(COMPONENT_NAME, "monitor",
                "duration", duration > 0 ? Integer.valueOf(duration) : null,
                "interval", interval > 0 ? Integer.valueOf(interval) : null);
    }
}
