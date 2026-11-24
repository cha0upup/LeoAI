package org.leo.service.team;

import org.leo.core.entity.Team;
import org.leo.core.entity.User;
import org.leo.dao.mapper.TeamMapper;
import org.leo.service.user.UserService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.leo.service.team.TeamOperationException.Kind.*;

/**
 * 团队管理服务。
 *
 * <p>负责团队可见范围及成员关系的事务变更；调用方负责登录和管理权限检查。
 * 内置团队 {@value #ADMIN_TEAM_ID} 不可删除。
 */
@Service
public class TeamService {
    private static final int MAX_ID_LENGTH = 50;
    private static final int MAX_TEAM_NAME_LENGTH = 100;

    /** 内置管理员团队 ID，禁止删除。 */
    public static final String ADMIN_TEAM_ID = "system-admin";

    /** 内置管理员团队名，禁止删除。 */
    public static final String ADMIN_TEAM_NAME = "系统管理员";

    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final TeamMapper teamMapper;
    private final UserService userService;

    public TeamService(TeamMapper teamMapper, UserService userService) {
        this.teamMapper = teamMapper;
        this.userService = userService;
    }

    // ── 查询 ─────────────────────────────────────────────────────────────────────

    public List<Team> getTeamsForUser(User user) {
        // 与团队页原先可查询的用户范围一致：管理员可查看所有队长名称，其他账号只匹配自己。
        return teamMapper.findTeamsForUser(UserService.PRIVILEGE_ADMIN.equals(user.getPrivilege()),
                UserService.PRIVILEGE_LEADER.equals(user.getPrivilege()),
                nullableText(user.getUserId()), nullableText(user.getTeamId()));
    }

    public List<Team> getAllTeams() {
        List<Team> teams = teamMapper.getAllTeams();
        return teams != null ? teams : new ArrayList<>();
    }

    public Team getTeamById(String teamId) {
        if (teamId == null || teamId.isBlank()) return null;
        return teamMapper.findTeamById(teamId.trim());
    }

