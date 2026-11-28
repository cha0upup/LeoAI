package org.leo.dao.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.leo.core.entity.AiModelConfig;

import java.util.List;

@Mapper
public interface AiModelConfigMapper {

    String MODEL_WITH_PROVIDER = """
            SELECT m.id, m.provider_id, m.name, m.model, m.is_active, m.enabled, m.fallback_model_id,
                   m.max_output_tokens, m.thinking_enabled, m.reasoning_effort, m.context_window_tokens,
                   m.temperature, m.create_time, MAX(m.update_time, p.update_time) AS update_time, m.remark,
                   p.provider_key, p.name AS provider_name, p.api_key, p.base_url,
                   p.protocol, p.completions_path, p.headers_json
            FROM ai_model_configs m JOIN ai_providers p ON p.id = m.provider_id
            """;

    @Select(MODEL_WITH_PROVIDER + "ORDER BY p.provider_key, m.id")
    List<AiModelConfig> listAll();

    @Select("SELECT id FROM ai_model_configs ORDER BY id")
    List<Integer> listIds();

    @Select("SELECT EXISTS (SELECT 1 FROM ai_model_configs WHERE id = #{id})")
    boolean existsById(@Param("id") Integer id);

    @Select(MODEL_WITH_PROVIDER + "WHERE m.enabled = 1 ORDER BY p.provider_key, m.id")
    List<AiModelConfig> listEnabled();

    @Select("SELECT * FROM ai_model_configs WHERE provider_id = #{providerId} ORDER BY id")
    List<AiModelConfig> listSettingsByProviderId(@Param("providerId") Integer providerId);

    @Select(MODEL_WITH_PROVIDER + "WHERE m.id = #{id}")
    AiModelConfig findById(@Param("id") Integer id);

    @Select("SELECT name FROM ai_model_configs WHERE id = #{id}")
    String findNameById(@Param("id") Integer id);

    @Select(MODEL_WITH_PROVIDER + "WHERE m.is_active = 1 AND m.enabled = 1 LIMIT 1")
    AiModelConfig findActive();

    @Select("SELECT COUNT(*) FROM ai_model_configs")
    int countAll();

    @Insert("""
            INSERT INTO ai_model_configs (
                provider_id, name, model, is_active, enabled, fallback_model_id, max_output_tokens,
                thinking_enabled, reasoning_effort, context_window_tokens, temperature, create_time, update_time, remark
            ) VALUES (
                #{providerId}, #{name}, #{model}, #{isActive}, #{enabled}, #{fallbackModelId}, #{maxOutputTokens},
                #{thinkingEnabled}, #{reasoningEffort}, #{contextWindowTokens}, #{temperature}, #{createTime}, #{updateTime}, #{remark}
            )
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insert(AiModelConfig row);

    @Update("""
            UPDATE ai_model_configs SET provider_id=#{providerId}, name=#{name}, model=#{model},
                is_active=#{isActive}, enabled=#{enabled}, fallback_model_id=#{fallbackModelId},
                max_output_tokens=#{maxOutputTokens}, thinking_enabled=#{thinkingEnabled},
                reasoning_effort=#{reasoningEffort}, context_window_tokens=#{contextWindowTokens},
                temperature=#{temperature}, update_time=#{updateTime}, remark=#{remark}
            WHERE id=#{id}
            """)
    int update(AiModelConfig row);

    @Update("UPDATE ai_model_configs SET enabled = 0, is_active = 0, update_time = #{updateTime} "
            + "WHERE provider_id = #{providerId}")
    int disableByProviderId(@Param("providerId") Integer providerId, @Param("updateTime") String updateTime);

    @Update("UPDATE ai_model_configs SET is_active = 0")
    int clearActive();

    @Update("UPDATE ai_model_configs SET is_active = 1, enabled = 1, update_time = #{updateTime} WHERE id = #{id}")
    int setActiveById(@Param("id") Integer id, @Param("updateTime") String updateTime);

    @Delete("DELETE FROM ai_model_configs WHERE id = #{id}")
    int deleteById(@Param("id") Integer id);
}
