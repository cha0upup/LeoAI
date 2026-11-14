package org.leo.web.application.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.leo.core.agent.AbstractAgentNode;
import org.leo.core.entity.Agent;
import org.leo.core.infrastructure.persistence.session.AgentHostCacheRepository;
import org.leo.core.infrastructure.persistence.session.AgentReconRepository;
import org.leo.core.session.AgentNodeSession;
import org.leo.service.agentnode.AgentService;
import org.leo.web.application.UseCaseException;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SessionReportServiceTest {
    private final AgentService agents = mock(AgentService.class);
    private final AgentHostCacheRepository hosts = mock(AgentHostCacheRepository.class);
    private final AgentReconRepository summaries = mock(AgentReconRepository.class);
    private final SessionReportService reports = new SessionReportService(agents, hosts, summaries);

    @ParameterizedTest
    @CsvSource({"false,agent-1", "true,agent-1", "true,' agent-1 '"})
    void readsPersistedDataUsingTheSameUserAgentAndHostForLiveAndCacheSessions(
            boolean cacheMode, String agentId) {
        AgentNodeSession session = session();
        session.setAgentId(agentId);
        Agent agent = new Agent();
        agent.setAgentId("agent-1");
        agent.setAgentName("server/report");
        agent.setConnLink("https://node.test");
        if (!cacheMode) {
            AbstractAgentNode node = mock(AbstractAgentNode.class);
            when(node.getAgent()).thenReturn(agent);
            session.setCacheMode(false);
            session.setAgentNode(node);
            session.setAgentId("stale-agent");
        }
        session.setReconSummary("unsaved summary");
        session.setBasicInfo("host-1", Map.of("OSInfo", Map.of("OSName", "unsaved-system")));
        when(agents.findAgentById("agent-1")).thenReturn(agent);
        when(hosts.loadBasicInfo("reader", "agent-1", "host-1")).thenReturn(Map.of(
                "OSInfo", Map.of("OSName", "Linux", "OSArch", ""),
                "JavaRuntimeInfo", Map.of("JavaVersion", 17),
                "MiddlewareInfo", List.of("invalid group"),
                "ProcessInfo", Map.of("ProcessId", 42)));
        when(summaries.load("reader", "agent-1", "host-1")).thenReturn(" persisted summary ");

        SessionReportService.Report report = reports.generate(session);

        assertEquals("connection", report.sessionId());
        assertEquals("recon-report-server_report.md", report.filename());
        assertTrue(report.report().contains("# 侦察报告 — server/report"));
        assertTrue(report.report().contains("连接地址：`https://node.test`"));
        assertTrue(report.report().contains("- OSName: Linux"));
        assertTrue(report.report().contains("- JavaVersion: 17"));
        assertTrue(report.report().contains("- ProcessId: 42"));
        assertTrue(report.report().contains("\npersisted summary\n"));
        assertFalse(report.report().contains("OSArch"));
        assertFalse(report.report().contains("**中间件**"));
        assertFalse(report.report().contains("unsaved"));
        verify(agents).findAgentById("agent-1");
        verify(hosts).loadBasicInfo("reader", "agent-1", "host-1");
        verify(summaries).load("reader", "agent-1", "host-1");
        verifyNoMoreInteractions(agents, hosts, summaries);
    }

    @Test
    void retainedSessionIdentitySupportsReportsWithoutALiveNodeOrSavedConfiguration() {
        AgentNodeSession session = session();
        session.setCacheMode(false);
        when(hosts.loadBasicInfo("reader", "agent-1", "host-1")).thenReturn(null);

        SessionReportService.Report report = reports.generate(session);

        assertEquals("recon-report-agent-1.md", report.filename());
        assertTrue(report.report().contains("# 侦察报告 — agent-1"));
        assertTrue(report.report().contains("连接地址：`-`"));
        assertTrue(report.report().contains("_暂无缓存数据_"));
        assertTrue(report.report().contains("_暂无侦察摘要_"));
        verify(hosts).loadBasicInfo("reader", "agent-1", "host-1");
        verify(summaries).load("reader", "agent-1", "host-1");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" "})
    void missingAgentIdentityIsRejectedBeforeReadingAnyData(String agentId) {
        AgentNodeSession session = session();
        session.setAgentId(agentId);

        UseCaseException error = assertThrows(UseCaseException.class, () -> reports.generate(session));

        assertEquals(UseCaseException.Kind.INVALID_INPUT, error.getKind());
        verifyNoInteractions(agents, hosts, summaries);
    }

    private AgentNodeSession session() {
        AgentNodeSession session = new AgentNodeSession();
        session.setSessionId("connection");
        session.setCreateByUser("reader");
        session.setCacheMode(true);
        session.setAgentId("agent-1");
        session.bindHostId("host-1");
        return session;
    }
}
