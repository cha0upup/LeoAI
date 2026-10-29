package org.leo.dao.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.leo.core.entity.AgentDatabaseConnection;

import java.util.List;

@Mapper
public interface AgentDatabaseConnectionMapper {

    @Insert("INSERT INTO agent_database_connections (connection_id, connection_name, agent_id, dialect, connection_spec, username, password, status, test_status, last_test_time, last_test_message, timeout_seconds, create_user_id, create_time, update_time, description, remark) " +
            "VALUES (#{connectionId}, #{connectionName}, #{agentId}, #{dialect}, #{connectionSpec}, #{username}, #{password}, #{status}, #{testStatus}, #{lastTestTime}, #{lastTestMessage}, #{timeoutSeconds}, #{createUserId}, #{createTime}, #{updateTime}, #{description}, #{remark})")
    int insert(AgentDatabaseConnection connection);

    @Update("UPDATE agent_database_connections SET connection_name=#{connectionName}, agent_id=#{agentId}, dialect=#{dialect}, connection_spec=#{connectionSpec}, username=#{username}, password=#{password}, status=#{status}, test_status=#{testStatus}, last_test_time=#{lastTestTime}, last_test_message=#{lastTestMessage}, timeout_seconds=#{timeoutSeconds}, update_time=#{updateTime}, description=#{description}, remark=#{remark} WHERE connection_id=#{connectionId}")
    int update(AgentDatabaseConnection connection);

    @Update("UPDATE agent_database_connections SET test_status=#{testStatus}, "
            + "last_test_time=datetime('now'), last_test_message=#{testMessage}, "
            + "update_time=datetime('now') WHERE connection_id=#{connectionId}")
    int updateTestStatus(@Param("connectionId") String connectionId,
                         @Param("testStatus") Integer testStatus,
                         @Param("testMessage") String testMessage);

    @Update("UPDATE agent_database_connections SET status=#{status}, "
            + "update_time=datetime('now') WHERE connection_id=#{connectionId} AND agent_id=#{agentId}")
    int updateStatusByAgent(@Param("connectionId") String connectionId,
                            @Param("agentId") String agentId,
                            @Param("status") Integer status);

    @Delete("DELETE FROM agent_database_connections WHERE connection_id=#{connectionId} AND agent_id=#{agentId}")
    int deleteByIdAndAgent(@Param("connectionId") String connectionId,
                           @Param("agentId") String agentId);

    @Select("SELECT * FROM agent_database_connections WHERE connection_id=#{connectionId}")
    AgentDatabaseConnection selectById(@Param("connectionId") String connectionId);

    @Select("SELECT * FROM agent_database_connections WHERE agent_id=#{agentId} ORDER BY create_time DESC")
    List<AgentDatabaseConnection> selectByAgentId(@Param("agentId") String agentId);

    @Select("<script>SELECT COUNT(*) > 0 FROM agent_database_connections "
            + "WHERE agent_id=#{agentId} AND connection_name=#{connectionName} " +
            "<if test='excludeConnectionId != null and excludeConnectionId != \"\"'>AND connection_id != #{excludeConnectionId}</if></script>")
    boolean existsByName(@Param("agentId") String agentId,
                         @Param("connectionName") String connectionName,
                         @Param("excludeConnectionId") String excludeConnectionId);

}
