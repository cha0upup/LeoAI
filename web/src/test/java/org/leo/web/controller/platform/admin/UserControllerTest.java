package org.leo.web.controller.platform.admin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.leo.core.entity.User;
import org.leo.service.user.UserService;
import org.leo.service.user.PasswordPolicy;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;
import java.util.Map;
import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.never;
import static org.mockito.ArgumentMatchers.any;

class UserControllerTest {
    private final UserService service = mock(UserService.class);
    private final UserController controller = new UserController(service, mock(PasswordPolicy.class));
    private final MockHttpServletRequest request = new MockHttpServletRequest();

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"invalid-session-user"})
    void rejectsMissingOrMalformedSessionIdentity(String cachedUser) {
        request.getSession().setAttribute("user", cachedUser);
        assertEquals(401, controller.getUsers(request).get("code"));
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"team-2", "missing-team"})
    void leaderCannotMoveMembersAcrossTeamBoundaries(String newTeam) {
        User target = account("member", "normal", "team-1");
        when(service.getUserById("member")).thenReturn(target);
        request.getSession().setAttribute("user", account("leader", "leader", "team-1"));
        HashMap<String, Object> params = new HashMap<>();
        params.put("userId", "member");
        params.put("userName", "changed-name");
        params.put("teamId", newTeam);

        var result = controller.updateUser(request, params);

        assertEquals(403, result.get("code"));
        assertEquals("team-1", target.getTeamId());
        assertEquals("member", target.getUserName());
        verify(service, never()).updateUser(any());
    }

    @Test
    void leaderCanEditMembersWithoutChangingTheirTeam() {
        User target = account("member", "normal", "team-1");
        when(service.getUserById("member")).thenReturn(target);
        when(service.updateUser(target)).thenReturn(true);
        request.getSession().setAttribute("user", account("leader", "leader", "team-1"));
        HashMap<String, Object> params = new HashMap<>(Map.of(
                "userId", "member", "teamId", " team-1 ", "remark", "updated"));

        var result = controller.updateUser(request, params);

        assertEquals(200, result.get("code"));
        assertEquals("team-1", target.getTeamId());
        assertEquals("updated", target.getRemark());
    }

    @Test
    void administratorRetainsTeamTransferAccess() {
        User target = account("member", "normal", "team-1");
        when(service.getUserById("member")).thenReturn(target);
        when(service.updateUser(target)).thenReturn(true);
        request.getSession().setAttribute("user", account("manager", "admin", null));

        var result = controller.updateUser(request,
                new HashMap<>(Map.of("userId", "member", "teamId", "team-2")));

        assertEquals(200, result.get("code"));
        assertEquals("team-2", target.getTeamId());
    }

    private static User account(String id, String role, String team) {
        User user = new User();
        user.setUserId(id);
        user.setUserName(id);
        user.setPrivilege(role);
        user.setTeamId(team);
        return user;
    }

    @Test
    void unassignedUserListingUsesTheSharedFilterAndRequiresAdmin() {
        User caller = new User();
        caller.setPrivilege(UserService.PRIVILEGE_NORMAL);
        request.getSession(true).setAttribute("user", caller);

        assertEquals(403, controller.getNoTeamUsers(request).get("code"));
        verifyNoInteractions(service);
        caller.setPrivilege(UserService.PRIVILEGE_ADMIN);
        when(service.getUsersWithoutTeam()).thenReturn(List.of());
        assertEquals(200, controller.getNoTeamUsers(request).get("code"));
        verify(service).getUsersWithoutTeam();
    }

    @Test
    void userListingKeepsLeaderAndNormalUserVisibilityBoundaries() {
        User caller = new User();
        caller.setUserId("user-1");
        caller.setPrivilege(UserService.PRIVILEGE_LEADER);
        caller.setTeamId("team-1");
        request.getSession(true).setAttribute("user", caller);
        when(service.getUsersByTeamId("team-1")).thenReturn(List.of(caller));
        when(service.getUserById("user-1")).thenReturn(caller);

        assertEquals(200, controller.getUsers(request).get("code"));
        verify(service).getUsersByTeamId("team-1");
        caller.setPrivilege(UserService.PRIVILEGE_NORMAL);
        assertEquals(200, controller.getUsers(request).get("code"));
        verify(service).getUserById("user-1");
        verify(service, org.mockito.Mockito.never()).getAllUsers();
    }

    @Test
    @SuppressWarnings("unchecked")
    void userListDoesNotMutateOrExposePasswordHash() {
        User stored = new User();
        stored.setUserId("user-1");
        stored.setUserName("alice");
        stored.setPassword("pbkdf2-secret-hash");
        stored.setPrivilege(UserService.PRIVILEGE_NORMAL);
        stored.setStatus(1);
        when(service.getAllUsers()).thenReturn(List.of(stored));
        User admin = new User();
        admin.setPrivilege(UserService.PRIVILEGE_ADMIN);
        request.getSession(true).setAttribute("user", admin);

        Map<String, Object> response = controller.getUsers(request);
        List<Map<String, Object>> users = (List<Map<String, Object>>) response.get("data");

        assertEquals("pbkdf2-secret-hash", stored.getPassword());
        assertEquals("alice", users.get(0).get("userName"));
        assertFalse(users.get(0).containsKey("password"));
    }

    @Test
    void marksAnAdministrativelyResetPasswordForMandatoryChange() {
        User target = new User();
        target.setUserId("user-1");
        target.setUserName("alice");
        target.setPrivilege(UserService.PRIVILEGE_NORMAL);
        target.setPasswordChangeRequired(0);
        when(service.getUserById("user-1")).thenReturn(target);
        when(service.updateUser(target)).thenReturn(true);
        User admin = new User();
        admin.setPrivilege(UserService.PRIVILEGE_ADMIN);
        request.getSession(true).setAttribute("user", admin);
        HashMap<String, Object> params = new HashMap<>();
        params.put("userId", "user-1");
        params.put("newPassword", "new-password");

        Map<String, Object> response = controller.resetPassword(request, params);

        assertEquals(200, response.get("code"));
        assertEquals(1, target.getPasswordChangeRequired());
        verify(service).updateUser(target);
    }
}
