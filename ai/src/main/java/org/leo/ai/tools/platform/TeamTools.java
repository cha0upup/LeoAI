package org.leo.ai.tools.platform;

import org.leo.core.entity.Team;
import org.leo.service.team.TeamService;
import org.leo.service.team.TeamOperationException;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.leo.ai.agent.AiToolAccess;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

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

    public TeamTools(TeamService teamService) {
        this.teamService = teamService;
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
        return teamService.getAllTeams();
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
        Team team = new Team();
        team.setTeamId(trimToNull(teamId));
        team.setTeamName(teamName);
        team.setLeaderId(leaderId);
        team.setDescription(trimToNull(description));
        team.setStatus(status == null ? 1 : status);
        team.setRemark(trimToNull(remark));
        return execute("created", () -> teamService.createWithLeader(team));
    }

    @Tool("更新平台团队。teamId 必填；若更换 leader，会校验新 leader 是否存在且未加入其他团队。")
    public Map<String, Object> updateTeam(
            @P("待更新团队 ID") String teamId,
            @P(value = "新团队名称", required = false) String teamName,
            @P(value = "新负责人用户 ID", required = false) String leaderId,
            @P(value = "新团队描述；空字符串表示清空", required = false) String description,
            @P(value = "新状态：1启用、0停用", required = false) Integer status,
            @P(value = "新备注；空字符串表示清空", required = false) String remark) {
        Team patch = new Team();
        patch.setTeamId(requireNonBlank(teamId, "teamId不能为空"));
        patch.setTeamName(teamName);
        patch.setLeaderId(leaderId);
        patch.setDescription(description);
        patch.setStatus(status);
        patch.setRemark(remark);
        return execute("updated", () -> teamService.updateTeam(patch));
    }

    @org.leo.ai.agent.AiToolPolicy(kind = org.leo.ai.agent.AiToolKind.COMMAND,
            operation = org.leo.ai.agent.AiToolOperation.DESTRUCTIVE, exclusive = true)
    @Tool("删除指定团队（仅 admin 可用）。删除前会清空团队成员的 teamId。内置 system-admin 不可删除。")
    public Map<String, Object> deleteTeam(@P("待删除团队 ID") String teamId) {
        String id = requireNonBlank(teamId, "teamId不能为空");
        return execute("deleted", () -> teamService.deleteWithMembers(id));
    }

    private Map<String, Object> execute(String status, Supplier<Team> operation) {
        try {
            Team team = operation.get();
            return Map.of("status", status, "success", true, "teamId", team.getTeamId(), "teamName", team.getTeamName());
        } catch (TeamOperationException error) {
            if (error.getKind() == TeamOperationException.Kind.PERSISTENCE) {
                throw new IllegalStateException(error.getMessage(), error);
            }
            throw new IllegalArgumentException(error.getMessage(), error);
        }
    }
}
