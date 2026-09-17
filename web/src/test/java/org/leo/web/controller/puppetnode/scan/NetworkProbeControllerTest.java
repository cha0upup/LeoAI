package org.leo.web.controller.puppetnode.scan;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.leo.core.puppet.capability.NetworkProbeCapable;
import org.leo.web.exception.ApiException;
import org.leo.web.service.NetworkProbeResultStore;
import org.leo.web.service.NetworkProbeWorkflowService;
import org.leo.web.service.discovery.ScanPlanService;
import org.leo.web.service.discovery.TargetResolver;
import org.leo.web.service.discovery.ScanPreviewService;
import org.leo.web.util.ControllerUtil;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verifyNoInteractions;

class NetworkProbeControllerTest {

    @Test
    void debugRejectsNonHttpRootsBeforeDnsResolution() {
        var workflow = mock(NetworkProbeWorkflowService.class);
        var resolver = mock(TargetResolver.class);
        var controller = new NetworkFingerprintController(workflow, resolver);
        for (String target : List.of("example.test", "ftp://example.test", "http://user:pass@example.test", "http://example.test/?q=1")) {
            var params = new HashMap<String, Object>(Map.of("sessionId", "session", "target", target));
            try (var utilities = mockStatic(ControllerUtil.class)) {
                utilities.when(() -> ControllerUtil.getRequiredStringParam(params, "sessionId")).thenReturn("session");
                utilities.when(() -> ControllerUtil.getRequiredStringParam(params, "target")).thenReturn(target);
                assertEquals(400, controller.debug(params).get("code"));
            }
        }
        verifyNoInteractions(workflow, resolver);
    }

    @Test
    void preservesAccessErrorsBeforePlanningOrTouchingTasks() {
        var workflow = mock(NetworkProbeWorkflowService.class);
        var preview = mock(ScanPreviewService.class);
        var planner = mock(ScanPlanService.class);
        var store = mock(NetworkProbeResultStore.class);
        var resolver = mock(TargetResolver.class);
        var fingerprints = new NetworkFingerprintController(workflow, resolver);
        var controller = new NetworkProbeController(workflow, preview, planner, store, new ObjectMapper());
        var params = new HashMap<String, Object>(Map.of("sessionId", "session", "taskId", "task"));
        ApiException denied = ApiException.forbidden("无权访问此会话");

        try (var utilities = mockStatic(ControllerUtil.class)) {
            utilities.when(() -> ControllerUtil.getRequiredStringParam(params, "sessionId")).thenReturn("session");
            utilities.when(() -> ControllerUtil.getRequiredStringParam(params, "taskId")).thenReturn("task");
            utilities.when(() -> ControllerUtil.getRequiredStringParam(params, "matchKey")).thenReturn("match");
            utilities.when(() -> ControllerUtil.requireCapability(params, NetworkProbeCapable.class)).thenThrow(denied);
            utilities.when(() -> ControllerUtil.getPuppetNodeSession("session")).thenThrow(denied);
            List<Function<HashMap<String, Object>, ?>> operations = List.of(
                    controller::startWorkflow, controller::previewWorkflow, controller::queryWorkflow,
                    controller::listWorkflows, controller::pauseWorkflow, controller::resumeWorkflow,
                    controller::stopWorkflow, controller::deleteWorkflow, controller::queryWorkflowResults,
                    controller::queryFingerprints, controller::fingerprintEvidence, fingerprints::start, fingerprints::debug);
            for (var operation : operations) {
                assertSame(denied, assertThrows(ApiException.class, () -> operation.apply(params)));
            }
        }
        verifyNoInteractions(workflow, preview, planner, store, resolver);
    }
}
