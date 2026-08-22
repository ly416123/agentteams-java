package io.agentteams.controlplane.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.agentteams.controlplane.persistence.AgentRecord;
import io.agentteams.controlplane.persistence.IdempotencyConflictException;
import io.agentteams.controlplane.persistence.OptimisticLockFailure;
import io.agentteams.controlplane.persistence.TaskRecord;
import io.agentteams.controlplane.service.AgentService;
import io.agentteams.controlplane.service.ResourceNotFoundException;
import io.agentteams.controlplane.service.TaskService;
import io.agentteams.controlplane.service.UnavailableDependencyException;
import io.agentteams.controlplane.security.AuthorizationService;
import io.agentteams.controlplane.security.Principal;
import io.agentteams.controlplane.security.PrincipalContext;
import io.agentteams.domain.agent.AgentPhase;
import io.agentteams.domain.task.IllegalTaskTransitionException;
import io.agentteams.domain.task.TaskPhase;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class ControlPlaneControllerTest {

    @Mock
    private AgentService agents;

    @Mock
    private TaskService tasks;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new AgentController(agents), new TaskController(tasks))
                .setControllerAdvice(new ApiErrorHandler())
                .build();
    }

    @Test
    void createsAndReadsAnAgentWithoutExposingMetadata() throws Exception {
        UUID id = UUID.randomUUID();
        AgentRecord agent = new AgentRecord(id, "worker-1", AgentPhase.PROVISIONING, "qwenpaw",
                "{\"token\":\"secret\"}", "{\"password\":\"secret\"}",
                Instant.parse("2026-08-16T00:00:00Z"), Instant.parse("2026-08-16T00:00:00Z"), 0);
        when(agents.create(eq("agent-key"), any())).thenReturn(agent);
        when(agents.get(id)).thenReturn(agent);

        mockMvc.perform(post("/api/v1/agents")
                        .header("Idempotency-Key", "agent-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"worker-1\",\"runtime\":\"qwenpaw\","
                                + "\"capabilities\":{\"language\":\"java\"}}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.phase").value("PROVISIONING"))
                .andExpect(jsonPath("$.metadata").doesNotExist());

        mockMvc.perform(get("/api/v1/agents/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.phase").value("PROVISIONING"))
                .andExpect(jsonPath("$.runtime").value("qwenpaw"));
    }

    @Test
    void createsAndReadsATaskInDraft() throws Exception {
        UUID id = UUID.randomUUID();
        TaskRecord task = new TaskRecord(id, "Build API", "description", TaskPhase.DRAFT, 0, "{}",
                "api", "rest", null, null, Instant.now(), Instant.now(), 0);
        when(tasks.create(eq("task-key"), any())).thenReturn(task);
        when(tasks.get(id)).thenReturn(task);

        mockMvc.perform(post("/api/v1/tasks")
                        .header("Idempotency-Key", "task-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Build API\",\"description\":\"description\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.phase").value("DRAFT"));

        mockMvc.perform(get("/api/v1/tasks/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phase").value("DRAFT"));
    }

    @Test
    void rejectsAuthenticatedTaskOutsidePrincipalScope() throws Exception {
        PrincipalContext.set(new Principal("alice",
                new AuthorizationService.Scope("tenant-a", "project-a", "team-a"),
                java.util.Set.of("task:create")));
        try {
            mockMvc.perform(post("/api/v1/tasks")
                            .header("Idempotency-Key", "scoped-task-key")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"title\":\"Build API\",\"spec\":{\"scope\":{"
                                    + "\"tenant\":\"tenant-b\",\"project\":\"project-a\",\"team\":\"team-a\"}}}"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"));
            verifyNoInteractions(tasks);
        } finally {
            PrincipalContext.clear();
        }
    }

    @Test
    void rejectsAuthenticatedAgentOutsidePrincipalScope() throws Exception {
        PrincipalContext.set(new Principal("alice",
                new AuthorizationService.Scope("tenant-a", "project-a", "team-a"),
                java.util.Set.of("agent:write")));
        try {
            mockMvc.perform(post("/api/v1/agents")
                            .header("Idempotency-Key", "scoped-agent-key")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"worker-1\",\"runtime\":\"qwenpaw\",\"metadata\":{\"scope\":{"
                                    + "\"tenant\":\"tenant-b\",\"project\":\"project-a\",\"team\":\"team-a\"}}}"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"));
            verifyNoInteractions(agents);
        } finally {
            PrincipalContext.clear();
        }
    }

    @Test
    void permitsAuthenticatedTaskWithinPrincipalScope() throws Exception {
        UUID id = UUID.randomUUID();
        TaskRecord task = new TaskRecord(id, "Build API", "description", TaskPhase.DRAFT, 0,
                "{\"scope\":{\"tenant\":\"tenant-a\",\"project\":\"project-a\",\"team\":\"team-a\"}}",
                "alice", "rest", null, null, Instant.now(), Instant.now(), 0);
        when(tasks.create(eq("scoped-task-key"), any())).thenReturn(task);
        PrincipalContext.set(new Principal("alice",
                new AuthorizationService.Scope("tenant-a", "project-a", "team-a"),
                java.util.Set.of("task:create")));
        try {
            mockMvc.perform(post("/api/v1/tasks")
                            .header("Idempotency-Key", "scoped-task-key")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"title\":\"Build API\",\"spec\":{\"scope\":{"
                                    + "\"tenant\":\"tenant-a\",\"project\":\"project-a\",\"team\":\"team-a\"}}}"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.id").value(id.toString()));
        } finally {
            PrincipalContext.clear();
        }
    }

    @Test
    void repeatedIdempotencyKeyIsForwardedAndReturnsTheOriginalResource() throws Exception {
        UUID id = UUID.randomUUID();
        AgentRecord agent = new AgentRecord(id, "worker-1", AgentPhase.PROVISIONING, "qwenpaw", "{}", "{}",
                Instant.now(), Instant.now(), 0);
        when(agents.create(eq("same-key"), any())).thenReturn(agent);

        for (int attempt = 0; attempt < 2; attempt++) {
            mockMvc.perform(post("/api/v1/agents")
                            .header("Idempotency-Key", "same-key")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"worker-1\",\"runtime\":\"qwenpaw\"}"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.id").value(id.toString()));
        }
        verify(agents, times(2)).create(eq("same-key"), any());
    }

    @Test
    void rejectsTheSameIdempotencyKeyWhenThePayloadChanges() throws Exception {
        UUID id = UUID.randomUUID();
        AgentRecord original = new AgentRecord(id, "worker-1", AgentPhase.PROVISIONING, "qwenpaw",
                "{}", "{}", Instant.now(), Instant.now(), 0);
        when(agents.create(eq("same-key"), any()))
                .thenReturn(original)
                .thenThrow(new IdempotencyConflictException("same-key", "CREATE_AGENT"));

        mockMvc.perform(post("/api/v1/agents")
                        .header("Idempotency-Key", "same-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"worker-1\",\"runtime\":\"qwenpaw\"}"))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/agents")
                        .header("Idempotency-Key", "same-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"worker-2\",\"runtime\":\"qwenpaw\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"))
                .andExpect(jsonPath("$.message").value(
                        "request conflicts with current resource state"));
    }

    @Test
    void rejectsMissingIdempotencyKeyAsValidationError() throws Exception {
        mockMvc.perform(post("/api/v1/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Build API\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        verifyNoInteractions(tasks);
    }

    @Test
    void rejectsMissingAgentIdempotencyKeyBeforeCallingTheService() throws Exception {
        mockMvc.perform(post("/api/v1/agents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"worker-1\",\"runtime\":\"qwenpaw\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        verifyNoInteractions(agents);
    }

    @Test
    void reportsIllegalCancellationWithAStableErrorCode() throws Exception {
        UUID id = UUID.randomUUID();
        when(tasks.cancel(eq(id), eq(0L), eq("cancel-key"), any(), any()))
                .thenThrow(new IllegalTaskTransitionException(TaskPhase.DRAFT, TaskPhase.CANCELLED));

        mockMvc.perform(post("/api/v1/tasks/{id}/cancel", id)
                        .header("Idempotency-Key", "cancel-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":0}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ILLEGAL_TRANSITION"));
    }

    @Test
    void queuesATaskWithExpectedVersionAndIdempotencyKey() throws Exception {
        UUID id = UUID.randomUUID();
        TaskRecord queued = new TaskRecord(id, "Build API", "description", TaskPhase.QUEUED, 0, "{}",
                "api", "rest", null, null, Instant.now(), Instant.now(), 1);
        when(tasks.queue(eq(id), eq(0L), eq("queue-key"))).thenReturn(queued);

        mockMvc.perform(post("/api/v1/tasks/{id}/queue", id)
                        .header("Idempotency-Key", "queue-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phase").value("QUEUED"));
    }

    @Test
    void reportsOptimisticConflictWithoutInternalDetails() throws Exception {
        UUID id = UUID.randomUUID();
        when(tasks.cancel(eq(id), eq(0L), eq("cancel-key"), any(), any()))
                .thenThrow(new OptimisticLockFailure("task", id, 0, 1));

        mockMvc.perform(post("/api/v1/tasks/{id}/cancel", id)
                        .header("Idempotency-Key", "cancel-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":0}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"))
                .andExpect(jsonPath("$.stackTrace").doesNotExist());
    }

    @Test
    void reportsNotFoundWithAStableErrorCode() throws Exception {
        UUID id = UUID.randomUUID();
        when(tasks.get(id)).thenThrow(new ResourceNotFoundException("task", id));

        mockMvc.perform(get("/api/v1/tasks/{id}", id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void reportsUnavailableDependenciesWithoutInternalDetails() throws Exception {
        when(agents.create(eq("agent-key"), any()))
                .thenThrow(new UnavailableDependencyException("database", new RuntimeException("secret")));

        mockMvc.perform(post("/api/v1/agents")
                        .header("Idempotency-Key", "agent-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"worker-1\",\"runtime\":\"qwenpaw\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("UNAVAILABLE_DEPENDENCY"))
                .andExpect(jsonPath("$.message").value("required dependency is unavailable"));
    }
}
