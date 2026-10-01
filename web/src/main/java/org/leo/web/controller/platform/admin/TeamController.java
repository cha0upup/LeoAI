package org.leo.web.controller.platform.admin;

import jakarta.servlet.http.HttpServletRequest;
import org.leo.core.entity.Team;
import org.leo.core.entity.User;
import org.leo.core.util.ApiResponse;
import org.leo.service.team.TeamService;
import org.leo.service.user.UserService;
import org.leo.web.dto.platform.admin.TeamDtos.DeleteTeamRequest;
import org.leo.web.exception.ApiException;
import org.leo.web.security.PermissionService;
import org.leo.web.security.RoleAwareAdminEndpoint;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 团队管理控制器。
 *
 * <p>权限规则：
 * <ul>
 *   <li>admin  — 可创建/删除/查询所有团队</li>
 *   <li>leader — 只能查询自己负责的团队</li>
 *   <li>normal — 只能查询自己所在的团队</li>
 * </ul>
 */
@RestController
@RequestMapping("/platform/admin")
@RoleAwareAdminEndpoint
public class TeamController {

    private static final int MAX_ID_LENGTH = 50;
    private static final int MAX_TEAM_NAME_LENGTH = 100;

    private final TeamService teamService;
    private final UserService userService;
    private final PermissionService permissionService;

    public TeamController(TeamService teamService,
                          UserService userService,
                          PermissionService permissionService) {
        this.teamService = teamService;
        this.userService = userService;
        this.permissionService = permissionService;
    }

    // ── 查询 ─────────────────────────────────────────────────────────────────────

    @RequestMapping(value = "/teams", method = RequestMethod.GET)
    public Map<String, Object> getTeams(HttpServletRequest request) {
        User caller = permissionService.requireLogin(request);

        List<Team> teams;
        if (UserService.PRIVILEGE_ADMIN.equals(caller.getPrivilege())) {
            teams = teamService.getAllTeams();
        } else if (UserService.PRIVILEGE_LEADER.equals(caller.getPrivilege())) {
            teams = teamService.getTeamsByLeader(caller.getUserId());
        } else {
            // normal 用户：返回自己所在的团队
            teams = new ArrayList<>();
            if (caller.getTeamId() != null && !caller.getTeamId().isBlank()) {
                Team t = teamService.getTeamById(caller.getTeamId());
                if (t != null) teams.add(t);
            }
        }
        return ApiResponse.success(teams);
    }

    // ── 创建 ─────────────────────────────────────────────────────────────────────

    /**
     * 创建团队（仅 admin）。
     * 若未传 teamId，则自动生成 UUID；leaderId 必填且对应用户须尚未加入任何团队。
     */
    @RequestMapping(value = "/teams", method = RequestMethod.POST)
    @Transactional
    public Map<String, Object> addTeam(HttpServletRequest request, @RequestBody Team team) {
        requireAdmin(request);
        if (team == null) throw ApiException.badRequest("team参数不能为空");

        String leaderId = team.getLeaderId();
        if (leaderId == null || leaderId.isBlank()) throw ApiException.badRequest("leaderId不能为空");
        if (team.getTeamName() == null || team.getTeamName().isBlank()) {
            throw ApiException.badRequest("teamName不能为空");
        }

        leaderId = leaderId.trim();
        String teamName = team.getTeamName().trim();
        if (leaderId.length() > MAX_ID_LENGTH) throw ApiException.badRequest("leaderId长度超出限制");
        if (teamName.length() > MAX_TEAM_NAME_LENGTH) throw ApiException.badRequest("teamName长度超出限制");
        team.setLeaderId(leaderId);
        team.setTeamName(teamName);

        User leader = userService.getUserById(leaderId);
        if (leader == null) throw ApiException.notFound("用户不存在");
        if (leader.getTeamId() != null && !leader.getTeamId().isBlank()) {
            throw ApiException.badRequest("该用户已属于某个团队");
        }
        if (teamService.getTeamByName(team.getTeamName()) != null) {
            throw ApiException.badRequest("团队名已存在");
        }

        if (team.getTeamId() == null || team.getTeamId().isBlank()) {
            team.setTeamId(UUID.randomUUID().toString());
        } else {
            team.setTeamId(team.getTeamId().trim());
            if (team.getTeamId().length() > MAX_ID_LENGTH) {
                throw ApiException.badRequest("teamId长度超出限制");
            }
        }

        // 将 leader 加入该团队，同时升级为 leader 角色
        leader.setTeamId(team.getTeamId());
        if (!UserService.PRIVILEGE_ADMIN.equals(leader.getPrivilege())) {
            leader.setPrivilege(UserService.PRIVILEGE_LEADER);
        }
        if (!userService.updateUser(leader)) {
            throw ApiException.serverError("更新团队负责人失败");
        }

        boolean ok = teamService.addTeam(team);
        if (!ok) {
            throw ApiException.serverError("创建团队失败");
        }
        return ApiResponse.success();
    }

    // ── 删除 ─────────────────────────────────────────────────────────────────────

    @RequestMapping(value = "/teams/delete", method = RequestMethod.POST)
    @Transactional
    public Map<String, Object> deleteTeam(HttpServletRequest request,
                                          @RequestBody DeleteTeamRequest body) {
        requireAdmin(request);
        String teamId = PermissionService.requireText(body != null ? body.id() : null, "id不能为空");

        // 清空该团队所有成员的 teamId，并将 leader 降为 normal
        Team team = teamService.getTeamById(teamId);
        if (team == null) throw ApiException.notFound("团队不存在");
        if (TeamService.ADMIN_TEAM_ID.equals(team.getTeamId()) || TeamService.ADMIN_TEAM_NAME.equals(team.getTeamName())) {
            throw ApiException.forbidden("不能删除内置团队 " + TeamService.ADMIN_TEAM_NAME);
        }

        for (User member : userService.getUsersByTeamId(teamId)) {
            if (member == null) continue;
            member.setTeamId(null);
            if (UserService.PRIVILEGE_LEADER.equals(member.getPrivilege())) {
                member.setPrivilege(UserService.PRIVILEGE_NORMAL);
            }
            if (!userService.updateUser(member)) {
                throw ApiException.serverError("解除团队成员关系失败");
            }
        }

        try {
            boolean ok = teamService.delTeam(teamId);
            if (!ok) {
                throw ApiException.serverError("删除团队失败");
            }
            return ApiResponse.success();
        } catch (IllegalStateException e) {
            throw ApiException.forbidden(e.getMessage());
        }
    }

    // ── 私有工具 ─────────────────────────────────────────────────────────────────

    private void requireAdmin(HttpServletRequest request) {
        permissionService.requireAdmin(permissionService.requireLogin(request));
    }
}
