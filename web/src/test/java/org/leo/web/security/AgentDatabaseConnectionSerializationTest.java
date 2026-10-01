package org.leo.web.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.leo.core.entity.AgentDatabaseConnection;

import static org.junit.jupiter.api.Assertions.assertFalse;

class AgentDatabaseConnectionSerializationTest {

    @Test
    void neverSerializesDatabasePassword() throws Exception {
        AgentDatabaseConnection connection = new AgentDatabaseConnection();
        connection.setConnectionId("connection-1");
        connection.setPassword("must-not-leak");

        String json = new ObjectMapper().writeValueAsString(connection);

        assertFalse(json.contains("password"));
        assertFalse(json.contains("must-not-leak"));
    }
}
