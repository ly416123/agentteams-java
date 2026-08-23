package io.agentteams.manager;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.time.Instant;
import org.assertj.core.api.Assertions;
import org.mockito.ArgumentCaptor;
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

    @Test
    void writesAllNullableDimensionsAsSeparateColumnsWithoutContent() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ModelCallAudit.Dimensions dimensions = new ModelCallAudit.Dimensions(
                "worker-1", "task-1", "team-1", "tool-1", "quota-1", "tokens");
        ModelCallAudit audit = new ModelCallAudit("deepseek", "deepseek-chat", Duration.ofMillis(12),
                new ModelCallAudit.TokenUsage(4, 6), "a".repeat(64), "b".repeat(64),
                ModelCallAudit.Outcome.SUCCESS, null, Instant.EPOCH,
                "tenant-a", "project-a", 0.12, ModelCallAudit.CostStatus.ESTIMATED, dimensions);

        new JdbcModelCallAuditor(jdbc).record(audit);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(sql.capture(), args.capture());
        Assertions.assertThat(sql.getValue()).contains(
                "worker_id, task_id, team_id, tool_id, quota_id, quota_dimension");
        Object[] values = args.getValue();
        Assertions.assertThat(values).hasSize(20);
        Assertions.assertThat(values[0]).isInstanceOf(java.util.UUID.class);
        Assertions.assertThat(values).containsExactly(values[0], "deepseek", "deepseek-chat", 12L, 4L, 6L,
                "a".repeat(64), "b".repeat(64), "SUCCESS", null, java.sql.Timestamp.from(Instant.EPOCH),
                "tenant-a", "project-a", 0.12, "worker-1", "task-1", "team-1", "tool-1", "quota-1", "tokens");
    }
}
