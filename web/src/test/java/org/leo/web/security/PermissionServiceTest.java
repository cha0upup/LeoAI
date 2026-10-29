package org.leo.web.security;

import org.junit.jupiter.api.Test;
import org.leo.core.entity.User;
import org.leo.service.AgentService;
import org.leo.service.user.UserService;
import org.leo.web.exception.ApiException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PermissionServiceTest {

    @Test
    void passwordChangesInvalidateEverySessionStillHoldingThePreviousCredential() {
        UserService users = mock(UserService.class);
        PermissionService permissions = new PermissionService(mock(AgentService.class), users);
        User changed = activeUser("user-1");
        changed.setPassword("new-password-hash");
        when(users.getUserById("user-1")).thenReturn(changed);
        for (int device = 0; device < 2; device++) {
            MockHttpServletRequest request = requestWithUser("user-1");
            User previous = (User) request.getSession(false).getAttribute("user");
            previous.setPassword("previous-password-hash");
            MockHttpSession session = (MockHttpSession) request.getSession(false);

            assertNull(permissions.getCurrentUser(request));
            assertTrue(session.isInvalid());
            assertNull(PermissionService.forVerifiedRequest(request));
        }
    }

    @Test
    void keepsTheCurrentSessionWhenItsCredentialWasExplicitlyRefreshed() {
        UserService users = mock(UserService.class);
        PermissionService permissions = new PermissionService(mock(AgentService.class), users);
        User changed = activeUser("user-1");
        changed.setPassword("new-password-hash");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.getSession().setAttribute("user", changed);
        when(users.getUserById("user-1")).thenReturn(changed);

        assertSame(changed, permissions.requireLogin(request));
        assertSame(permissions, PermissionService.forVerifiedRequest(request));
        request.getSession().setAttribute("user", activeUser("user-1"));
        assertNull(PermissionService.forVerifiedRequest(request));
    }

    @Test
    void refreshesActiveUserFromDatabase() {
        UserService users = mock(UserService.class);
        PermissionService service = new PermissionService(mock(AgentService.class), users);
        MockHttpServletRequest request = requestWithUser("user-1");
        User fresh = new User();
        fresh.setUserId("user-1");
        fresh.setStatus(1);
        when(users.getUserById("user-1")).thenReturn(fresh);

        assertSame(fresh, service.getCurrentUser(request));
        assertSame(fresh, request.getSession(false).getAttribute("user"));
    }

    @Test
    void invalidatesSessionWhenAccountIsDisabled() {
        UserService users = mock(UserService.class);
        PermissionService service = new PermissionService(mock(AgentService.class), users);
        MockHttpServletRequest request = requestWithUser("user-1");
        User disabled = new User();
        disabled.setUserId("user-1");
        disabled.setStatus(0);
        when(users.getUserById("user-1")).thenReturn(disabled);
        var session = (org.springframework.mock.web.MockHttpSession) request.getSession(false);

        assertNull(service.getCurrentUser(request));
        assertTrue(session.isInvalid());
    }

    @Test
    void sharesOneDatabaseVerificationWithinTheRequest() {
        UserService users = mock(UserService.class);
        PermissionService service = new PermissionService(mock(AgentService.class), users);
        MockHttpServletRequest request = requestWithUser("user-1");
        User fresh = activeUser("user-1");
        fresh.setPrivilege("normal");
        when(users.getUserById("user-1")).thenReturn(fresh);

        assertSame(fresh, service.getCurrentUser(request));
        assertSame(fresh, service.requireLogin(request));
        assertSame(fresh, service.getCurrentUser(request));
        verify(users).getUserById("user-1");
    }

    @Test
    void reloadsRoleTeamAndPasswordChangeFlagOnEveryNewRequest() {
        UserService users = mock(UserService.class);
        PermissionService service = new PermissionService(mock(AgentService.class), users);
        MockHttpServletRequest first = requestWithUser("user-1");
        User previous = activeUser("user-1");
        previous.setPrivilege("admin");
        previous.setTeamId("team-1");
        User changed = activeUser("user-1");
        changed.setPrivilege("normal");
        changed.setTeamId("team-2");
        changed.setPasswordChangeRequired(1);
        when(users.getUserById("user-1")).thenReturn(previous, changed);
        assertSame(previous, service.requireLogin(first));
        MockHttpServletRequest next = new MockHttpServletRequest();
        next.setSession(first.getSession(false));

        User current = service.requireLogin(next);

        assertSame(changed, current);
        assertEquals("normal", current.getPrivilege());
        assertEquals("team-2", current.getTeamId());
        assertTrue(current.requiresPasswordChange());
        verify(users, times(2)).getUserById("user-1");
    }

    @Test
    void noticesDisabledAccountOnTheNextRequestAndRejectsFurtherAccess() {
        UserService users = mock(UserService.class);
        PermissionService service = new PermissionService(mock(AgentService.class), users);
        MockHttpServletRequest first = requestWithUser("user-1");
        User disabled = activeUser("user-1");
        disabled.setStatus(0);
        when(users.getUserById("user-1")).thenReturn(activeUser("user-1"), disabled);
        service.requireLogin(first);
        MockHttpSession session = (MockHttpSession) first.getSession(false);
        MockHttpServletRequest next = new MockHttpServletRequest();
        next.setSession(session);

        assertNull(service.getCurrentUser(next));
        assertTrue(session.isInvalid());
        assertEquals(401, assertThrows(ApiException.class, () -> service.requireLogin(next)).getCode());
        verify(users, times(2)).getUserById("user-1");
    }

    @Test
    void invalidatesDeletedAccountsAndDoesNotReuseVerifiedUserAfterLogout() {
        UserService users = mock(UserService.class);
        PermissionService service = new PermissionService(mock(AgentService.class), users);
        MockHttpServletRequest request = requestWithUser("user-1");
        when(users.getUserById("user-1")).thenReturn(activeUser("user-1"));
        service.requireLogin(request);
        request.getSession(false).invalidate();

        assertNull(service.getCurrentUser(request));
        assertNull(request.getSession(false));
        verify(users).getUserById("user-1");

        MockHttpServletRequest deleted = requestWithUser("deleted");
        MockHttpSession deletedSession = (MockHttpSession) deleted.getSession(false);
        assertNull(service.getCurrentUser(deleted));
        assertTrue(deletedSession.isInvalid());
    }

    @Test
    void revalidatesWhenTheSessionUserIsReplacedEvenWithTheSameId() {
        UserService users = mock(UserService.class);
        PermissionService service = new PermissionService(mock(AgentService.class), users);
        MockHttpServletRequest request = requestWithUser("user-1");
        User fresh = activeUser("user-1");
        User replacement = activeUser("user-1");
        when(users.getUserById("user-1")).thenReturn(fresh, replacement);
        service.requireLogin(request);
        request.getSession(false).setAttribute("user", activeUser("user-1"));

        assertSame(replacement, service.requireLogin(request));
        verify(users, times(2)).getUserById("user-1");
    }

    @Test
    void revalidatesWhenIdentityIsChangedOnTheSameObject() {
        UserService users = mock(UserService.class);
        PermissionService service = new PermissionService(mock(AgentService.class), users);
        MockHttpServletRequest request = requestWithUser("user-1");
        User first = activeUser("user-1");
        User second = activeUser("user-2");
        when(users.getUserById("user-1")).thenReturn(first);
        when(users.getUserById("user-2")).thenReturn(second);
        service.requireLogin(request);
        first.setUserId("user-2");

        assertSame(second, service.requireLogin(request));
        verify(users).getUserById("user-2");
    }

    @Test
    void revalidatesAfterSessionReplacement() {
        UserService users = mock(UserService.class);
        PermissionService service = new PermissionService(mock(AgentService.class), users);
        MockHttpServletRequest request = requestWithUser("user-1");
        User fresh = activeUser("user-1");
        when(users.getUserById("user-1")).thenReturn(fresh);
        service.requireLogin(request);
        MockHttpSession replacement = new MockHttpSession();
        replacement.setAttribute("user", fresh);
        request.setSession(replacement);

        assertSame(fresh, service.requireLogin(request));
        verify(users, times(2)).getUserById("user-1");
    }

    @Test
    void doesNotCreateAnonymousSessionsOrCacheAnonymousStateOverLogin() {
        UserService users = mock(UserService.class);
        PermissionService service = new PermissionService(mock(AgentService.class), users);
        MockHttpServletRequest request = new MockHttpServletRequest();

        assertNull(service.getCurrentUser(null));
        assertNull(service.getCurrentUser(request));
        assertNull(request.getSession(false));
        request.getSession(true).setAttribute("user", "invalid");
        assertNull(service.getCurrentUser(request));
        request.getSession(false).setAttribute("user", new User());
        assertNull(service.getCurrentUser(request));
        verifyNoInteractions(users);

        User fresh = activeUser("user-1");
        request.getSession(false).setAttribute("user", fresh);
        when(users.getUserById("user-1")).thenReturn(fresh);
        assertSame(fresh, service.requireLogin(request));
        verify(users).getUserById("user-1");
    }

    private static User activeUser(String userId) {
        User user = new User();
        user.setUserId(userId);
        user.setStatus(1);
        return user;
    }

    private static MockHttpServletRequest requestWithUser(String userId) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        User cached = new User();
        cached.setUserId(userId);
        cached.setStatus(1);
        request.getSession(true).setAttribute("user", cached);
        return request;
    }
}
