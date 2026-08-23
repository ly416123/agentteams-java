package io.agentteams.controlplane.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RedactingAuditRecorderTest {
    @Test
    void redactsCredentialAttributesButKeepsOperationalContext() {
        var events = new java.util.ArrayList<AuditEvent>();
        new RedactingAuditRecorder(events::add).record(new AuditEvent(UUID.randomUUID(), "manager", "call",
                "task", "task-1", Map.of("apiKey", "secret-value", "model", "deepseek-chat",
                        "details", "Authorization: Bearer jwt-value and api_key=url-secret"), Instant.EPOCH));

        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.attributes()).containsEntry("apiKey", "[REDACTED]")
                    .containsEntry("model", "deepseek-chat")
                    .containsEntry("details", "Authorization: Bearer [REDACTED] and api_key=[REDACTED]");
        });
    }
}
