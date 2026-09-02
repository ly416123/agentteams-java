package io.agentteams.controlplane.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

@ExtendWith(MockitoExtension.class)
class JdbcAuditRecorderTest {
    @Mock
    private JdbcTemplate jdbc;

    @Test
    void serializesOnlyRedactedAttributes() throws Exception {
        JdbcAuditRecorder recorder = new JdbcAuditRecorder(jdbc, new ObjectMapper());
        UUID id = UUID.randomUUID();
        recorder.record(new AuditEvent(id, "operator", "worker.update", "worker", "worker-1",
                Map.of("apiKey", "do-not-store", "message", "Bearer do-not-store", "model", "qwen"),
                Instant.EPOCH));

        ArgumentCaptor<Object[]> values = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(eq(JdbcAuditRecorder.INSERT_SQL), values.capture());
        JsonNode attributes = new ObjectMapper().readTree((String) values.getValue()[5]);
        assertThat(attributes.get("apiKey").asText()).isEqualTo("[REDACTED]");
        assertThat(attributes.get("message").asText()).isEqualTo("Bearer [REDACTED]");
        assertThat(attributes.get("model").asText()).isEqualTo("qwen");
        assertThat(values.getValue()[0]).isEqualTo(id);
        assertThat(values.getValue()[6]).isEqualTo(Timestamp.from(Instant.EPOCH));
    }
}
