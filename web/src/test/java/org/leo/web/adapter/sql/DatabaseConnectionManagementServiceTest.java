package org.leo.web.adapter.sql;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.leo.core.entity.User;
import org.leo.service.sql.DatabaseConnectionProfileException;
import org.leo.service.sql.DatabaseConnectionProfileException.Kind;
import org.leo.service.sql.DatabaseConnectionProfileService;
import org.leo.web.exception.ApiException;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DatabaseConnectionManagementServiceTest {

    private final DatabaseConnectionProfileService profiles = mock(DatabaseConnectionProfileService.class);
    private final DatabaseConnectionManagementService management = new DatabaseConnectionManagementService(profiles);

    @Test
    void forwardsContextAndKeepsApplicationResultsUnchanged() {
        User user = user("user-1");
        Map<String, Object> params = Map.of("connectionName", "inventory");
        Map<String, Object> profile = Map.of("connectionId", "connection-1");
        Map<String, Object> status = Map.of("status", 0);
        List<Map<String, Object>> rows = List.of(profile);
        when(profiles.save("user-1", "agent-1", params)).thenReturn(profile);
        when(profiles.listByAgent("user-1", "agent-1")).thenReturn(rows);
        when(profiles.setEnabled("user-1", "agent-1", "connection-1", false)).thenReturn(status);

        assertSame(profile, management.save(user, "agent-1", params));
        assertSame(rows, management.listByAgent("agent-1", user));
        assertSame(status, management.setEnabled("connection-1", "agent-1", false, user));
        management.delete("connection-1", "agent-1", user);
        verify(profiles).delete("user-1", "agent-1", "connection-1");
    }

    @ParameterizedTest
    @CsvSource({"VALIDATION,400", "NOT_FOUND,404", "FORBIDDEN,403", "PERSISTENCE,500"})
    void translatesApplicationErrorsWithoutChangingTheMessage(Kind kind, int status) {
        when(profiles.save("user-1", "agent-1", Map.of()))
                .thenThrow(new DatabaseConnectionProfileException(kind, "profile error"));

        ApiException error = assertThrows(ApiException.class,
                () -> management.save(user("user-1"), "agent-1", Map.of()));

        assertEquals(status, error.getHttpStatus().value());
        assertEquals(status, error.getCode());
        assertEquals("profile error", error.getMessage());
    }

    @ParameterizedTest
    @MethodSource("unauthenticatedUsers")
    void rejectsMissingUserContextBeforeCallingTheApplication(User user) {
        for (Runnable operation : List.<Runnable>of(
                () -> management.save(user, "agent-1", Map.of()),
                () -> management.listByAgent("agent-1", user),
                () -> management.delete("connection-1", "agent-1", user),
                () -> management.setEnabled("connection-1", "agent-1", false, user))) {
            assertEquals(401, assertThrows(ApiException.class, operation::run).getHttpStatus().value());
        }
        verifyNoInteractions(profiles);
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
