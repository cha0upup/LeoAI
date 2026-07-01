package org.leo.dao.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.leo.core.entity.AiModelCapability;

import java.util.List;

@Mapper
public interface AiModelCapabilityMapper {

    @Select("SELECT model_name AS modelName, source, "
            + "context_window_tokens AS contextWindowTokens, max_output_tokens AS maxOutputTokens, "
            + "supports_text_generation AS supportsTextGeneration, supports_reasoning AS supportsReasoning, "
            + "supports_streaming AS supportsStreaming, supports_function_calling AS supportsFunctionCalling, "
            + "supports_structured_output AS supportsStructuredOutput, supports_web_search AS supportsWebSearch, "
            + "supports_parallel_tool_calls AS supportsParallelToolCalls, "
            + "create_time AS createTime, update_time AS updateTime, remark "
            + "FROM ai_model_capabilities ORDER BY model_name")
    List<AiModelCapability> listAll();

    @Select("SELECT model_name AS modelName, source, "
            + "context_window_tokens AS contextWindowTokens, max_output_tokens AS maxOutputTokens, "
            + "supports_text_generation AS supportsTextGeneration, supports_reasoning AS supportsReasoning, "
            + "supports_streaming AS supportsStreaming, supports_function_calling AS supportsFunctionCalling, "
            + "supports_structured_output AS supportsStructuredOutput, supports_web_search AS supportsWebSearch, "
            + "supports_parallel_tool_calls AS supportsParallelToolCalls, "
            + "create_time AS createTime, update_time AS updateTime, remark "
            + "FROM ai_model_capabilities WHERE model_name = #{modelName} LIMIT 1")
    AiModelCapability findByModelName(@Param("modelName") String modelName);

    @Insert("INSERT INTO ai_model_capabilities "
            + "(model_name, source, context_window_tokens, max_output_tokens, "
            + "supports_text_generation, supports_reasoning, supports_streaming, supports_function_calling, "
            + "supports_structured_output, supports_web_search, supports_parallel_tool_calls, "
            + "create_time, update_time, remark) "
            + "VALUES (#{modelName}, #{source}, #{contextWindowTokens}, #{maxOutputTokens}, "
            + "#{supportsTextGeneration}, #{supportsReasoning}, #{supportsStreaming}, #{supportsFunctionCalling}, "
            + "#{supportsStructuredOutput}, #{supportsWebSearch}, #{supportsParallelToolCalls}, "
            + "#{createTime}, #{updateTime}, #{remark})")
    int insert(AiModelCapability row);

    @Update("UPDATE ai_model_capabilities SET source=#{source}, "
            + "context_window_tokens=#{contextWindowTokens}, max_output_tokens=#{maxOutputTokens}, "
            + "supports_text_generation=#{supportsTextGeneration}, supports_reasoning=#{supportsReasoning}, "
            + "supports_streaming=#{supportsStreaming}, supports_function_calling=#{supportsFunctionCalling}, "
            + "supports_structured_output=#{supportsStructuredOutput}, supports_web_search=#{supportsWebSearch}, "
            + "supports_parallel_tool_calls=#{supportsParallelToolCalls}, "
            + "update_time=#{updateTime}, remark=#{remark} WHERE model_name=#{modelName}")
    int update(AiModelCapability row);

    @Delete("DELETE FROM ai_model_capabilities WHERE model_name = #{modelName}")
    int deleteByModelName(@Param("modelName") String modelName);
}
