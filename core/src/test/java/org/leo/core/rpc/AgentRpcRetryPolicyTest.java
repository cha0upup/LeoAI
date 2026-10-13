package org.leo.core.rpc;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AgentRpcRetryPolicyTest {
    @ParameterizedTest
    @CsvSource({
            "BasicInfoComponent,basic,true", "BasicInfoComponent,get,true",
            "FileComponent,list,true", "FileDownloadComponent,,true",
            "ResourceComponent,,true", "DatabaseComponent,capabilities,true",
            "NetworkProbeComponent,queryTask,true", "NetworkProbeComponent,ackTask,true",
            "TomcatContainerManageComponent,inspectRuntime,true", "SpringFrameworkManageComponent,getFrameworkInfo,true",
            "BasicInfoComponent,killProcess,false", "FileComponent,delete,false",
            "FileUploadComponent,,false", "DatabaseComponent,execSql,false",
            "ExecCommandComponent,,false", "ProxyForwardComponent,,false",
            "NetworkProbeComponent,startTask,false", "FileEnhanceComponent,list,false",
            "UnknownComponent,get,false"
    })
    void replaysOnlyKnownSnapshotOperations(String component, String action, boolean expected) {
        assertEquals(expected, AgentRpcRetryPolicy.isReplayable(request(AgentOperation.COMPONENT_INVOKE,
                component, action, Map.of("sql", "SELECT 1", "op", 1))));
    }

    @Test
    void doesNotReplayLoadsRelaysOrConsumingTerminalReads() {
        assertTrue(AgentRpcRetryPolicy.isReplayable(request(AgentOperation.PING, null, null, Map.of())));
        for (AgentOperation operation : new AgentOperation[]{AgentOperation.COMPONENT_LOAD, AgentOperation.RELAY}) {
            assertFalse(AgentRpcRetryPolicy.isReplayable(request(operation, "BasicInfoComponent", "basic", Map.of())));
        }
        assertFalse(AgentRpcRetryPolicy.isReplayable(request(AgentOperation.COMPONENT_INVOKE, null, null, Map.of())));
        for (int op : new int[]{1, 5}) assertFalse(AgentRpcRetryPolicy.isReplayable(
                request(AgentOperation.COMPONENT_INVOKE, "ExecCommandComponent", null, Map.of("op", op))));
    }

    @Test
    void backoffRemainsBoundedAtOverflowAndHonorsDisabledDelays() {
        assertEquals(0, AgentRpcRetryPolicy.delayMillis("fixture", 1, 0, 1000));
        assertEquals(0, AgentRpcRetryPolicy.delayMillis("fixture", 1, 100, 0));
        long delay = AgentRpcRetryPolicy.delayMillis("fixture", Integer.MAX_VALUE, Long.MAX_VALUE, 2000);
        assertTrue(delay > 0 && delay <= 2000);
        assertEquals(delay, AgentRpcRetryPolicy.delayMillis("fixture", Integer.MAX_VALUE, Long.MAX_VALUE, 2000));
    }

    private AgentRpcRequest request(AgentOperation operation, String component, String action, Map<String, Object> params) {
        return new AgentRpcRequest("fixture", operation, "host", component, action, params);
    }
}
