package org.leo.dao.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.leo.core.entity.AiProvider;

import java.util.List;

@Mapper
public interface AiProviderMapper {

    @Select("SELECT * FROM ai_providers ORDER BY id")
    List<AiProvider> listAll();

    @Select("SELECT * FROM ai_providers WHERE id = #{id}")
    AiProvider findById(@Param("id") Integer id);

    @Select("SELECT COUNT(*) FROM ai_model_configs WHERE provider_id = #{providerId}")
    int countModels(@Param("providerId") Integer providerId);

    @Insert("INSERT INTO ai_providers (name, provider_key, api_key, base_url, protocol, completions_path, "
            + "headers_json, enabled, create_time, update_time, remark) "
            + "VALUES (#{name}, #{providerKey}, #{apiKey}, #{baseUrl}, #{protocol}, #{completionsPath}, "
            + "#{headersJson}, #{enabled}, #{createTime}, #{updateTime}, #{remark})")
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insert(AiProvider row);

    @Update("UPDATE ai_providers SET name=#{name}, provider_key=#{providerKey}, api_key=#{apiKey}, "
            + "base_url=#{baseUrl}, protocol=#{protocol}, completions_path=#{completionsPath}, headers_json=#{headersJson}, "
            + "enabled=#{enabled}, update_time=#{updateTime}, remark=#{remark} WHERE id=#{id}")
    int update(AiProvider row);

    @Delete("DELETE FROM ai_providers WHERE id = #{id}")
    int deleteById(@Param("id") Integer id);
}
