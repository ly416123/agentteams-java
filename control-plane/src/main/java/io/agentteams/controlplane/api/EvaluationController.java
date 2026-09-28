package io.agentteams.controlplane.api;

import io.agentteams.controlplane.evaluation.EvaluationReportRecord;
import io.agentteams.controlplane.evaluation.EvaluationRepository;
import io.agentteams.controlplane.evaluation.EvaluationRunRecord;
import io.agentteams.controlplane.evaluation.EvaluationResultRecord;
import io.agentteams.controlplane.evaluation.EvaluationService;
import io.agentteams.controlplane.security.ExecutionContext;
import io.agentteams.controlplane.security.ExecutionContextResolver;
import io.agentteams.controlplane.security.PrincipalContext;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/evaluations")
public final class EvaluationController {
    private final EvaluationService service;
    private final EvaluationRepository repository;
    private final ExecutionContextResolver contexts;

    public EvaluationController(EvaluationService service, EvaluationRepository repository,
            ExecutionContextResolver contexts) {
        this.service = Objects.requireNonNull(service, "service");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.contexts = Objects.requireNonNull(contexts, "contexts");
    }

    @PostMapping("/runs")
    public ResponseEntity<EvaluationResponse> create(@RequestBody CreateRunRequest request) {
        Objects.requireNonNull(request, "request");
        EvaluationService.EvaluationOutcome outcome = service.evaluate(context(), request.toCommand(),
                request.semanticRequested());
        return ResponseEntity.accepted().body(EvaluationResponse.from(outcome));
    }

    @GetMapping("/runs")
    public List<EvaluationRunRecord> runs() {
        return repository.listRuns(context());
    }

    @GetMapping("/runs/{runId}")
    public EvaluationRunRecord run(@PathVariable UUID runId) {
        return repository.findRun(context(), runId)
                .orElseThrow(() -> new IllegalArgumentException("evaluation run is not available"));
    }

    @GetMapping("/runs/{runId}/report")
    public EvaluationReportResponse report(@PathVariable UUID runId) {
        ExecutionContext context = context();
        EvaluationReportRecord report = repository.findReport(context, runId)
                .orElseThrow(() -> new IllegalArgumentException("evaluation report is not available"));
        return new EvaluationReportResponse(report, repository.findResults(context, runId));
    }

    private ExecutionContext context() {
        return PrincipalContext.executionContext(contexts)
                .orElseThrow(() -> new IllegalArgumentException("authenticated scope is required"));
    }

    public record CreateRunRequest(UUID id, UUID suiteId, UUID caseId, UUID taskId, UUID executionRunId,
            String evaluatorVersion, boolean semanticRequested, Instant createdAt) {
        EvaluationRepository.CreateEvaluationRun toCommand() {
            return new EvaluationRepository.CreateEvaluationRun(id == null ? UUID.randomUUID() : id, suiteId, caseId,
                    taskId, executionRunId, evaluatorVersion, createdAt == null ? Instant.now() : createdAt);
        }
    }

    public record EvaluationResponse(EvaluationRunRecord run, List<?> results, EvaluationReportRecord report) {
        static EvaluationResponse from(EvaluationService.EvaluationOutcome outcome) {
            return new EvaluationResponse(outcome.run(), outcome.results(), outcome.report());
        }
    }

    public record EvaluationReportResponse(EvaluationReportRecord report, List<EvaluationResultRecord> results) { }
}
