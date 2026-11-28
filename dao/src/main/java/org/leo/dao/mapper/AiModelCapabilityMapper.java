package org.leo.dao.mapper;

import org.apache.ibatis.annotations.Arg;
import org.apache.ibatis.annotations.ConstructorArgs;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.leo.core.entity.AiModelCapability;

import java.util.List;

@Mapper
public interface AiModelCapabilityMapper {

    String CATALOG_COLUMNS = "model_name AS modelName, source, "
            + "context_window_tokens AS contextWindowTokens, max_output_tokens AS maxOutputTokens, "
            + "supports_text_generation AS supportsTextGeneration, supports_reasoning AS supportsReasoning, "
            + "supports_streaming AS supportsStreaming, supports_function_calling AS supportsFunctionCalling, "
            + "supports_structured_output AS supportsStructuredOutput, supports_web_search AS supportsWebSearch, "
            + "supports_parallel_tool_calls AS supportsParallelToolCalls, "
            + "create_time AS createTime, update_time AS updateTime, remark";

    String OBSERVATION_COLUMNS = """
            observation.supports_text_generation, observation.supports_reasoning,
            observation.supports_streaming, observation.supports_function_calling,
            observation.supports_structured_output, observation.update_time
            """;

    @Select("SELECT " + CATALOG_COLUMNS + " FROM ai_model_capabilities ORDER BY model_name")
    List<AiModelCapability> listAll();

    @Select("SELECT model_name FROM ai_model_capabilities ORDER BY model_name")
    List<String> listModelNames();

    @Select("SELECT " + CATALOG_COLUMNS + " FROM ai_model_capabilities WHERE model_name = #{modelName} LIMIT 1")
    AiModelCapability findByModelName(@Param("modelName") String modelName);

    @Select("SELECT " + CATALOG_COLUMNS + " FROM ai_model_capabilities "
            + "WHERE model_name IN (SELECT value FROM json_each(#{modelNamesJson}))")
    List<AiModelCapability> findByModelNames(@Param("modelNamesJson") String modelNamesJson);

    @Insert("INSERT INTO ai_model_capabilities "
            + "(model_name, source, context_window_tokens, max_output_tokens, "
            + "supports_text_generation, supports_reasoning, supports_streaming, supports_function_calling, "
            + "supports_structured_output, supports_web_search, supports_parallel_tool_calls, "
            + "create_time, update_time, remark) "
            + "VALUES (#{modelName}, #{source}, #{contextWindowTokens}, #{maxOutputTokens}, "
            + "#{supportsTextGeneration}, #{supportsReasoning}, #{supportsStreaming}, #{supportsFunctionCalling}, "
            + "#{supportsStructuredOutput}, #{supportsWebSearch}, #{supportsParallelToolCalls}, "
            + "#{createTime}, #{updateTime}, #{remark}) ON CONFLICT(model_name) DO NOTHING")
    int insertIfAbsent(AiModelCapability row);

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

    @Select("SELECT " + OBSERVATION_COLUMNS
            + "FROM ai_model_capability_observations observation WHERE provider_id=#{providerId} AND protocol=#{protocol} "
            + "AND model_name=#{modelName} AND connection_key=#{connectionKey}")
    @Results(id = "observation", value = {
            @Result(column = "supports_text_generation", property = "supportsTextGeneration"),
            @Result(column = "supports_reasoning", property = "supportsReasoning"),
            @Result(column = "supports_streaming", property = "supportsStreaming"),
            @Result(column = "supports_function_calling", property = "supportsFunctionCalling"),
            @Result(column = "supports_structured_output", property = "supportsStructuredOutput"),
            @Result(column = "update_time", property = "updateTime")
    })
    AiModelCapability findObservation(@Param("providerId") Integer providerId, @Param("protocol") String protocol,
                                      @Param("modelName") String modelName, @Param("connectionKey") String connectionKey);

    @Select("SELECT request.value ->> '$.configId' AS config_id, " + OBSERVATION_COLUMNS + """
            FROM json_each(#{observationsJson}) request
            JOIN ai_model_capability_observations observation
                ON observation.provider_id = request.value ->> '$.providerId'
               AND observation.protocol = request.value ->> '$.protocol'
               AND observation.model_name = request.value ->> '$.modelName'
               AND observation.connection_key = request.value ->> '$.connectionKey'
            """)
    @ConstructorArgs({
            @Arg(column = "config_id", javaType = int.class, id = true),
            @Arg(javaType = AiModelCapability.class, resultMap = "observation")
    })
    List<MatchedObservation> findObservations(@Param("observationsJson") String observationsJson);

    record MatchedObservation(int configId, AiModelCapability capability) {
    }

    @Insert("INSERT INTO ai_model_capability_observations "
            + "(provider_id, protocol, model_name, connection_key, supports_text_generation, supports_reasoning, "
            + "supports_streaming, supports_function_calling, supports_structured_output, update_time) "
            + "VALUES (#{providerId}, #{protocol}, #{modelName}, #{connectionKey}, #{row.supportsTextGeneration}, "
            + "#{row.supportsReasoning}, #{row.supportsStreaming}, #{row.supportsFunctionCalling}, "
            + "#{row.supportsStructuredOutput}, #{row.updateTime}) "
            + "ON CONFLICT(provider_id, protocol, model_name) DO UPDATE SET connection_key=excluded.connection_key, "
            + "supports_text_generation=excluded.supports_text_generation, supports_reasoning=excluded.supports_reasoning, "
            + "supports_streaming=excluded.supports_streaming, supports_function_calling=excluded.supports_function_calling, "
            + "supports_structured_output=excluded.supports_structured_output, update_time=excluded.update_time")
    int saveObservation(@Param("providerId") Integer providerId, @Param("protocol") String protocol,
                        @Param("modelName") String modelName, @Param("connectionKey") String connectionKey,
                        @Param("row") AiModelCapability row);
}
