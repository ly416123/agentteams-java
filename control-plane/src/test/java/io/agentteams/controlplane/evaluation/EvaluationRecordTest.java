package io.agentteams.controlplane.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EvaluationRecordTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final UUID SUITE_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID CASE_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID TASK_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID RUN_ID = UUID.fromString("00000000-0000-0000-0000-000000000004");
    private static final Instant NOW = Instant.parse("2026-09-28T07:00:00Z");

    @Test
    void modelsSuiteCaseRunResultAndReportWithExecutionEvidenceIdentity() throws Exception {
        EvaluationSuiteRecord suite = new EvaluationSuiteRecord(SUITE_ID, "tenant-a", "project-a", "core",
                "1.0.0", "evaluator-1", NOW);
        EvaluationCaseRecord evaluationCase = new EvaluationCaseRecord(CASE_ID, SUITE_ID, "phase-success",
                "Phase success", JSON.readTree("{\"taskType\":\"chat\"}"),
                JSON.readTree("[{\"type\":\"phase\",\"expected\":\"SUCCEEDED\"}]"), NOW);
        EvaluationRunRecord run = EvaluationRunRecord.queued(RUN_ID, SUITE_ID, CASE_ID, TASK_ID,
                UUID.fromString("00000000-0000-0000-0000-000000000005"), "evaluator-1", NOW);
        EvaluationResultRecord result = new EvaluationResultRecord(UUID.randomUUID(), RUN_ID, "phase",
                new BigDecimal("1.00000"), new BigDecimal("1.00000"), EvaluationResultRecord.Status.PASSED,
                List.of("task-event:event-1"), "phase matched", NOW);
        EvaluationReportRecord report = new EvaluationReportRecord(UUID.randomUUID(), RUN_ID,
                new BigDecimal("1.00000"), JSON.readTree("{\"phase\":1.0}"),
                EvaluationRunRecord.Status.PASSED, true, NOW);

        assertThat(suite.version()).isEqualTo("1.0.0");
        assertThat(evaluationCase.assertions().isArray()).isTrue();
        assertThat(run.executionRunId()).isNotEqualTo(run.taskId());
        assertThat(result.evidenceRefs()).containsExactly("task-event:event-1");
        assertThat(report.taskSuccess()).isTrue();
        assertThat(report.evaluationStatus()).isEqualTo(EvaluationRunRecord.Status.PASSED);
    }

    @Test
    void keepsTaskSuccessIndependentFromEvaluationStatus() {
        EvaluationReportRecord report = new EvaluationReportRecord(UUID.randomUUID(), RUN_ID,
                BigDecimal.ZERO, JSON.createObjectNode(), EvaluationRunRecord.Status.FAILED, true, NOW);

        assertThat(report.taskSuccess()).isTrue();
        assertThat(report.evaluationStatus()).isEqualTo(EvaluationRunRecord.Status.FAILED);
    }

    @Test
    void rejectsInvalidVersionsJsonShapesAndScores() throws Exception {
        assertThatThrownBy(() -> new EvaluationSuiteRecord(SUITE_ID, "tenant-a", "project-a", "core", " ",
                "evaluator-1", NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EvaluationCaseRecord(CASE_ID, SUITE_ID, "case", "Case",
                JSON.createArrayNode(), JSON.createObjectNode(), NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EvaluationResultRecord(UUID.randomUUID(), RUN_ID, "phase", null,
                new BigDecimal("1.1"), EvaluationResultRecord.Status.PASSED, List.of(), "", NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EvaluationReportRecord(UUID.randomUUID(), RUN_ID,
                new BigDecimal("1.1"), JSON.createObjectNode(), EvaluationRunRecord.Status.PASSED, true, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
