package org.leo.web.controller.platform.agent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.leo.core.agent.AbstractAgentNode;
import org.leo.core.entity.Agent;
import org.leo.core.entity.User;
import org.leo.service.sql.DatabaseConnectionProfileException;
import org.leo.service.sql.DatabaseConnectionProfileService;
import org.leo.web.exception.ApiException;
import org.leo.web.util.ControllerUtil;
import org.mockito.MockedStatic;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DatabaseConnectionControllerTest {
    private final DatabaseConnectionProfileService profiles = mock(DatabaseConnectionProfileService.class);
    private final DatabaseConnectionController controller = new DatabaseConnectionController(profiles);
    private final MockHttpServletRequest request = new MockHttpServletRequest();
    private final HashMap<String, Object> params = new HashMap<>(Map.of(
            "sessionId", "session-1", "agentId", "forged-agent", "connectionId", " connection-1 ", "enabled", false));
    private MockedStatic<ControllerUtil> context;

    @BeforeEach
    void bindVerifiedAgentContext() {
        Agent agent = new Agent();
        agent.setAgentId("agent-1");
        AbstractAgentNode node = mock(AbstractAgentNode.class);
        when(node.getAgent()).thenReturn(agent);
        context = mockStatic(ControllerUtil.class);
        context.when(() -> ControllerUtil.getCurrentUser(request)).thenReturn(user("user-1"));
        context.when(() -> ControllerUtil.getAbstractAgentNode(params)).thenReturn(node);
    }

    @AfterEach
    void closeContext() {
        context.close();
    }

    @Test
    void usesVerifiedScopeAndPreservesResultsAndTypedFailures() {
        Map<String, Object> profile = Map.of("connectionId", "connection-1");
        Map<String, Object> status = Map.of("status", 0);
        List<Map<String, Object>> rows = List.of(profile);
        when(profiles.save("user-1", "agent-1", params)).thenReturn(profile);
        when(profiles.listByAgent("user-1", "agent-1")).thenReturn(rows);
        when(profiles.setEnabled("user-1", "agent-1", "connection-1", false)).thenReturn(status);

        assertSame(profile, controller.save(request, params).get("data"));
        assertSame(rows, controller.list(request, params).get("data"));
        assertSame(status, controller.updateStatus(request, params).get("data"));
        assertEquals(200, controller.delete(request, params).get("code"));
        verify(profiles).delete("user-1", "agent-1", "connection-1");

        var failure = new DatabaseConnectionProfileException(DatabaseConnectionProfileException.Kind.NOT_FOUND, "profile error");
        when(profiles.save("user-1", "agent-1", params)).thenThrow(failure);
        assertSame(failure, assertThrows(DatabaseConnectionProfileException.class, () -> controller.save(request, params)));
    }

    @ParameterizedTest
    @MethodSource("unauthenticatedUsers")
    void rejectsMissingUserBeforeAgentLookupAndProfileAccess(User user) {
        context.when(() -> ControllerUtil.getCurrentUser(request)).thenReturn(user);
        for (Runnable operation : operations()) {
            assertEquals(401, assertThrows(ApiException.class, operation::run).getCode());
        }
        context.verify(() -> ControllerUtil.getAbstractAgentNode(params), never());
        verifyNoInteractions(profiles);
    }

    @Test
    void rejectsDeniedAgentAccessBeforeAnyProfileOperation() {
        context.when(() -> ControllerUtil.getAbstractAgentNode(params)).thenThrow(ApiException.forbidden("agent denied"));
        for (Runnable operation : operations()) {
            assertEquals(403, assertThrows(ApiException.class, operation::run).getCode());
        }
        verifyNoInteractions(profiles);
    }

    private List<Runnable> operations() {
        return List.of(() -> controller.save(request, params), () -> controller.list(request, params),
                () -> controller.delete(request, params), () -> controller.updateStatus(request, params));
    }

    private static Stream<User> unauthenticatedUsers() {
        return Stream.of(null, user(null), user(""), user(" "));
    }

    private static User user(String id) {
        User user = new User();
        user.setUserId(id);
        return user;
    }
}
