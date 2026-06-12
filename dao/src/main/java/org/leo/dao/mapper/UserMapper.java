package org.leo.dao.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.leo.core.entity.User;

import java.util.List;


@Mapper
public interface UserMapper {

    @Select("SELECT * FROM users WHERE user_name = #{userName}")
    User findUserByUsername(@Param("userName") String userName);

    @Select("SELECT * FROM users WHERE user_id = #{userId}")
    User findUserById(@Param("userId") String userId);

    @Select("SELECT * FROM users WHERE team_id = #{teamId}")
    List<User> findUserByTeamId(@Param("teamId") String teamId);

    @Select("SELECT * FROM users")
    List<User> getAllUser();

    @Insert("INSERT INTO users (user_id, user_name, password, privilege, email, phone, status, last_login_time, login_count, create_time, update_time, team_id, remark) VALUES (#{userId}, #{userName}, #{password}, #{privilege}, #{email}, #{phone}, #{status}, #{lastLoginTime}, #{loginCount}, #{createTime}, #{updateTime}, #{teamId}, #{remark})")
    boolean insertUser(@Param("userId") String userId, 
                      @Param("userName") String userName,
                      @Param("password") String password, 
                      @Param("privilege") String privilege,
                      @Param("email") String email,
                      @Param("phone") String phone,
                      @Param("status") Integer status,
                      @Param("lastLoginTime") String lastLoginTime,
                      @Param("loginCount") Integer loginCount,
                      @Param("createTime") String createTime,
                      @Param("updateTime") String updateTime,
                      @Param("teamId") String teamId,
                      @Param("remark") String remark);

    @Delete("DELETE FROM users WHERE user_id = #{userId}")
    boolean delUser(@Param("userId") String userId);

    @Update("UPDATE users SET user_name=#{userName}, password=#{password}, privilege=#{privilege}, email=#{email}, phone=#{phone}, status=#{status}, last_login_time=#{lastLoginTime}, login_count=#{loginCount}, update_time=#{updateTime}, team_id=#{teamId}, remark=#{remark} WHERE user_id=#{userId}")
    boolean updateUserById(@Param("userName") String userName,
                          @Param("password") String password, 
                          @Param("privilege") String privilege,
                          @Param("email") String email,
                          @Param("phone") String phone,
                          @Param("status") Integer status,
                          @Param("lastLoginTime") String lastLoginTime,
                          @Param("loginCount") Integer loginCount,
                          @Param("updateTime") String updateTime,
                          @Param("teamId") String teamId,
                          @Param("remark") String remark,
                          @Param("userId") String userId);
}
