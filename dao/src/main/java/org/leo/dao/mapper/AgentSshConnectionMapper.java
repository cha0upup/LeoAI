package org.leo.dao.mapper;

import org.apache.ibatis.annotations.*;
import org.leo.core.entity.AgentSshConnection;

import java.util.List;

/** Agent-shared profiles, matching database-connection scope. Access is checked at the session boundary. */
@Mapper
public interface AgentSshConnectionMapper {
    String METADATA = "profile_id, version, name, host, port, username, auth_type, fingerprint, "
            + "has_password, has_private_key, has_passphrase, updated_at, last_connected_at";

    @Select("SELECT " + METADATA + " FROM agent_ssh_connections WHERE agent_id=#{agentId}"
            + " ORDER BY last_connected_at DESC, updated_at DESC, profile_id")
    List<AgentSshConnection> selectMetadataByAgent(@Param("agentId") String agentId);

    @Select("SELECT " + METADATA + " FROM agent_ssh_connections WHERE agent_id=#{agentId} AND profile_id=#{profileId}")
    AgentSshConnection selectMetadataById(@Param("agentId") String agentId, @Param("profileId") String profileId);

    @Select("SELECT * FROM agent_ssh_connections WHERE agent_id=#{agentId} AND profile_id=#{profileId}")
    AgentSshConnection selectById(@Param("agentId") String agentId, @Param("profileId") String profileId);

    // One SQLite statement enforces the shared per-Agent capacity, also across backend instances.
    @Insert("INSERT INTO agent_ssh_connections (profile_id, create_user_id, agent_id, version, name, "
            + "host, port, username, auth_type, credentials, fingerprint, has_password, has_private_key, "
            + "has_passphrase, updated_at, last_connected_at) "
            + "SELECT #{row.profileId}, #{row.createUserId}, #{row.agentId}, #{row.version}, #{row.name}, #{row.host}, #{row.port}, "
            + "#{row.username}, #{row.authType}, #{row.credentials}, #{row.fingerprint}, #{row.hasPassword}, #{row.hasPrivateKey}, "
            + "#{row.hasPassphrase}, #{row.updatedAt}, #{row.lastConnectedAt} "
            + "WHERE (SELECT COUNT(*) FROM agent_ssh_connections WHERE agent_id=#{row.agentId}) < #{limit}")
    int insertWithinLimit(@Param("row") AgentSshConnection connection, @Param("limit") int limit);

    // Preserve creator metadata and the newest connection timestamp/pin on same-endpoint edits.
    @Update("UPDATE agent_ssh_connections SET name=#{row.name}, "
            + "fingerprint=CASE WHEN host=#{row.host} AND port=#{row.port} THEN fingerprint ELSE '' END, "
            + "host=#{row.host}, port=#{row.port}, username=#{row.username}, auth_type=#{row.authType}, "
            + "credentials=#{row.credentials}, has_password=#{row.hasPassword}, has_private_key=#{row.hasPrivateKey}, "
            + "has_passphrase=#{row.hasPassphrase}, updated_at=#{row.updatedAt}, version=version+1 "
            + "WHERE profile_id=#{row.profileId} AND agent_id=#{row.agentId} AND version=#{expectedVersion}")
    int updateIfVersion(@Param("row") AgentSshConnection connection, @Param("expectedVersion") long expectedVersion);

    @Delete("DELETE FROM agent_ssh_connections WHERE agent_id=#{agentId} AND profile_id=#{profileId} AND version=#{version}")
    int deleteIfVersion(@Param("agentId") String agentId, @Param("profileId") String profileId, @Param("version") long version);

    @Update("UPDATE agent_ssh_connections SET fingerprint=#{fingerprint}, "
            + "last_connected_at=MAX(last_connected_at, #{connectedAt}) WHERE agent_id=#{agentId}"
            + " AND profile_id=#{profileId} AND version=#{version}")
    int recordConnectionIfVersion(@Param("agentId") String agentId, @Param("profileId") String profileId, @Param("version") long version,
            @Param("fingerprint") String fingerprint, @Param("connectedAt") long connectedAt);
}
