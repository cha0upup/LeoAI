package org.leo.service.team;

import org.leo.core.entity.Team;
import org.leo.dao.mapper.TeamMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 团队管理服务。
 *
 * <p>只有 admin 用户可以创建或删除团队；队长只能查询/修改自己负责的团队。
 * 内置团队 {@value #ADMIN_TEAM_ID} 不可删除。
 */
@Service
public class TeamService {

    /** 内置管理员团队 ID，禁止删除。 */
    public static final String ADMIN_TEAM_ID = "system-admin";

    /** 内置管理员团队名，禁止删除。 */
    public static final String ADMIN_TEAM_NAME = "系统管理员";

    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final TeamMapper teamMapper;

    public TeamService(TeamMapper teamMapper) {
        this.teamMapper = teamMapper;
    }

    // ── 查询 ─────────────────────────────────────────────────────────────────────

    public List<Team> getAllTeams() {
        List<Team> teams = teamMapper.getAllTeams();
        return teams != null ? teams : new ArrayList<>();
    }

    public Team getTeamById(String teamId) {
        if (teamId == null || teamId.isBlank()) return null;
        return teamMapper.findTeamById(teamId.trim());
    }

    public Team getTeamByName(String teamName) {
        if (teamName == null || teamName.isBlank()) return null;
        return teamMapper.findTeamByName(teamName.trim());
    }

    public List<Team> getTeamsByLeader(String leaderId) {
        if (leaderId == null || leaderId.isBlank()) return new ArrayList<>();
        List<Team> teams = teamMapper.findTeamsByLeaderId(leaderId.trim());
        return teams != null ? teams : new ArrayList<>();
    }

    // ── 写操作 ───────────────────────────────────────────────────────────────────

    public boolean addTeam(Team team) {
        if (team == null) throw new IllegalArgumentException("team不能为空");
        String now = DATE_FORMAT.format(LocalDateTime.now());
        return teamMapper.insertTeam(team, now);
    }

    public boolean updateTeam(Team team) {
        if (team == null || team.getTeamId() == null || team.getTeamId().isBlank()) {
            throw new IllegalArgumentException("teamId不能为空");
        }
        String now = DATE_FORMAT.format(LocalDateTime.now());
        return teamMapper.updateTeamById(team, now);
    }

    public boolean delTeam(String teamId) {
        if (teamId == null || teamId.isBlank()) throw new IllegalArgumentException("teamId不能为空");
        Team team = teamMapper.findTeamById(teamId.trim());
        if (team == null) throw new RuntimeException("团队不存在，teamId: " + teamId);
        if (ADMIN_TEAM_ID.equals(team.getTeamId()) || ADMIN_TEAM_NAME.equals(team.getTeamName())) {
            throw new IllegalStateException("不能删除内置团队 " + ADMIN_TEAM_NAME);
        }
        return teamMapper.delTeam(teamId.trim());
    }
}
