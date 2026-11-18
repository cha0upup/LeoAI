package org.leo.ai.thread;

import com.alibaba.fastjson.JSON;
import org.leo.core.entity.AiThreadRecord;
import org.leo.core.util.json.JsonUtil;
import org.leo.dao.mapper.AiConversationMapper;
import org.springframework.stereotype.Repository;

import java.util.Map;

/** Persistence boundary for compressed conversation context checkpoints. */
@Repository
public class AiContextCheckpointRepository {

    private final AiConversationMapper mapper;

    public AiContextCheckpointRepository(AiConversationMapper mapper) {
        this.mapper = mapper;
    }

    public ConversationCheckpoint find(String threadId) {
        if (blank(threadId)) return null;
        AiThreadRecord thread = mapper.findThreadContextCheckpoint(threadId);
        if (thread == null || blank(thread.getContextSummary())
                || blank(thread.getContextCheckpointJson())) return null;
        try {
            var metadata = JSON.parseObject(thread.getContextCheckpointJson());
            Integer version = metadata.getInteger("version");
            Long boundarySequence = metadata.getLong("boundarySequence");
            String boundaryHash = metadata.getString("boundaryHash");
            if (version == null || boundarySequence == null || blank(boundaryHash)) {
                return null;
            }
            return new ConversationCheckpoint(
                    thread.getContextSummary(), boundarySequence, boundaryHash, version);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    public void update(String threadId, String contextSummary,
                       long boundarySequence, String boundaryHash, int version) {
        if (blank(threadId) || blank(contextSummary) || blank(boundaryHash)) return;
        String metadata = JsonUtil.toJsonString(Map.of(
                "version", version,
                "boundarySequence", boundarySequence,
                "boundaryHash", boundaryHash));
        mapper.updateThreadContextCheckpoint(
                threadId, contextSummary, metadata, System.currentTimeMillis());
    }

    public void clear(String threadId) {
        if (blank(threadId)) return;
        mapper.updateThreadContextCheckpoint(
                threadId, null, null, System.currentTimeMillis());
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
