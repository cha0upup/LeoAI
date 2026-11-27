package org.leo.web.controller.platform.admin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.leo.core.entity.User;
import org.leo.core.entity.UserOption;
import org.leo.core.util.PasswordUtil;
import org.leo.service.user.UserService;
import org.leo.service.user.PasswordPolicy;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;
import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.never;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

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

    @Test
    void accountCreationBindsProfileFieldsButNotServerManagedState() throws Exception {
        User caller = account("manager", "admin", null);
        when(service.addUser(any())).thenReturn(true);
        MockMvcBuilders.standaloneSetup(controller).build()
                .perform(post("/platform/admin/users").sessionAttr("user", caller)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {
                                  "userName":" Alice ", "password":" fixture-password ",
                                  "privilege":"normal", "teamId":" team-1 ", "status":0,
                                  "email":"alice@example.test", "phone":"12345", "remark":"fixture note",
                                  "userId":"client-id", "lastLoginTime":"2026-01-01 00:00:00",
                                  "loginCount":99, "passwordChangeRequired":0
                                }
                                """))
                .andExpect(jsonPath("$.code").value(200));

        var saved = ArgumentCaptor.forClass(User.class);
        verify(service).checkCreatePermission(caller, "normal", "team-1");
        verify(service).addUser(saved.capture());
        User user = saved.getValue();
        assertEquals("Alice", user.getUserName());
        assertEquals("normal", user.getPrivilege());
        assertEquals("team-1", user.getTeamId());
        assertEquals(0, user.getStatus());
        assertEquals("alice@example.test", user.getEmail());
        assertEquals("12345", user.getPhone());
        assertEquals("fixture note", user.getRemark());
        assertTrue(PasswordUtil.verify(" fixture-password ", user.getPassword()));
        assertNotEquals("client-id", user.getUserId());
        assertNull(user.getLastLoginTime());
        assertEquals(0, user.getLoginCount());
        assertEquals(1, user.getPasswordChangeRequired());
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
    void teamLeaderOptionsRequireAdmin() {
        User caller = new User();
        caller.setPrivilege(UserService.PRIVILEGE_NORMAL);
        request.getSession(true).setAttribute("user", caller);

        assertEquals(403, controller.getTeamLeaderOptions(request).get("code"));
        verifyNoInteractions(service);
    }

    @Test
    void unassignedOptionsReturnOnlyIdentityFields() throws Exception {
        when(service.getUserOptionsWithoutTeam()).thenReturn(List.of(new UserOption("member", "Alice")));
        MockMvcBuilders.standaloneSetup(controller).build()
                .perform(get("/platform/admin/users/no-team")
                        .sessionAttr("user", account("admin", "admin", null)))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data[0].userId").value("member"))
                .andExpect(jsonPath("$.data[0].userName").value("Alice"))
                .andExpect(jsonPath("$.data[0].email").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.data[0].status").doesNotHaveJsonPath());
        verify(service).getUserOptionsWithoutTeam();
        verify(service, never()).getUsersWithoutTeam();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"leader", "normal"})
    void onlyAdministratorsCanReadEnabledAccountOptions(String role) {
        if (role != null) request.getSession().setAttribute("user", account("caller", role, "team"));

        assertEquals(role == null ? 401 : 403, controller.getEnabledUserOptions(request).get("code"));
        verifyNoInteractions(service);
    }

    @Test
    void enabledOptionsReturnOnlyIdentityFieldsWithoutReadingUserProfiles() throws Exception {
        when(service.getEnabledUserOptions()).thenReturn(List.of(new UserOption("member", "Alice")));
        MockMvcBuilders.standaloneSetup(controller).build()
                .perform(get("/platform/admin/users/enabled-options")
                        .sessionAttr("user", account("admin", "admin", null)))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data[0].userId").value("member"))
                .andExpect(jsonPath("$.data[0].userName").value("Alice"))
                .andExpect(jsonPath("$.data[0].email").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.data[0].status").doesNotHaveJsonPath());
        verify(service).getEnabledUserOptions();
        verify(service, never()).getAllUsers();
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

}
