package org.leo.ai.tools.platform;

import org.leo.core.entity.Team;
import org.leo.core.entity.User;
import org.leo.service.team.TeamService;
import org.leo.service.user.UserService;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.leo.ai.agent.AiToolAccess;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.leo.ai.tools.platform.PlatformToolArguments.*;

/**
 * 平台团队管理 AI 工具。
 *
 * <p>这些工具以平台管理员权限运行。
 * 只有 admin 可创建/删除团队；内置团队 system-admin 不可删除。
 */
@Component("platformTeamTools")
@AiToolAccess(AiToolAccess.Level.ADMIN)
@org.leo.ai.agent.AiToolPolicy(kind = org.leo.ai.agent.AiToolKind.COMMAND,
        operation = org.leo.ai.agent.AiToolOperation.WRITE)
public class TeamTools {

    private final TeamService teamService;
    private final UserService userService;

    public TeamTools(TeamService teamService, UserService userService) {
        this.teamService = teamService;
        this.userService = userService;
    }

    @Tool("列出平台团队。leaderId 可选；为空时返回全部团队。")
    @org.leo.ai.agent.AiToolPolicy(kind = org.leo.ai.agent.AiToolKind.QUERY,
            operation = org.leo.ai.agent.AiToolOperation.READ_ONLY, parallelizable = true)
    public List<Team> listTeams(
            @P(value = "可选 leaderId；为空返回全部团队", required = false)
            String leaderId) {
        String normalizedLeaderId = trimToNull(leaderId);
        if (normalizedLeaderId != null) {
            return teamService.getTeamsByLeader(normalizedLeaderId);
        }
        return teamService.getAllTeam();
    }

    @Tool("按 teamId 或 teamName 获取团队详情；两者必须且只能提供一个。")
    @org.leo.ai.agent.AiToolPolicy(kind = org.leo.ai.agent.AiToolKind.QUERY,
            operation = org.leo.ai.agent.AiToolOperation.READ_ONLY, parallelizable = true)
    public Team getTeam(
            @P(value = "团队 ID，与 teamName 二选一", required = false) String teamId,
            @P(value = "团队名称，与 teamId 二选一", required = false) String teamName) {
        String id = trimToNull(teamId);
        String name = trimToNull(teamName);
        if ((id == null) == (name == null)) {
            throw new IllegalArgumentException("teamId 与 teamName 必须且只能提供一个");
        }
        Team team = id != null ? teamService.getTeamById(id) : teamService.getTeamByName(name);
        if (team == null) throw new IllegalArgumentException("团队不存在");
        return team;
    }

    @Tool("创建平台团队（仅 admin 可用）。teamName、leaderId 必填；未传 teamId 则自动生成 UUID。"
            + " leader 必须是已存在且尚未加入其他团队的用户。")
    public Map<String, Object> addTeam(
            @P(value = "团队 ID；省略时自动生成", required = false) String teamId,
            @P("唯一团队名称") String teamName,
            @P("团队负责人用户 ID") String leaderId,
            @P(value = "团队描述", required = false) String description,
            @P(value = "状态：1启用、0停用；默认1", required = false, defaultValue = "1") Integer status,
            @P(value = "备注", required = false) String remark) {
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
    public Map<String, Object> updateTeam(
            @P("待更新团队 ID") String teamId,
            @P(value = "新团队名称", required = false) String teamName,
            @P(value = "新负责人用户 ID", required = false) String leaderId,
            @P(value = "新团队描述；空字符串表示清空", required = false) String description,
            @P(value = "新状态：1启用、0停用", required = false) Integer status,
            @P(value = "新备注；空字符串表示清空", required = false) String remark) {
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

    @org.leo.ai.agent.AiToolPolicy(kind = org.leo.ai.agent.AiToolKind.COMMAND,
            operation = org.leo.ai.agent.AiToolOperation.DESTRUCTIVE, exclusive = true)
    @Tool("删除指定团队（仅 admin 可用）。删除前会清空团队成员的 teamId。内置 system-admin 不可删除。")
    public Map<String, Object> deleteTeam(@P("待删除团队 ID") String teamId) {
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
}
