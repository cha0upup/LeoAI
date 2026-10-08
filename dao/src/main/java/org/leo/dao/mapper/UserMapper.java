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

    @Select("SELECT * FROM users WHERE user_name = #{userName} COLLATE NOCASE")
    User findUserByUsername(@Param("userName") String userName);

    @Select("SELECT * FROM users WHERE user_id = #{userId}")
    User findUserById(@Param("userId") String userId);

    @Select("SELECT * FROM users WHERE team_id = #{teamId}")
    List<User> findUsersByTeamId(@Param("teamId") String teamId);

    @Select("SELECT * FROM users")
    List<User> getAllUsers();

    @Insert("""
            INSERT INTO users (
                user_id, user_name, password,
                privilege, email, phone,
                status, last_login_time, login_count,
                password_change_required, create_time, update_time,
                team_id, remark
            ) VALUES (
                #{user.userId}, #{user.userName}, #{user.password},
                COALESCE(#{user.privilege}, 'normal'), #{user.email}, #{user.phone},
                COALESCE(#{user.status}, 1), #{user.lastLoginTime}, COALESCE(#{user.loginCount}, 0),
                COALESCE(#{user.passwordChangeRequired}, 0), #{timestamp}, #{timestamp},
                #{user.teamId}, #{user.remark}
            )
            """)
    boolean insertUser(@Param("user") User user, @Param("timestamp") String timestamp);

    @Delete("DELETE FROM users WHERE user_id = #{userId}")
    boolean delUser(@Param("userId") String userId);

    @Update("""
            UPDATE users SET
                user_name=#{user.userName}, password=#{user.password},
                privilege=#{user.privilege}, email=#{user.email},
                phone=#{user.phone}, status=#{user.status},
                last_login_time=#{user.lastLoginTime}, login_count=#{user.loginCount},
                password_change_required=COALESCE(#{user.passwordChangeRequired}, 0), update_time=#{timestamp},
                team_id=#{user.teamId}, remark=#{user.remark}
            WHERE user_id=#{user.userId}
            """)
    boolean updateUserById(@Param("user") User user, @Param("timestamp") String timestamp);

    @Update("UPDATE users SET last_login_time=#{lastLoginTime}, login_count=COALESCE(login_count, 0) + 1, "
            + "update_time=#{updateTime} WHERE user_id=#{userId}")
    boolean recordSuccessfulLogin(@Param("userId") String userId,
                                  @Param("lastLoginTime") String lastLoginTime,
                                  @Param("updateTime") String updateTime);
}
