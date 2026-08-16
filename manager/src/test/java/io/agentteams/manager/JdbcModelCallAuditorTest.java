package io.agentteams.manager;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class JdbcModelCallAuditorTest {
    @Test
    void writesOperationalMetadataOnly() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ModelCallAudit audit = new ModelCallAudit("deepseek", "deepseek-chat", Duration.ofMillis(12),
                new ModelCallAudit.TokenUsage(4, 6), "a".repeat(64), "b".repeat(64),
                ModelCallAudit.Outcome.SUCCESS, null, Instant.EPOCH);
        new JdbcModelCallAuditor(jdbc).record(audit);
        verify(jdbc).update(any(String.class), any(Object[].class));
    }
}
