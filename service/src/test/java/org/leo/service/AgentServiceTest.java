package org.leo.service;

import org.junit.jupiter.api.Test;
import org.leo.core.entity.Agent;
import org.leo.dao.mapper.AgentMapper;

import java.util.List;
import java.util.Arrays;
import java.util.stream.IntStream;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AgentServiceTest {

    @Test
    void skipsChildQueriesForEmptyOrInvalidParentIds() {
        AgentMapper mapper = mock(AgentMapper.class);
        AgentService service = new AgentService(mapper);
        assertEquals(List.of(), service.findAgentsByParentAgentIds(null));
        assertEquals(List.of(), service.findAgentsByParentAgentIds(List.of()));
        assertEquals(List.of(), service.findAgentsByParentAgentIds(Arrays.asList(null, "", " ")));
        verifyNoInteractions(mapper);
    }

    @Test
    void normalizesAndDeduplicatesParentsBeforeOneChildQuery() {
        AgentMapper mapper = mock(AgentMapper.class);
        AgentService service = new AgentService(mapper);
        Agent child = agent("child");
        when(mapper.findAgentsByParentAgentIds(List.of("a", "b"))).thenReturn(List.of(child));
        assertEquals(List.of(child), service.findAgentsByParentAgentIds(Arrays.asList(" a ", "b", "a", null)));
        verify(mapper).findAgentsByParentAgentIds(List.of("a", "b"));
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void chunksLargeDirectoriesBelowSqliteParameterLimits() {
        AgentMapper mapper = mock(AgentMapper.class);
        AgentService service = new AgentService(mapper);
        when(mapper.findAgentsByParentAgentIds(anyList())).thenReturn(List.of());
        List<String> ids = IntStream.range(0, 1001).mapToObj(i -> "parent-" + i).toList();
        assertEquals(List.of(), service.findAgentsByParentAgentIds(ids));
        ArgumentCaptor<List<String>> batches = ArgumentCaptor.forClass((Class) List.class);
        verify(mapper, times(3)).findAgentsByParentAgentIds(batches.capture());
        assertEquals(List.of(500, 500, 1), batches.getAllValues().stream().map(List::size).toList());
        assertEquals(ids, batches.getAllValues().stream().flatMap(List::stream).toList());
    }

    @Test
    void rejectsMaximumRequestCountOutsideThePublicContract() {
        AgentMapper mapper = mock(AgentMapper.class);
        AgentService service = new AgentService(mapper);
        Agent agent = new Agent();
        agent.setMaxReqCount(Agent.MAX_REQUEST_COUNT + 1);

        assertThrows(IllegalArgumentException.class, () -> service.insertAgent(agent));
        verifyNoInteractions(mapper);
    }

    @Test
    void deletesCompleteAgentSubtreeFromLeavesToRoot() {
        AgentMapper mapper = mock(AgentMapper.class);
        AgentService service = new AgentService(mapper);
        Agent root = agent("root-node");
        Agent child = agent("child-node");
        Agent grandchild = agent("grandchild-node");
        when(mapper.findAgentById("root-node")).thenReturn(root);
        when(mapper.findAgentsByParentAgentId("root-node")).thenReturn(List.of(child));
        when(mapper.findAgentsByParentAgentId("child-node")).thenReturn(List.of(grandchild));
        when(mapper.findAgentsByParentAgentId("grandchild-node")).thenReturn(List.of());
        when(mapper.deleteAgentById("grandchild-node")).thenReturn(true);
        when(mapper.deleteAgentById("child-node")).thenReturn(true);
        when(mapper.deleteAgentById("root-node")).thenReturn(true);

        assertTrue(service.deleteAgentById("root-node"));

        var order = inOrder(mapper);
        order.verify(mapper).deleteAgentById("grandchild-node");
        order.verify(mapper).deleteAgentById("child-node");
        order.verify(mapper).deleteAgentById("root-node");
    }

    @Test
    void rollsBackContractWhenAnySubtreeNodeCannotBeDeleted() {
        AgentMapper mapper = mock(AgentMapper.class);
        AgentService service = new AgentService(mapper);
        Agent root = agent("root-node");
        Agent child = agent("child-node");
        when(mapper.findAgentById("root-node")).thenReturn(root);
        when(mapper.findAgentsByParentAgentId("root-node")).thenReturn(List.of(child));
        when(mapper.findAgentsByParentAgentId("child-node")).thenReturn(List.of());
        when(mapper.deleteAgentById("child-node")).thenReturn(false);

        assertThrows(IllegalStateException.class, () -> service.deleteAgentById("root-node"));
    }

    private static Agent agent(String agentId) {
        Agent agent = new Agent();
        agent.setAgentId(agentId);
        return agent;
    }
}
