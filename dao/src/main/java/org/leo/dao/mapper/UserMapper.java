package org.leo.dao.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.leo.core.entity.User;
import org.leo.core.entity.UserOption;

import java.util.List;


@Mapper
public interface UserMapper {

    String USERNAME_MATCH = " WHERE user_name = #{userName} COLLATE NOCASE";

    /** Directory reads omit credentials; single-account lookups still support login and updates. */
    String USER_LIST_COLUMNS = """
            user_id, user_name, privilege, email, phone, status, last_login_time, login_count,
            password_change_required, account_revision, create_time, update_time, team_id, remark
            """;

    String WITHOUT_TEAM = """
            FROM users
            WHERE team_id IS NULL OR trim(team_id, #{whitespace}) = ''
            ORDER BY rowid
            """;

    @Select("SELECT * FROM users" + USERNAME_MATCH)
    User findUserByUsername(@Param("userName") String userName);

    @Select("SELECT EXISTS (SELECT 1 FROM users" + USERNAME_MATCH + ")")
    boolean existsUserByUsername(@Param("userName") String userName);

    @Select("SELECT * FROM users WHERE user_id = #{userId}")
    User findUserById(@Param("userId") String userId);

    @Select("SELECT " + USER_LIST_COLUMNS + "FROM users WHERE team_id = #{teamId}")
    List<User> findUsersByTeamId(@Param("teamId") String teamId);

    @Select("SELECT " + USER_LIST_COLUMNS + "FROM users")
    List<User> getAllUsers();

    @Select("SELECT " + USER_LIST_COLUMNS + WITHOUT_TEAM)
    List<User> findUsersWithoutTeam(@Param("whitespace") String whitespace);

    @Select("SELECT user_id, user_name " + WITHOUT_TEAM)
    List<UserOption> findUserOptionsWithoutTeam(@Param("whitespace") String whitespace);

    @Select("SELECT user_id, user_name FROM users WHERE status = 1 ORDER BY user_name COLLATE NOCASE, user_id")
    List<UserOption> findEnabledUserOptions();

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

    @Delete("""
            DELETE FROM users WHERE user_id = #{userId}
              AND NOT EXISTS (SELECT 1 FROM projects WHERE owner_user_id = #{userId})
              AND NOT EXISTS (SELECT 1 FROM teams WHERE leader_id = #{userId})
            """)
    boolean delUser(@Param("userId") String userId);

    @Update("""
            UPDATE users SET
                user_name=#{user.userName}, password=#{user.password},
                privilege=#{user.privilege}, email=#{user.email},
                phone=#{user.phone}, status=#{user.status},
                password_change_required=COALESCE(#{user.passwordChangeRequired}, 0), update_time=#{timestamp},
                team_id=#{user.teamId}, remark=#{user.remark}, account_revision=account_revision + 1
            WHERE user_id=#{user.userId} AND account_revision=#{user.accountRevision}
            """)
    boolean updateUserById(@Param("user") User user, @Param("timestamp") String timestamp);

    @Update("""
            UPDATE users SET team_id=NULL, update_time=#{timestamp}, account_revision=account_revision + 1,
                privilege=CASE WHEN privilege='leader' THEN 'normal' ELSE privilege END
            WHERE team_id=#{teamId}
            """)
    void detachTeamMembers(@Param("teamId") String teamId, @Param("timestamp") String timestamp);

    @Update("UPDATE users SET last_login_time=#{lastLoginTime}, login_count=COALESCE(login_count, 0) + 1, "
            + "update_time=#{lastLoginTime} WHERE user_id=#{userId} AND status=1 AND password=#{verifiedPasswordHash}")
    boolean recordSuccessfulLogin(@Param("userId") String userId,
                                  @Param("verifiedPasswordHash") String verifiedPasswordHash,
                                  @Param("lastLoginTime") String lastLoginTime);
}
