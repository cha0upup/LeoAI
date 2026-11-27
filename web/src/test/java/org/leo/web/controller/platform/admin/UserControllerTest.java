package org.leo.web.controller.platform.admin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.leo.core.entity.User;
import org.leo.core.entity.UserOption;
import org.leo.core.util.PasswordUtil;
import org.leo.service.agentnode.AgentService;
import org.leo.service.user.PasswordPolicy;
import org.leo.service.user.UserService;
import org.leo.web.interceptor.LoginInterceptor;
import org.leo.web.security.PermissionService;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class UserControllerTest {
    private final UserService service = mock(UserService.class);
    private final UserService verifiedAccounts = mock(UserService.class);
    private final PermissionService permissions = new PermissionService(mock(AgentService.class), verifiedAccounts);
    private final UserController controller = new UserController(service, mock(PasswordPolicy.class), permissions);
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

    private User account(String id, String role, String team) {
        User user = new User();
        user.setUserId(id);
        user.setUserName(id);
        user.setPrivilege(role);
        user.setTeamId(team);
        user.setStatus(1);
        user.setPassword("fixture-hash");
        when(verifiedAccounts.getUserById(id)).thenReturn(user);
        return user;
    }

    @Test
    void teamLeaderOptionsRequireAdmin() {
        request.getSession(true).setAttribute("user", account("member", "normal", "team-1"));

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
    void userListingReusesVerifiedIdentityAndKeepsTeamVisibility() throws Exception {
        User leader = account("team-leader", "leader", "team-1");
        User member = account("user-1", "normal", "team-1");
        member.setEmail("current@example.test");
        User cachedMember = new User();
        cachedMember.setUserId(member.getUserId());
        cachedMember.setPassword(member.getPassword());
        cachedMember.setPrivilege("leader");
        when(service.getUsersByTeamId("team-1")).thenReturn(List.of(leader, member));

        var mvc = MockMvcBuilders.standaloneSetup(controller).addInterceptors(new LoginInterceptor(permissions)).build();
        mvc.perform(get("/platform/admin/users").sessionAttr("user", leader))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2));
        mvc.perform(get("/platform/admin/users").sessionAttr("user", cachedMember))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].userId").value(member.getUserId()))
                .andExpect(jsonPath("$.data[0].privilege").value("normal"))
                .andExpect(jsonPath("$.data[0].email").value(member.getEmail()))
                .andExpect(jsonPath("$.data[0].password").doesNotHaveJsonPath());

        verify(verifiedAccounts).getUserById(member.getUserId());
        verify(service).getUsersByTeamId("team-1");
        verify(service, never()).getUserById(any());
        verify(service, never()).getAllUsers();
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
        request.getSession(true).setAttribute("user", account("manager", "admin", null));

        Map<String, Object> response = controller.getUsers(request);
        List<Map<String, Object>> users = (List<Map<String, Object>>) response.get("data");

        assertEquals("pbkdf2-secret-hash", stored.getPassword());
        assertEquals("alice", users.get(0).get("userName"));
        assertFalse(users.get(0).containsKey("password"));
    }

}
