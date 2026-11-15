package org.leo.web.controller.platform.admin;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.leo.core.entity.Team;
import org.leo.core.entity.User;
import org.leo.service.team.TeamService;
import org.leo.web.config.GlobalExceptionHandler;
import org.leo.web.exception.ApiException;
import org.leo.web.security.PermissionService;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class TeamControllerTest {
    private final TeamService teams = mock(TeamService.class);
    private final PermissionService permissions = mock(PermissionService.class);
    private final User caller = new User();
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        when(permissions.requireLogin(any())).thenReturn(caller);
        mvc = MockMvcBuilders.standaloneSetup(new TeamController(teams, permissions))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test
    void listsOnlyTheTeamsSelectedForTheVerifiedCaller() throws Exception {
        Team team = new Team();
        team.setTeamId("visible");
        when(teams.getTeamsForUser(caller)).thenReturn(List.of(team));

        mvc.perform(get("/platform/admin/teams"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].teamId").value("visible"));

        verify(teams).getTeamsForUser(same(caller));
    }

    @Test
    void createKeepsTheRequestAndResponseContract() throws Exception {
        mvc.perform(post("/platform/admin/teams").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"teamId\":\"team\",\"teamName\":\"research\",\"leaderId\":\"leader\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200));

        var team = ArgumentCaptor.forClass(Team.class);
        verify(teams).createWithLeader(team.capture());
        assertEquals("team", team.getValue().getTeamId());
        assertEquals("research", team.getValue().getTeamName());
        assertEquals("leader", team.getValue().getLeaderId());
        verify(permissions).requireAdmin(caller);
    }

    @Test
    void deleteKeepsTheIdPayloadAndDelegatesOneCompleteOperation() throws Exception {
        mvc.perform(post("/platform/admin/teams/delete").contentType(MediaType.APPLICATION_JSON).content("{\"id\":\"team\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200));

        verify(teams).deleteWithMembers("team");
        verify(permissions).requireAdmin(caller);
    }

    @ParameterizedTest
    @CsvSource({"list,401", "create,401", "delete,401", "create,403", "delete,403"})
    void rejectedCallersCannotReachTeamOperations(String operation, int code) throws Exception {
        if (code == 401) when(permissions.requireLogin(any())).thenThrow(ApiException.unauthorized("未登录"));
        else doThrow(ApiException.forbidden("管理员权限不足")).when(permissions).requireAdmin(caller);
        var request = switch (operation) {
            case "list" -> get("/platform/admin/teams");
            case "create" -> post("/platform/admin/teams").contentType(MediaType.APPLICATION_JSON).content("{}");
            case "delete" -> post("/platform/admin/teams/delete").contentType(MediaType.APPLICATION_JSON).content("{}");
            default -> throw new AssertionError(operation);
        };

        mvc.perform(request).andExpect(status().is(code)).andExpect(jsonPath("$.code").value(code));

        verifyNoInteractions(teams);
    }
}
