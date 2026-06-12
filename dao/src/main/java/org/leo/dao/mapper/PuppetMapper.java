package org.leo.dao.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.leo.core.entity.Puppet;

import java.util.List;

/**
 * Puppet数据访问层
 * 
 * @author LeoSpring
 * @version 2.1
 */
@Mapper
public interface PuppetMapper {

    @Select("SELECT * FROM puppets WHERE puppet_id = #{puppetId}")
    Puppet findPuppetById(@Param("puppetId") String puppetId);

    @Insert("INSERT INTO puppets (puppet_id, puppet_name, parent_puppet_id, create_by_user_id, team_id, conn_link, protocol, headers, req_disguise_id, resp_disguise_id, proxy_enabled, proxy_type, proxy_host, proxy_port, balance_enabled, max_req_count, permission, last_heartbeat, heartbeat_interval, create_time, update_time, remark, url_strategy, padding_strategy, header_noise_strategy, tls_fingerprint_strategy, type) VALUES (#{puppetId}, #{puppetName}, #{parentPuppetId}, #{createByUserId}, #{teamId}, #{connLink}, #{protocol}, #{headers}, #{reqDisguiseId}, #{respDisguiseId}, #{proxyEnabled}, #{proxyType}, #{proxyHost}, #{proxyPort}, #{balanceEnabled}, #{maxReqCount}, #{permission}, #{lastHeartbeat}, #{heartbeatInterval}, #{createTime}, #{updateTime}, #{remark}, #{urlStrategy}, #{paddingStrategy}, #{headerNoiseStrategy}, #{tlsFingerprintStrategy}, #{type})")
    boolean insertPuppet(@Param("puppetId") String puppetId,
                        @Param("puppetName") String puppetName,
                        @Param("parentPuppetId") String parentPuppetId,
                        @Param("createByUserId") String createByUserId,
                        @Param("teamId") String teamId,
                        @Param("connLink") String connLink,
                        @Param("protocol") String protocol,
                        @Param("headers") String headers,
                        @Param("reqDisguiseId") String reqDisguiseId,
                        @Param("respDisguiseId") String respDisguiseId,
                        @Param("proxyEnabled") Integer proxyEnabled,
                        @Param("proxyType") String proxyType,
                        @Param("proxyHost") String proxyHost,
                        @Param("proxyPort") Integer proxyPort,
                        @Param("balanceEnabled") Integer balanceEnabled,
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
                        @Param("type") String type);

    @Select("SELECT * FROM puppets WHERE parent_puppet_id = #{parentPuppetId}")
    List<Puppet> findPuppetByParentPuppetId(@Param("parentPuppetId") String parentPuppetId);

    @Select("SELECT * FROM puppets WHERE create_by_user_id = #{createByUserId}")
    List<Puppet> findPuppetByCreateUser(@Param("createByUserId") String createByUserId);

    @Select("SELECT * FROM puppets WHERE permission = #{permission}")
    List<Puppet> findPuppetByPermission(@Param("permission") String permission);

    @Select("SELECT * FROM puppets")
    List<Puppet> getAllPuppet();

    @Delete("DELETE FROM puppets WHERE puppet_id = #{puppetId}")
    boolean deletePuppetById(@Param("puppetId") String puppetId);

    @Update("UPDATE puppets SET puppet_name=#{puppetName}, parent_puppet_id=#{parentPuppetId}, create_by_user_id=#{createByUserId}, team_id=#{teamId}, conn_link=#{connLink}, protocol=#{protocol}, headers=#{headers}, req_disguise_id=#{reqDisguiseId}, resp_disguise_id=#{respDisguiseId}, proxy_enabled=#{proxyEnabled}, proxy_type=#{proxyType}, proxy_host=#{proxyHost}, proxy_port=#{proxyPort}, balance_enabled=#{balanceEnabled}, max_req_count=#{maxReqCount}, permission=#{permission}, last_heartbeat=#{lastHeartbeat}, heartbeat_interval=#{heartbeatInterval}, update_time=#{updateTime}, remark=#{remark}, url_strategy=#{urlStrategy}, padding_strategy=#{paddingStrategy}, header_noise_strategy=#{headerNoiseStrategy}, tls_fingerprint_strategy=#{tlsFingerprintStrategy}, type=#{type} WHERE puppet_id=#{puppetId}")
    boolean updatePuppetById(@Param("puppetId") String puppetId,
                            @Param("puppetName") String puppetName,
                            @Param("parentPuppetId") String parentPuppetId,
                            @Param("createByUserId") String createByUserId,
                            @Param("teamId") String teamId,
                            @Param("connLink") String connLink,
                            @Param("protocol") String protocol,
                            @Param("headers") String headers,
                            @Param("reqDisguiseId") String reqDisguiseId,
                            @Param("respDisguiseId") String respDisguiseId,
                            @Param("proxyEnabled") Integer proxyEnabled,
                            @Param("proxyType") String proxyType,
                            @Param("proxyHost") String proxyHost,
                            @Param("proxyPort") Integer proxyPort,
                            @Param("balanceEnabled") Integer balanceEnabled,
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
                            @Param("type") String type);

    /**
     * 仅更新 last_heartbeat 字段，避免全量覆盖。
     * 连接测试成功或初始化成功后调用。
     */
    @Update("UPDATE puppets SET last_heartbeat=#{lastHeartbeat}, update_time=#{updateTime} WHERE puppet_id=#{puppetId}")
    boolean updateLastHeartbeat(@Param("puppetId") String puppetId,
                                @Param("lastHeartbeat") String lastHeartbeat,
                                @Param("updateTime") String updateTime);

}
