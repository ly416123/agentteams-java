package io.agentteams.controlplane.artifact;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.agentteams.controlplane.api.ApiErrorHandler;
import io.agentteams.controlplane.audit.AuditEvent;
import io.agentteams.controlplane.audit.AuditRecorder;
import io.agentteams.controlplane.security.AuthorizationService;
import io.agentteams.controlplane.security.Principal;
import io.agentteams.controlplane.security.PrincipalContext;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class ArtifactRetentionManagementControllerTest {
    private static final UUID POLICY_ID = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-09-02T00:00:00Z");

    @Mock
    private ArtifactRetentionRepository repository;

    @Mock
    private AuditRecorder auditRecorder;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new ArtifactRetentionManagementController(repository, auditRecorder,
                () -> NOW))
                .setControllerAdvice(new ApiErrorHandler()).build();
        PrincipalContext.set(new Principal("alice",
                new AuthorizationService.Scope("tenant-a", "project-a", "team-a"), Set.of()));
    }

    @AfterEach
    void tearDown() {
        PrincipalContext.clear();
    }

    @Test
    void readsOnlyTheAuthenticatedProjectPolicy() throws Exception {
        ArtifactRetentionProjectPolicy policy = policy(4);
        when(repository.findProjectPolicy("tenant-a", "project-a")).thenReturn(Optional.of(policy));

        mockMvc.perform(get("/api/v1/artifacts/retention"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projectId").value("project-a"))
                .andExpect(jsonPath("$.configured").value(true))
                .andExpect(jsonPath("$.successfulTaskRetentionSeconds").value(86400))
                .andExpect(jsonPath("$.legalHold").value(true))
                .andExpect(jsonPath("$.version").value(4));
        verify(repository).findProjectPolicy("tenant-a", "project-a");
    }

    @Test
    void updatesTheProjectPolicyWithAnOptimisticVersionAndAuditEvent() throws Exception {
        ArtifactRetentionPolicy candidate = new ArtifactRetentionPolicy(Duration.ofDays(7), Duration.ofDays(30),
                Duration.ofHours(4), true);
        ArtifactRetentionProjectPolicy updated = new ArtifactRetentionProjectPolicy(
                POLICY_ID, "tenant-a", "project-a", candidate, 5, NOW, NOW);
        when(repository.upsertProjectPolicy(eq("tenant-a"), eq("project-a"), eq(candidate), eq(NOW), eq(4L)))
                .thenReturn(updated);

        mockMvc.perform(put("/api/v1/artifacts/retention")
                        .contentType("application/json")
                        .content("{\"successfulTaskRetentionSeconds\":604800,\"failedTaskRetentionSeconds\":2592000,"
                                + "\"temporaryUploadRetentionSeconds\":14400,\"legalHold\":true,\"expectedVersion\":4}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(true))
                .andExpect(jsonPath("$.failedTaskRetentionSeconds").value(2592000))
                .andExpect(jsonPath("$.version").value(5));

        verify(repository).upsertProjectPolicy("tenant-a", "project-a", candidate, NOW, 4);
        ArgumentCaptor<AuditEvent> event = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditRecorder).record(event.capture());
        org.assertj.core.api.Assertions.assertThat(event.getValue().action())
                .isEqualTo("ARTIFACT_RETENTION_POLICY_UPDATED");
        org.assertj.core.api.Assertions.assertThat(event.getValue().actor()).isEqualTo("alice");
    }

    private static ArtifactRetentionProjectPolicy policy(long version) {
        return new ArtifactRetentionProjectPolicy(POLICY_ID, "tenant-a", "project-a",
                new ArtifactRetentionPolicy(Duration.ofDays(1), Duration.ofDays(2), Duration.ofHours(3), true),
                version, NOW, NOW);
    }
}
