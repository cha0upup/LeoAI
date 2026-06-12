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

    @Select("SELECT * FROM ai_model_configs ORDER BY id")
    List<AiModelConfig> listAll();

    @Select("SELECT * FROM ai_model_configs WHERE id = #{id}")
    AiModelConfig findById(@Param("id") Integer id);

    @Select("SELECT * FROM ai_model_configs WHERE is_active = 1 LIMIT 1")
    AiModelConfig findActive();

    @Select("SELECT COUNT(*) FROM ai_model_configs")
    int countAll();

    @Insert("INSERT INTO ai_model_configs (name, api_key, base_url, model, completions_path, "
            + "is_active, max_output_tokens, thinking_enabled, context_window_tokens, "
            + "create_time, update_time, remark) "
            + "VALUES (#{name}, #{apiKey}, #{baseUrl}, #{model}, #{completionsPath}, "
            + "#{isActive}, #{maxOutputTokens}, #{thinkingEnabled}, #{contextWindowTokens}, "
            + "#{createTime}, #{updateTime}, #{remark})")
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insert(AiModelConfig row);

    @Update("UPDATE ai_model_configs SET name=#{name}, api_key=#{apiKey}, base_url=#{baseUrl}, "
            + "model=#{model}, completions_path=#{completionsPath}, is_active=#{isActive}, "
            + "max_output_tokens=#{maxOutputTokens}, thinking_enabled=#{thinkingEnabled}, "
            + "context_window_tokens=#{contextWindowTokens}, update_time=#{updateTime}, "
            + "remark=#{remark} WHERE id=#{id}")
    int update(AiModelConfig row);

    @Update("UPDATE ai_model_configs SET is_active = 0")
    int clearActive();

    @Update("UPDATE ai_model_configs SET is_active = 1, update_time = #{updateTime} WHERE id = #{id}")
    int setActiveById(@Param("id") Integer id, @Param("updateTime") String updateTime);

    @Delete("DELETE FROM ai_model_configs WHERE id = #{id}")
    int deleteById(@Param("id") Integer id);
}
