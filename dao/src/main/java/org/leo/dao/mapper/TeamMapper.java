package org.leo.dao.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.leo.core.entity.Team;

import java.util.List;

/**
 * 团队数据访问层
 * 
 * @author LeoSpring
 * @version 2.1
 */
@Mapper
public interface TeamMapper {
    
    @Select("SELECT * FROM teams WHERE team_id = #{teamId}")
    Team findTeamById(@Param("teamId") String teamId);

    @Select("SELECT * FROM teams WHERE leader_id = #{leaderId}")
    List<Team> findTeamByLeader(@Param("leaderId") String leaderId);

    @Select("SELECT * FROM teams WHERE team_name = #{teamName}")
    Team findTeamByName(@Param("teamName") String teamName);

    @Select("SELECT * FROM teams")
    List<Team> getAllTeam();

    @Insert("INSERT INTO teams (team_id, team_name, leader_id, description, status, create_time, update_time, remark) VALUES (#{teamId}, #{teamName}, #{leaderId}, #{description}, #{status}, #{createTime}, #{updateTime}, #{remark})")
    boolean insertTeam(@Param("teamId") String teamId, 
                      @Param("teamName") String teamName,
                      @Param("leaderId") String leaderId,
                      @Param("description") String description,
                      @Param("status") Integer status,
                      @Param("createTime") String createTime,
                      @Param("updateTime") String updateTime,
                      @Param("remark") String remark);

    @Delete("DELETE FROM teams WHERE team_id = #{teamId}")
    boolean delTeam(@Param("teamId") String teamId);

    @Update("UPDATE teams SET team_name=#{teamName}, leader_id=#{leaderId}, description=#{description}, status=#{status}, update_time=#{updateTime}, remark=#{remark} WHERE team_id=#{teamId}")
    boolean updateTeamById(@Param("teamId") String teamId,
                          @Param("teamName") String teamName,
                          @Param("leaderId") String leaderId,
                          @Param("description") String description,
                          @Param("status") Integer status,
                          @Param("updateTime") String updateTime,
                          @Param("remark") String remark);

}
