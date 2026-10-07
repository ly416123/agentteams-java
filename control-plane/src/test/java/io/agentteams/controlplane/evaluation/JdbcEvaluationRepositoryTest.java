package io.agentteams.controlplane.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentteams.controlplane.security.ExecutionContext;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class JdbcEvaluationRepositoryTest {
    private static final Instant NOW = Instant.parse("2026-09-28T07:00:00Z");
    private static final UUID SUITE_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID CASE_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID RUN_ID = UUID.fromString("00000000-0000-0000-0000-000000000004");
    private static final ExecutionContext CONTEXT = new ExecutionContext("org-a", "tenant-a", "project-a", "team-a", "alice");

    @Test
    void persistsSuiteAndCaseAsTenantScopedJsonDocuments() throws Exception {
        JdbcTemplate jdbc = Mockito.mock(JdbcTemplate.class);
        JdbcEvaluationRepository repository = new JdbcEvaluationRepository(jdbc);
        EvaluationSuiteRecord suite = new EvaluationSuiteRecord(SUITE_ID, "tenant-a", "project-a", "core",
                "1.0.0", "evaluator-1", NOW);
        EvaluationCaseRecord evaluationCase = new EvaluationCaseRecord(CASE_ID, SUITE_ID, "case-1", "Case 1",
                new ObjectMapper().readTree("{}"), new ObjectMapper().readTree("[]"), NOW);

        repository.saveSuite(CONTEXT, suite);
        repository.saveCase(CONTEXT, evaluationCase);

        verify(jdbc).update(contains("INSERT INTO evaluation_suites"), any(Object[].class));
        verify(jdbc).update(contains("INSERT INTO evaluation_cases"), any(Object[].class));
    }

    @Test
    void refusesResultsForA_DifferentRunBeforeWriting() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        JdbcEvaluationRepository repository = new JdbcEvaluationRepository(jdbc);
        EvaluationResultRecord result = new EvaluationResultRecord(UUID.randomUUID(), UUID.randomUUID(), "phase",
                null, null, EvaluationResultRecord.Status.INCONCLUSIVE, List.of(), "missing manifest", NOW);

        assertThatThrownBy(() -> repository.saveResults(CONTEXT, RUN_ID, List.of(result)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("different evaluation run");
    }

    @Test
    void persistsAndReadsIndependentGateStatuses() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        JdbcEvaluationRepository repository = new JdbcEvaluationRepository(jdbc);
        EvaluationReportRecord report = new EvaluationReportRecord(UUID.randomUUID(), RUN_ID, null,
                new ObjectMapper().readTree("{}"), EvaluationRunRecord.Status.PASSED, true,
                EvaluationReportRecord.GateStatus.FAILED, EvaluationReportRecord.GateStatus.PASSED, NOW);
        ResultSet row = mock(ResultSet.class);
        when(row.getObject("id", UUID.class)).thenReturn(report.id());
        when(row.getObject("evaluation_run_id", UUID.class)).thenReturn(RUN_ID);
        when(row.getBigDecimal("aggregate_score")).thenReturn(null);
        when(row.getString("dimension_scores")).thenReturn("{}");
        when(row.getString("evaluation_status")).thenReturn("PASSED");
        when(row.getBoolean("task_success")).thenReturn(true);
        when(row.getString("execution_gate_status")).thenReturn("FAILED");
        when(row.getString("result_gate_status")).thenReturn("PASSED");
        when(row.getTimestamp("created_at")).thenReturn(Timestamp.from(NOW));
        when(jdbc.query(contains("SELECT report.id"),
                org.mockito.ArgumentMatchers.<RowMapper<EvaluationReportRecord>>any(),
                org.mockito.ArgumentMatchers.any(Object[].class)))
                .thenAnswer(invocation -> List.of(invocation.<RowMapper<EvaluationReportRecord>>getArgument(1)
                        .mapRow(row, 0)));

        EvaluationReportRecord saved = repository.saveReport(CONTEXT, report);
        assertThat(saved.executionGateStatus()).isEqualTo(EvaluationReportRecord.GateStatus.FAILED);
        assertThat(saved.resultGateStatus()).isEqualTo(EvaluationReportRecord.GateStatus.PASSED);

        ArgumentCaptor<Object[]> arguments = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(contains("execution_gate_status, result_gate_status"), arguments.capture());
        assertThat(arguments.getValue()[5]).isEqualTo("FAILED");
        assertThat(arguments.getValue()[6]).isEqualTo("PASSED");
        verify(jdbc).query(contains("report.execution_gate_status, report.result_gate_status"),
                org.mockito.ArgumentMatchers.<RowMapper<EvaluationReportRecord>>any(),
                eq(new Object[] {RUN_ID, CONTEXT.organizationId(), CONTEXT.tenantId()}));
    }
}
