package org.leo.dao.mapper;

import org.apache.ibatis.annotations.Arg;
import org.apache.ibatis.annotations.ConstructorArgs;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.leo.core.entity.Project;
import org.leo.core.entity.ProjectAgent;
import org.leo.core.entity.Agent;
import org.leo.dao.entity.ProjectAgentCount;

import java.util.List;

@Mapper
public interface ProjectMapper {

    @Select("SELECT * FROM projects ORDER BY status ASC, update_time DESC, project_name COLLATE NOCASE ASC")
    List<Project> findAllProjects();

    @Select("SELECT * FROM projects WHERE project_id = #{projectId}")
    Project findProjectById(@Param("projectId") String projectId);

    @Insert("INSERT INTO projects (project_id, project_name, project_code, description, status, owner_user_id, team_id, permission, create_time, update_time) " +
            "VALUES (#{projectId}, #{projectName}, #{projectCode}, #{description}, #{status}, #{ownerUserId}, #{teamId}, #{permission}, #{createTime}, #{updateTime})")
    boolean insertProject(Project project);

    @Update("UPDATE projects SET project_name=#{projectName}, project_code=#{projectCode}, description=#{description}, " +
            "status=#{status}, team_id=#{teamId}, permission=#{permission}, update_time=#{updateTime} WHERE project_id=#{projectId}")
    boolean updateProject(Project project);

    @Delete("DELETE FROM project_agents WHERE project_id=#{projectId}")
    int deleteAgentRelations(@Param("projectId") String projectId);

    @Delete("DELETE FROM projects WHERE project_id=#{projectId}")
    int deleteProject(@Param("projectId") String projectId);

    @Insert("INSERT INTO project_agents (project_id, agent_id, alias, environment, tags, sort_order, added_by_user_id, create_time) " +
            "VALUES (#{projectId}, #{agentId}, #{alias}, #{environment}, #{tags}, #{sortOrder}, #{addedByUserId}, #{createTime}) " +
            "ON CONFLICT(project_id, agent_id) DO UPDATE SET alias=excluded.alias, environment=excluded.environment, " +
            "tags=excluded.tags, sort_order=excluded.sort_order")
    boolean attachAgent(ProjectAgent projectAgent);

    @Delete("DELETE FROM project_agents WHERE project_id=#{projectId} AND agent_id=#{agentId}")
    boolean detachAgent(@Param("projectId") String projectId,
                         @Param("agentId") String agentId);

    @Select("SELECT project_id, COUNT(*) AS agent_count FROM project_agents GROUP BY project_id")
    @ConstructorArgs({
            @Arg(column = "project_id", javaType = String.class),
            @Arg(column = "agent_count", javaType = int.class)
    })
    List<ProjectAgentCount> countAgentsByProject();

    @Select("SELECT COUNT(*) FROM project_agents WHERE project_id=#{projectId} AND agent_id=#{agentId}")
    int containsAgent(@Param("projectId") String projectId,
                       @Param("agentId") String agentId);

    @Select("SELECT p.* FROM projects p INNER JOIN project_agents pp ON pp.project_id=p.project_id " +
            "WHERE pp.agent_id=#{agentId} ORDER BY p.status ASC, p.project_name COLLATE NOCASE ASC")
    List<Project> findProjectsByAgentId(@Param("agentId") String agentId);

    @Select("SELECT p.* FROM agents p INNER JOIN project_agents pp ON pp.agent_id=p.agent_id " +
            "WHERE pp.project_id=#{projectId} AND p.parent_agent_id=#{parentAgentId} " +
            "ORDER BY pp.sort_order ASC, p.update_time DESC, p.agent_name COLLATE NOCASE ASC")
    List<Agent> findAgentsByProjectAndParent(@Param("projectId") String projectId,
                                               @Param("parentAgentId") String parentAgentId);

    @Select("SELECT p.* FROM agents p WHERE p.parent_agent_id='root' " +
            "AND NOT EXISTS (SELECT 1 FROM project_agents pp WHERE pp.agent_id=p.agent_id) " +
            "ORDER BY p.update_time DESC, p.agent_name COLLATE NOCASE ASC")
    List<Agent> findUnassignedRootAgents();
}
