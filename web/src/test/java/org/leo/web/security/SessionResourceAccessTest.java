package org.leo.web.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.leo.core.agent.AbstractAgentNode;
import org.leo.core.agent.capability.BasicInfoCapable;
import org.leo.core.entity.Agent;
import org.leo.core.entity.User;
import org.leo.core.runtime.CapabilitySet;
import org.leo.core.session.AgentNodeSession;
import org.leo.core.session.AgentNodeSessionContainer;
import org.leo.service.agentnode.AgentService;
import org.leo.service.user.UserService;
import org.leo.web.application.agent.AgentCacheService;
import org.leo.web.controller.agentnode.AgentNodeController;
import org.leo.web.dto.SessionIdRequest;
import org.leo.web.exception.ApiException;
import org.leo.web.interceptor.LoginInterceptor;
import org.leo.web.util.ControllerUtil;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.method.HandlerMethod;

import java.util.Map;
import java.util.function.Supplier;

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
        session.bindHostId("cached-instance");
        var controller = new AgentNodeController(null, permissions, null, null, null, null);
        var body = new SessionIdRequest("connection");
        authenticate(new MockHttpServletRequest("POST", "/agent-node/capabilities"));
        assertSame(session, ControllerUtil.getAgentNodeSession("connection"));
        var data = (Map<?, ?>) controller.capabilities(body).get("data");
        assertEquals("cached-instance", data.get("currentHostId"));
        assertEquals(true, data.get("cacheMode"));

        host.setPermission("private");

        assertEquals(403, assertThrows(ApiException.class,
                () -> controller.capabilities(body)).getCode());
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

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void basicInfoAuthorizesOnceForEitherSessionMode(boolean cacheMode) throws Exception {
        var cache = mock(AgentCacheService.class);
        var controller = new AgentNodeController(null, permissions, null, null, cache, null);
        var body = new SessionIdRequest("connection");
        Map<String, Object> info = Map.of("OSInfo", Map.of("HostName", "fixture"));
        session.bindHostId("instance");
        session.setCacheMode(cacheMode);
        if (cacheMode) {
            when(cache.loadBasicInfo("reader", "host", "instance")).thenReturn(info);
        } else {
            AbstractAgentNode node = mock(AbstractAgentNode.class, withSettings().extraInterfaces(BasicInfoCapable.class));
            when(node.getAgent()).thenReturn(host);
            when(node.getCapabilitySet()).thenReturn(CapabilitySet.empty());
            when(((BasicInfoCapable) node).getBasicInfo()).thenReturn(Map.of("BasicInfo", info));
            session.setAgentNode(node);
        }
        var request = new MockHttpServletRequest("POST", "/agent-node/basic-info");
        authenticate(request);

        assertEquals(Map.of("BasicInfo", info), controller.basicInfo(body).get("data"));
        verify(agents).findAgentById("host");
        if (cacheMode) verify(cache).loadBasicInfo("reader", "host", "instance");
        else verify(cache).saveBasicInfo("connection", "instance", info);

        host.setPermission("private");
        var next = new MockHttpServletRequest("POST", "/agent-node/basic-info");
        next.setSession(request.getSession(false));
        authenticate(next);
        assertEquals(403, assertThrows(ApiException.class, () -> controller.basicInfo(body)).getCode());
        verify(agents, times(2)).findAgentById("host");
        verifyNoMoreInteractions(cache);
    }

    @Test
    void overlappingRefreshRequestsKeepTheSameLoginAndConnectionValid() throws Exception {
        when(users.getUserById("reader")).thenAnswer(invocation -> user("team-a"));
        MockHttpServletRequest first = new MockHttpServletRequest("POST", "/agent-node/network-probe/workflow/tasks");
        authenticate(first);
        for (int refresh = 0; refresh < 3; refresh++) {
            MockHttpServletRequest parallel = new MockHttpServletRequest("POST", "/agent-node/basic-info");
            parallel.setSession(first.getSession(false));
            authenticate(parallel);

            RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(first));
            assertSame(session, ControllerUtil.getAgentNodeSession("connection"));
            RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(parallel));
            assertSame(session, ControllerUtil.getAgentNodeSession("connection"));
            assertFalse(((org.springframework.mock.web.MockHttpSession) first.getSession(false)).isInvalid());
        }
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

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void currentUserReadsDoNotCreateOrRecreateAnonymousSessions(boolean fromContext) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        Supplier<User> currentUser = fromContext ? ControllerUtil::getCurrentUser
                : () -> ControllerUtil.getCurrentUser(request);

        assertNull(currentUser.get());
        assertFalse(ControllerUtil.isAdmin(request));
        assertNull(request.getSession(false));
        request.getSession().setAttribute("user", reader);
        assertSame(reader, currentUser.get());
        request.getSession(false).invalidate();
        assertNull(currentUser.get());
        assertFalse(ControllerUtil.isAdmin(request));
        assertNull(request.getSession(false));
        verifyNoInteractions(agents, users);
    }

    @Test
    void textValidationTrimsValuesAndKeepsEndpointErrors() {
        assertEquals("connection", ControllerUtil.requireText(" connection ", "缺少 sessionId"));
        for (String value : new String[]{null, "", " \t "}) {
            var error = assertThrows(ApiException.class, () -> ControllerUtil.requireText(value, "缺少 sessionId"));
            assertEquals(400, error.getCode());
            assertEquals("缺少 sessionId", error.getMessage());
        }
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
