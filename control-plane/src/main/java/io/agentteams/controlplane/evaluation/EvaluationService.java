package io.agentteams.controlplane.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentteams.controlplane.security.ExecutionContext;
import io.agentteams.application.api.TaskEventVisibility;
import io.agentteams.application.api.TaskProcessEvent;
import io.agentteams.controlplane.task.TaskTreeNode;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
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
import java.util.regex.Pattern;
import java.net.URI;

/** Runs deterministic checks over an immutable evidence bundle; it never mutates Task state. */
public final class EvaluationService {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern SAFE_ARTIFACT_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");
    private static final String UUID_PATH_SEGMENT = "[0-9a-fA-F]{8}-(?:[0-9a-fA-F]{4}-){3}[0-9a-fA-F]{12}";
    private static final Pattern SAFE_STORAGE_REF = Pattern.compile("tasks/" + UUID_PATH_SEGMENT
            + "/attempts/" + UUID_PATH_SEGMENT + "/artifacts/[A-Za-z0-9][A-Za-z0-9._-]{0,127}");
    private static final Pattern CITED_URL = Pattern.compile("https?://[^\\s<>\\\"]+", Pattern.CASE_INSENSITIVE);
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
                case "run_status" -> runStatus(runId, assertion, bundle, createdAt);
                case "required_tool" -> requiredTool(runId, assertion, bundle, createdAt);
                case "forbidden_tool" -> forbiddenTool(runId, assertion, bundle, createdAt);
                case "failed_tool" -> failedTool(runId, assertion, bundle, createdAt);
                case "tool_order" -> toolOrder(runId, assertion, bundle, createdAt);
                case "tool_count" -> toolCount(runId, assertion, bundle, createdAt);
                case "dependency_success" -> dependencySuccess(runId, bundle, createdAt);
                case "dependency_handoff" -> inconclusive(runId, type,
                        "dependency handoff result references are unavailable", "task-tree:" + bundle.executionRunId(), createdAt);
                case "retry_limit" -> inconclusive(runId, type,
                        "retry attempt evidence is unavailable", "task-run:" + bundle.executionRunId(), createdAt);
                case "artifact_integrity" -> artifactIntegrity(runId, bundle, createdAt);
                case "artifact_pdf" -> artifactPdf(runId, bundle, createdAt);
                case "schema" -> schema(runId, assertion, bundle, createdAt);
                case "source_citation" -> sourceCitation(runId, bundle, createdAt);
                default -> inconclusive(runId, type, "unsupported deterministic assertion", createdAt);
            };
            results.add(result);
        }
        return results;
    }

    private static EvaluationResultRecord sourceCitation(UUID runId, EvidenceBundle bundle, Instant at) {
        EvidenceBundle.SourceEvidenceCompleteness coverage = bundle.sourceEvidenceCompleteness();
        if (!"COMPLETE".equals(coverage.status()) || coverage.observedSearchCalls() == 0
                || coverage.observedSearchCalls() != coverage.recordedSearchCalls()
                || bundle.sourceEvidence() == null || !bundle.sourceEvidence().taskId().equals(bundle.taskId())
                || !bundle.sourceEvidence().runId().equals(bundle.executionRunId())) {
            return inconclusive(runId, "source_citation", "search source evidence is incomplete",
                    "task-source:" + bundle.executionRunId(), at);
        }
        if (bundle.resultManifest() == null || bundle.resultManifest().summary() == null) {
            return inconclusive(runId, "source_citation", "result summary is unavailable",
                    "task-result:" + bundle.executionRunId(), at);
        }
        List<String> cited = new ArrayList<>();
        var matcher = CITED_URL.matcher(bundle.resultManifest().summary());
        while (matcher.find()) {
            String candidate = matcher.group().replaceAll("[),.;]+$", "");
            try {
                URI uri = URI.create(candidate).normalize();
                if (uri.getHost() == null || uri.getRawUserInfo() != null || uri.getRawFragment() != null
                        || !("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))) {
                    return inconclusive(runId, "source_citation", "result contains an invalid citation URL",
                            "task-result:" + bundle.executionRunId(), at);
                }
                cited.add(uri.getScheme().toLowerCase(Locale.ROOT) + "://"
                        + uri.getRawAuthority().toLowerCase(Locale.ROOT)
                        + (uri.getRawPath() == null ? "" : uri.getRawPath())
                        + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery()));
            } catch (IllegalArgumentException error) {
                return inconclusive(runId, "source_citation", "result citation URL cannot be parsed",
                        "task-result:" + bundle.executionRunId(), at);
            }
        }
        if (cited.isEmpty()) return inconclusive(runId, "source_citation", "result contains no explicit citation URL",
                "task-result:" + bundle.executionRunId(), at);
        Map<String, String> available = new HashMap<>();
        bundle.sourceEvidence().calls().stream().flatMap(call -> call.sources().stream())
                .forEach(source -> available.put(source.url(), source.evidenceRef()));
        List<String> matchedRefs = cited.stream().filter(available::containsKey).map(available::get).distinct().toList();
        boolean match = !cited.isEmpty() && cited.stream().allMatch(available::containsKey);
        return new EvaluationResultRecord(UUID.randomUUID(), runId, "source_citation", match ? BigDecimal.ONE : BigDecimal.ZERO,
                BigDecimal.ONE, match ? EvaluationResultRecord.Status.PASSED : EvaluationResultRecord.Status.FAILED,
                match ? matchedRefs : List.of("task-source:" + bundle.executionRunId()),
                match ? "citation URL matched this Run search evidence" : "no citation URL matched this Run search evidence", at);
    }

    private static EvaluationResultRecord phase(UUID runId, JsonNode assertion, EvidenceBundle bundle, Instant at) {
        String expected = text(assertion, "expected");
        String actual = bundle.taskSnapshot().phase();
        if ("UNKNOWN".equalsIgnoreCase(actual)) return inconclusive(runId, "phase", "Task phase is unavailable",
                "task-snapshot:" + bundle.taskId(), at);
        boolean passed = expected.equalsIgnoreCase(actual);
        return result(runId, "phase", passed, expected, actual, "task-snapshot:" + bundle.taskId(), at);
    }

    private static EvaluationResultRecord requiredTool(UUID runId, JsonNode assertion, EvidenceBundle bundle, Instant at) {
        String expected = text(assertion, "name");
        if (!eventsComplete(bundle)) return inconclusive(runId, "required_tool:" + expected,
                "tool lifecycle evidence is incomplete", "task-events:" + bundle.executionRunId(), at);
        if (hasUnparseableToolEvent(bundle, "tool.called")) return inconclusive(runId, "required_tool:" + expected,
                "tool call details are unavailable", "task-events:" + bundle.executionRunId(), at);
        if (hasUnparseableToolEvent(bundle, "tool.finished")) return inconclusive(runId, "required_tool:" + expected,
                "tool completion details are unavailable", "task-events:" + bundle.executionRunId(), at);
        if (!hasValidToolLifecyclePairs(bundle)) return inconclusive(runId, "required_tool:" + expected,
                "tool call identifiers are missing, duplicated, or unmatched", "task-events:" + bundle.executionRunId(), at);
        List<ToolLifecycle> calls = toolEvents(bundle, "tool.called", expected);
        List<Boolean> outcomes = toolEvents(bundle, "tool.finished", expected).stream().map(ToolLifecycle::ok).toList();
        if (calls.isEmpty() && !outcomes.isEmpty()) return inconclusive(runId, "required_tool:" + expected,
                "tool completion is present without a matching call event", "task-events:" + bundle.executionRunId(), at);
        if (calls.isEmpty()) return result(runId, "required_tool:" + expected, false, "successful completion", "not called",
                "task-events:" + bundle.executionRunId(), at);
        if (outcomes.isEmpty()) return inconclusive(runId, "required_tool:" + expected,
                "tool completion outcome is unavailable", "task-events:" + bundle.executionRunId(), at);
        if (outcomes.size() != calls.size()) return inconclusive(runId, "required_tool:" + expected,
                "tool completion count does not match call count", "task-events:" + bundle.executionRunId(), at);
        if (outcomes.stream().anyMatch(Boolean.TRUE::equals)) return result(runId, "required_tool:" + expected,
                true, "successful completion", "succeeded", "task-events:" + bundle.executionRunId(), at);
        return result(runId, "required_tool:" + expected, false, "successful completion", "failed",
                "task-events:" + bundle.executionRunId(), at);
    }

    private static EvaluationResultRecord forbiddenTool(UUID runId, JsonNode assertion, EvidenceBundle bundle, Instant at) {
        String forbidden = text(assertion, "name");
        if (!eventsComplete(bundle)) return inconclusive(runId, "forbidden_tool:" + forbidden,
                "tool event evidence is incomplete", "task-events:" + bundle.executionRunId(), at);
        if (hasUnparseableToolEvent(bundle, "tool.called")) return inconclusive(runId, "forbidden_tool:" + forbidden,
                "tool call details are unavailable", "task-events:" + bundle.executionRunId(), at);
        boolean passed = bundle.toolCallSummaries().stream().noneMatch(tool -> tool.toolName().equals(forbidden));
        return result(runId, "forbidden_tool:" + forbidden, passed, "absent", passed ? "absent" : "present",
                "task-events:" + bundle.executionRunId(), at);
    }

    private static EvaluationResultRecord runStatus(UUID runId, JsonNode assertion, EvidenceBundle bundle, Instant at) {
        String expected = text(assertion, "expected");
        String actual = bundle.runSnapshot().status();
        if ("UNKNOWN".equals(actual)) return inconclusive(runId, "run_status", "Run status is unavailable",
                "task-run:" + bundle.executionRunId(), at);
        return result(runId, "run_status", expected.equalsIgnoreCase(actual), expected, actual,
                "task-run:" + bundle.executionRunId(), at);
    }

    private static EvaluationResultRecord failedTool(UUID runId, JsonNode assertion, EvidenceBundle bundle, Instant at) {
        String name = text(assertion, "name");
        if (hasUnparseableToolEvent(bundle, "tool.called") || hasUnparseableToolEvent(bundle, "tool.finished")) {
            return inconclusive(runId, "failed_tool:" + name, "tool lifecycle details are unavailable",
                    "task-events:" + bundle.executionRunId(), at);
        }
        List<ToolLifecycle> visibleOutcomes = toolEvents(bundle, "tool.finished", name);
        if (!eventsComplete(bundle)) {
            if (hasObservedFailedToolPair(bundle, name)) {
                return result(runId, "failed_tool:" + name, false, "no failed calls", "failed call observed",
                        "task-events:" + bundle.executionRunId(), at);
            }
            return inconclusive(runId, "failed_tool:" + name,
                    "tool event evidence is incomplete", "task-events:" + bundle.executionRunId(), at);
        }
        if (!hasValidToolLifecyclePairs(bundle)) return inconclusive(runId, "failed_tool:" + name,
                "tool call identifiers are missing, duplicated, or unmatched", "task-events:" + bundle.executionRunId(), at);
        if (visibleOutcomes.stream().anyMatch(outcome -> Boolean.FALSE.equals(outcome.ok()))) {
            return result(runId, "failed_tool:" + name, false, "no failed calls", "failed call observed",
                    "task-events:" + bundle.executionRunId(), at);
        }
        if (hasUnparseableToolEvent(bundle, "tool.called")) return inconclusive(runId, "failed_tool:" + name,
                "tool call details are unavailable", "task-events:" + bundle.executionRunId(), at);
        if (hasUnparseableToolEvent(bundle, "tool.finished")) return inconclusive(runId, "failed_tool:" + name,
                "tool completion details are unavailable", "task-events:" + bundle.executionRunId(), at);
        List<ToolLifecycle> calls = toolEvents(bundle, "tool.called", name);
        List<Boolean> outcomes = visibleOutcomes.stream().map(ToolLifecycle::ok).toList();
        if (calls.isEmpty() && !outcomes.isEmpty()) return inconclusive(runId, "failed_tool:" + name,
                "tool completion is present without a matching call event", "task-events:" + bundle.executionRunId(), at);
        if (calls.isEmpty()) return result(runId, "failed_tool:" + name, true, "no failed calls", "not called",
                "task-events:" + bundle.executionRunId(), at);
        if (outcomes.isEmpty()) return inconclusive(runId, "failed_tool:" + name,
                "tool completion outcome is unavailable", "task-events:" + bundle.executionRunId(), at);
        boolean failed = outcomes.stream().anyMatch(Boolean.FALSE::equals);
        if (!failed && outcomes.size() != calls.size()) return inconclusive(runId, "failed_tool:" + name,
                "tool completion count does not match call count", "task-events:" + bundle.executionRunId(), at);
        return result(runId, "failed_tool:" + name, !failed, "no failed calls", failed ? "failed call observed" : "no failed call",
                "task-events:" + bundle.executionRunId(), at);
    }

    private static EvaluationResultRecord toolOrder(UUID runId, JsonNode assertion, EvidenceBundle bundle, Instant at) {
        if (!eventsComplete(bundle)) return inconclusive(runId, "tool_order", "tool event evidence is incomplete",
                "task-events:" + bundle.executionRunId(), at);
        if (hasUnparseableToolEvent(bundle, "tool.called")) return inconclusive(runId, "tool_order",
                "tool call details are unavailable", "task-events:" + bundle.executionRunId(), at);
        JsonNode names = assertion.path("names");
        if (!names.isArray() || names.isEmpty()) return inconclusive(runId, "tool_order", "tool order assertion is malformed",
                "evaluation:tool_order", at);
        List<String> expected = new ArrayList<>();
        names.forEach(value -> expected.add(value.asText()));
        List<String> actual = toolEvents(bundle, "tool.called", null).stream().sorted(Comparator.comparingLong(ToolLifecycle::sequence))
                .map(ToolLifecycle::toolName).toList();
        return result(runId, "tool_order", expected.equals(actual), String.join(",", expected), String.join(",", actual),
                "task-events:" + bundle.executionRunId(), at);
    }

    private static EvaluationResultRecord toolCount(UUID runId, JsonNode assertion, EvidenceBundle bundle, Instant at) {
        String name = text(assertion, "name");
        if (!eventsComplete(bundle)) return inconclusive(runId, "tool_count:" + name,
                "tool event evidence is incomplete", "task-events:" + bundle.executionRunId(), at);
        if (hasUnparseableToolEvent(bundle, "tool.called")) return inconclusive(runId, "tool_count:" + name,
                "tool call details are unavailable", "task-events:" + bundle.executionRunId(), at);
        int expected = assertion.path("expected").asInt(-1);
        if (expected < 0) return inconclusive(runId, "tool_count:" + name, "expected count is invalid", "evaluation:tool_count", at);
        long actual = toolEvents(bundle, "tool.called", name).size();
        return result(runId, "tool_count:" + name, actual == expected, Integer.toString(expected), Long.toString(actual),
                "task-events:" + bundle.executionRunId(), at);
    }

    private static EvaluationResultRecord dependencySuccess(UUID runId, EvidenceBundle bundle, Instant at) {
        if (bundle.taskTree().isEmpty()) return inconclusive(runId, "dependency_success", "task tree evidence is unavailable",
                "task-tree:" + bundle.executionRunId(), at);
        Map<UUID, TaskTreeNode> nodes = new HashMap<>();
        bundle.taskTree().forEach(node -> nodes.put(node.taskId(), node));
        boolean hasDependencies = bundle.taskTree().stream().anyMatch(node -> !node.dependencyIds().isEmpty());
        for (TaskTreeNode node : bundle.taskTree()) {
            for (UUID dependencyId : node.dependencyIds()) {
                TaskTreeNode dependency = nodes.get(dependencyId);
                if (dependency == null) return inconclusive(runId, "dependency_success", "dependency status is unavailable",
                        "task-tree:" + bundle.executionRunId(), at);
                if (!"SUCCEEDED".equalsIgnoreCase(dependency.status())) return result(runId, "dependency_success", false,
                        "all dependencies SUCCEEDED", "dependency not successful", "task-tree:" + bundle.executionRunId(), at);
            }
        }
        return result(runId, "dependency_success", true, "all dependencies SUCCEEDED",
                hasDependencies ? "all dependencies SUCCEEDED" : "no dependencies", "task-tree:" + bundle.executionRunId(), at);
    }

    private static EvaluationResultRecord artifactPdf(UUID runId, EvidenceBundle bundle, Instant at) {
        String ref = "result-manifest:" + bundle.executionRunId();
        if (bundle.resultManifest() == null) return inconclusive(runId, "artifact_pdf", "result manifest is unavailable", ref, at);
        List<EvidenceBundle.ArtifactRef> pdfs = bundle.artifactRefs().stream()
                .filter(artifact -> artifact.artifactId().toLowerCase(Locale.ROOT).endsWith(".pdf")
                        || artifact.contentType().equalsIgnoreCase("application/pdf")).toList();
        if (pdfs.isEmpty()) return result(runId, "artifact_pdf", false, "PDF artifact", "missing",
                ref, at);
        boolean metadataValid = pdfs.stream().allMatch(artifact -> artifact.artifactId().toLowerCase(Locale.ROOT).endsWith(".pdf")
                && artifact.contentType().equalsIgnoreCase("application/pdf") && artifact.sizeBytes() > 0
                && artifact.sha256().matches("[0-9a-fA-F]{64}") && hasDurableStorageRef(artifact));
        if (!metadataValid) return result(runId, "artifact_pdf", false, "PDF MIME, extension, size and SHA-256",
                "invalid PDF metadata", ref, at);
        return inconclusive(runId, "artifact_pdf", "PDF signature bytes are unavailable for %PDF- verification", ref, at);
    }

    private static EvaluationResultRecord artifactIntegrity(UUID runId, EvidenceBundle bundle, Instant at) {
        if (bundle.resultManifest() == null) return inconclusive(runId, "artifact_integrity", "result manifest is unavailable",
                "result-manifest:" + bundle.executionRunId(), at);
        boolean passed = !bundle.artifactRefs().isEmpty() && bundle.artifactRefs().stream()
                .allMatch(ref -> ref.sizeBytes() > 0 && ref.sha256().matches("[0-9a-fA-F]{64}")
                        && hasDurableStorageRef(ref));
        return result(runId, "artifact_integrity", passed, "durable metadata", passed ? "valid" : "missing/invalid",
                "result-manifest:" + bundle.executionRunId(), at);
    }

    private static boolean hasDurableStorageRef(EvidenceBundle.ArtifactRef artifact) {
        return SAFE_ARTIFACT_NAME.matcher(artifact.artifactId()).matches()
                && SAFE_STORAGE_REF.matcher(artifact.storageRef()).matches()
                && artifact.storageRef().endsWith("/artifacts/" + artifact.artifactId());
    }

    private static EvaluationResultRecord schema(UUID runId, JsonNode assertion, EvidenceBundle bundle, Instant at) {
        String expectedType = text(assertion, "taskType");
        String actualType = bundle.taskSnapshot().taskType();
        if ("unknown".equalsIgnoreCase(actualType)) return inconclusive(runId, "schema", "Task type is unavailable",
                "task-snapshot:" + bundle.taskId(), at);
        boolean passed = expectedType.equalsIgnoreCase(actualType);
        return result(runId, "schema", passed, expectedType, actualType,
                "task-snapshot:" + bundle.taskId(), at);
    }

    private static EvaluationResultRecord result(UUID runId, String metric, boolean passed, String expected,
            String actual, String evidenceRef, Instant at) {
        return new EvaluationResultRecord(UUID.randomUUID(), runId, metric, passed ? BigDecimal.ONE : BigDecimal.ZERO,
                BigDecimal.ONE, passed ? EvaluationResultRecord.Status.PASSED : EvaluationResultRecord.Status.FAILED,
                List.of(evidenceRef), "expected=" + expected + ", actual=" + actual, at);
    }

    private static boolean eventsComplete(EvidenceBundle bundle) {
        EvidenceBundle.EvidenceCompleteness completeness = bundle.evidenceCompleteness();
        return !"TRUNCATED".equals(completeness.status())
                && completeness.observedEventCount() <= completeness.eventLimit();
    }

    private static boolean hasUnparseableToolEvent(EvidenceBundle bundle, String eventType) {
        return bundle.taskEvents().stream().filter(event -> eventType.equalsIgnoreCase(event.eventType()))
                .anyMatch(event -> parseToolEvent(event, eventType) == null);
    }

    private static boolean hasValidToolLifecyclePairs(EvidenceBundle bundle) {
        List<ToolLifecycle> calls = toolEvents(bundle, "tool.called", null);
        List<ToolLifecycle> finishes = toolEvents(bundle, "tool.finished", null);
        Map<String, ToolLifecycle> callsById = new HashMap<>();
        for (ToolLifecycle call : calls) {
            if (call.callId() == null || call.callId().isBlank()
                    || callsById.putIfAbsent(call.callId(), call) != null) return false;
        }
        Map<String, ToolLifecycle> finishesById = new HashMap<>();
        for (ToolLifecycle finish : finishes) {
            if (finish.callId() == null || finish.callId().isBlank()
                    || finishesById.putIfAbsent(finish.callId(), finish) != null) return false;
        }
        if (callsById.size() != finishesById.size()) return false;
        for (Map.Entry<String, ToolLifecycle> entry : finishesById.entrySet()) {
            ToolLifecycle call = callsById.get(entry.getKey());
            ToolLifecycle finish = entry.getValue();
            if (call == null || !call.toolName().equals(finish.toolName()) || finish.sequence() <= call.sequence()) return false;
        }
        return true;
    }

    private static boolean hasObservedFailedToolPair(EvidenceBundle bundle, String toolName) {
        List<ToolLifecycle> calls = toolEvents(bundle, "tool.called", null);
        List<ToolLifecycle> finishes = toolEvents(bundle, "tool.finished", null);
        for (ToolLifecycle failed : finishes) {
            if (!toolName.equals(failed.toolName()) || !Boolean.FALSE.equals(failed.ok())
                    || failed.callId() == null || failed.callId().isBlank()) continue;
            List<ToolLifecycle> matchingCalls = calls.stream()
                    .filter(call -> failed.callId().equals(call.callId())).toList();
            List<ToolLifecycle> matchingFinishes = finishes.stream()
                    .filter(finish -> failed.callId().equals(finish.callId())).toList();
            if (matchingCalls.size() == 1 && matchingFinishes.size() == 1
                    && toolName.equals(matchingCalls.getFirst().toolName())
                    && matchingFinishes.getFirst() == failed
                    && failed.sequence() > matchingCalls.getFirst().sequence()) return true;
        }
        return false;
    }

    private static List<ToolLifecycle> toolEvents(EvidenceBundle bundle, String eventType, String toolName) {
        return bundle.taskEvents().stream().filter(event -> eventType.equalsIgnoreCase(event.eventType()))
                .map(event -> parseToolEvent(event, eventType)).filter(Objects::nonNull)
                .filter(event -> toolName == null || toolName.equals(event.toolName())).toList();
    }

    private static ToolLifecycle parseToolEvent(TaskProcessEvent event, String eventType) {
        try {
            JsonNode payload = JSON.readTree(event.payload());
            if (payload == null || !payload.isObject()) return null;
            JsonNode toolNode = payload.path("tool");
            JsonNode sequenceNode = payload.path("sequence");
            String callId = payload.path("callId").isTextual() ? payload.path("callId").asText().trim() : null;
            if (!toolNode.isTextual() || toolNode.asText().isBlank() || !sequenceNode.isIntegralNumber()
                    || !sequenceNode.canConvertToLong() || sequenceNode.asLong() < 0) return null;
            Boolean ok = null;
            if ("tool.finished".equalsIgnoreCase(eventType)) {
                JsonNode okNode = payload.path("ok");
                if (!okNode.isBoolean()) return null;
                ok = okNode.asBoolean();
            }
            return new ToolLifecycle(toolNode.asText().trim(), sequenceNode.asLong(), ok, callId);
        } catch (Exception ignored) {
            return null;
        }
    }

    private record ToolLifecycle(String toolName, long sequence, Boolean ok, String callId) { }

    private static EvaluationResultRecord inconclusive(UUID runId, String metric, String message, Instant at) {
        return inconclusive(runId, metric, message, "evaluation:" + metric, at);
    }

    private static EvaluationResultRecord inconclusive(UUID runId, String metric, String message, String evidenceRef, Instant at) {
        return new EvaluationResultRecord(UUID.randomUUID(), runId, metric, null, null,
                EvaluationResultRecord.Status.INCONCLUSIVE, List.of(evidenceRef), message, at);
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
        EvaluationReportRecord.GateStatus executionGate = gateStatus(results.stream()
                .filter(result -> isExecutionAssertion(result.metric())).toList());
        EvaluationReportRecord.GateStatus resultGate = gateStatus(results.stream()
                .filter(result -> !isExecutionAssertion(result.metric()) && !"semantic".equals(result.metric())).toList());
        return new EvaluationReportRecord(UUID.randomUUID(), run.id(), aggregate, dimensions, run.status(), taskSuccess,
                executionGate, resultGate, at);
    }

    private static EvaluationReportRecord.GateStatus gateStatus(List<EvaluationResultRecord> results) {
        if (results.stream().anyMatch(result -> result.status() == EvaluationResultRecord.Status.FAILED)) {
            return EvaluationReportRecord.GateStatus.FAILED;
        }
        if (results.isEmpty() || results.stream().anyMatch(result -> result.status() == EvaluationResultRecord.Status.INCONCLUSIVE)) {
            return EvaluationReportRecord.GateStatus.INCONCLUSIVE;
        }
        return EvaluationReportRecord.GateStatus.PASSED;
    }

    private static boolean isExecutionAssertion(String metric) {
        String type = metric.contains(":") ? metric.substring(0, metric.indexOf(':')) : metric;
        return Set.of("phase", "required_tool", "forbidden_tool", "failed_tool", "tool_order", "tool_count",
                "dependency_success", "dependency_handoff", "retry_limit", "run_status").contains(type);
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
