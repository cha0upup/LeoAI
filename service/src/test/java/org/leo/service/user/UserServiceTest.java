package org.leo.service.user;

import org.junit.jupiter.api.Test;
import org.leo.core.entity.User;
import org.leo.dao.mapper.UserMapper;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UserServiceTest {

    private final UserMapper mapper = mock(UserMapper.class);
    private final UserService service = new UserService(mapper);

    @Test
    void filtersUnassignedUsersInOrderAndKeepsTheirCredentials() {
        User noTeam = user(null);
        User emptyTeam = user("");
        User blankTeam = user("  ");
        User assigned = user("team-1");
        when(mapper.getAllUser()).thenReturn(Arrays.asList(noTeam, assigned, null, emptyTeam, blankTeam));

        assertEquals(List.of(noTeam, emptyTeam, blankTeam), service.getUsersWithoutTeam());
        assertEquals("stored-hash", noTeam.getPassword());
        assertEquals("normal", noTeam.getPrivilege());
        assertEquals(0, noTeam.getPasswordChangeRequired());
        verify(mapper).getAllUser();
    }

    @Test
    void missingUsersProduceAnEmptyResult() {
        when(mapper.getAllUser()).thenReturn(null);
        assertTrue(service.getUsersWithoutTeam().isEmpty());
    }

    private User user(String teamId) {
        User user = new User();
        user.setTeamId(teamId);
        user.setPassword("stored-hash");
        return user;
    }
}
