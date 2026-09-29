package org.leo.web.controller.platform;

import org.junit.jupiter.api.Test;
import org.leo.core.entity.User;
import org.leo.core.entity.Team;
import org.leo.core.util.PasswordUtil;
import org.leo.service.team.TeamService;
import org.leo.service.user.UserService;
import org.leo.web.dto.platform.user.LoginRequest;
import org.leo.web.dto.platform.user.ChangePasswordRequest;
import org.leo.web.dto.platform.user.UpdateProfileRequest;
import org.leo.web.exception.ApiException;
import org.leo.web.security.PermissionService;
import org.leo.web.security.LoginAttemptService;
import org.leo.service.user.PasswordPolicy;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LoginControllerTest {

    private final UserService userService = mock(UserService.class);
    private final TeamService teamService = mock(TeamService.class);
    private final PermissionService permissionService = mock(PermissionService.class);
    private final LoginAttemptService loginAttemptService = mock(LoginAttemptService.class);
    private final PasswordPolicy passwordPolicy = mock(PasswordPolicy.class);
    private final LoginController controller = new LoginController(
            userService, teamService, permissionService, loginAttemptService, passwordPolicy);

    @Test
    void statusKeepsItsPublicFieldsAndBooleanPasswordChangeFlag() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        assertEquals(Map.of("isLoggedIn", false), controller.status(request).get("data"));
        User user = new User("user-1", "alice", "stored-hash", "normal", "2026-09-01");
        user.setPasswordChangeRequired(1);
        when(permissionService.getCurrentUser(request)).thenReturn(user);

        Map<?, ?> data = (Map<?, ?>) controller.status(request).get("data");

        assertEquals(Set.of("isLoggedIn", "userId", "userName", "privilege", "teamId", "passwordChangeRequired"), data.keySet());
        assertEquals(true, data.get("isLoggedIn"));
        assertEquals(true, data.get("passwordChangeRequired"));
        assertFalse(data.containsKey("password"));
        assertEquals("stored-hash", user.getPassword());
    }

    @Test
    void profileUsesTheSharedPublicViewAndIncludesTeamName() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        User user = new User("user-1", "alice", "stored-hash", "normal", "2026-09-01");
        user.setTeamId("team-1");
        Team team = new Team();
        team.setTeamName("Example team");
        when(permissionService.requireLogin(request)).thenReturn(user);
        when(userService.getUserById("user-1")).thenReturn(user);
        when(teamService.getTeamById("team-1")).thenReturn(team);

        Map<?, ?> data = (Map<?, ?>) controller.profile(request).get("data");

        assertEquals(Set.of("userId", "userName", "privilege", "email", "phone", "status", "teamId",
                "teamName", "remark", "lastLoginTime", "loginCount", "createTime", "updateTime"), data.keySet());
        assertEquals("Example team", data.get("teamName"));
        assertEquals("stored-hash", user.getPassword());
    }

    @Test
    void recordsLoginAndRotatesSessionOnSuccessfulLogin() {
        User user = new User();
        user.setUserId("user-1");
        user.setUserName("alice");
        user.setPassword(PasswordUtil.hash("secret"));
        user.setStatus(1);
        when(userService.getUserByName("alice")).thenReturn(user);
        when(userService.recordSuccessfulLogin("user-1")).thenReturn(user);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.getSession(true);
        String previousSessionId = request.getSession().getId();

        var response = controller.login(request, new LoginRequest("alice", "secret"));

        assertEquals(200, response.get("code"));
        assertSame(user, request.getSession(false).getAttribute("user"));
        verify(userService).recordSuccessfulLogin("user-1");
        verify(loginAttemptService).recordSuccess("alice", "127.0.0.1");
        // MockHttpServletRequest supports session rotation just like the servlet container.
        assertFalse(previousSessionId.equals(request.getSession(false).getId()));
        @SuppressWarnings("unchecked")
        var data = (java.util.Map<String, Object>) response.get("data");
        assertEquals(false, data.get("passwordChangeRequired"));
    }

    @Test
    void clearsRequiredPasswordChangeAfterSuccessfulUpdate() {
        User sessionUser = new User();
        sessionUser.setUserId("admin");
        User storedUser = new User();
        storedUser.setUserId("admin");
        storedUser.setPassword(PasswordUtil.hash("54ikun"));
        storedUser.setPasswordChangeRequired(1);
        when(permissionService.requireLogin(org.mockito.ArgumentMatchers.any())).thenReturn(sessionUser);
        when(userService.getUserById("admin")).thenReturn(storedUser);
        when(userService.updateUser(storedUser)).thenReturn(true);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.getSession(true).setAttribute("user", sessionUser);

        var response = controller.changePassword(request,
                new ChangePasswordRequest("54ikun", "new-password"));

        assertEquals(200, response.get("code"));
        assertEquals(0, storedUser.getPasswordChangeRequired());
        assertTrue(PasswordUtil.verify("new-password", storedUser.getPassword()));
        verify(passwordPolicy).validate("new-password");
        verify(userService).updateUser(storedUser);
    }

    @Test
    void returnsUnauthorizedForInvalidCredentials() {
        when(userService.getUserByName("missing")).thenReturn(null);

        ApiException error = assertThrows(ApiException.class,
                () -> controller.login(new MockHttpServletRequest(),
                        new LoginRequest("missing", "wrong")));

        assertEquals(401, error.getCode());
        verify(loginAttemptService).recordFailure("missing", "127.0.0.1");
    }

    @Test
    void rejectsDisabledUserEvenWhenPasswordIsCorrect() {
        User user = new User();
        user.setUserId("disabled-user");
        user.setUserName("disabled");
        user.setPassword(PasswordUtil.hash("secret"));
        user.setStatus(0);
        when(userService.getUserByName("disabled")).thenReturn(user);

        ApiException error = assertThrows(ApiException.class,
                () -> controller.login(new MockHttpServletRequest(),
                        new LoginRequest("disabled", "secret")));

        assertEquals(403, error.getCode());
        verify(loginAttemptService).recordFailure("disabled", "127.0.0.1");
    }

    @Test
    void updatesOnlyEditableProfileFieldsAndRefreshesSession() {
        User sessionUser = new User();
        sessionUser.setUserId("user-1");
        User storedUser = new User();
        storedUser.setUserId("user-1");
        storedUser.setUserName("alice");
        storedUser.setPrivilege(UserService.PRIVILEGE_NORMAL);
        storedUser.setTeamId("team-1");
        storedUser.setStatus(1);
        when(permissionService.requireLogin(org.mockito.ArgumentMatchers.any()))
                .thenReturn(sessionUser);
        when(userService.getUserById("user-1")).thenReturn(storedUser);
        when(userService.updateUser(storedUser)).thenReturn(true);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.getSession(true).setAttribute("user", sessionUser);

        var response = controller.updateProfile(request,
                new UpdateProfileRequest(" alice@example.com ", " 13800138000 ", "  "));

        assertEquals(200, response.get("code"));
        assertEquals("alice@example.com", storedUser.getEmail());
        assertEquals("13800138000", storedUser.getPhone());
        assertNull(storedUser.getRemark());
        assertEquals("alice", storedUser.getUserName());
        assertEquals("team-1", storedUser.getTeamId());
        assertSame(storedUser, request.getSession(false).getAttribute("user"));
        verify(userService).updateUser(storedUser);
    }

    @Test
    void rejectsProfileFieldsOverTheirLimits() {
        User sessionUser = new User();
        sessionUser.setUserId("user-1");
        when(permissionService.requireLogin(org.mockito.ArgumentMatchers.any()))
                .thenReturn(sessionUser);

        ApiException error = assertThrows(ApiException.class,
                () -> controller.updateProfile(new MockHttpServletRequest(),
                        new UpdateProfileRequest("x".repeat(101), null, null)));

        assertEquals(400, error.getCode());
    }

    @Test
    void rejectsInvalidProfileEmail() {
        User sessionUser = new User();
        sessionUser.setUserId("user-1");
        when(permissionService.requireLogin(org.mockito.ArgumentMatchers.any()))
                .thenReturn(sessionUser);

        ApiException error = assertThrows(ApiException.class,
                () -> controller.updateProfile(new MockHttpServletRequest(),
                        new UpdateProfileRequest("invalid-email", null, null)));

        assertEquals(400, error.getCode());
        assertEquals("邮箱格式不正确", error.getMessage());
    }
}
