package org.leo.web.controller.platform.admin;

import org.junit.jupiter.api.Test;
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

class UserControllerTest {

    @Test
    void unassignedUserListingUsesTheSharedFilterAndRequiresAdmin() {
        UserService service = mock(UserService.class);
        UserController controller = new UserController(service, mock(PasswordPolicy.class));
        MockHttpServletRequest request = new MockHttpServletRequest();
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
        UserService service = mock(UserService.class);
        UserController controller = new UserController(service, mock(PasswordPolicy.class));
        MockHttpServletRequest request = new MockHttpServletRequest();
        User caller = new User();
        caller.setUserId("user-1");
        caller.setPrivilege(UserService.PRIVILEGE_LEADER);
        caller.setTeamId("team-1");
        request.getSession(true).setAttribute("user", caller);
        when(service.getUserByTeamId("team-1")).thenReturn(List.of(caller));
        when(service.getUserById("user-1")).thenReturn(caller);

        assertEquals(200, controller.getUsers(request).get("code"));
        verify(service).getUserByTeamId("team-1");
        caller.setPrivilege(UserService.PRIVILEGE_NORMAL);
        assertEquals(200, controller.getUsers(request).get("code"));
        verify(service).getUserById("user-1");
        verify(service, org.mockito.Mockito.never()).getAllUser();
    }

    @Test
    @SuppressWarnings("unchecked")
    void userListDoesNotMutateOrExposePasswordHash() {
        UserService userService = mock(UserService.class);
        User stored = new User();
        stored.setUserId("user-1");
        stored.setUserName("alice");
        stored.setPassword("pbkdf2-secret-hash");
        stored.setPrivilege(UserService.PRIVILEGE_NORMAL);
        stored.setStatus(1);
        when(userService.getAllUser()).thenReturn(List.of(stored));
        UserController controller = new UserController(
                userService, mock(PasswordPolicy.class));
        MockHttpServletRequest request = new MockHttpServletRequest();
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
        UserService userService = mock(UserService.class);
        User target = new User();
        target.setUserId("user-1");
        target.setUserName("alice");
        target.setPrivilege(UserService.PRIVILEGE_NORMAL);
        target.setPasswordChangeRequired(0);
        when(userService.getUserById("user-1")).thenReturn(target);
        when(userService.updateUser(target)).thenReturn(true);
        UserController controller = new UserController(
                userService, mock(PasswordPolicy.class));
        MockHttpServletRequest request = new MockHttpServletRequest();
        User admin = new User();
        admin.setPrivilege(UserService.PRIVILEGE_ADMIN);
        request.getSession(true).setAttribute("user", admin);
        HashMap<String, Object> params = new HashMap<>();
        params.put("userId", "user-1");
        params.put("newPassword", "new-password");

        Map<String, Object> response = controller.resetPassword(request, params);

        assertEquals(200, response.get("code"));
        assertEquals(1, target.getPasswordChangeRequired());
        verify(userService).updateUser(target);
    }
}
