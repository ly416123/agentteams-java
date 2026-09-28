package io.agentteams.controlplane.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentteams.controlplane.security.ExecutionContext;
import io.agentteams.application.api.TaskEventVisibility;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Runs deterministic checks over an immutable evidence bundle; it never mutates Task state. */
public final class EvaluationService {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final EvaluationRepository repository;
    private final EvidenceBundleAssembler assembler;
    private final Clock clock;

    public EvaluationService(EvaluationRepository repository, EvidenceBundleAssembler assembler, Clock clock) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.assembler = Objects.requireNonNull(assembler, "assembler");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public EvaluationOutcome evaluate(ExecutionContext context, EvaluationRepository.CreateEvaluationRun command,
            boolean semanticRequested) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(command, "command");
        Instant createdAt = command.createdAt();
        EvaluationRunRecord queued = repository.createRun(context, command);
        EvaluationCaseRecord evaluationCase = repository.findCase(context, queued.caseId())
                .orElseThrow(() -> new IllegalArgumentException("evaluation case is not available"));
        EvidenceBundle bundle = assembler.assemble(context, queued.taskId(), queued.executionRunId(),
                Set.of(TaskEventVisibility.REQUESTER, TaskEventVisibility.PROJECT_MEMBER));
        Instant startedAt = clock.instant();
        EvaluationRunRecord running = repository.updateRunStatus(context, queued.id(), EvaluationRunRecord.Status.RUNNING,
                startedAt, null);
        List<EvaluationResultRecord> results = evaluateAssertions(running.id(), evaluationCase.assertions(), bundle, createdAt);
        if (semanticRequested) {
            results = new ArrayList<>(results);
            results.add(new EvaluationResultRecord(UUID.randomUUID(), running.id(), "semantic",
                    null, null, EvaluationResultRecord.Status.INCONCLUSIVE, List.of("evaluation:semantic"),
                    "no controlled semantic evaluator is configured", createdAt));
        }
        EvaluationRunRecord.Status status = statusOf(results);
        Instant completedAt = clock.instant();
        EvaluationRunRecord completed = repository.updateRunStatus(context, running.id(), status, startedAt, completedAt);
        repository.saveResults(context, completed.id(), results);
        EvaluationReportRecord report = repository.saveReport(context, report(completed, bundle, results, createdAt));
        return new EvaluationOutcome(completed, results, report, bundle);
    }

    private static List<EvaluationResultRecord> evaluateAssertions(UUID runId, JsonNode assertions,
            EvidenceBundle bundle, Instant createdAt) {
        List<EvaluationResultRecord> results = new ArrayList<>();
        for (JsonNode assertion : assertions) {
            String type = text(assertion, "type");
            EvaluationResultRecord result = switch (type.toLowerCase(Locale.ROOT)) {
                case "phase" -> phase(runId, assertion, bundle, createdAt);
                case "required_tool" -> requiredTool(runId, assertion, bundle, createdAt);
                case "forbidden_tool" -> forbiddenTool(runId, assertion, bundle, createdAt);
                case "artifact_integrity" -> artifactIntegrity(runId, bundle, createdAt);
                case "schema" -> schema(runId, assertion, bundle, createdAt);
                default -> inconclusive(runId, type, "unsupported deterministic assertion", createdAt);
            };
            results.add(result);
        }
        return results;
    }

    private static EvaluationResultRecord phase(UUID runId, JsonNode assertion, EvidenceBundle bundle, Instant at) {
        String expected = text(assertion, "expected");
        String actual = bundle.taskSnapshot().phase();
        boolean passed = expected.equalsIgnoreCase(actual);
        return result(runId, "phase", passed, expected, actual, "task-snapshot:" + bundle.taskId(), at);
    }

    private static EvaluationResultRecord requiredTool(UUID runId, JsonNode assertion, EvidenceBundle bundle, Instant at) {
        String expected = text(assertion, "name");
        boolean passed = bundle.toolCallSummaries().stream().anyMatch(tool -> tool.toolName().equals(expected));
        return result(runId, "required_tool:" + expected, passed, "present", passed ? "present" : "missing",
                "task-events:" + bundle.executionRunId(), at);
    }

    private static EvaluationResultRecord forbiddenTool(UUID runId, JsonNode assertion, EvidenceBundle bundle, Instant at) {
        String forbidden = text(assertion, "name");
        boolean passed = bundle.toolCallSummaries().stream().noneMatch(tool -> tool.toolName().equals(forbidden));
        return result(runId, "forbidden_tool:" + forbidden, passed, "absent", passed ? "absent" : "present",
                "task-events:" + bundle.executionRunId(), at);
    }

    private static EvaluationResultRecord artifactIntegrity(UUID runId, EvidenceBundle bundle, Instant at) {
        boolean passed = !bundle.artifactRefs().isEmpty() && bundle.artifactRefs().stream()
                .allMatch(ref -> ref.sizeBytes() >= 0 && ref.sha256().matches("[0-9a-fA-F]{64}"));
        return result(runId, "artifact_integrity", passed, "durable metadata", passed ? "valid" : "missing/invalid",
                "result-manifest:" + bundle.executionRunId(), at);
    }

    private static EvaluationResultRecord schema(UUID runId, JsonNode assertion, EvidenceBundle bundle, Instant at) {
        String expectedType = text(assertion, "taskType");
        boolean passed = expectedType.equalsIgnoreCase(bundle.taskSnapshot().taskType());
        return result(runId, "schema", passed, expectedType, bundle.taskSnapshot().taskType(),
                "task-snapshot:" + bundle.taskId(), at);
    }

    private static EvaluationResultRecord result(UUID runId, String metric, boolean passed, String expected,
            String actual, String evidenceRef, Instant at) {
        return new EvaluationResultRecord(UUID.randomUUID(), runId, metric, passed ? BigDecimal.ONE : BigDecimal.ZERO,
                BigDecimal.ONE, passed ? EvaluationResultRecord.Status.PASSED : EvaluationResultRecord.Status.FAILED,
                List.of(evidenceRef), "expected=" + expected + ", actual=" + actual, at);
    }

    private static EvaluationResultRecord inconclusive(UUID runId, String metric, String message, Instant at) {
        return new EvaluationResultRecord(UUID.randomUUID(), runId, metric, null, null,
                EvaluationResultRecord.Status.INCONCLUSIVE, List.of("evaluation:" + metric), message, at);
    }

    private static EvaluationRunRecord.Status statusOf(List<EvaluationResultRecord> results) {
        if (results.stream().anyMatch(result -> result.status() == EvaluationResultRecord.Status.FAILED)) {
            return EvaluationRunRecord.Status.FAILED;
        }
        if (results.stream().anyMatch(result -> result.status() == EvaluationResultRecord.Status.INCONCLUSIVE)) {
            return EvaluationRunRecord.Status.INCONCLUSIVE;
        }
        return EvaluationRunRecord.Status.PASSED;
    }

    private static EvaluationReportRecord report(EvaluationRunRecord run, EvidenceBundle bundle,
            List<EvaluationResultRecord> results, Instant at) {
        long scored = results.stream().filter(result -> result.score() != null).count();
        BigDecimal aggregate = scored == 0 ? null : results.stream().filter(result -> result.score() != null)
                .map(EvaluationResultRecord::score).reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(scored), 5, RoundingMode.HALF_UP);
        ObjectNode dimensions = JSON.createObjectNode();
        results.forEach(result -> dimensions.putPOJO(result.metric(), result.score()));
        boolean taskSuccess = "SUCCEEDED".equalsIgnoreCase(bundle.taskSnapshot().phase());
        return new EvaluationReportRecord(UUID.randomUUID(), run.id(), aggregate, dimensions, run.status(), taskSuccess, at);
    }

    private static String text(JsonNode node, String field) {
        String value = node.path(field).asText(null);
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }

    public record EvaluationOutcome(EvaluationRunRecord run, List<EvaluationResultRecord> results,
            EvaluationReportRecord report, EvidenceBundle evidence) {
        public EvaluationOutcome {
            results = List.copyOf(results);
            Objects.requireNonNull(run, "run");
            Objects.requireNonNull(report, "report");
            Objects.requireNonNull(evidence, "evidence");
        }
    }
}