    public boolean existsTeamById(String teamId) {
        return teamId != null && !teamId.isBlank() && teamMapper.existsTeamById(teamId.trim());
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

    /** Creates the team and assigns its leader in the same transaction. */
    @Transactional
    public Team createWithLeader(Team team) {
        if (team == null) throw failure(VALIDATION, "team参数不能为空");
        String leaderId = team.getLeaderId();
        if (leaderId == null || leaderId.isBlank()) throw failure(VALIDATION, "leaderId不能为空");
        if (team.getTeamName() == null || team.getTeamName().isBlank()) {
            throw failure(VALIDATION, "teamName不能为空");
        }
        leaderId = leaderId.trim();
        String teamName = team.getTeamName().trim();
        if (leaderId.length() > MAX_ID_LENGTH) throw failure(VALIDATION, "leaderId长度超出限制");
        if (teamName.length() > MAX_TEAM_NAME_LENGTH) throw failure(VALIDATION, "teamName长度超出限制");
        team.setLeaderId(leaderId);
        team.setTeamName(teamName);

        User leader = userService.getUserById(leaderId);
        if (leader == null) throw failure(NOT_FOUND, "用户不存在");
        if (leader.getTeamId() != null && !leader.getTeamId().isBlank()) {
            throw failure(VALIDATION, "该用户已属于某个团队");
        }
        if (teamMapper.existsTeamByName(teamName, null)) throw failure(VALIDATION, "团队名已存在");
        if (team.getTeamId() == null || team.getTeamId().isBlank()) {
            team.setTeamId(UUID.randomUUID().toString());
        } else {
            team.setTeamId(team.getTeamId().trim());
            if (team.getTeamId().length() > MAX_ID_LENGTH) throw failure(VALIDATION, "teamId长度超出限制");
        }
        if (existsTeamById(team.getTeamId())) throw failure(VALIDATION, "teamId已存在");

        leader.setTeamId(team.getTeamId());
        if (!UserService.PRIVILEGE_ADMIN.equals(leader.getPrivilege())) leader.setPrivilege(UserService.PRIVILEGE_LEADER);
        if (!userService.updateUser(leader)) throw failure(PERSISTENCE, "更新团队负责人失败");
        if (!addTeam(team)) throw failure(PERSISTENCE, "创建团队失败");
        return team;
    }

    /** Raw insertion for administrator provisioning inside the caller's transaction. */
    public boolean addTeam(Team team) {
        if (team == null) throw new IllegalArgumentException("team不能为空");
        String now = DATE_FORMAT.format(LocalDateTime.now());
        return teamMapper.insertTeam(team, now);
    }

    @Transactional
    public Team updateTeam(Team patch) {
        if (patch == null || patch.getTeamId() == null || patch.getTeamId().isBlank()) {
            throw failure(VALIDATION, "teamId不能为空");
        }
        Team team = getTeamById(patch.getTeamId());
        if (team == null) throw failure(NOT_FOUND, "团队不存在");
        String name = nullableText(patch.getTeamName());
        if (name != null) {
            if (name.length() > MAX_TEAM_NAME_LENGTH) throw failure(VALIDATION, "teamName长度超出限制");
            if (teamMapper.existsTeamByName(name, team.getTeamId())) {
                throw failure(VALIDATION, "团队名已存在");
            }
            team.setTeamName(name);
        }
        String leaderId = nullableText(patch.getLeaderId());
        if (leaderId != null && !leaderId.equals(team.getLeaderId())) {
            if (leaderId.length() > MAX_ID_LENGTH) throw failure(VALIDATION, "leaderId长度超出限制");
            User newLeader = userService.getUserById(leaderId);
            if (newLeader == null) throw failure(NOT_FOUND, "新 leader 用户不存在");
            if (newLeader.getTeamId() != null && !newLeader.getTeamId().isBlank()
                    && !team.getTeamId().equals(newLeader.getTeamId())) {
                throw failure(VALIDATION, "新 leader 已属于其他团队");
            }
            User oldLeader = userService.getUserById(team.getLeaderId());
            if (oldLeader != null && UserService.PRIVILEGE_LEADER.equals(oldLeader.getPrivilege())) {
                oldLeader.setPrivilege(UserService.PRIVILEGE_NORMAL);
                if (!userService.updateUser(oldLeader)) throw failure(PERSISTENCE, "更新原团队负责人失败");
            }
            newLeader.setTeamId(team.getTeamId());
            if (!UserService.PRIVILEGE_ADMIN.equals(newLeader.getPrivilege())) newLeader.setPrivilege(UserService.PRIVILEGE_LEADER);
            if (!userService.updateUser(newLeader)) throw failure(PERSISTENCE, "更新团队负责人失败");
            team.setLeaderId(newLeader.getUserId());
        }
        if (patch.getDescription() != null) team.setDescription(nullableText(patch.getDescription()));
        if (patch.getStatus() != null) team.setStatus(patch.getStatus());
        if (patch.getRemark() != null) team.setRemark(nullableText(patch.getRemark()));
        String now = DATE_FORMAT.format(LocalDateTime.now());
        if (!teamMapper.updateTeamById(team, now)) throw failure(PERSISTENCE, "更新团队失败");
        return team;
    }

    /** Deletes a team and detaches its members without changing administrator privileges. */
    @Transactional
    public Team deleteWithMembers(String teamId) {
        if (teamId == null || teamId.isBlank()) throw failure(VALIDATION, "id不能为空");
        String normalized = teamId.trim();
        Team team = getTeamById(normalized);
        if (team == null) throw failure(NOT_FOUND, "团队不存在");
        if (ADMIN_TEAM_ID.equals(team.getTeamId()) || ADMIN_TEAM_NAME.equals(team.getTeamName())) {
            throw failure(FORBIDDEN, "不能删除内置团队 " + ADMIN_TEAM_NAME);
        }
        if (teamMapper.hasSharedProjects(normalized)) {
            throw failure(CONFLICT, "该团队仍有共享项目，请先取消相关项目的团队共享");
        }
        userService.detachTeamMembers(normalized);
        if (!teamMapper.delTeam(normalized)) throw failure(PERSISTENCE, "删除团队失败");
        return team;
    }

    private static TeamOperationException failure(TeamOperationException.Kind kind, String message) {
        return new TeamOperationException(kind, message);
    }

    private static String nullableText(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
