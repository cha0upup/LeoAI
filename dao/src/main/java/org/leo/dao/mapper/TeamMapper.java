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
    List<Team> findTeamsByLeaderId(@Param("leaderId") String leaderId);

    @Select("SELECT * FROM teams WHERE team_name = #{teamName} COLLATE NOCASE")
    Team findTeamByName(@Param("teamName") String teamName);

    @Select("SELECT * FROM teams")
    List<Team> getAllTeams();

    @Insert("""
            INSERT INTO teams (
                team_id, team_name, leader_id,
                description, status, create_time,
                update_time, remark
            ) VALUES (
                #{team.teamId}, #{team.teamName}, #{team.leaderId},
                #{team.description}, COALESCE(#{team.status}, 1), #{timestamp},
                #{timestamp}, #{team.remark}
            )
            """)
    boolean insertTeam(@Param("team") Team team, @Param("timestamp") String timestamp);

    @Delete("DELETE FROM teams WHERE team_id = #{teamId}")
    boolean delTeam(@Param("teamId") String teamId);

    @Update("""
            UPDATE teams SET
                team_name=#{team.teamName}, leader_id=#{team.leaderId},
                description=#{team.description}, status=#{team.status},
                update_time=#{timestamp}, remark=#{team.remark}
            WHERE team_id=#{team.teamId}
            """)
    boolean updateTeamById(@Param("team") Team team, @Param("timestamp") String timestamp);

}
