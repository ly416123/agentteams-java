package io.agentteams.controlplane.quality;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.agentteams.controlplane.api.ApiErrorHandler;
import io.agentteams.controlplane.project.ProjectMembershipRecord;
import io.agentteams.controlplane.project.ProjectRecord;
import io.agentteams.controlplane.project.ProjectRepository;
import io.agentteams.controlplane.project.ProjectRole;
import io.agentteams.controlplane.security.AuthorizationService;
import io.agentteams.controlplane.security.ExecutionContext;
import io.agentteams.controlplane.security.Principal;
import io.agentteams.controlplane.security.PrincipalContext;
import io.agentteams.controlplane.security.ProjectScopeResolver;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
final class ArtifactQualityControllerTest {
    private static final UUID PROJECT = UUID.fromString("ef5916eb-14d9-4e75-9e9c-041c6b5fb447");
    @Mock private ProjectRepository projects;
    @Mock private ArtifactQualityEventRepository events;
    @Mock private ArtifactQualityQueryContextResolver queryContexts;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        Instant now = Instant.parse("2026-10-13T00:00:00Z");
        ProjectRecord project = ProjectRecord.create(PROJECT, "tenant-a", "project-a", "alice", now);
        org.mockito.Mockito.lenient().when(projects.findProjectByName("tenant-a", "project-a")).thenReturn(Optional.of(project));
        org.mockito.Mockito.lenient().when(projects.findProject("tenant-a", PROJECT)).thenReturn(Optional.of(project));
        org.mockito.Mockito.lenient().when(projects.findMembership("tenant-a", PROJECT, "alice")).thenReturn(Optional.of(
                ProjectMembershipRecord.create("tenant-a", PROJECT, "alice", ProjectRole.DEVELOPER, now)));
        org.mockito.Mockito.lenient().when(queryContexts.resolve(any(Principal.class), any()))
                .thenAnswer(invocation -> {
                    Principal principal = invocation.getArgument(0);
                    return new ExecutionContext("org-a", "canonical-tenant-id", PROJECT.toString(),
                            principal.scope().team(), principal.subject());
                });
        mvc = MockMvcBuilders.standaloneSetup(new ArtifactQualityController(events, projects, queryContexts))
                .setControllerAdvice(new ApiErrorHandler()).build();
        PrincipalContext.set(new Principal("alice", new AuthorizationService.Scope("tenant-a", "project-a", "team-a"), Set.of()));
    }

    @AfterEach
    void tearDown() { PrincipalContext.clear(); }

    @Test
    void returnsScopedFilteredEventsWithoutLocalSourcePaths() throws Exception {
        ArtifactQualityEvent event = new ArtifactQualityEvent(UUID.randomUUID(), "org-a", "tenant-a", PROJECT,
                UUID.randomUUID(), UUID.randomUUID(), 0, "QUALITY_CHECK", "report.pdf", null, null, null,
                "pdf_missing", "artifact_pdf", null, "FAILED", null, null, "PDF artifact missing", "HISTORICAL",
                "history:sha256:" + "a".repeat(64) + "#case:report:assertion:0", "HIGH", "history-key",
                Instant.parse("2026-10-13T00:00:00Z"));
        when(events.find(any(), any())).thenReturn(new ArtifactQualityEventFilter.Page(List.of(event), 1, 0, 20));

        mvc.perform(get("/api/v1/artifact-quality/events").param("status", "FAILED")
                        .param("issueType", "pdf_missing").param("pageSize", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.events[0].status").value("FAILED"))
                .andExpect(jsonPath("$.events[0].sourceType").value("HISTORICAL"))
                .andExpect(jsonPath("$.events[0].confidence").value("HIGH"))
                .andExpect(jsonPath("$.events[0].sourceReference").doesNotExist())
                .andExpect(jsonPath("$.events[0].sourceRef").doesNotExist());
        verify(events).find(any(ExecutionContext.class), eq(new ArtifactQualityEventFilter(
                PROJECT, null, null, "FAILED", "pdf_missing", null, null, 0, 20)));
    }

    @Test
    void rejectsRequestsForProjectsWithoutMembership() throws Exception {
        UUID other = UUID.randomUUID();
        Instant now = Instant.parse("2026-10-13T00:00:00Z");
        when(projects.findProject("tenant-a", other)).thenReturn(Optional.of(
                ProjectRecord.create(other, "tenant-a", "project-b", "bob", now)));
        when(projects.findMembership("tenant-a", other, "alice")).thenReturn(Optional.empty());

        mvc.perform(get("/api/v1/artifact-quality/events").param("projectId", other.toString()))
                .andExpect(status().isForbidden());
    }
}
