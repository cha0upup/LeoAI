package org.leo.dao.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.leo.core.entity.PuppetJdbc;

import java.util.List;
import java.util.Map;

/**
 * PuppetJdbc数据访问层
 * 
 * @author LeoSpring
 * @version 2.1
 */
@Mapper
public interface PuppetJdbcMapper {
    /**
     * 插入新的数据库连接
     *
     * @param connection 数据库连接信息
     * @return 影响的行数
     */
    @Insert("INSERT INTO puppet_jdbc (conn_id, conn_name, puppet_id, db_type, host, port, database_name, username, password, url_template, jdbc_url, driver_class, connection_params, status, test_status, last_test_time, last_test_message, max_connections, timeout_seconds, create_user_id, team_id, is_public, create_time, update_time, description, remark) " +
            "VALUES (#{connId}, #{connName}, #{puppetId}, #{dbType}, #{host}, #{port}, #{databaseName}, #{username}, #{password}, #{urlTemplate}, #{jdbcUrl}, #{driverClass}, #{connectionParams}, #{status}, #{testStatus}, #{lastTestTime}, #{lastTestMessage}, #{maxConnections}, #{timeoutSeconds}, #{createUserId}, #{teamId}, #{isPublic}, #{createTime}, #{updateTime}, #{description}, #{remark})")
    int insert(PuppetJdbc connection);

    /**
     * 根据ID更新数据库连接
     *
     * @param connection 数据库连接信息
     * @return 影响的行数
     */
    @Update("UPDATE puppet_jdbc SET conn_name=#{connName}, puppet_id=#{puppetId}, db_type=#{dbType}, host=#{host}, port=#{port}, database_name=#{databaseName}, username=#{username}, password=#{password}, url_template=#{urlTemplate}, jdbc_url=#{jdbcUrl}, driver_class=#{driverClass}, connection_params=#{connectionParams}, status=#{status}, test_status=#{testStatus}, last_test_time=#{lastTestTime}, last_test_message=#{lastTestMessage}, max_connections=#{maxConnections}, timeout_seconds=#{timeoutSeconds}, team_id=#{teamId}, is_public=#{isPublic}, update_time=#{updateTime}, description=#{description}, remark=#{remark} WHERE conn_id=#{connId}")
    int update(PuppetJdbc connection);

    /**
     * 根据ID删除数据库连接
     *
     * @param connId 连接ID
     * @return 影响的行数
     */
    @Delete("DELETE FROM puppet_jdbc WHERE conn_id = #{connId}")
    int deleteById(@Param("connId") String connId);

    /**
     * 根据ID查询数据库连接
     *
     * @param connId 连接ID
     * @return 数据库连接信息
     */
    @Select("SELECT * FROM puppet_jdbc WHERE conn_id = #{connId}")
    PuppetJdbc selectById(@Param("connId") String connId);

    /**
     * 根据名称查询数据库连接
     *
     * @param connName 连接名称
     * @return 数据库连接信息
     */
    @Select("SELECT * FROM puppet_jdbc WHERE conn_name = #{connName}")
    PuppetJdbc selectByName(@Param("connName") String connName);

    /**
     * 查询所有数据库连接
     *
     * @return 数据库连接列表
     */
    @Select("SELECT * FROM puppet_jdbc ORDER BY create_time DESC")
    List<PuppetJdbc> selectAll();

    /**
     * 根据用户ID查询数据库连接
     *
     * @param userId 用户ID
     * @return 数据库连接列表
     */
    @Select("SELECT * FROM puppet_jdbc WHERE create_user_id = #{userId} ORDER BY create_time DESC")
    List<PuppetJdbc> selectByUserId(@Param("userId") String userId);

    /**
     * 根据puppet ID查询数据库连接
     *
     * @param puppetId puppet ID
     * @return 数据库连接列表
     */
    @Select("SELECT * FROM puppet_jdbc WHERE puppet_id = #{puppetId} ORDER BY create_time DESC")
    List<PuppetJdbc> selectByPuppetId(@Param("puppetId") String puppetId);

    /**
     * 根据团队ID查询数据库连接
     *
     * @param teamId 团队ID
     * @return 数据库连接列表
     */
    @Select("SELECT * FROM puppet_jdbc WHERE team_id = #{teamId} ORDER BY create_time DESC")
    List<PuppetJdbc> selectByTeamId(@Param("teamId") String teamId);

    /**
     * 根据数据库类型查询数据库连接
     *
     * @param dbType 数据库类型
     * @return 数据库连接列表
     */
    @Select("SELECT * FROM puppet_jdbc WHERE db_type = #{dbType} ORDER BY create_time DESC")
    List<PuppetJdbc> selectByDbType(@Param("dbType") String dbType);

