package io.agentteams.controlplane.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentteams.controlplane.evaluation.EvaluationReportRecord;
import io.agentteams.controlplane.evaluation.EvaluationResultRecord;
import io.agentteams.controlplane.evaluation.EvaluationRepository;
import io.agentteams.controlplane.evaluation.EvaluationRunRecord;
import io.agentteams.controlplane.evaluation.EvaluationService;
import io.agentteams.controlplane.security.AuthorizationService;
import io.agentteams.controlplane.security.ExecutionContext;
import io.agentteams.controlplane.security.ExecutionContextResolver;
import io.agentteams.controlplane.security.Principal;
import io.agentteams.controlplane.security.PrincipalContext;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

class EvaluationControllerTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final UUID RUN_ID = UUID.fromString("00000000-0000-0000-0000-000000000005");
    private static final ExecutionContext CONTEXT = new ExecutionContext("org-a", "tenant-a", "project-a", "team-a", "alice");
    private static final Principal PRINCIPAL = new Principal("alice",
            new AuthorizationService.Scope("tenant-a", "project-a", "team-a"), Set.of());
    private EvaluationService service;
    private EvaluationRepository repository;
    private ExecutionContextResolver contexts;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(EvaluationService.class);
        repository = mock(EvaluationRepository.class);
        contexts = mock(ExecutionContextResolver.class);
        PrincipalContext.set(PRINCIPAL);
        when(contexts.resolve(PRINCIPAL)).thenReturn(CONTEXT);
        mvc = standaloneSetup(new EvaluationController(service, repository, contexts))
                .setControllerAdvice(new ApiErrorHandler()).build();
    }

    @AfterEach
    void clearPrincipal() {
        PrincipalContext.clear();
    }

    @Test
    void createsEvaluationWithoutCallingTaskLifecycle() throws Exception {
        EvaluationRunRecord run = new EvaluationRunRecord(RUN_ID, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "evaluator-1", EvaluationRunRecord.Status.PASSED, Instant.now(), Instant.now(), Instant.now());
        EvaluationReportRecord report = new EvaluationReportRecord(UUID.randomUUID(), RUN_ID, BigDecimal.ONE,
                JSON.createObjectNode(), EvaluationRunRecord.Status.PASSED, true, Instant.now());
        EvaluationService.EvaluationOutcome outcome = new EvaluationService.EvaluationOutcome(run, List.of(), report,
                mock(io.agentteams.controlplane.evaluation.EvidenceBundle.class));
        when(service.evaluate(eq(CONTEXT), any(EvaluationRepository.CreateEvaluationRun.class), eq(false))).thenReturn(outcome);

        mvc.perform(post("/api/v1/evaluations/runs").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"suiteId\":\"00000000-0000-0000-0000-000000000001\",\"caseId\":\"00000000-0000-0000-0000-000000000002\",\"taskId\":\"00000000-0000-0000-0000-000000000003\",\"executionRunId\":\"00000000-0000-0000-0000-000000000004\",\"evaluatorVersion\":\"evaluator-1\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.run.status").value("PASSED"))
                .andExpect(jsonPath("$.report.taskSuccess").value(true));

        verify(service).evaluate(eq(CONTEXT), any(EvaluationRepository.CreateEvaluationRun.class), eq(false));
    }

    @Test
    void readsScopedRun() throws Exception {
        EvaluationRunRecord run = EvaluationRunRecord.queued(RUN_ID, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "evaluator-1", Instant.now());
        when(repository.findRun(CONTEXT, RUN_ID)).thenReturn(Optional.of(run));

        mvc.perform(get("/api/v1/evaluations/runs/{id}", RUN_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("QUEUED"));
    }

    @Test
    void listsRunsWithinTheCurrentProjectScope() throws Exception {
        EvaluationRunRecord run = EvaluationRunRecord.queued(RUN_ID, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "evaluator-1", Instant.now());
        when(repository.listRuns(CONTEXT)).thenReturn(List.of(run));

        mvc.perform(get("/api/v1/evaluations/runs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("QUEUED"));
        verify(repository).listRuns(CONTEXT);
    }

    @Test
    void readsReportWithScopedResults() throws Exception {
        EvaluationReportRecord report = new EvaluationReportRecord(UUID.randomUUID(), RUN_ID, BigDecimal.ONE,
                JSON.createObjectNode(), EvaluationRunRecord.Status.PASSED, true, Instant.now());
        EvaluationResultRecord result = new EvaluationResultRecord(UUID.randomUUID(), RUN_ID, "phase",
                BigDecimal.ONE, BigDecimal.ONE, EvaluationResultRecord.Status.PASSED,
                List.of("task-snapshot:task-1"), "phase matched", Instant.now());
        when(repository.findReport(CONTEXT, RUN_ID)).thenReturn(Optional.of(report));
        when(repository.findResults(CONTEXT, RUN_ID)).thenReturn(List.of(result));

        mvc.perform(get("/api/v1/evaluations/runs/{id}/report", RUN_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.report.taskSuccess").value(true))
                .andExpect(jsonPath("$.results[0].evidenceRefs[0]").value("task-snapshot:task-1"));
    }
}
