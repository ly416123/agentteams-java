package io.agentteams.controlplane.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.agentteams.controlplane.api.ApiErrorHandler;
import io.agentteams.controlplane.organization.ManagementAuthorizationService;
import io.agentteams.controlplane.security.AuthorizationService;
import io.agentteams.controlplane.security.Principal;
import io.agentteams.controlplane.security.PrincipalContext;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ProjectControllerAuthorizationHttpTest {
    private static final UUID PROJECT_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final Instant NOW = Instant.parse("2026-08-27T00:00:00Z");
    private ProjectRepository projects;
    private ProjectInvitationRepository invitationRepository;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        projects = mock(ProjectRepository.class);
        invitationRepository = mock(ProjectInvitationRepository.class);
        ProjectAuthorizationService service = new ProjectAuthorizationService(projects,
                Clock.fixed(NOW, ZoneOffset.UTC), event -> { });
        ProjectInvitationService invitations = new ProjectInvitationService(invitationRepository,
                Clock.fixed(NOW, ZoneOffset.UTC));
        mvc = MockMvcBuilders.standaloneSetup(new ProjectController(service, invitations))
                .setControllerAdvice(new ApiErrorHandler()).build();
        PrincipalContext.set(new Principal("operator-a",
                new AuthorizationService.Scope("tenant-a", PROJECT_ID.toString(), "team-a"), Set.of()));
        ProjectRecord project = new ProjectRecord(
                PROJECT_ID, "tenant-a", "project-a", "ACTIVE", "owner-a", NOW, NOW, 7);
        when(projects.findProject("tenant-a", PROJECT_ID)).thenReturn(Optional.of(project));
        when(invitationRepository.findProject("tenant-a", PROJECT_ID)).thenReturn(Optional.of(project));
        when(projects.findMembership("tenant-a", PROJECT_ID, "operator-a")).thenReturn(Optional.of(
                ProjectMembershipRecord.create("tenant-a", PROJECT_ID, "operator-a", ProjectRole.OPERATOR, NOW)));
        when(projects.findProjects("tenant-a", "operator-a", null, 51,
                io.agentteams.controlplane.api.CursorPageRequest.Direction.DESC, null, null)).thenReturn(List.of());
    }

    @AfterEach
    void clearPrincipal() {
        PrincipalContext.clear();
    }

    @Test
    void operatorCanListOwnProjectsAndCheckRoleButCannotUseAdministrativeProjectRoutes() throws Exception {
        mvc.perform(get("/api/v1/projects"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray());
        mvc.perform(get("/api/v1/projects/{projectId}/role", PROJECT_ID).param("requiredRole", "ADMIN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("OPERATOR"))
                .andExpect(jsonPath("$.allowed").value(false));

        mvc.perform(post("/api/v1/projects")
                        .contentType(APPLICATION_JSON).content("{\"name\":\"new-project\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/projects/{projectId}/members", PROJECT_ID)
                        .header("Idempotency-Key", "operator-add")
                        .contentType(APPLICATION_JSON).content("{\"subject\":\"alice\",\"role\":\"VIEWER\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/projects/{projectId}/invitations", PROJECT_ID)
                        .header("Idempotency-Key", "operator-invite")
                        .contentType(APPLICATION_JSON).content("{\"subject\":\"alice\",\"role\":\"VIEWER\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/projects/{projectId}/members", PROJECT_ID))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/projects/{projectId}/authorization/roles", PROJECT_ID))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/projects/{projectId}/owner/transfer", PROJECT_ID)
                        .contentType(APPLICATION_JSON)
                        .content("{\"newOwner\":\"alice\",\"expectedProjectVersion\":7}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/projects/{projectId}/members/alice/enable", PROJECT_ID)
                        .contentType(APPLICATION_JSON).content("{\"expectedMembershipVersion\":0}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/projects/{projectId}/members/alice/role", PROJECT_ID)
                        .header("Idempotency-Key", "operator-role")
                        .contentType(APPLICATION_JSON)
                        .content("{\"role\":\"VIEWER\",\"expectedMembershipVersion\":0}"))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/v1/projects/{projectId}/members/alice", PROJECT_ID))
                .andExpect(status().isForbidden());

        verify(projects, never()).insertProject(any());
        verify(projects, never()).insertProjectCreateIdempotency(any());
        verify(projects, never()).insertMembershipIdempotency(any());
        verify(projects, never()).upsertMembership(any());
        verify(projects, never()).deactivateMembership(any(), any(), any(), any());
        verify(projects, never()).updateMembershipStatus(any(), any(), any(), any(), anyLong(), any());
        verify(projects, never()).updateMembershipRole(any(), any(), any(), any(), anyLong(), any());
        verify(projects, never()).transferOwnership(any(), any(), any(), any(), anyLong(), any());
        verify(invitationRepository, never()).insertInvitation(any());
        verify(invitationRepository, never()).insertInvitationIdempotency(any());
    }

    @Test
    void invitationAcceptRouteRejectsAProjectMemberWhoIsNotTheInvitee() throws Exception {
        String token = "invitation-token-for-alice";
        ProjectInvitationRecord invitation = ProjectInvitationRecord.invited(UUID.randomUUID(), "tenant-a",
                PROJECT_ID, "alice", ProjectRole.VIEWER, hash(token), NOW.plusSeconds(3600), "owner-a", NOW);
        when(invitationRepository.findInvitationByTokenHash("tenant-a", hash(token)))
                .thenReturn(Optional.of(invitation));

        mvc.perform(post("/api/v1/projects/{projectId}/invitations/accept", PROJECT_ID)
                        .contentType(APPLICATION_JSON).content("{\"token\":\"" + token + "\"}"))
                .andExpect(status().isForbidden());

        verify(invitationRepository, never()).acceptInvitation(any(), any());
        verify(invitationRepository, never()).upsertMembership(any());
    }

    @Test
    void invitedSubjectCanAcceptTheirInvitationOverHttp() throws Exception {
        String token = "invitation-token-for-alice";
        UUID invitationId = UUID.randomUUID();
        ProjectInvitationRecord invitation = ProjectInvitationRecord.invited(invitationId, "tenant-a",
                PROJECT_ID, "alice", ProjectRole.DEVELOPER, hash(token), NOW.plusSeconds(3600), "owner-a", NOW);
        ProjectMembershipRecord member = ProjectMembershipRecord.create(
                "tenant-a", PROJECT_ID, "alice", ProjectRole.DEVELOPER, NOW);
        PrincipalContext.set(new Principal("alice",
                new AuthorizationService.Scope("tenant-a", PROJECT_ID.toString(), "team-a"), Set.of()));
        when(invitationRepository.findInvitationByTokenHash("tenant-a", hash(token)))
                .thenReturn(Optional.of(invitation));
        when(invitationRepository.acceptInvitation(invitationId, NOW)).thenReturn(true);
        when(invitationRepository.findMembership("tenant-a", PROJECT_ID, "alice"))
                .thenReturn(Optional.of(member));

        mvc.perform(post("/api/v1/projects/{projectId}/invitations/accept", PROJECT_ID)
                        .contentType(APPLICATION_JSON).content("{\"token\":\"" + token + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subject").value("alice"))
                .andExpect(jsonPath("$.role").value("DEVELOPER"));
    }

    @Test
    void ownerCanUseProjectMembershipRoutesButCannotCreatePlatformProjects() throws Exception {
        PrincipalContext.set(new Principal("owner-a",
                new AuthorizationService.Scope("tenant-a", PROJECT_ID.toString(), "team-a"), Set.of()));
        when(projects.findMembership("tenant-a", PROJECT_ID, "owner-a")).thenReturn(Optional.of(
                ProjectMembershipRecord.create("tenant-a", PROJECT_ID, "owner-a", ProjectRole.OWNER, NOW)));
        when(invitationRepository.findMembership("tenant-a", PROJECT_ID, "owner-a")).thenReturn(Optional.of(
                ProjectMembershipRecord.create("tenant-a", PROJECT_ID, "owner-a", ProjectRole.OWNER, NOW)));
        when(projects.findMembership("tenant-a", PROJECT_ID, "alice")).thenReturn(Optional.of(
                ProjectMembershipRecord.create("tenant-a", PROJECT_ID, "alice", ProjectRole.DEVELOPER, NOW)));
        when(projects.findMembershipIncludingInactive("tenant-a", PROJECT_ID, "alice")).thenReturn(Optional.of(
                new ProjectMembershipRecord("tenant-a", PROJECT_ID, "alice", ProjectRole.DEVELOPER,
                        "INACTIVE", NOW, NOW, 2)));
        when(projects.findProjects("tenant-a", "owner-a", null, 51,
                io.agentteams.controlplane.api.CursorPageRequest.Direction.DESC, null, null)).thenReturn(List.of());
        when(projects.findMemberships("tenant-a", PROJECT_ID)).thenReturn(List.of(
                ProjectMembershipRecord.create("tenant-a", PROJECT_ID, "owner-a", ProjectRole.OWNER, NOW)));
        when(projects.insertMembershipIdempotency(any())).thenReturn(true);
        when(projects.updateMembershipStatus("tenant-a", PROJECT_ID, "alice", "ACTIVE", 2, NOW)).thenReturn(true);
        when(projects.transferOwnership("tenant-a", PROJECT_ID, "owner-a", "alice", 7, NOW)).thenReturn(true);
        when(projects.insertRoleChangeIdempotency(any())).thenReturn(true);
        when(projects.updateMembershipRole("tenant-a", PROJECT_ID, "alice", ProjectRole.OPERATOR, 2, NOW))
                .thenReturn(true);
        when(projects.deactivateMembership("tenant-a", PROJECT_ID, "alice", NOW)).thenReturn(true);
        when(invitationRepository.insertInvitationIdempotency(any())).thenReturn(true);

        mvc.perform(get("/api/v1/projects")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/projects/{projectId}/role", PROJECT_ID))
                .andExpect(status().isOk()).andExpect(jsonPath("$.allowed").value(true));
        mvc.perform(get("/api/v1/projects/{projectId}/members", PROJECT_ID))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].role").value("OWNER"));
        mvc.perform(get("/api/v1/projects/{projectId}/authorization/roles", PROJECT_ID))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].role").value("OWNER"));
        mvc.perform(post("/api/v1/projects/{projectId}/members", PROJECT_ID)
                        .header("Idempotency-Key", "owner-add")
                        .contentType(APPLICATION_JSON).content("{\"subject\":\"alice\",\"role\":\"VIEWER\"}"))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/v1/projects/{projectId}/invitations", PROJECT_ID)
                        .header("Idempotency-Key", "owner-invite")
                        .contentType(APPLICATION_JSON).content("{\"subject\":\"bob\",\"role\":\"DEVELOPER\"}"))
                .andExpect(status().isCreated());

        mvc.perform(post("/api/v1/projects/{projectId}/owner/transfer", PROJECT_ID)
                        .contentType(APPLICATION_JSON)
                        .content("{\"newOwner\":\"alice\",\"expectedProjectVersion\":7}"))
                .andExpect(status().isNoContent());
        mvc.perform(post("/api/v1/projects/{projectId}/members/alice/enable", PROJECT_ID)
                        .contentType(APPLICATION_JSON).content("{\"expectedMembershipVersion\":2}"))
                .andExpect(status().isNoContent());
        mvc.perform(post("/api/v1/projects/{projectId}/members/alice/role", PROJECT_ID)
                        .header("Idempotency-Key", "owner-role")
                        .contentType(APPLICATION_JSON)
                        .content("{\"role\":\"OPERATOR\",\"expectedMembershipVersion\":2}"))
                .andExpect(status().isNoContent());
        mvc.perform(delete("/api/v1/projects/{projectId}/members/alice", PROJECT_ID))
                .andExpect(status().isNoContent());
        mvc.perform(post("/api/v1/projects").contentType(APPLICATION_JSON)
                        .content("{\"name\":\"platform-project\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void platformAdministratorCanCreateAProjectThroughTheHttpController() throws Exception {
        ManagementAuthorizationService managementAuthorization = mock(ManagementAuthorizationService.class);
        ProjectAuthorizationService platformService = new ProjectAuthorizationService(projects, event -> { },
                managementAuthorization);
        mvc = MockMvcBuilders.standaloneSetup(new ProjectController(platformService,
                        new ProjectInvitationService(invitationRepository, Clock.fixed(NOW, ZoneOffset.UTC))))
                .setControllerAdvice(new ApiErrorHandler()).build();
        PrincipalContext.set(new Principal("platform-admin",
                new AuthorizationService.Scope("tenant-a", PROJECT_ID.toString(), "team-a"), Set.of()));
        when(projects.insertProjectCreateIdempotency(any())).thenReturn(true);

        mvc.perform(post("/api/v1/projects")
                        .header("Idempotency-Key", "platform-create")
                        .contentType(APPLICATION_JSON).content("{\"name\":\"new-project\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tenantId").value("tenant-a"))
                .andExpect(jsonPath("$.createdBy").value("platform-admin"));

        verify(managementAuthorization).requirePlatformAdmin();
        verify(projects).insertProject(any());
    }

    @Test
    void nonMemberCannotReadOrChangeProjectMemberships() throws Exception {
        PrincipalContext.set(new Principal("outsider",
                new AuthorizationService.Scope("tenant-a", PROJECT_ID.toString(), "team-a"), Set.of()));

        mvc.perform(get("/api/v1/projects/{projectId}/members", PROJECT_ID))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/projects/{projectId}/role", PROJECT_ID))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/projects/{projectId}/members", PROJECT_ID)
                        .header("Idempotency-Key", "outsider-add")
                        .contentType(APPLICATION_JSON).content("{\"subject\":\"alice\",\"role\":\"VIEWER\"}"))
                .andExpect(status().isForbidden());

        verify(projects, never()).findMemberships("tenant-a", PROJECT_ID);
        verify(projects, never()).insertMembershipIdempotency(any());
    }

    @Test
    void projectFromAnotherTenantIsNotDisclosed() throws Exception {
        PrincipalContext.set(new Principal("outsider",
                new AuthorizationService.Scope("tenant-b", PROJECT_ID.toString(), "team-b"), Set.of()));

        mvc.perform(get("/api/v1/projects/{projectId}/members", PROJECT_ID))
                .andExpect(status().isNotFound());

        verify(projects, never()).findMembership(any(), any(), any());
        verify(projects, never()).findMemberships(any(), any());
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new AssertionError(error);
        }
    }
}
