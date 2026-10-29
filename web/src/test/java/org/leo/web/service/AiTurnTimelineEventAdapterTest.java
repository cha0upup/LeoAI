package org.leo.web.service;

import org.junit.jupiter.api.Test;
import org.leo.ai.runtime.AiTurnEvent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AiTurnTimelineEventAdapterTest {

    @Test
    void mapsEngineEventsToStableTimelineProtocol() {
        List<String> emitted = new ArrayList<>();
        AiTimelineRecorder recorder = new AiTimelineRecorder(
                (name, data) -> emitted.add(name + ":" + data));
        AtomicInteger thinkingStarted = new AtomicInteger();
        AtomicInteger toolCompleted = new AtomicInteger();
        AiTurnTimelineEventAdapter adapter = new AiTurnTimelineEventAdapter(
                recorder,
                (name, data) -> emitted.add(name + ":" + data),
                thinkingStarted::incrementAndGet,
                toolCompleted::incrementAndGet,
                true);
        LinkedHashMap<String, Object> tool = new LinkedHashMap<>();
        tool.put("kind", "tool");

        adapter.accept(AiTurnEvent.thinkingDelta("分析"));
        adapter.accept(AiTurnEvent.textDelta("答案"));
        adapter.accept(AiTurnEvent.toolStarted(tool));
        adapter.accept(AiTurnEvent.toolCompleted(tool));
        recorder.onBoundary();

        assertEquals(1, thinkingStarted.get());
        assertEquals(1, toolCompleted.get());
        assertEquals("答案", org.leo.ai.runtime.AiTurnRecoveryContext.visibleOutput(recorder.eventLog()));
        assertEquals(List.of("node", "node", "node", "patch"),
                recorder.eventLog().stream().map(event -> event.name()).toList());
    }
}
