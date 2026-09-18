package org.leo.web.controller.puppetnode.scan;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.leo.core.puppet.capability.NetworkProbeCapable;
import org.leo.service.discovery.NetworkDiscoveryDtos.ScanConfig;
import org.leo.service.discovery.NetworkProbeAnalysisService;
import org.leo.service.discovery.PortPolicyResolver;
import org.leo.service.fingerprint.FingerprintManageService;
import org.leo.web.exception.ApiException;
import org.leo.web.service.NetworkProbeResultStore;
import org.leo.web.service.NetworkProbeWorkflowService;
import org.leo.service.discovery.ScanPlanService;
import org.leo.service.discovery.TargetResolver;
import org.leo.service.discovery.ScanPreviewService;
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
    void parsesLegacyAndExplicitStagesFromJson() throws Exception {
        var mapper = new ObjectMapper();
        var planner = new ScanPlanService(new TargetResolver(), new PortPolicyResolver(),
                new NetworkProbeAnalysisService(mock(FingerprintManageService.class)));
        var legacy = mapper.readValue("{\"targets\":{\"items\":[\"127.0.0.1:80\"]}}", ScanConfig.class);
        assertEquals(List.of("REACHABILITY", "PORT_SCAN", "SERVICE_PROBE"),
                planner.plan(legacy).stages().stream().map(Enum::name).toList());
        var explicit = mapper.readValue("{\"targets\":{\"items\":[\"127.0.0.1:80\"]},\"stages\":[\"REACHABILITY\"]}", ScanConfig.class);
        assertEquals(List.of("REACHABILITY"), planner.plan(explicit).stages().stream().map(Enum::name).toList());
    }

    @Test
    void previewPreservesJsonContractWithServiceOwnedScanTypes() throws Exception {
        var workflow = mock(NetworkProbeWorkflowService.class);
        var store = mock(NetworkProbeResultStore.class);
        var planner = new ScanPlanService(new TargetResolver(), new PortPolicyResolver(),
                new NetworkProbeAnalysisService(mock(FingerprintManageService.class)));
        var mapper = new ObjectMapper();
        var controller = new NetworkProbeController(workflow, new ScanPreviewService(planner), planner, store, mapper);
        var params = new HashMap<String, Object>(Map.of("sessionId", "session", "scan", Map.of(
                "targets", Map.of("items", List.of("127.0.0.1")),
                "portPolicy", Map.of("profile", "custom", "include", List.of(80, 443)),
                "execution", Map.of("workers", 4, "timeoutMs", 1000),
                "stages", List.of("PORT_SCAN"))));

        try (var utilities = mockStatic(ControllerUtil.class)) {
            utilities.when(() -> ControllerUtil.getRequiredStringParam(params, "sessionId")).thenReturn("session");
            var json = mapper.readTree(mapper.writeValueAsString(controller.previewWorkflow(params)));

            assertEquals(200, json.path("code").asInt());
            var data = json.path("data");
            assertEquals(mapper.readTree("[]"), data.path("errors"));
            var preview = data.path("preview");
            assertEquals(1, preview.path("hostCount").asInt());
            assertEquals(2, preview.path("portCount").asInt());
            assertEquals(2, preview.path("combinationCount").asInt());
            assertEquals(mapper.readTree("[\"PORT_SCAN\"]"), preview.path("stages"));
            utilities.verify(() -> ControllerUtil.getPuppetNodeSession("session"));
        }
        verifyNoInteractions(workflow, store);
    }

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
