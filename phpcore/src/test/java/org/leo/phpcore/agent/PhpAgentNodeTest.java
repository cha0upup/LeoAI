package org.leo.phpcore.agent;

import org.junit.jupiter.api.Test;
import org.leo.core.entity.Disguise;
import org.leo.core.net.Communication;
import org.leo.core.net.layer.RequestLayer;
import org.leo.core.net.layer.ResponseLayer;
import org.leo.core.agent.capability.TerminalCapable;
import org.leo.core.agent.capability.HttpSenderCapable;
import org.leo.core.agent.capability.HttpProxyCapable;
import org.leo.core.agent.capability.EventLogCapable;
import org.leo.core.agent.capability.FirewallCapable;
import org.leo.core.agent.capability.LocalForwardCapable;
import org.leo.core.agent.capability.NetworkConnectionCapable;
import org.leo.core.agent.capability.NetworkInfoCapable;
import org.leo.core.agent.capability.ProcessCapable;
import org.leo.core.agent.capability.RegistryCapable;
import org.leo.core.agent.capability.ReverseTunnelCapable;
import org.leo.core.agent.capability.NetworkProbeCapable;
import org.leo.core.agent.capability.ScheduledTaskCapable;
import org.leo.core.agent.capability.ServiceCapable;
import org.leo.core.agent.capability.Socks5ProxyCapable;
import org.leo.core.agent.capability.SqlCapable;
import org.leo.core.agent.capability.UserAccountCapable;
import org.leo.core.agent.database.DatabaseConnectionSpec;
import org.leo.core.agent.database.SqlCommand;
import org.leo.core.rpc.AgentOperation;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PhpAgentNodeTest {
    private static final String KEY = "php-node-test-key";

    @Test
    void rejectsPingWithoutCurrentComponentList() throws Exception {
        Communication communication = bytes -> response(decodeRequest(bytes),
                Map.of("code", 200, "hostId", "php-host"));
        PhpAgentNode node = node(communication, new PortableDisguise());

        assertEquals(502, node.testConnection().get("code"));
    }

    @Test
    void restoresOpaquePingAliasesAndUsesEndpointVariantForInvocations() throws Exception {
        List<Map<String, Object>> invokes = new ArrayList<>();
        Disguise portable = new PortableDisguise();
        String host = "variant-host";
        String alias = new PhpComponentVariantBuilder().alias("BasicInfoComponent", host);
        Communication communication = bytes -> {
            DecodedRequest decoded = decodeRequest(bytes);
            if (decoded.operation() == AgentOperation.PING) {
                return response(decoded, Map.of("code", 200, "hostId", host, "components", List.of(alias)));
            }
            if (decoded.operation() == AgentOperation.COMPONENT_INVOKE) invokes.add(decoded.execution());
            return response(decoded, Map.of("code", 200));
        };
        PhpAgentNode node = node(communication, portable);

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
            if (decoded.operation() == AgentOperation.COMPONENT_INVOKE) invokes.add(request);
            return response(decoded, Map.of("code", 200));
        };
        PhpAgentNode node = node(communication, portable);

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
            if (decoded.operation() == AgentOperation.COMPONENT_INVOKE) invokes.add(request);
            return response(decoded, Map.of("code", 200));
        };
        PhpAgentNode node = node(communication, portable);

        assertTrue(node instanceof NetworkConnectionCapable);
        assertTrue(node instanceof NetworkProbeCapable);
        assertEquals(16, node.networkProbeWorkerLimit());
        assertTrue(node instanceof ServiceCapable);
        assertTrue(node instanceof ScheduledTaskCapable);
        node.listNetworkConnections("LISTEN", "TCP", "8080", "12", "php", "10.", true, 100);
        node.networkConnectionSummary();
        node.startNetworkProbe(Map.of("targets", List.of(Map.of("host", "127.0.0.1", "port", 80)),
                "stages", List.of("tcp-connect")));
        node.queryNetworkProbe("scan-task", 0L, 0L, 50, 524288, true);
        node.releaseNetworkProbe("scan-task");
        node.listServices();
        node.createService("demo", "/opt/demo", "Demo", "auto");
        node.listScheduledTasks();
        node.createScheduledTaskLinux("*/5 * * * *", "/opt/demo --check");

        assertEquals(List.of("NetworkConnectionComponent", "NetworkConnectionComponent",
                        "NetworkProbeComponent", "NetworkProbeComponent", "NetworkProbeComponent", "ServiceComponent",
                        "ServiceComponent", "ScheduledTaskComponent", "ScheduledTaskComponent"),
                invokes.stream().map(item -> String.valueOf(item.get("componentName"))).toList());
                assertEquals(List.of("list", "summary", "startTask", "queryTask", "releaseTask", "list",
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
            if (decoded.operation() == AgentOperation.COMPONENT_INVOKE) invokes.add(request);
            return response(decoded, Map.of("code", 200));
        };
        PhpAgentNode node = node(communication, portable);

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
        List<AgentOperation> methods = new ArrayList<>();
        Set<String> cachedKeys = new HashSet<>();
        Disguise portable = new PortableDisguise();
        Communication communication = bytes -> {
            DecodedRequest decoded = decodeRequest(bytes);
            Map<String, Object> request = decoded.execution();
            AgentOperation operation = decoded.operation();
            methods.add(operation);
            String componentKey = String.valueOf(request.get("componentKey"));
            if (operation == AgentOperation.COMPONENT_LOAD) {
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
        PhpAgentNode node = node(communication, portable);

        node.getBasicInfo();
        node.getBasicInfo();

        assertEquals(List.of(AgentOperation.COMPONENT_INVOKE, AgentOperation.COMPONENT_LOAD,
                AgentOperation.COMPONENT_INVOKE, AgentOperation.COMPONENT_INVOKE), methods);
        assertTrue(node.getLoadedComponents().contains("BasicInfoComponent"));
        assertTrue(node.getAvailableComponents().contains("PluginComponent"));
    }

    @Test
    void reloadsOnceWhenPreviouslyAvailableComponentDisappears() throws Exception {
        List<AgentOperation> methods = new ArrayList<>();
        AtomicInteger invokes = new AtomicInteger();
        Disguise portable = new PortableDisguise();
        Communication communication = bytes -> {
            DecodedRequest decoded = decodeRequest(bytes);
            AgentOperation operation = decoded.operation();
            methods.add(operation);
            if (operation == AgentOperation.COMPONENT_INVOKE && invokes.getAndIncrement() == 1) {
                return response(decoded, Map.of("code", 424));
            }
            return response(decoded, Map.of("code", 200, "data", "ok"));
        };
        PhpAgentNode node = node(communication, portable);

        node.execSimpleCommand("echo first");
        Map<String, Object> result = node.execSimpleCommand("echo second");

        assertEquals("ok", result.get("data"));
        assertEquals(List.of(AgentOperation.COMPONENT_INVOKE, AgentOperation.COMPONENT_INVOKE,
                AgentOperation.COMPONENT_LOAD, AgentOperation.COMPONENT_INVOKE), methods);
    }

    @Test
    void testConnectionUsesMethodZeroAndUnloadOnlyClearsPlatformState() throws Exception {
        List<AgentOperation> methods = new ArrayList<>();
        Disguise portable = new PortableDisguise();
        Communication communication = bytes -> {
            DecodedRequest decoded = decodeRequest(bytes);
            methods.add(decoded.operation());
            return response(decoded, Map.of("code", 200, "hostId", "php-host",
                    "components", List.of("FileComponent")));
        };
        PhpAgentNode node = node(communication, portable);

        Map<String, Object> result = node.testConnection();
        assertEquals("php-host", result.get("hostId"));
        assertTrue(node.getLoadedComponents().contains("FileComponent"));
        node.unloadComponent("FileComponent");
        assertFalse(node.getLoadedComponents().contains("FileComponent"));
        assertEquals(List.of(AgentOperation.PING), methods);
    }

    @Test
    void exposesTerminalCapabilityAndForwardsTerminalActions() throws Exception {
        List<Map<String, Object>> invokes = new ArrayList<>();
        Disguise portable = new PortableDisguise();
        Communication communication = bytes -> {
            DecodedRequest decoded = decodeRequest(bytes);
            Map<String, Object> request = decoded.execution();
            if (decoded.operation() == AgentOperation.COMPONENT_INVOKE) invokes.add(request);
            return response(decoded, Map.of("code", 200));
        };
        PhpAgentNode node = node(communication, portable);

        assertTrue(node instanceof TerminalCapable);
        node.execCommand("init", "", "terminal-1");
        node.execCommand("read", "", "terminal-1");
        node.execCommand("resize", "120,40", "terminal-1");
        node.execCommand("stop", "", "terminal-1");

        assertEquals(List.of("init", "read", "resize", "stop"),
                invokes.stream().map(item -> String.valueOf(item.get("action"))).toList());
        assertTrue(invokes.stream().allMatch(item -> "ExecCommandComponent".equals(item.get("componentName"))));
        assertTrue(invokes.stream().allMatch(item -> "terminal-1".equals(item.get("processId"))));
        assertTrue(invokes.stream().noneMatch(item -> item.containsKey("includeOutput")));
        invokes.clear();
        node.readTerminals(List.of("terminal-1", "terminal-2"));
        assertEquals(1, invokes.size());
        assertEquals("read-batch", invokes.get(0).get("action"));
        assertEquals(List.of("terminal-1", "terminal-2"), invokes.get(0).get("processIds"));
        invokes.clear();
        node.execTerminal("write", "pwd\n", "terminal-1", null, true);
        assertEquals(1, invokes.size());
        assertEquals("write", invokes.get(0).get("action"));
        assertEquals(true, invokes.get(0).get("includeOutput"));
        assertThrows(IllegalArgumentException.class,
                () -> node.execTerminal("read", "", "terminal-1", null, true));
        assertThrows(IllegalArgumentException.class,
                () -> node.execTerminal("init", "", "terminal-1", "python-pty", true));
        assertEquals(1, invokes.size());
    }

    @Test
    void exposesHttpSenderCapabilityAndForwardsStructuredAndRawRequests() throws Exception {
        List<Map<String, Object>> invokes = new ArrayList<>();
        Disguise portable = new PortableDisguise();
        Communication communication = bytes -> {
            DecodedRequest decoded = decodeRequest(bytes);
            Map<String, Object> request = decoded.execution();
            if (decoded.operation() == AgentOperation.COMPONENT_INVOKE) invokes.add(request);
            return response(decoded, Map.of("code", 200, "statusCode", 202,
                    "bodyType", "text", "body", "accepted"));
        };
        PhpAgentNode node = node(communication, portable);

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
            if (decoded.operation() == AgentOperation.COMPONENT_INVOKE) invokes.add(request);
            return response(decoded, Map.of("code", 200, "columns", List.of(), "rows", List.of(),
                    "rowCount", 0, "affectedRows", 1));
        };
        PhpAgentNode node = node(communication, portable);

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
            if (decoded.operation() == AgentOperation.COMPONENT_INVOKE) {
                invokes.add(decoded.execution());
            }
            return response(decoded, Map.of("code", 200, "columns", List.of(), "rows", List.of(),
                    "rowCount", 0, "affectedRows", 1));
        };
        PhpAgentNode node = node(communication, new PortableDisguise());
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
            if (decoded.operation() == AgentOperation.COMPONENT_INVOKE) invokes.add(request);
            return response(decoded, Map.of(
                    "code", 200,
                    "runtime", "php",
                    "provider", "pdo",
                    "available", true));
        };
        PhpAgentNode node = node(communication, portable);

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

    private PhpAgentNode node(Communication communication, Disguise disguise) {
        PhpRpcClient client = new PhpRpcClient(communication,
                List.of(new RequestLayer("/", Map.of(), disguise, KEY)),
                List.of(new ResponseLayer(disguise, KEY)));
        return new PhpAgentNode(client, new PhpComponentArtifactRegistry());
    }

    @SuppressWarnings("unchecked")
    private DecodedRequest decodeRequest(byte[] bytes) {
        try {
            Map<String, Object> wire = new PhpPayloadCodec(KEY).decode(bytes);
            Map<String, Object> execution = new LinkedHashMap<>((Map<String, Object>) wire.get("params"));
            if (wire.containsKey("component")) execution.put("componentName", wire.get("component"));
            if (wire.containsKey("action")) execution.put("action", wire.get("action"));
            if (wire.containsKey("hostId")) execution.put("hostId", wire.get("hostId"));
            return new DecodedRequest(execution, (String) wire.get("requestId"),
                    AgentOperation.valueOf((String) wire.get("operation")));
        } catch (Exception e) {
            throw new IllegalStateException("PHP payload decode failed", e);
        }
    }

    private byte[] response(DecodedRequest request, Map<String, Object> data) {
        try {
            // The simulated server builds wire data independently of the client mapper.
            Map<String, Object> payload = new LinkedHashMap<>(data);
            int code = ((Number) payload.remove("code")).intValue();
            String payloadKey = code >= 200 && code < 300 ? "data" : "error";
            if ("error".equals(payloadKey)) {
                Object message = payload.remove("msg");
                if (message != null) payload.put("message", message);
            }
            return new PhpPayloadCodec(KEY).encode(Map.of(
                    "requestId", request.requestId(), "code", code, payloadKey, payload));
        } catch (Exception e) {
            throw new IllegalStateException("PHP payload encode failed", e);
        }
    }

    private record DecodedRequest(Map<String, Object> execution, String requestId, AgentOperation operation) { }

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
