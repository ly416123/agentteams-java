package io.agentteams.controlplane.evaluation;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentteams.controlplane.security.ExecutionContext;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;

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
}