    /**
     * 根据状态查询数据库连接
     *
     * @param status 状态 (1:启用 0:禁用)
     * @return 数据库连接列表
     */
    @Select("SELECT * FROM puppet_jdbc WHERE status = #{status} ORDER BY create_time DESC")
    List<PuppetJdbc> selectByStatus(@Param("status") Integer status);

    /**
     * 根据测试状态查询数据库连接
     *
     * @param testStatus 测试状态 (0:未测试 1:成功 2:失败)
     * @return 数据库连接列表
     */
    @Select("SELECT * FROM puppet_jdbc WHERE test_status = #{testStatus} ORDER BY create_time DESC")
    List<PuppetJdbc> selectByTestStatus(@Param("testStatus") Integer testStatus);

    /**
     * 查询公开的数据库连接
     *
     * @return 公开的数据库连接列表
     */
    @Select("SELECT * FROM puppet_jdbc WHERE is_public = 1 ORDER BY create_time DESC")
    List<PuppetJdbc> selectPublic();

    /**
     * 根据条件查询数据库连接
     *
     * @param params 查询条件参数
     * @return 数据库连接列表
     */
    @Select("<script>" +
            "SELECT * FROM puppet_jdbc WHERE 1=1 " +
            "<if test='params.puppetId != null'> AND puppet_id = #{params.puppetId}</if>" +
            "<if test='params.dbType != null'> AND db_type = #{params.dbType}</if>" +
            "<if test='params.status != null'> AND status = #{params.status}</if>" +
            "<if test='params.testStatus != null'> AND test_status = #{params.testStatus}</if>" +
            "<if test='params.createUserId != null'> AND create_user_id = #{params.createUserId}</if>" +
            "<if test='params.teamId != null'> AND team_id = #{params.teamId}</if>" +
            "<if test='params.isPublic != null'> AND is_public = #{params.isPublic}</if>" +
            "<if test='params.connName != null and params.connName != \"\"'> AND conn_name LIKE '%' || #{params.connName} || '%'</if>" +
            " ORDER BY create_time DESC" +
            "</script>")
    List<PuppetJdbc> selectByCondition(@Param("params") Map<String, Object> params);

    /**
     * 统计用户创建的数据库连接数量
     *
     * @param userId 用户ID
     * @return 连接数量
     */
    @Select("SELECT COUNT(*) FROM puppet_jdbc WHERE create_user_id = #{userId}")
    int countByUserId(@Param("userId") String userId);

    /**
     * 统计团队的数据库连接数量
     *
     * @param teamId 团队ID
     * @return 连接数量
     */
    @Select("SELECT COUNT(*) FROM puppet_jdbc WHERE team_id = #{teamId}")
    int countByTeamId(@Param("teamId") String teamId);

    /**
     * 统计各数据库类型的连接数量
     *
     * @return 各类型连接数量统计
     */
    @Select("SELECT db_type, COUNT(*) as count FROM puppet_jdbc GROUP BY db_type")
    List<Map<String, Object>> countByDbType();

    /**
     * 更新连接测试状态
     *
     * @param connId 连接ID
     * @param testStatus 测试状态
     * @param testMessage 测试结果信息
     * @return 影响的行数
     */
    @Update("UPDATE puppet_jdbc SET test_status = #{testStatus}, last_test_message = #{testMessage}, last_test_time = datetime('now') WHERE conn_id = #{connId}")
    int updateTestStatus(@Param("connId") String connId, @Param("testStatus") Integer testStatus, @Param("testMessage") String testMessage);

    /**
     * 更新连接使用信息
     *
     * @param connId 连接ID
     * @return 影响的行数
     */
    @Update("UPDATE puppet_jdbc SET update_time = datetime('now') WHERE conn_id = #{connId}")
    int updateUsageInfo(@Param("connId") String connId);

    /**
     * 批量更新连接状态
     *
     * @param connIds 连接ID列表
     * @param status 状态
     * @return 影响的行数
     */
    @Update("<script>" +
            "UPDATE puppet_jdbc SET status = #{status}, update_time = datetime('now') WHERE conn_id IN " +
            "<foreach collection='connIds' item='id' open='(' separator=',' close=')'>" +
            "#{id}" +
            "</foreach>" +
            "</script>")
    int batchUpdateStatus(@Param("connIds") List<String> connIds, @Param("status") Integer status);

    /**
     * 检查连接名称是否已存在
     *
     * @param connName 连接名称
     * @param excludeConnId 排除的连接ID（用于更新时检查）
     * @return 是否存在
     */
    @Select("<script>" +
            "SELECT COUNT(*) > 0 FROM puppet_jdbc WHERE conn_name = #{connName} " +
            "<if test='excludeConnId != null and excludeConnId != \"\"'> AND conn_id != #{excludeConnId}</if>" +
            "</script>")
    boolean existsByName(@Param("connName") String connName, @Param("excludeConnId") String excludeConnId);
}
