package org.leo.phpcore.puppet;

import org.junit.jupiter.api.Test;
import org.leo.core.entity.Disguise;
import org.leo.core.net.Communication;
import org.leo.core.net.layer.RequestLayer;
import org.leo.core.net.layer.ResponseLayer;
import org.leo.core.puppet.capability.TerminalCapable;
import org.leo.core.puppet.capability.HttpSenderCapable;
import org.leo.core.puppet.capability.HttpProxyCapable;
import org.leo.core.puppet.capability.EventLogCapable;
import org.leo.core.puppet.capability.FirewallCapable;
import org.leo.core.puppet.capability.LocalForwardCapable;
import org.leo.core.puppet.capability.NetworkConnectionCapable;
import org.leo.core.puppet.capability.NetworkInfoCapable;
import org.leo.core.puppet.capability.ProcessCapable;
import org.leo.core.puppet.capability.RegistryCapable;
import org.leo.core.puppet.capability.ReverseTunnelCapable;
import org.leo.core.puppet.capability.ScanCapable;
import org.leo.core.puppet.capability.ScheduledTaskCapable;
import org.leo.core.puppet.capability.ServiceCapable;
import org.leo.core.puppet.capability.Socks5ProxyCapable;
import org.leo.core.puppet.capability.SqlCapable;
import org.leo.core.puppet.capability.UserAccountCapable;
import org.leo.core.puppet.database.DatabaseConnectionSpec;
import org.leo.core.puppet.database.SqlCommand;
import org.leo.core.rpc.PuppetRpcEnvelopeMapper;
import org.leo.core.rpc.PuppetOperation;
import org.leo.core.rpc.PuppetRpcRequest;
import org.leo.phpcore.component.PhpComponentArtifactRegistry;
import org.leo.phpcore.component.PhpComponentVariantBuilder;
import org.leo.phpcore.rpc.PhpRpcClient;
import org.leo.phpcore.payload.PhpPayloadCodec;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PhpPuppetNodeTest {
    private static final String KEY = "php-node-test-key";

    @Test
    void restoresOpaquePingAliasesAndUsesEndpointVariantForInvocations() throws Exception {
        List<Map<String, Object>> invokes = new ArrayList<>();
        Disguise portable = new PortableDisguise();
        String host = "variant-host";
        String alias = new PhpComponentVariantBuilder().alias("BasicInfoComponent", host);
        Communication communication = bytes -> {
            DecodedRequest decoded = decodeRequest(bytes);
            if (decoded.request().operation() == PuppetOperation.PING) {
                return response(decoded, Map.of("code", 200, "hostId", host, "components", List.of(alias)));
            }
            if (decoded.request().operation() == PuppetOperation.COMPONENT_INVOKE) invokes.add(decoded.execution());
            return response(decoded, Map.of("code", 200));
        };
        PhpPuppetNode node = node(communication, portable);

        Map<String, Object> ping = node.testConnection();
        node.getBasicInfo();

        assertEquals(List.of("BasicInfoComponent"), ping.get("components"));
        assertTrue(node.getLoadedComponents().contains("BasicInfoComponent"));
        assertEquals(alias, invokes.get(0).get("componentName"));
        assertFalse(String.valueOf(invokes.get(0).get("componentKey")).startsWith("BasicInfoComponent"));
    }

    @Test
    void exposesAndRoutesAdministrationCapabilities() throws Exception {
        List<Map<String, Object>> invokes = new ArrayList<>();
        Disguise portable = new PortableDisguise();
        Communication communication = bytes -> {
            DecodedRequest decoded = decodeRequest(bytes);
            Map<String, Object> request = decoded.execution();
            if (decoded.request().operation() == PuppetOperation.COMPONENT_INVOKE) invokes.add(request);
            return response(decoded, Map.of("code", 200));
        };
        PhpPuppetNode node = node(communication, portable);

        assertTrue(node instanceof RegistryCapable);
        assertTrue(node instanceof EventLogCapable);
        assertTrue(node instanceof FirewallCapable);
        assertTrue(node instanceof UserAccountCapable);
        node.queryRegistry("HKCU\\Software", true);
        node.queryEventLog("/var/log/app.log", 50, "error", "ERROR", null, null,
                null, "auto", 65536, 10L, "newer", 400, 599, "10.", "/api");
        node.aggregateEventLog("/var/log/app.log", "combined", "status", 10, 1000,
                65536, null, null, null, null, null, false);
        node.metaEventLog("/var/log/app.log", "combined", 20, true);
        node.getFirewallStatus();
        node.listFirewallRules("in", "public");
        node.listUsers();
        node.whoami();

        assertEquals(List.of("RegistryComponent", "EventLogComponent", "EventLogComponent",
                        "EventLogComponent", "FirewallComponent", "FirewallComponent",
                        "UserAccountComponent", "UserAccountComponent"),
                invokes.stream().map(item -> String.valueOf(item.get("componentName"))).toList());
        assertEquals(List.of("query", "query", "aggregate", "meta", "status", "list",
                        "listUsers", "whoami"),
                invokes.stream().map(item -> String.valueOf(item.get("action"))).toList());
        assertEquals(10L, ((Number) invokes.get(1).get("cursor")).longValue());
        assertEquals(400, invokes.get(1).get("minStatus"));
        assertEquals("status", invokes.get(2).get("groupBy"));
        assertEquals("public", invokes.get(5).get("profile"));
    }

    @Test
    void exposesAndRoutesOperationalCapabilities() throws Exception {
        List<Map<String, Object>> invokes = new ArrayList<>();
        Disguise portable = new PortableDisguise();
        Communication communication = bytes -> {
            DecodedRequest decoded = decodeRequest(bytes);
            Map<String, Object> request = decoded.execution();
            if (decoded.request().operation() == PuppetOperation.COMPONENT_INVOKE) invokes.add(request);
            return response(decoded, Map.of("code", 200));
        };
        PhpPuppetNode node = node(communication, portable);

        assertTrue(node instanceof NetworkConnectionCapable);
        assertTrue(node instanceof ScanCapable);
        assertTrue(node instanceof ServiceCapable);
        assertTrue(node instanceof ScheduledTaskCapable);
        node.listNetworkConnections("LISTEN", "TCP", "8080", "12", "php", "10.", true, 100);
        node.networkConnectionSummary();
        node.startScanPort("127.0.0.1", new int[]{80, 443}, 500, 2);
        node.queryScanPortResult("scan-task");
        node.scanReachableHost(new ArrayList<>(List.of("127.0.0.1")), 500);
        node.listServices();
        node.createService("demo", "/opt/demo", "Demo", "auto");
        node.listScheduledTasks();
        node.createScheduledTaskLinux("*/5 * * * *", "/opt/demo --check");

        assertEquals(List.of("NetworkConnectionComponent", "NetworkConnectionComponent",
                        "ScanComponent", "ScanComponent", "ScanComponent", "ServiceComponent",
                        "ServiceComponent", "ScheduledTaskComponent", "ScheduledTaskComponent"),
                invokes.stream().map(item -> String.valueOf(item.get("componentName"))).toList());
        assertEquals(List.of("list", "summary", "start", "query", "reachable", "list",
                        "create", "list", "createLinux"),
                invokes.stream().map(item -> String.valueOf(item.get("action"))).toList());
        assertEquals(true, invokes.get(0).get("listeningOnly"));
        assertEquals(100, invokes.get(0).get("maxEntries"));
        assertEquals("*/5 * * * *", invokes.get(8).get("cronExpression"));
    }

    @Test
    void exposesAndRoutesSystemCapabilities() throws Exception {
        List<Map<String, Object>> invokes = new ArrayList<>();
        Disguise portable = new PortableDisguise();
        Communication communication = bytes -> {
            DecodedRequest decoded = decodeRequest(bytes);
            Map<String, Object> request = decoded.execution();
            if (decoded.request().operation() == PuppetOperation.COMPONENT_INVOKE) invokes.add(request);
            return response(decoded, Map.of("code", 200));
        };
        PhpPuppetNode node = node(communication, portable);

        assertTrue(node instanceof ProcessCapable);
        assertTrue(node instanceof NetworkInfoCapable);
        node.listProcesses();
        node.findProcesses("php", 123, 8080);
        node.killProcess(123, true);
        node.collectNetworkInfo();

        assertEquals(List.of("ProcessComponent", "ProcessComponent", "ProcessComponent",
                        "NetworkInfoComponent"),
                invokes.stream().map(item -> String.valueOf(item.get("componentName"))).toList());
        assertEquals(List.of("list", "find", "kill", "collect"),
                invokes.stream().map(item -> String.valueOf(item.get("action"))).toList());
        assertEquals("php", invokes.get(1).get("name"));
        assertEquals(123, invokes.get(1).get("pid"));
        assertEquals(8080, invokes.get(1).get("port"));
        assertEquals(true, invokes.get(2).get("force"));
    }

    @Test
    void invokesByDigestAndLoadsOnlyWhenTargetCacheMisses() throws Exception {
        List<PuppetOperation> methods = new ArrayList<>();
        Set<String> cachedKeys = new HashSet<>();
        Disguise portable = new PortableDisguise();
        Communication communication = bytes -> {
            DecodedRequest decoded = decodeRequest(bytes);
            Map<String, Object> request = decoded.execution();
            PuppetOperation operation = decoded.request().operation();
            methods.add(operation);
            String componentKey = String.valueOf(request.get("componentKey"));
            if (operation == PuppetOperation.COMPONENT_LOAD) {
                assertEquals("BasicInfoComponent", request.get("componentName"));
                assertEquals(80, componentKey.length());
                assertFalse(request.containsKey("componentDigest"));
                assertTrue(String.valueOf(request.get("source")).startsWith("<?php"));
                cachedKeys.add(componentKey);
                return response(decoded, Map.of("code", 200, "cached", false));
            }
            if (!cachedKeys.contains(componentKey)) {
                return response(decoded, Map.of("code", 424));
            }
            return response(decoded, Map.of("code", 200,
                    "BasicInfo", Map.of("OSInfo", Map.of("OSName", "test"))));
        };
        PhpPuppetNode node = node(communication, portable);

        node.getBasicInfo();
        node.getBasicInfo();

        assertEquals(List.of(PuppetOperation.COMPONENT_INVOKE, PuppetOperation.COMPONENT_LOAD,
                PuppetOperation.COMPONENT_INVOKE, PuppetOperation.COMPONENT_INVOKE), methods);
        assertTrue(node.getLoadedComponents().contains("BasicInfoComponent"));
        assertTrue(node.getAvailableComponents().contains("PluginComponent"));
    }

    @Test
    void reloadsOnceWhenPreviouslyAvailableComponentDisappears() throws Exception {
        List<PuppetOperation> methods = new ArrayList<>();
        AtomicInteger invokes = new AtomicInteger();
        Disguise portable = new PortableDisguise();
        Communication communication = bytes -> {
            DecodedRequest decoded = decodeRequest(bytes);
            PuppetOperation operation = decoded.request().operation();
            methods.add(operation);
            if (operation == PuppetOperation.COMPONENT_INVOKE && invokes.getAndIncrement() == 1) {
                return response(decoded, Map.of("code", 424));
            }
            return response(decoded, Map.of("code", 200, "data", "ok"));
        };
        PhpPuppetNode node = node(communication, portable);

        node.execSimpleCommand("echo first");
        Map<String, Object> result = node.execSimpleCommand("echo second");

        assertEquals("ok", result.get("data"));
        assertEquals(List.of(PuppetOperation.COMPONENT_INVOKE, PuppetOperation.COMPONENT_INVOKE,
                PuppetOperation.COMPONENT_LOAD, PuppetOperation.COMPONENT_INVOKE), methods);
    }

    @Test
    void testConnectionUsesMethodZeroAndUnloadOnlyClearsPlatformState() throws Exception {
        List<PuppetOperation> methods = new ArrayList<>();
        Disguise portable = new PortableDisguise();
        Communication communication = bytes -> {
            DecodedRequest decoded = decodeRequest(bytes);
            methods.add(decoded.request().operation());
            return response(decoded, Map.of("code", 200, "hostId", "php-host",
                    "components", List.of("FileComponent")));
        };
        PhpPuppetNode node = node(communication, portable);

        Map<String, Object> result = node.testConnection();
        assertEquals("php-host", result.get("hostId"));
        assertTrue(node.getLoadedComponents().contains("FileComponent"));
        node.unloadComponent("FileComponent");
        assertFalse(node.getLoadedComponents().contains("FileComponent"));
        assertEquals(List.of(PuppetOperation.PING), methods);
    }

    @Test
    void exposesTerminalCapabilityAndForwardsTerminalActions() throws Exception {
        List<Map<String, Object>> invokes = new ArrayList<>();
        Disguise portable = new PortableDisguise();
        Communication communication = bytes -> {
            DecodedRequest decoded = decodeRequest(bytes);
            Map<String, Object> request = decoded.execution();
            if (decoded.request().operation() == PuppetOperation.COMPONENT_INVOKE) invokes.add(request);
            return response(decoded, Map.of("code", 200));
        };
        PhpPuppetNode node = node(communication, portable);

        assertTrue(node instanceof TerminalCapable);
        node.execCommand("write", "init", "terminal-1");
        node.execCommand("read", "read", "terminal-1");
        node.execCommand("resize", "120,40", "terminal-1");
        node.execCommand("stop", "", "terminal-1");

        assertEquals(List.of("write", "read", "resize", "stop"),
                invokes.stream().map(item -> String.valueOf(item.get("action"))).toList());
        assertTrue(invokes.stream().allMatch(item -> "ExecCommandComponent".equals(item.get("componentName"))));
        assertTrue(invokes.stream().allMatch(item -> "terminal-1".equals(item.get("processId"))));
    }

    @Test
    void exposesHttpSenderCapabilityAndForwardsStructuredAndRawRequests() throws Exception {
        List<Map<String, Object>> invokes = new ArrayList<>();
        Disguise portable = new PortableDisguise();
        Communication communication = bytes -> {
            DecodedRequest decoded = decodeRequest(bytes);
            Map<String, Object> request = decoded.execution();
            if (decoded.request().operation() == PuppetOperation.COMPONENT_INVOKE) invokes.add(request);
            return response(decoded, Map.of("code", 200, "statusCode", 202,
                    "bodyType", "text", "body", "accepted"));
        };
        PhpPuppetNode node = node(communication, portable);

        assertTrue(node instanceof HttpSenderCapable);
        assertTrue(node instanceof Socks5ProxyCapable);
        assertTrue(node instanceof HttpProxyCapable);
        assertTrue(node instanceof LocalForwardCapable);
        assertTrue(node instanceof ReverseTunnelCapable);
        node.httpRequest("PUT", "http://example.test/direct", Map.of("X-Test", "yes"),
                "direct-body", 1000, 2000, true);
        Map<String, Object> raw = node.sendRawHttp(
                "POST /raw HTTP/1.1\r\nHost: example.test\r\n\r\nraw-body",
                "example.test", 8080, false, false, 3000, 4000);

        assertEquals(2, invokes.size());
        assertTrue(invokes.stream().allMatch(item -> "HttpRequestComponent".equals(item.get("componentName"))));
        assertTrue(invokes.stream().allMatch(item -> "send".equals(item.get("action"))));
        assertEquals("PUT", invokes.get(0).get("method"));
        assertEquals("direct-body", invokes.get(0).get("body"));
        assertEquals("http://example.test:8080/raw", invokes.get(1).get("url"));
        assertEquals("raw-body", invokes.get(1).get("body"));
        assertEquals("http://example.test:8080/raw", raw.get("requestUrl"));
    }

    @Test
    void forwardsTheSharedDatabaseContractToThePhpComponent() throws Exception {
        List<Map<String, Object>> invokes = new ArrayList<>();
        Disguise portable = new PortableDisguise();
        Communication communication = bytes -> {
            DecodedRequest decoded = decodeRequest(bytes);
            Map<String, Object> request = decoded.execution();
            if (decoded.request().operation() == PuppetOperation.COMPONENT_INVOKE) invokes.add(request);
            return response(decoded, Map.of("code", 200, "columns", List.of(), "rows", List.of(),
                    "rowCount", 0, "affectedRows", 1));
        };
        PhpPuppetNode node = node(communication, portable);

        assertTrue(node instanceof SqlCapable);
        Map<String, Object> result = node.executeSql(DatabaseConnectionSpec.fromMap(Map.of(
                        "dialect", "sqlite", "connectionMode", "standard",
                        "variant", "file", "file", "/tmp/example.sqlite",
                        "username", "db-user", "password", "db-password")),
                "UPDATE inventory SET quantity = 2 WHERE id = 1");

        assertEquals(1, invokes.size());
        Map<String, Object> request = invokes.get(0);
        assertEquals("DatabaseComponent", request.get("componentName"));
        assertEquals("exec", request.get("action"));
        assertEquals("pdo", request.get("provider"));
        assertEquals("sqlite", request.get("pdoDriver"));
        assertEquals("sqlite:/tmp/example.sqlite", request.get("dsn"));
        assertEquals("db-user", request.get("username"));
        assertEquals("db-password", request.get("password"));
        assertEquals("UPDATE inventory SET quantity = 2 WHERE id = 1", request.get("sql"));
        assertEquals(1, ((Number) result.get("affectedRows")).intValue());
    }

    @Test
    void forwardsBoundDatabaseParametersSeparatelyFromSql() throws Exception {
        List<Map<String, Object>> invokes = new ArrayList<>();
        Communication communication = bytes -> {
            DecodedRequest decoded = decodeRequest(bytes);
            if (decoded.request().operation() == PuppetOperation.COMPONENT_INVOKE) {
                invokes.add(decoded.execution());
            }
            return response(decoded, Map.of("code", 200, "columns", List.of(), "rows", List.of(),
                    "rowCount", 0, "affectedRows", 1));
        };
        PhpPuppetNode node = node(communication, new PortableDisguise());
        DatabaseConnectionSpec connection = DatabaseConnectionSpec.fromMap(Map.of(
                "dialect", "sqlite", "connectionMode", "standard",
                "variant", "file", "file", "/tmp/example.sqlite"));

        node.executeSql(connection, SqlCommand.parameterized(
                "UPDATE inventory SET name = ? WHERE id = ?", List.of("O'Reilly", 7)));

        assertEquals("UPDATE inventory SET name = ? WHERE id = ?", invokes.get(0).get("sql"));
        assertEquals(List.of("O'Reilly", 7), invokes.get(0).get("parameters"));
    }

    @Test
    void routesDatabaseRuntimeInspectionWithoutOpeningAConnection() throws Exception {
        List<Map<String, Object>> invokes = new ArrayList<>();
        Disguise portable = new PortableDisguise();
        Communication communication = bytes -> {
            DecodedRequest decoded = decodeRequest(bytes);
            Map<String, Object> request = decoded.execution();
            if (decoded.request().operation() == PuppetOperation.COMPONENT_INVOKE) invokes.add(request);
            return response(decoded, Map.of(
                    "code", 200,
                    "runtime", "php",
                    "provider", "pdo",
                    "available", true));
        };
        PhpPuppetNode node = node(communication, portable);

        Map<String, Object> result = node.inspectDatabaseRuntime(Map.of(
                "dialect", "postgresql",
                "runtimeOptions", Map.of("php", Map.of("pdoDriver", "pgsql"))));

        assertEquals("php", result.get("runtime"));
        assertEquals(1, invokes.size());
        assertEquals("DatabaseComponent", invokes.get(0).get("componentName"));
        assertEquals("capabilities", invokes.get(0).get("action"));
        assertEquals("pgsql", invokes.get(0).get("requestedDriver"));
        assertFalse(invokes.get(0).containsKey("dsn"));
        assertFalse(invokes.get(0).containsKey("password"));
    }

    private PhpPuppetNode node(Communication communication, Disguise disguise) {
        PhpRpcClient client = new PhpRpcClient(communication,
                List.of(new RequestLayer("/", Map.of(), disguise)),
                List.of(new ResponseLayer(disguise)), KEY);
        return new PhpPuppetNode(client, new PhpComponentArtifactRegistry());
    }

    private DecodedRequest decodeRequest(byte[] bytes) {
        try {
            Map<String, Object> wire = new PhpPayloadCodec(KEY).decode(bytes);
            PuppetRpcRequest request = PuppetRpcEnvelopeMapper.requestFromMap(wire);
            Map<String, Object> execution = new LinkedHashMap<>(request.params());
            if (request.component() != null) execution.put("componentName", request.component());
            if (request.action() != null) execution.put("action", request.action());
            if (request.hostId() != null) execution.put("hostId", request.hostId());
            return new DecodedRequest(execution, request);
        } catch (Exception e) {
            throw new IllegalStateException("PHP payload decode failed", e);
        }
    }

    private byte[] response(DecodedRequest request, Map<String, Object> data) {
        try {
            return new PhpPayloadCodec(KEY).encode(PuppetRpcEnvelopeMapper.toMap(
                    PuppetRpcEnvelopeMapper.responseFromResult(request.request().requestId(), data)));
        } catch (Exception e) {
            throw new IllegalStateException("PHP payload encode failed", e);
        }
    }

    private record DecodedRequest(Map<String, Object> execution, PuppetRpcRequest request) { }

    private static final class PortableDisguise extends Disguise {
        private PortableDisguise() {
            setTrafficEncodeBody("public byte[] encodeTraffic(byte[] data){return data;}");
            setTrafficDecodeBody("public byte[] decodeTraffic(byte[] data){return data;}");
        }

        @Override
        public byte[] encodeTraffic(byte[] payload) {
            return payload;
        }

        @Override
        public byte[] decodeTraffic(byte[] data) {
            return data;
        }
    }
}
