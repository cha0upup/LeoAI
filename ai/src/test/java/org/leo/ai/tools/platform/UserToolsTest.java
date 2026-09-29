package org.leo.ai.tools.platform;

import org.junit.jupiter.api.Test;
import org.leo.core.entity.User;
import org.leo.core.util.PasswordUtil;
import org.leo.dao.mapper.SystemConfigMapper;
import org.leo.service.config.SystemConfigService;
import org.leo.service.user.PasswordPolicy;
import org.leo.service.user.UserService;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UserToolsTest {

    private final UserService service = mock(UserService.class);
    private final SystemConfigMapper config = mock(SystemConfigMapper.class);
    private final UserTools tools = new UserTools(service, new PasswordPolicy(new SystemConfigService(config)));

    @Test
    void listingUsersDoesNotModifyStoredPasswordHashes() {
        User stored = user();
        when(service.getAllUser()).thenReturn(List.of(stored));

        User result = tools.listUsers(false).get(0);

        assertNotSame(stored, result);
        assertEquals("", result.getPassword());
        assertEquals("stored-hash", stored.getPassword());
        assertEquals(stored.getUserName(), result.getUserName());
    }

    @Test
    void individualUserLookupsReturnIndependentSafeCopies() {
        User stored = user();
        when(service.getUserById("user-1")).thenReturn(stored);
        when(service.getUserByName("alice")).thenReturn(stored);

        for (User result : List.of(tools.getUser("user-1", null), tools.getUser(null, "alice"))) {
            assertNotSame(stored, result);
            assertEquals("", result.getPassword());
        }
        assertEquals("stored-hash", stored.getPassword());
    }

    @Test
    void unassignedUserQueriesUseTheSharedFilter() {
        User stored = user();
        when(service.getUsersWithoutTeam()).thenReturn(List.of(stored));
        assertEquals("alice", tools.listUsers(true).get(0).getUserName());
        assertEquals("stored-hash", stored.getPassword());
        verify(service).getUsersWithoutTeam();
        verify(service, never()).getAllUser();
    }

    @Test
    void createUsesTheConfiguredPasswordPolicyBeforePersistence() {
        when(config.findValueByKey("security.password.min.length")).thenReturn("12");
        assertThrows(IllegalArgumentException.class, () -> addUser("short"));
        verifyNoInteractions(service);
    }

    @Test
    void createHashesPasswordsAndRequiresAnInitialPasswordChange() {
        when(service.addUser(any())).thenReturn(true);
        assertEquals(true, addUser("new-password").get("success"));
        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(service).addUser(saved.capture());
        assertTrue(PasswordUtil.verify("new-password", saved.getValue().getPassword()));
        assertEquals(1, saved.getValue().getPasswordChangeRequired());
        assertEquals("normal", saved.getValue().getPrivilege());
    }

    @Test
    void passwordResetRequiresAChangeAtTheNextLogin() {
        User stored = user();
        when(service.getUserById("user-1")).thenReturn(stored);
        when(service.updateUser(stored)).thenReturn(true);
        tools.updateUser("user-1", null, "new-password", null, null, null, null, null, null);
        assertTrue(PasswordUtil.verify("new-password", stored.getPassword()));
        assertEquals(1, stored.getPasswordChangeRequired());
        verify(service).updateUser(stored);
    }

    @Test
    void invalidPasswordDoesNotMutateTheUserOrReachPersistence() {
        User stored = user();
        when(service.getUserById("user-1")).thenReturn(stored);
        assertThrows(IllegalArgumentException.class, () -> tools.updateUser(
                "user-1", "renamed", "short", null, null, null, null, null, null));
        assertEquals("alice", stored.getUserName());
        assertEquals("stored-hash", stored.getPassword());
        verify(service, never()).updateUser(any());
    }

    @Test
    void metadataUpdatesDoNotResetThePasswordChangeFlag() {
        User stored = user();
        when(service.getUserById("user-1")).thenReturn(stored);
        tools.updateUser("user-1", null, null, null, null, null, null, null, "updated");
        assertEquals("stored-hash", stored.getPassword());
        assertEquals(0, stored.getPasswordChangeRequired());
    }

    @Test
    void builtInAdminRestrictionsStillApplyToAiTools() {
        User admin = user();
        admin.setUserId("admin");
        admin.setPrivilege("admin");
        when(service.getUserById("admin")).thenReturn(admin);
        assertThrows(IllegalArgumentException.class, () -> tools.deleteUser("admin"));
        assertThrows(IllegalArgumentException.class, () -> tools.updateUser(
                "admin", null, null, "normal", null, null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> tools.updateUser(
                "admin", null, null, null, null, null, 0, null, null));
        verify(service, never()).delUser(any());
        verify(service, never()).updateUser(any());
    }

    private Map<String, Object> addUser(String password) {
        return tools.addUser("alice", password, null, null, null, null, null, null, null);
    }

    private User user() {
        return new User("user-1", "alice", "stored-hash", "normal", "2026-09-01");
    }
}
