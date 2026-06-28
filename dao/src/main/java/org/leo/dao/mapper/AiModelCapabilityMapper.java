package org.leo.dao.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.leo.core.entity.AiModelCapability;

import java.util.List;

@Mapper
public interface AiModelCapabilityMapper {

    @Select("SELECT model_name AS modelName, provider_key AS providerKey, source, "
            + "context_window_tokens AS contextWindowTokens, max_output_tokens AS maxOutputTokens, "
            + "supports_text_generation AS supportsTextGeneration, supports_reasoning AS supportsReasoning, "
            + "supports_streaming AS supportsStreaming, supports_function_calling AS supportsFunctionCalling, "
            + "supports_structured_output AS supportsStructuredOutput, supports_web_search AS supportsWebSearch, "
            + "supports_parallel_tool_calls AS supportsParallelToolCalls, "
            + "create_time AS createTime, update_time AS updateTime, remark "
            + "FROM ai_model_capabilities ORDER BY provider_key, model_name")
    List<AiModelCapability> listAll();

    @Select("SELECT model_name AS modelName, provider_key AS providerKey, source, "
            + "context_window_tokens AS contextWindowTokens, max_output_tokens AS maxOutputTokens, "
            + "supports_text_generation AS supportsTextGeneration, supports_reasoning AS supportsReasoning, "
            + "supports_streaming AS supportsStreaming, supports_function_calling AS supportsFunctionCalling, "
            + "supports_structured_output AS supportsStructuredOutput, supports_web_search AS supportsWebSearch, "
            + "supports_parallel_tool_calls AS supportsParallelToolCalls, "
            + "create_time AS createTime, update_time AS updateTime, remark "
            + "FROM ai_model_capabilities WHERE lower(model_name) = lower(#{modelName}) LIMIT 1")
    AiModelCapability findByModelName(@Param("modelName") String modelName);
}
