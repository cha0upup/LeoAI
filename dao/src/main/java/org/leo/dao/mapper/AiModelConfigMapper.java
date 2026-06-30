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

    @Select("SELECT * FROM ai_model_configs ORDER BY provider_key, id")
    List<AiModelConfig> listAll();

    @Select("SELECT * FROM ai_model_configs WHERE enabled = 1 ORDER BY provider_key, id")
    List<AiModelConfig> listEnabled();

    @Select("SELECT * FROM ai_model_configs WHERE id = #{id}")
    AiModelConfig findById(@Param("id") Integer id);

    @Select("SELECT * FROM ai_model_configs WHERE is_active = 1 AND enabled = 1 LIMIT 1")
    AiModelConfig findActive();

    @Select("SELECT COUNT(*) FROM ai_model_configs")
    int countAll();

    @Insert("INSERT INTO ai_model_configs (provider_id, name, provider_key, provider_name, api_key, base_url, "
            + "model, protocol, completions_path, is_active, enabled, max_output_tokens, thinking_enabled, "
            + "reasoning_effort, context_window_tokens, temperature, headers_json, "
            + "create_time, update_time, remark) "
            + "VALUES (#{providerId}, #{name}, #{providerKey}, #{providerName}, #{apiKey}, #{baseUrl}, "
            + "#{model}, #{protocol}, #{completionsPath}, #{isActive}, #{enabled}, #{maxOutputTokens}, "
            + "#{thinkingEnabled}, #{reasoningEffort}, #{contextWindowTokens}, #{temperature}, "
            + "#{headersJson}, #{createTime}, #{updateTime}, #{remark})")
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insert(AiModelConfig row);

    @Update("UPDATE ai_model_configs SET provider_id=#{providerId}, name=#{name}, provider_key=#{providerKey}, "
            + "provider_name=#{providerName}, api_key=#{apiKey}, base_url=#{baseUrl}, "
            + "model=#{model}, protocol=#{protocol}, completions_path=#{completionsPath}, is_active=#{isActive}, "
            + "enabled=#{enabled}, max_output_tokens=#{maxOutputTokens}, "
            + "thinking_enabled=#{thinkingEnabled}, reasoning_effort=#{reasoningEffort}, "
            + "context_window_tokens=#{contextWindowTokens}, temperature=#{temperature}, "
            + "headers_json=#{headersJson}, update_time=#{updateTime}, remark=#{remark} WHERE id=#{id}")
    int update(AiModelConfig row);

    @Update("UPDATE ai_model_configs SET provider_key=#{providerKey}, provider_name=#{providerName}, "
            + "api_key=#{apiKey}, base_url=#{baseUrl}, protocol=#{protocol}, completions_path=#{completionsPath}, "
            + "headers_json=#{headersJson}, update_time=#{updateTime} WHERE provider_id=#{providerId}")
    int updateProviderSnapshot(AiModelConfig row);

    @Update("UPDATE ai_model_configs SET enabled = 0, is_active = 0, update_time = #{updateTime} "
            + "WHERE provider_id = #{providerId}")
    int disableByProviderId(@Param("providerId") Integer providerId, @Param("updateTime") String updateTime);

    @Update("UPDATE ai_model_configs SET is_active = 0")
    int clearActive();

    @Update("UPDATE ai_model_configs SET is_active = 1, enabled = 1, update_time = #{updateTime} WHERE id = #{id}")
    int setActiveById(@Param("id") Integer id, @Param("updateTime") String updateTime);

    @Delete("DELETE FROM ai_model_configs WHERE id = #{id}")
    int deleteById(@Param("id") Integer id);

    @Delete("DELETE FROM ai_model_configs WHERE provider_id = #{providerId}")
    int deleteByProviderId(@Param("providerId") Integer providerId);
}
