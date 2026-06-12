package org.leo.ai.tools.platform;

import org.leo.core.entity.Team;
import org.leo.core.entity.User;
import org.leo.service.team.TeamService;
import org.leo.service.user.UserService;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 平台团队管理 AI 工具。
 *
 * <p>这些工具以平台管理员权限运行。
 * 只有 admin 可创建/删除团队；内置团队 adminteam 不可删除。
 */
@Component("platformTeamTools")
public class TeamTools {

    private final TeamService teamService;
    private final UserService userService;

    public TeamTools(TeamService teamService, UserService userService) {
        this.teamService = teamService;
        this.userService = userService;
    }

    @Tool("获取当前平台所有团队。")
    public List<Team> getAllTeam() {
        return teamService.getAllTeam();
    }

    @Tool("获取当前平台所有团队名称。")
    public List<String> getAllTeamName() {
        List<String> names = new ArrayList<>();
        for (Team t : teamService.getAllTeam()) {
            if (t != null && t.getTeamName() != null) names.add(t.getTeamName());
        }
        return names;
    }

    @Tool("根据 teamId 获取团队详情。")
    public Team getTeamById(String teamId) {
        Team team = teamService.getTeamById(requireNonBlank(teamId, "teamId不能为空"));
        if (team == null) throw new IllegalArgumentException("团队不存在");
        return team;
    }

    @Tool("根据 teamName 获取团队详情。")
    public Team getTeamByName(String teamName) {
        Team team = teamService.getTeamByName(requireNonBlank(teamName, "teamName不能为空"));
        if (team == null) throw new IllegalArgumentException("团队不存在");
        return team;
    }

    @Tool("获取指定 leaderId 负责的团队列表。")
    public List<Team> getTeamsByLeader(String leaderId) {
        return teamService.getTeamsByLeader(requireNonBlank(leaderId, "leaderId不能为空"));
    }

    @Tool("创建平台团队（仅 admin 可用）。teamName、leaderId 必填；未传 teamId 则自动生成 UUID。"
            + " leader 必须是已存在且尚未加入其他团队的用户。")
    public Map<String, Object> addTeam(String teamId, String teamName, String leaderId,
                                        String description, Integer status, String remark) {
        String nTeamName = requireNonBlank(teamName, "teamName不能为空");
        String nLeaderId = requireNonBlank(leaderId, "leaderId不能为空");
        String nTeamId   = defaultIfBlank(teamId, java.util.UUID.randomUUID().toString());

        if (teamService.getTeamById(nTeamId) != null) throw new IllegalArgumentException("teamId已存在");
        if (teamService.getTeamByName(nTeamName) != null) throw new IllegalArgumentException("teamName已存在");

        User leader = userService.getUserById(nLeaderId);
        if (leader == null) throw new IllegalArgumentException("用户不存在");
        if (!isBlank(leader.getTeamId())) throw new IllegalArgumentException("该用户已属于某个团队");

        Team team = new Team();
        team.setTeamId(nTeamId);
        team.setTeamName(nTeamName);
        team.setLeaderId(nLeaderId);
        team.setDescription(trimToNull(description));
        team.setStatus(status == null ? 1 : status);
        team.setRemark(trimToNull(remark));

        // 将 leader 加入团队并设置角色
        leader.setTeamId(nTeamId);
        if (!UserService.PRIVILEGE_ADMIN.equals(leader.getPrivilege())) {
            leader.setPrivilege(UserService.PRIVILEGE_LEADER);
        }
        userService.updateUser(leader);

        boolean created = teamService.addTeam(team);
        return buildResult("created", created, nTeamId, nTeamName);
    }

    @Tool("更新平台团队。teamId 必填；若更换 leader，会校验新 leader 是否存在且未加入其他团队。")
    public Map<String, Object> updateTeam(String teamId, String teamName, String leaderId,
                                           String description, Integer status, String remark) {
        Team existing = teamService.getTeamById(requireNonBlank(teamId, "teamId不能为空"));
        if (existing == null) throw new IllegalArgumentException("团队不存在");

        if (!isBlank(teamName) && !teamName.equals(existing.getTeamName())) {
            if (teamService.getTeamByName(teamName) != null) throw new IllegalArgumentException("teamName已存在");
            existing.setTeamName(teamName.trim());
        }

        if (!isBlank(leaderId) && !leaderId.equals(existing.getLeaderId())) {
            User newLeader = userService.getUserById(leaderId.trim());
            if (newLeader == null) throw new IllegalArgumentException("新 leader 用户不存在");
            if (!isBlank(newLeader.getTeamId()) && !existing.getTeamId().equals(newLeader.getTeamId())) {
                throw new IllegalArgumentException("新 leader 已属于其他团队");
            }
            // 降级旧 leader
            User oldLeader = userService.getUserById(existing.getLeaderId());
            if (oldLeader != null && UserService.PRIVILEGE_LEADER.equals(oldLeader.getPrivilege())) {
                oldLeader.setPrivilege(UserService.PRIVILEGE_NORMAL);
                userService.updateUser(oldLeader);
            }
            // 升级新 leader
            newLeader.setTeamId(existing.getTeamId());
            if (!UserService.PRIVILEGE_ADMIN.equals(newLeader.getPrivilege())) {
                newLeader.setPrivilege(UserService.PRIVILEGE_LEADER);
            }
            userService.updateUser(newLeader);
            existing.setLeaderId(newLeader.getUserId());
        }

        if (description != null) existing.setDescription(trimToNull(description));
        if (status != null)      existing.setStatus(status);
        if (remark != null)      existing.setRemark(trimToNull(remark));

        boolean updated = teamService.updateTeam(existing);
        return buildResult("updated", updated, existing.getTeamId(), existing.getTeamName());
    }

    @Tool("删除指定团队（仅 admin 可用）。删除前会清空团队成员的 teamId。内置 adminteam 不可删除。")
    public Map<String, Object> deleteTeam(String teamId) {
        Team team = teamService.getTeamById(requireNonBlank(teamId, "teamId不能为空"));
        if (team == null) throw new IllegalArgumentException("团队不存在");

        // 清空团队成员
        for (User member : userService.getUserByTeamId(teamId)) {
            if (member == null) continue;
            member.setTeamId(null);
            if (UserService.PRIVILEGE_LEADER.equals(member.getPrivilege())) {
                member.setPrivilege(UserService.PRIVILEGE_NORMAL);
            }
            userService.updateUser(member);
        }

        try {
            boolean deleted = teamService.delTeam(teamId);
            return buildResult("deleted", deleted, team.getTeamId(), team.getTeamName());
        } catch (IllegalStateException e) {
            throw new IllegalArgumentException(e.getMessage());
        }
    }

    // ── 私有工具 ─────────────────────────────────────────────────────────────────

    private Map<String, Object> buildResult(String status, boolean success, String teamId, String teamName) {
        HashMap<String, Object> result = new HashMap<>();
        result.put("status",   status);
        result.put("success",  success);
        result.put("teamId",   teamId);
        result.put("teamName", teamName);
        return result;
    }

    private String requireNonBlank(String value, String message) {
        String t = trimToNull(value);
        if (t == null) throw new IllegalArgumentException(message);
        return t;
    }

    private String defaultIfBlank(String value, String def) {
        String t = trimToNull(value);
        return t == null ? def : t;
    }

    private String trimToNull(String value) {
        if (value == null) return null;
        String t = value.trim();
        return t.isEmpty() ? null : t;
    }

    private boolean isBlank(String value) {
        return trimToNull(value) == null;
    }
}
