package org.leo.dao.mapper;

import org.apache.ibatis.annotations.Arg;
import org.apache.ibatis.annotations.ConstructorArgs;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
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

    @Select("""
            <script>
            SELECT * FROM projects
            <if test="!admin">
                WHERE permission = 'public' OR owner_user_id = #{userId}
                   OR (permission = 'team' AND team_id = #{teamId})
            </if>
            ORDER BY status ASC, update_time DESC, project_name COLLATE NOCASE ASC
            </script>
            """)
    List<Project> findVisibleProjectCandidates(@Param("userId") String userId,
                                                @Param("teamId") String teamId,
                                                @Param("admin") boolean admin);

    @Select("SELECT * FROM projects WHERE project_id = #{projectId}")
    Project findProjectById(@Param("projectId") String projectId);

    @Insert("INSERT INTO projects (project_id, project_name, project_code, description, status, owner_user_id, team_id, permission, create_time, update_time) " +
            "VALUES (#{projectId}, #{projectName}, #{projectCode}, #{description}, #{status}, #{ownerUserId}, #{teamId}, #{permission}, #{createTime}, #{updateTime})")
    boolean insertProject(Project project);

    @Update("UPDATE projects SET project_name=#{projectName}, project_code=#{projectCode}, description=#{description}, " +
            "status=#{status}, team_id=#{teamId}, permission=#{permission}, update_time=#{updateTime} " +
            "WHERE project_id=#{projectId} AND owner_user_id=#{ownerUserId}")
    boolean updateProject(Project project);

    @Select(value = """
            UPDATE projects SET status = 'archived', update_time = #{updatedAt}
            WHERE project_id = #{projectId} AND owner_user_id = #{ownerUserId}
            RETURNING *
            """, affectData = true)
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    Project archiveProject(@Param("projectId") String projectId,
                           @Param("ownerUserId") String ownerUserId,
                           @Param("updatedAt") String updatedAt);

    @Update("""
            UPDATE projects SET owner_user_id = #{newOwnerId}, update_time = #{updatedAt}
            WHERE project_id = #{projectId} AND owner_user_id = #{currentOwnerId}
              AND EXISTS (SELECT 1 FROM users WHERE user_id = #{newOwnerId} AND status = 1)
            """)
    boolean transferOwner(@Param("projectId") String projectId,
                          @Param("currentOwnerId") String currentOwnerId,
                          @Param("newOwnerId") String newOwnerId,
                          @Param("updatedAt") String updatedAt);

    @Delete("DELETE FROM project_agents WHERE project_id=#{projectId}")
    int deleteAgentRelations(@Param("projectId") String projectId);

    @Delete("DELETE FROM projects WHERE project_id=#{projectId} AND owner_user_id=#{ownerUserId}")
    int deleteProject(@Param("projectId") String projectId, @Param("ownerUserId") String ownerUserId);

    /** Omitted metadata keeps its existing value; an unchanged relation does not count as a write. */
    @Insert("""
            INSERT INTO project_agents (project_id, agent_id, alias, tags, sort_order, added_by_user_id, create_time)
            VALUES (#{projectId}, #{agentId}, #{alias}, #{tags}, #{sortOrder}, #{addedByUserId}, #{createTime})
            ON CONFLICT(project_id, agent_id) DO UPDATE SET
                alias=COALESCE(excluded.alias, project_agents.alias),
                tags=COALESCE(excluded.tags, project_agents.tags)
            WHERE (excluded.alias IS NOT NULL AND project_agents.alias IS NOT excluded.alias)
               OR (excluded.tags IS NOT NULL AND project_agents.tags IS NOT excluded.tags)
            """)
    boolean attachAgent(ProjectAgent projectAgent);

    @Delete("""
            DELETE FROM project_agents
            WHERE project_id = #{projectId}
              AND agent_id IN (SELECT value FROM json_each(#{agentIdsJson}))
            """)
    int detachAgents(@Param("projectId") String projectId,
                     @Param("agentIdsJson") String agentIdsJson);

    @Select("""
            SELECT project_id, COUNT(*) AS agent_count
            FROM project_agents
            WHERE project_id IN (SELECT value FROM json_each(#{projectIdsJson}))
            GROUP BY project_id
            """)
    @ConstructorArgs({
            @Arg(column = "project_id", javaType = String.class),
            @Arg(column = "agent_count", javaType = int.class)
    })
    List<ProjectAgentCount> countAgentsByProject(@Param("projectIdsJson") String projectIdsJson);

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
