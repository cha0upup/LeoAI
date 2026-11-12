package org.leo.service.discovery;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.leo.service.discovery.NetworkDiscoveryDtos.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NetworkProbeWorkflowPlanTest {
    @ParameterizedTest
    @ValueSource(ints = {100, 300, 1000})
    void derivesStageBudgetsWithoutMutatingValidatedInputs(int timeout) {
        var resolver = mock(TargetResolver.class);
        when(resolver.resolve(any())).thenReturn(List.of(
                new ResolvedTarget("v6", "2001:db8::1", "2001:db8::1", null, "tcp", "host", "2001:db8::1"),
                new ResolvedTarget("v4", "192.0.2.1", "192.0.2.1", null, "tcp", "host", "192.0.2.1")));
        var validated = new ScanPlanService(resolver, new PortPolicyResolver(), null).plan(new ScanConfig(
                "stages", new TargetInput(List.of("2001:db8::1", "192.0.2.1"), List.of()),
                new PortPolicy("custom", List.of(), List.of(443, 8080), List.of()),
                new ExecutionConfig(64, timeout), null, List.of("REACHABILITY", "PORT_SCAN", "SERVICE_PROBE")));
        var definition = NetworkProbeWorkflowPlan.from(validated);
        assertSame(validated.targets(), definition.targets());
        assertSame(validated.reachabilityTargets(), definition.reachabilityScan().get("targets"));

        var ports = definition.portPlan(Set.of("2001:db8::1"));
        var targets = NetworkProbePayloads.mapRows(ports.get("targets"));
        assertEquals(2, targets.size());
        assertEquals(List.of("tcp-connect"), ports.get("stages"));
        assertEquals(Map.of("timeout", timeout, "threads", 2, "maxReadBytes", NetworkProbeLimits.NODE_MAX_READ_BYTES), ports.get("limits"));
        targets.get(0).put("host", "changed");
        assertEquals("2001:db8::1", validated.targets().get(0).get("host"));

        var endpoint = new LinkedHashMap<String, Object>(Map.of("host", "2001:db8::1", "port", 443,
                "service", "https", "serviceIdentified", true, "protocol", "https"));
        var before = NetworkProbePayloads.copyMap(endpoint);
        var tcp = NetworkProbePayloads.mapRows(definition.servicePlan(List.of(endpoint)).get("targets")).get(0);
        assertEquals("tcp", tcp.get("protocol"));
        assertEquals(Math.min(timeout, 300), tcp.get("timeout"));
        assertFalse(tcp.containsKey("service"));
        assertFalse(tcp.containsKey("serviceIdentified"));
        var http = NetworkProbePayloads.mapRows(definition.serviceHttpPlan(List.of(endpoint), false).get("targets")).get(0);
        assertEquals("https://[2001:db8::1]:443/", http.get("baseUrl"));
        assertEquals(Math.min(timeout, 700), http.get("timeout"));
        endpoint.put("port", 8080);
        var tls = NetworkProbePayloads.mapRows(definition.serviceHttpPlan(List.of(endpoint), true).get("targets")).get(0);
        assertEquals("https://[2001:db8::1]:8080/", tls.get("baseUrl"));
        endpoint.put("port", 443);
        assertEquals(before, endpoint);
        assertEquals(32, definition.fingerprintRequest(List.of()).get("threads"));
        assertFalse(definition.durableConfig().containsKey("sourceTaskId"));
    }

    @Test
    void retainsVirtualHostsAndApplicationPathsWithStableDeduplicatedIds() {
        var endpoint = Map.<String, Object>of("host", "192.0.2.1", "port", 8080, "service", "http");
        var target = Map.<String, Object>of("host", "192.0.2.1", "port", 8080, "applications", List.of(
                Map.of("baseUrl", "http://app.example/prefix?ignored=1#fragment"),
                Map.of("baseUrl", "http://app.example/prefix/"), Map.of("hostname", "other.example")));
        var definition = NetworkProbeWorkflowPlan.fingerprint(List.of(endpoint), List.of(target), List.of(), "extra", "source", true);
        var applications = definition.fingerprintTargets(List.of(endpoint));
        assertEquals(List.of("http://app.example/prefix/", "http://other.example:8080/"),
                applications.stream().map(row -> row.get("baseUrl")).toList());
        assertEquals("192.0.2.1:8080|http://app.example/prefix/", applications.get(0).get("targetId"));
        assertEquals("192.0.2.1", applications.get(0).get("host"));
        assertEquals(applications, definition.fingerprintPreflight().get("targets"));
        assertFalse(definition.fingerprintPreflight().containsKey("debug"));
        assertFalse(definition.fingerprintPreflight().containsKey("threads"));
        assertEquals(true, definition.fingerprintRequest(applications).get("debug"));
        assertEquals(16, definition.fingerprintRequest(applications).get("threads"));
    }

    @Test
    void supplementalDefinitionDetachesInputsAndPreservesDurableSourceContext() {
        var endpoint = new LinkedHashMap<String, Object>(Map.of("host", "192.0.2.1", "port", 80,
                "protocol", "http", "service", "http", "title", "original", "responseSize", 42));
        var application = new LinkedHashMap<String, Object>(Map.of("baseUrl", "http://app.example/path"));
        var target = new LinkedHashMap<String, Object>(Map.of("host", "192.0.2.1", "port", 80,
                "applications", new ArrayList<>(List.of(application))));
        var rule = new LinkedHashMap<String, Object>(Map.of("id", "rule", "tags", new ArrayList<>(List.of("web"))));
        var definition = NetworkProbeWorkflowPlan.fingerprint(List.of(endpoint), List.of(target), List.of(rule), "extra", "source", true);
        endpoint.put("title", "changed");
        application.put("baseUrl", "http://changed.example/");
        ((List<?>) rule.get("tags")).clear();

        var config = definition.durableConfig();
        assertEquals("source", config.get("sourceTaskId"));
        assertEquals(List.of("FINGERPRINT"), config.get("stages"));
        assertEquals(List.of("web"), definition.fingerprintRules().get(0).get("tags"));
        assertEquals("http://app.example/path/", definition.fingerprintTargets(definition.seedEndpoints()).get(0).get("baseUrl"));
        var observation = definition.seedObservations().get(0);
        assertEquals("source", observation.get("sourceTaskId"));
        assertEquals("SERVICE_PROBE", observation.get("workflowStage"));
        assertEquals(true, observation.get("contextOnly"));
        assertEquals(Map.of("title", "original", "bodyLength", 42), observation.get("evidence"));
        assertEquals(List.of("192.0.2.1"), definition.hosts());
    }

    @Test
    void skipsNonHttpEndpointsAndBuildsIpv6FallbackApplications() {
        var ssh = Map.<String, Object>of("host", "192.0.2.1", "port", 22, "service", "ssh");
        var https = Map.<String, Object>of("host", "2001:db8::1", "port", 8443, "service", "https");
        var definition = NetworkProbeWorkflowPlan.fingerprint(List.of(ssh, https), List.of(), List.of(), "extra", "source", false);
        assertEquals(List.of("https://[2001:db8::1]:8443/"), definition.fingerprintTargets(definition.seedEndpoints())
                .stream().map(row -> row.get("baseUrl")).toList());
    }

    @ParameterizedTest
    @ValueSource(strings = {"ftp://app.example/", "http://user:password@app.example/"})
    void rejectsInvalidApplicationRequestsBeforeExecution(String url) {
        var endpoint = Map.<String, Object>of("host", "192.0.2.1", "port", 80);
        var target = Map.<String, Object>of("host", "192.0.2.1", "port", 80, "baseUrl", url);
        var definition = NetworkProbeWorkflowPlan.fingerprint(List.of(endpoint), List.of(target), List.of(), "extra", "source", false);
        assertThrows(IllegalArgumentException.class, definition::fingerprintPreflight);
    }
}
