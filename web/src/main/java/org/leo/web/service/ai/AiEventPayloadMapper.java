package org.leo.web.service.ai;

import org.leo.core.entity.AiSseEvent;

import java.util.LinkedHashMap;
import java.util.Map;

/** Converts persisted AI events to the stable HTTP response shape. */
public final class AiEventPayloadMapper {

    private AiEventPayloadMapper() {
    }

    public static Map<String, Object> toMap(AiSseEvent event) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("seq", event.seq());
        item.put("timestamp", event.timestamp());
        item.put("name", event.name());
        item.put("data", event.data());
        if (event.subagentInvocationId() != null) {
            item.put("subagentInvocationId", event.subagentInvocationId());
        }
        if (event.turnId() != null) item.put("turnId", event.turnId());
        if (event.itemId() != null) item.put("itemId", event.itemId());
        if (event.runId() != null) item.put("runId", event.runId());
        return item;
    }
}
