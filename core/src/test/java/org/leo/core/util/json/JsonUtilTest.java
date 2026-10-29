package org.leo.core.util.json;

import com.alibaba.fastjson.JSONException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.leo.core.entity.Team;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JsonUtilTest {

    @Test
    void parsesDirectlyIntoTheRequestedModelType() {
        Team team = JsonUtil.fromJsonString(
                "{\"teamId\":\"fixture\",\"teamName\":\"测试团队\",\"status\":1}", Team.class);

        assertEquals("fixture", team.getTeamId());
        assertEquals("测试团队", team.getTeamName());
        assertEquals(1, team.getStatus());
    }

    @Test
    void roundTripsUnicodeAndEscapedTextWithoutCallSiteCasts() {
        Map<String, Object> source = Map.of("text", "中文\n\"引号\"", "enabled", true);
        Map<?, ?> decoded = JsonUtil.fromJsonString(JsonUtil.toJsonString(source), Map.class);

        assertEquals(source, decoded);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = "null")
    void keepsTheParserNullResult(String json) {
        assertNull(JsonUtil.fromJsonString(json, Team.class));
    }

    @Test
    void preservesParsingFailuresInsteadOfReturningAnEmptyModel() {
        assertThrows(JSONException.class, () -> JsonUtil.fromJsonString("{broken", Team.class));
    }
}
