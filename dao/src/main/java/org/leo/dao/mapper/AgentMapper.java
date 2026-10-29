package org.leo.dao.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.leo.core.entity.Agent;

import java.util.List;

/**
 * Agent数据访问层
 * 
 * @author LeoSpring
 * @version 2.1
 */
@Mapper
public interface AgentMapper {

    @Select("SELECT * FROM agents WHERE agent_id = #{agentId}")
    Agent findAgentById(@Param("agentId") String agentId);

    @Insert("""
            INSERT INTO agents (
                agent_id, agent_name, parent_agent_id, create_by_user_id,
                team_id, conn_link, protocol, headers,
                req_disguise_id, resp_disguise_id, payload_key, proxy_enabled,
                proxy_type, proxy_host, proxy_port, max_req_count,
                permission, last_connected_at, create_time,
                update_time, remark, url_strategy, padding_strategy,
                header_noise_strategy, tls_fingerprint_strategy, component_class_name_strategy, type
            ) VALUES (
                #{agent.agentId}, #{agent.agentName}, #{agent.parentAgentId}, #{agent.createByUserId},
                #{agent.teamId}, #{agent.connLink}, #{agent.protocol}, #{agent.headers},
                #{agent.reqDisguiseId}, #{agent.respDisguiseId}, #{agent.payloadKey}, #{agent.proxyEnabled},
                #{agent.proxyType}, #{agent.proxyHost}, #{agent.proxyPort}, #{agent.maxReqCount},
                #{agent.permission}, #{agent.lastConnectedAt}, #{timestamp},
                #{timestamp}, #{agent.remark}, #{agent.urlStrategy}, #{agent.paddingStrategy},
                #{agent.headerNoiseStrategy}, #{agent.tlsFingerprintStrategy}, #{agent.componentClassNameStrategy}, #{agent.type}
            )
            """)
    boolean insertAgent(@Param("agent") Agent agent, @Param("timestamp") String timestamp);

    @Select("SELECT * FROM agents WHERE parent_agent_id = #{parentAgentId}")
    List<Agent> findAgentsByParentAgentId(@Param("parentAgentId") String parentAgentId);

    @Select("<script>SELECT * FROM agents WHERE parent_agent_id IN "
            + "<foreach collection='parentAgentIds' item='parentId' open='(' separator=',' close=')'>"
            + "#{parentId}</foreach></script>")
    List<Agent> findAgentsByParentAgentIds(@Param("parentAgentIds") List<String> parentAgentIds);

    @Select("SELECT * FROM agents")
    List<Agent> getAllAgents();

    @Delete("DELETE FROM agents WHERE agent_id = #{agentId}")
    boolean deleteAgentById(@Param("agentId") String agentId);

    @Delete("DELETE FROM project_agents WHERE agent_id = #{agentId}")
    boolean deleteProjectRelationsByAgentId(@Param("agentId") String agentId);

    /** Editable configuration only; connection timestamps are server-owned. */
    @Update("""
            UPDATE agents SET
                agent_name=#{agent.agentName}, parent_agent_id=#{agent.parentAgentId},
                create_by_user_id=#{agent.createByUserId}, team_id=#{agent.teamId},
                conn_link=#{agent.connLink}, protocol=#{agent.protocol},
                headers=#{agent.headers}, req_disguise_id=#{agent.reqDisguiseId},
                resp_disguise_id=#{agent.respDisguiseId}, payload_key=#{agent.payloadKey},
                proxy_enabled=#{agent.proxyEnabled}, proxy_type=#{agent.proxyType},
                proxy_host=#{agent.proxyHost}, proxy_port=#{agent.proxyPort},
                max_req_count=#{agent.maxReqCount}, permission=#{agent.permission},
                update_time=#{timestamp}, remark=#{agent.remark},
                url_strategy=#{agent.urlStrategy}, padding_strategy=#{agent.paddingStrategy},
                header_noise_strategy=#{agent.headerNoiseStrategy}, tls_fingerprint_strategy=#{agent.tlsFingerprintStrategy},
                component_class_name_strategy=#{agent.componentClassNameStrategy}, type=#{agent.type}
            WHERE agent_id=#{agent.agentId}
            """)
    boolean updateAgentById(@Param("agent") Agent agent, @Param("timestamp") String timestamp);

    /**
     * 仅更新 last_connected_at 字段，避免全量覆盖。
     * 连接测试成功或初始化成功后调用。
     */
    @Update("UPDATE agents SET last_connected_at=#{lastConnectedAt}, update_time=#{updateTime} WHERE agent_id=#{agentId}")
    boolean updateLastConnectedAt(@Param("agentId") String agentId,
                                @Param("lastConnectedAt") String lastConnectedAt,
                                @Param("updateTime") String updateTime);

}
