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
import org.leo.core.entity.ProjectPuppet;
import org.leo.core.entity.Puppet;
import org.leo.dao.entity.ProjectPuppetCount;

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

    @Delete("DELETE FROM project_puppets WHERE project_id=#{projectId}")
    int deletePuppetRelations(@Param("projectId") String projectId);

    @Update("UPDATE puppet_sessions SET project_id=NULL WHERE project_id=#{projectId}")
    int clearSessionProject(@Param("projectId") String projectId);

    @Delete("DELETE FROM projects WHERE project_id=#{projectId}")
    int deleteProject(@Param("projectId") String projectId);

    @Insert("INSERT INTO project_puppets (project_id, puppet_id, alias, environment, tags, sort_order, added_by_user_id, create_time) " +
            "VALUES (#{projectId}, #{puppetId}, #{alias}, #{environment}, #{tags}, #{sortOrder}, #{addedByUserId}, #{createTime}) " +
            "ON CONFLICT(project_id, puppet_id) DO UPDATE SET alias=excluded.alias, environment=excluded.environment, " +
            "tags=excluded.tags, sort_order=excluded.sort_order")
    boolean attachPuppet(ProjectPuppet projectPuppet);

    @Delete("DELETE FROM project_puppets WHERE project_id=#{projectId} AND puppet_id=#{puppetId}")
    boolean detachPuppet(@Param("projectId") String projectId,
                         @Param("puppetId") String puppetId);

    @Select("SELECT project_id, COUNT(*) AS puppet_count FROM project_puppets GROUP BY project_id")
    @ConstructorArgs({
            @Arg(column = "project_id", javaType = String.class),
            @Arg(column = "puppet_count", javaType = int.class)
    })
    List<ProjectPuppetCount> countPuppetsByProject();

    @Select("SELECT COUNT(*) FROM project_puppets WHERE project_id=#{projectId} AND puppet_id=#{puppetId}")
    int containsPuppet(@Param("projectId") String projectId,
                       @Param("puppetId") String puppetId);

    @Select("SELECT p.* FROM projects p INNER JOIN project_puppets pp ON pp.project_id=p.project_id " +
            "WHERE pp.puppet_id=#{puppetId} ORDER BY p.status ASC, p.project_name COLLATE NOCASE ASC")
    List<Project> findProjectsByPuppetId(@Param("puppetId") String puppetId);

    @Select("SELECT p.* FROM puppets p INNER JOIN project_puppets pp ON pp.puppet_id=p.puppet_id " +
            "WHERE pp.project_id=#{projectId} AND p.parent_puppet_id=#{parentPuppetId} " +
            "ORDER BY pp.sort_order ASC, p.update_time DESC, p.puppet_name COLLATE NOCASE ASC")
    List<Puppet> findPuppetsByProjectAndParent(@Param("projectId") String projectId,
                                               @Param("parentPuppetId") String parentPuppetId);

    @Select("SELECT p.* FROM puppets p WHERE p.parent_puppet_id='root' " +
            "AND NOT EXISTS (SELECT 1 FROM project_puppets pp WHERE pp.puppet_id=p.puppet_id) " +
            "ORDER BY p.update_time DESC, p.puppet_name COLLATE NOCASE ASC")
    List<Puppet> findUnassignedRootPuppets();
}
