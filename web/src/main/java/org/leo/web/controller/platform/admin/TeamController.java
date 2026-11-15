package org.leo.web.controller.platform.admin;

import jakarta.servlet.http.HttpServletRequest;
import org.leo.core.entity.Team;
import org.leo.core.util.ApiResponse;
import org.leo.service.team.TeamService;
import org.leo.web.dto.platform.admin.TeamDtos.DeleteTeamRequest;
import org.leo.web.security.PermissionService;
import org.leo.web.security.RoleAwareAdminEndpoint;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

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

    private final TeamService teamService;
    private final PermissionService permissionService;

    public TeamController(TeamService teamService,
                          PermissionService permissionService) {
        this.teamService = teamService;
        this.permissionService = permissionService;
    }

    // ── 查询 ─────────────────────────────────────────────────────────────────────

    @RequestMapping(value = "/teams", method = RequestMethod.GET)
    public Map<String, Object> getTeams(HttpServletRequest request) {
        return ApiResponse.success(teamService.getTeamsForUser(permissionService.requireLogin(request)));
    }

    // ── 创建 ─────────────────────────────────────────────────────────────────────

    /**
     * 创建团队（仅 admin）。
     * 若未传 teamId，则自动生成 UUID；leaderId 必填且对应用户须尚未加入任何团队。
     */
    @RequestMapping(value = "/teams", method = RequestMethod.POST)
    public Map<String, Object> addTeam(HttpServletRequest request, @RequestBody Team team) {
        requireAdmin(request);
        teamService.createWithLeader(team);
        return ApiResponse.success();
    }

    // ── 删除 ─────────────────────────────────────────────────────────────────────

    @RequestMapping(value = "/teams/delete", method = RequestMethod.POST)
    public Map<String, Object> deleteTeam(HttpServletRequest request,
                                          @RequestBody DeleteTeamRequest body) {
        requireAdmin(request);
        teamService.deleteWithMembers(body != null ? body.id() : null);
        return ApiResponse.success();
    }

    // ── 私有工具 ─────────────────────────────────────────────────────────────────

    private void requireAdmin(HttpServletRequest request) {
        permissionService.requireAdmin(permissionService.requireLogin(request));
    }
}
