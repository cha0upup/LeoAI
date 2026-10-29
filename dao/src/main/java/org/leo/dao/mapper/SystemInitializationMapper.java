package org.leo.dao.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/** The durable marker prevents the public setup entry from reopening after account changes. */
@Mapper
public interface SystemInitializationMapper {
    @Select("""
            SELECT NOT EXISTS (SELECT 1 FROM users)
               AND NOT EXISTS (SELECT 1 FROM system_configs WHERE config_key = 'system.initialization.completed')
            """)
    boolean isInitializationRequired();

    /** First statement in the setup transaction: serialize contenders before any reads. */
    @Insert("""
            INSERT INTO system_configs (config_key, config_value, config_type, description, create_time, update_time)
            SELECT 'system.initialization.completed', 'true', 'boolean', '首次初始化完成', datetime('now'), datetime('now')
            WHERE NOT EXISTS (SELECT 1 FROM users)
            ON CONFLICT(config_key) DO NOTHING
            """)
    int claimEmptyInstallation();

}
