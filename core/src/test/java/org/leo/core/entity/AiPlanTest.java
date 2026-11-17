package org.leo.core.entity;

import com.alibaba.fastjson.JSON;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiPlanTest {

    @ParameterizedTest
    @CsvSource({"PLANNING,PENDING", "IN_PROGRESS,IN_PROGRESS", "COMPLETED,COMPLETED",
            "FAILED,FAILED", "COMPLETED,SKIPPED"})
    void snapshotsPreserveLifecycleAndRetryState(AiPlanStatus planStatus, AiStepStatus stepStatus) {
        AiPlan plan = new AiPlan("snapshot", "restore progress", List.of(
                step(0, "record findings", 1, List.of())));
        plan.setStepTimeoutMs(1234L);
        switch (stepStatus) {
            case PENDING -> { }
            case IN_PROGRESS -> assertTrue(plan.startStep(0));
            case COMPLETED -> {
                assertTrue(plan.startStep(0));
                assertTrue(plan.completeStep(0, "findings"));
                assertEquals(planStatus, plan.getStatus());
                plan.complete("finished");
            }
            case FAILED -> {
                assertTrue(plan.startStep(0));
                assertTrue(plan.failStep(0, "first failure"));
                assertTrue(plan.startStep(0));
                assertTrue(plan.failStep(0, "retry exhausted"));
                assertEquals(planStatus, plan.getStatus());
                plan.complete("failed");
            }
            case SKIPPED -> {
                assertTrue(plan.skipStep(0, "not needed"));
                assertEquals(planStatus, plan.getStatus());
                plan.complete("skipped");
            }
        }
        String snapshot = JSON.toJSONString(plan);

        AiPlan restored = JSON.parseObject(snapshot, AiPlan.class);

        assertEquals(planStatus, restored.getStatus());
        assertEquals(stepStatus, restored.getStep(0).getStatus());
        assertEquals(snapshot, JSON.toJSONString(restored));
        assertEquals(plan.getStep(0).canStart(), restored.getStep(0).canStart());
    }

    @Test
    void dependencyMustBeCompletedRatherThanMerelyTerminated() {
        AiPlan plan = new AiPlan("test", "goal", List.of(
                step(0, null, 1, List.of()),
                step(1, null, 1, List.of(0))));

        assertTrue(plan.startStep(0));
        assertTrue(plan.failStep(0, "failed"));

        assertFalse(plan.startStep(1));
    }

    @Test
    void cannotCompletePlanWhileStepsAreStillActive() {
        AiPlan plan = new AiPlan("test", "goal", List.of(
                step(0, null, 1, List.of())));

        assertThrows(IllegalStateException.class,
                () -> plan.complete("too early"));
        assertFalse(plan.allStepsTerminated());
        assertTrue(plan.startStep(0));
        assertThrows(IllegalStateException.class,
                () -> plan.complete("still running"));
        assertFalse(plan.allStepsTerminated());
    }

    @Test
    void enforcesRetryLimitAndTracksAttempts() {
        AiPlan plan = new AiPlan("retry", "goal", List.of(
                step(0, null, 1, List.of())));

        assertTrue(plan.startStep(0));
        assertTrue(plan.failStep(0, "first"));
        assertTrue(plan.startStep(0));
        assertTrue(plan.failStep(0, "second"));
        assertFalse(plan.startStep(0));
        assertEquals(2, plan.getStep(0).getAttemptCount());
    }

    @Test
    void requiresEvidenceWhenSuccessCriteriaIsDeclared() {
        AiPlan plan = new AiPlan("criteria", "goal", List.of(
                step(0, "返回主机列表", 1, List.of())));

        assertTrue(plan.startStep(0));
        assertFalse(plan.completeStep(0, null));
        assertTrue(plan.completeStep(0, "发现 3 台主机"));
    }

    @Test
    void marksExpiredInProgressStepAsFailed() throws Exception {
        AiPlan plan = new AiPlan("timeout", "goal", List.of(
                step(0, null, 0, List.of())));
        plan.setStepTimeoutMs(1);
        assertTrue(plan.startStep(0));

        Thread.sleep(5L);

        assertEquals(1, plan.checkStepTimeouts());
        assertEquals(AiStepStatus.FAILED, plan.getStep(0).getStatus());
        assertEquals(AiPlanStatus.FAILED, plan.getStatus());
    }

    private static AiPlanStep step(int index, String successCriteria,
                                   int maxRetries, List<Integer> dependsOn) {
        return new AiPlanStep(index, "step-" + index,
                null, false, successCriteria, maxRetries, dependsOn);
    }
}
