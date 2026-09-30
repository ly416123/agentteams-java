package io.agentteams.controlplane.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class JdbcSupportTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void redactsSensitiveJsonFieldsWithoutCorruptingDeepSeekModelNames() throws Exception {
        String input = "{\"modelName\":\"deepseek:deepseek-chat\","
                + "\"credentials\":{\"apiKey\":\"secret-value\"}}";

        String redacted = String.valueOf(JdbcSupport.json(input).getValue());
        JsonNode payload = MAPPER.readTree(redacted);

        assertThat(payload.path("modelName").asText()).isEqualTo("deepseek:deepseek-chat");
        assertThat(payload.path("credentials").path("apiKey").asText()).isEqualTo("[REDACTED]");
    }
}
