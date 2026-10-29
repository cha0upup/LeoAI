package org.leo.web.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.leo.core.entity.Agent;
import org.leo.core.entity.User;
import org.leo.core.session.AgentNodeSession;
import org.leo.core.session.AgentNodeSessionContainer;
import org.leo.service.AgentService;
import org.leo.service.user.UserService;
import org.leo.web.exception.ApiException;
import org.leo.web.interceptor.LoginInterceptor;
import org.leo.web.util.ControllerUtil;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.method.HandlerMethod;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SessionResourceAccessTest {

    private final AgentService agents = mock(AgentService.class);
    private final UserService users = mock(UserService.class);
    private final PermissionService permissions = new PermissionService(agents, users);
    private final User reader = user("team-a");
    private final Agent host = new Agent();
    private final AgentNodeSession session = new AgentNodeSession();

    @BeforeEach
    void setUp() {
        host.setAgentId("host");
        host.setParentAgentId("root");
        host.setCreateByUserId("owner");
        host.setTeamId("team-a");
        host.setPermission("team");
        session.setSessionId("connection");
        session.setCreateByUser("reader");
        session.setAgentId("host");
        session.setCacheMode(true);
        AgentNodeSessionContainer.addSession("connection", session);
        when(agents.findAgentById("host")).thenReturn(host);
        when(users.getUserById("reader")).thenReturn(reader);
    }

    @AfterEach
    void cleanUp() {
        RequestContextHolder.resetRequestAttributes();
        AgentNodeSessionContainer.clearAllSessions();
    }

    @Test
    void withdrawnSharingBlocksAnExistingHttpSessionAndAgentConnection() throws Exception {
        authenticate(new MockHttpServletRequest("POST", "/agent-node/basic-info"));
        assertSame(session, ControllerUtil.getAgentNodeSession("connection"));

        host.setPermission("private");

        assertEquals(403, assertThrows(ApiException.class,
                () -> ControllerUtil.getAgentNodeSession("connection")).getCode());
    }

    @Test
    void membershipChangesAreRecheckedOnTheNextRequest() throws Exception {
        MockHttpServletRequest first = new MockHttpServletRequest("POST", "/agent-node/basic-info");
        authenticate(first);
        assertSame(session, ControllerUtil.getAgentNodeSession("connection"));
        when(users.getUserById("reader")).thenReturn(user("team-b"));
        MockHttpServletRequest next = new MockHttpServletRequest("POST", "/agent-node/basic-info");
        next.setSession(first.getSession(false));
        authenticate(next);

        assertEquals(403, assertThrows(ApiException.class,
                () -> ControllerUtil.getAgentNodeSession("connection")).getCode());
    }

    @Test
    void aSessionAttributeAloneDoesNotReplaceRequestVerification() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.getSession().setAttribute("user", reader);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        assertEquals(401, assertThrows(ApiException.class,
                () -> ControllerUtil.getAgentNodeSession("connection")).getCode());
        verifyNoInteractions(agents);
    }

    private void authenticate(MockHttpServletRequest request) throws Exception {
        if (request.getSession(false) == null) request.getSession().setAttribute("user", reader);
        HandlerMethod handler = new HandlerMethod(new Endpoint(), Endpoint.class.getMethod("read"));
        assertTrue(new LoginInterceptor(permissions).preHandle(request, new MockHttpServletResponse(), handler));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    private static User user(String team) {
        User user = new User();
        user.setUserId("reader");
        user.setStatus(1);
        user.setPrivilege("normal");
        user.setPassword("fixture-hash");
        user.setTeamId(team);
        return user;
    }

    public static class Endpoint {
        public void read() { }
    }
}
