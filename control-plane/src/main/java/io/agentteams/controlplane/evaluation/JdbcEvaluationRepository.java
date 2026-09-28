package io.agentteams.controlplane.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentteams.controlplane.persistence.JdbcSupport;
import io.agentteams.controlplane.security.ExecutionContext;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcEvaluationRepository implements EvaluationRepository {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final JdbcTemplate jdbc;

    @Autowired
    public JdbcEvaluationRepository(DataSource dataSource) {
        this(new JdbcTemplate(Objects.requireNonNull(dataSource, "dataSource")));
    }

    public JdbcEvaluationRepository(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public EvaluationSuiteRecord saveSuite(ExecutionContext context, EvaluationSuiteRecord suite) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(suite, "suite");
        if (!context.tenantId().equals(suite.tenantId()) || !context.projectId().equals(suite.projectId())) {
            throw new IllegalArgumentException("evaluation suite is outside the execution context");
        }
        jdbc.update("""
                INSERT INTO evaluation_suites
                    (id, organization_id, tenant_id, project_id, suite_key, version, evaluator_version, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (id) DO NOTHING
                """, suite.id(), context.organizationId(), suite.tenantId(), suite.projectId(), suite.suiteKey(),
                suite.version(), suite.evaluatorVersion(), JdbcSupport.timestamp(suite.createdAt()));
        return suite;
    }

    @Override
    public EvaluationCaseRecord saveCase(ExecutionContext context, EvaluationCaseRecord evaluationCase) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(evaluationCase, "evaluationCase");
        jdbc.update("""
                INSERT INTO evaluation_cases (id, suite_id, case_key, name, task_spec, assertions, created_at)
                SELECT ?, suite.id, ?, ?, ?, ?, ?
                  FROM evaluation_suites suite
                 WHERE suite.id = ? AND suite.organization_id = ? AND suite.tenant_id = ?
                ON CONFLICT (id) DO NOTHING
                """, evaluationCase.id(), evaluationCase.caseKey(), evaluationCase.name(),
                JdbcSupport.json(evaluationCase.taskSpec().toString()), JdbcSupport.json(evaluationCase.assertions().toString()),
                JdbcSupport.timestamp(evaluationCase.createdAt()), evaluationCase.suiteId(), context.organizationId(),
                context.tenantId());
        return evaluationCase;
    }

    @Override
    public EvaluationRunRecord createRun(ExecutionContext context, CreateEvaluationRun command) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(command, "command");
        jdbc.update("""
                INSERT INTO evaluation_runs
                    (id, suite_id, case_id, organization_id, tenant_id, project_id, team_id,
                     task_id, execution_run_id, evaluator_version, status, created_at)
                SELECT ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
                WHERE EXISTS (
                    SELECT 1 FROM evaluation_suites suite
                     WHERE suite.id = ? AND suite.organization_id = ? AND suite.tenant_id = ?
                )
                ON CONFLICT (suite_id, case_id, execution_run_id, evaluator_version) DO NOTHING
                """, command.id(), command.suiteId(), command.caseId(), context.organizationId(), context.tenantId(),
                context.projectId(), context.teamId(), command.taskId(), command.executionRunId(),
                command.evaluatorVersion(), EvaluationRunRecord.Status.QUEUED.name(),
                JdbcSupport.timestamp(command.createdAt()), command.suiteId(), context.organizationId(), context.tenantId());
        return findRun(context, command.id()).orElseGet(() -> findByIdentity(context, command)
                .orElseThrow(() -> new IllegalStateException("evaluation run was not persisted")));
    }

    @Override
    public Optional<EvaluationRunRecord> findRun(ExecutionContext context, UUID runId) {
        Objects.requireNonNull(context, "context");
        return jdbc.query(selectRun() + " WHERE run.id = ? AND run.organization_id = ? AND run.tenant_id = ?",
                this::mapRun, runId, context.organizationId(), context.tenantId()).stream().findFirst();
    }

    @Override
    public void saveResults(ExecutionContext context, UUID runId, List<EvaluationResultRecord> results) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(results, "results");
        for (EvaluationResultRecord result : results) {
            Objects.requireNonNull(result, "results must not contain null");
            if (!runId.equals(result.evaluationRunId())) {
                throw new IllegalArgumentException("result belongs to a different evaluation run");
            }
            jdbc.update("""
                    INSERT INTO evaluation_results
                        (id, evaluation_run_id, metric, score, threshold, status, evidence_refs, finding_summary, created_at)
                    SELECT ?, run.id, ?, ?, ?, ?, ?, ?, ?
                      FROM evaluation_runs run
                     WHERE run.id = ? AND run.organization_id = ? AND run.tenant_id = ?
                    ON CONFLICT (evaluation_run_id, metric) DO UPDATE SET
                        score = EXCLUDED.score, threshold = EXCLUDED.threshold, status = EXCLUDED.status,
                        evidence_refs = EXCLUDED.evidence_refs, finding_summary = EXCLUDED.finding_summary
                    """, result.id(), result.metric(), result.score(), result.threshold(), result.status().name(),
                    JdbcSupport.json(JdbcSupport.jsonArray(result.evidenceRefs())), result.findingSummary(),
                    JdbcSupport.timestamp(result.createdAt()), runId, context.organizationId(), context.tenantId());
        }
    }

    @Override
    public EvaluationReportRecord saveReport(ExecutionContext context, EvaluationReportRecord report) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(report, "report");
        jdbc.update("""
                INSERT INTO evaluation_reports
                    (id, evaluation_run_id, aggregate_score, dimension_scores, evaluation_status, task_success, created_at)
                SELECT ?, run.id, ?, ?, ?, ?, ?
                  FROM evaluation_runs run
                 WHERE run.id = ? AND run.organization_id = ? AND run.tenant_id = ?
                ON CONFLICT (evaluation_run_id) DO UPDATE SET
                    aggregate_score = EXCLUDED.aggregate_score, dimension_scores = EXCLUDED.dimension_scores,
                    evaluation_status = EXCLUDED.evaluation_status, task_success = EXCLUDED.task_success
                """, report.id(), report.aggregateScore(), JdbcSupport.json(report.dimensionScores().toString()),
                report.evaluationStatus().name(), report.taskSuccess(), JdbcSupport.timestamp(report.createdAt()),
                report.evaluationRunId(), context.organizationId(), context.tenantId());
        return jdbc.query(selectReport() + " WHERE report.evaluation_run_id = ? AND run.organization_id = ? AND run.tenant_id = ?",
                this::mapReport, report.evaluationRunId(), context.organizationId(), context.tenantId()).stream()
                .findFirst().orElseThrow(() -> new IllegalStateException("evaluation report was not persisted"));
    }

    private Optional<EvaluationRunRecord> findByIdentity(ExecutionContext context, CreateEvaluationRun command) {
        return jdbc.query(selectRun() + " WHERE run.suite_id = ? AND run.case_id = ? AND run.execution_run_id = ?"
                        + " AND run.evaluator_version = ? AND run.organization_id = ? AND run.tenant_id = ?",
                this::mapRun, command.suiteId(), command.caseId(), command.executionRunId(), command.evaluatorVersion(),
                context.organizationId(), context.tenantId()).stream().findFirst();
    }

    private static String selectRun() {
        return """
                SELECT run.id, run.suite_id, run.case_id, run.task_id, run.execution_run_id,
                       run.evaluator_version, run.status, run.started_at, run.completed_at, run.created_at
                  FROM evaluation_runs run
                """;
    }

    private static String selectReport() {
        return """
                SELECT report.id, report.evaluation_run_id, report.aggregate_score,
                       report.dimension_scores::text, report.evaluation_status, report.task_success, report.created_at
                  FROM evaluation_reports report
                  JOIN evaluation_runs run ON run.id = report.evaluation_run_id
                """;
    }

    private EvaluationRunRecord mapRun(ResultSet rs, int row) throws SQLException {
        return new EvaluationRunRecord(rs.getObject("id", UUID.class), rs.getObject("suite_id", UUID.class),
                rs.getObject("case_id", UUID.class), rs.getObject("task_id", UUID.class),
                rs.getObject("execution_run_id", UUID.class), rs.getString("evaluator_version"),
                EvaluationRunRecord.Status.valueOf(rs.getString("status")), nullableInstant(rs, "started_at"),
                nullableInstant(rs, "completed_at"), JdbcSupport.instant(rs, "created_at"));
    }

    private EvaluationReportRecord mapReport(ResultSet rs, int row) throws SQLException {
        try {
            JsonNode dimensions = JSON.readTree(rs.getString("dimension_scores"));
            java.math.BigDecimal score = rs.getBigDecimal("aggregate_score");
            return new EvaluationReportRecord(rs.getObject("id", UUID.class),
                    rs.getObject("evaluation_run_id", UUID.class), score, dimensions,
                    EvaluationRunRecord.Status.valueOf(rs.getString("evaluation_status")),
                    rs.getBoolean("task_success"), JdbcSupport.instant(rs, "created_at"));
        } catch (java.io.IOException error) {
            throw new SQLException("invalid evaluation report JSON", error);
        }
    }

    private static java.time.Instant nullableInstant(ResultSet rs, String column) throws SQLException {
        java.sql.Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
