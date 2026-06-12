package org.leo.dao.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.leo.core.entity.UserAiPolicy;

@Mapper
public interface UserAiPolicyMapper {

    @Select("SELECT * FROM user_ai_policy WHERE user_id = #{userId}")
    UserAiPolicy findByUserId(@Param("userId") String userId);

    /**
     * 插入或替换（SQLite 语法）。
     */
    @Insert("INSERT OR REPLACE INTO user_ai_policy (user_id, allowed_tool_types, update_time) " +
            "VALUES (#{userId}, #{allowedToolTypes}, #{updateTime})")
    boolean upsert(@Param("userId") String userId,
                   @Param("allowedToolTypes") String allowedToolTypes,
                   @Param("updateTime") String updateTime);

    @Delete("DELETE FROM user_ai_policy WHERE user_id = #{userId}")
    boolean deleteByUserId(@Param("userId") String userId);
}
