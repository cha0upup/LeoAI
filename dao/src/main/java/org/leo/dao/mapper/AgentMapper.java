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

    @Insert("INSERT INTO agents (agent_id, agent_name, parent_agent_id, create_by_user_id, team_id, conn_link, protocol, headers, req_disguise_id, resp_disguise_id, payload_key, proxy_enabled, proxy_type, proxy_host, proxy_port, max_req_count, permission, last_heartbeat, heartbeat_interval, create_time, update_time, remark, url_strategy, padding_strategy, header_noise_strategy, tls_fingerprint_strategy, component_class_name_strategy, type) VALUES (#{agentId}, #{agentName}, #{parentAgentId}, #{createByUserId}, #{teamId}, #{connLink}, #{protocol}, #{headers}, #{reqDisguiseId}, #{respDisguiseId}, #{payloadKey}, #{proxyEnabled}, #{proxyType}, #{proxyHost}, #{proxyPort}, #{maxReqCount}, #{permission}, #{lastHeartbeat}, #{heartbeatInterval}, #{createTime}, #{updateTime}, #{remark}, #{urlStrategy}, #{paddingStrategy}, #{headerNoiseStrategy}, #{tlsFingerprintStrategy}, #{componentClassNameStrategy}, #{type})")
    boolean insertAgent(@Param("agentId") String agentId,
                        @Param("agentName") String agentName,
                        @Param("parentAgentId") String parentAgentId,
                        @Param("createByUserId") String createByUserId,
                        @Param("teamId") String teamId,
                        @Param("connLink") String connLink,
                        @Param("protocol") String protocol,
                        @Param("headers") String headers,
                        @Param("reqDisguiseId") String reqDisguiseId,
                        @Param("respDisguiseId") String respDisguiseId,
                        @Param("payloadKey") String payloadKey,
                        @Param("proxyEnabled") Integer proxyEnabled,
                        @Param("proxyType") String proxyType,
                        @Param("proxyHost") String proxyHost,
                        @Param("proxyPort") Integer proxyPort,
                        @Param("maxReqCount") Integer maxReqCount,
                        @Param("permission") String permission,
                        @Param("lastHeartbeat") String lastHeartbeat,
                        @Param("heartbeatInterval") Integer heartbeatInterval,
                        @Param("createTime") String createTime,
                        @Param("updateTime") String updateTime,
                        @Param("remark") String remark,
                        @Param("urlStrategy") String urlStrategy,
                        @Param("paddingStrategy") String paddingStrategy,
                        @Param("headerNoiseStrategy") String headerNoiseStrategy,
                        @Param("tlsFingerprintStrategy") String tlsFingerprintStrategy,
                        @Param("componentClassNameStrategy") String componentClassNameStrategy,
                        @Param("type") String type);

    @Select("SELECT * FROM agents WHERE parent_agent_id = #{parentAgentId}")
    List<Agent> findAgentsByParentAgentId(@Param("parentAgentId") String parentAgentId);

    @Select("SELECT * FROM agents WHERE permission = #{permission}")
    List<Agent> findAgentsByPermission(@Param("permission") String permission);

    @Select("SELECT * FROM agents")
    List<Agent> getAllAgents();

    @Delete("DELETE FROM agents WHERE agent_id = #{agentId}")
    boolean deleteAgentById(@Param("agentId") String agentId);

    @Delete("DELETE FROM project_agents WHERE agent_id = #{agentId}")
    boolean deleteProjectRelationsByAgentId(@Param("agentId") String agentId);

    @Update("UPDATE agents SET agent_name=#{agentName}, parent_agent_id=#{parentAgentId}, create_by_user_id=#{createByUserId}, team_id=#{teamId}, conn_link=#{connLink}, protocol=#{protocol}, headers=#{headers}, req_disguise_id=#{reqDisguiseId}, resp_disguise_id=#{respDisguiseId}, payload_key=#{payloadKey}, proxy_enabled=#{proxyEnabled}, proxy_type=#{proxyType}, proxy_host=#{proxyHost}, proxy_port=#{proxyPort}, max_req_count=#{maxReqCount}, permission=#{permission}, last_heartbeat=#{lastHeartbeat}, heartbeat_interval=#{heartbeatInterval}, update_time=#{updateTime}, remark=#{remark}, url_strategy=#{urlStrategy}, padding_strategy=#{paddingStrategy}, header_noise_strategy=#{headerNoiseStrategy}, tls_fingerprint_strategy=#{tlsFingerprintStrategy}, component_class_name_strategy=#{componentClassNameStrategy}, type=#{type} WHERE agent_id=#{agentId}")
    boolean updateAgentById(@Param("agentId") String agentId,
                            @Param("agentName") String agentName,
                            @Param("parentAgentId") String parentAgentId,
                            @Param("createByUserId") String createByUserId,
                            @Param("teamId") String teamId,
                            @Param("connLink") String connLink,
                            @Param("protocol") String protocol,
                            @Param("headers") String headers,
                            @Param("reqDisguiseId") String reqDisguiseId,
                            @Param("respDisguiseId") String respDisguiseId,
                            @Param("payloadKey") String payloadKey,
                            @Param("proxyEnabled") Integer proxyEnabled,
                            @Param("proxyType") String proxyType,
                            @Param("proxyHost") String proxyHost,
                            @Param("proxyPort") Integer proxyPort,
                            @Param("maxReqCount") Integer maxReqCount,
                            @Param("permission") String permission,
                            @Param("lastHeartbeat") String lastHeartbeat,
                            @Param("heartbeatInterval") Integer heartbeatInterval,
                            @Param("updateTime") String updateTime,
                            @Param("remark") String remark,
                            @Param("urlStrategy") String urlStrategy,
                            @Param("paddingStrategy") String paddingStrategy,
                            @Param("headerNoiseStrategy") String headerNoiseStrategy,
                            @Param("tlsFingerprintStrategy") String tlsFingerprintStrategy,
                            @Param("componentClassNameStrategy") String componentClassNameStrategy,
                            @Param("type") String type);

    /**
     * 仅更新 last_heartbeat 字段，避免全量覆盖。
     * 连接测试成功或初始化成功后调用。
     */
    @Update("UPDATE agents SET last_heartbeat=#{lastHeartbeat}, update_time=#{updateTime} WHERE agent_id=#{agentId}")
    boolean updateLastHeartbeat(@Param("agentId") String agentId,
                                @Param("lastHeartbeat") String lastHeartbeat,
                                @Param("updateTime") String updateTime);

}
