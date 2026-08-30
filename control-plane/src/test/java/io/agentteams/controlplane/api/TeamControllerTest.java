package io.agentteams.controlplane.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.agentteams.controlplane.persistence.TeamMemberRecord;
import io.agentteams.controlplane.persistence.TeamPolicyRecord;
import io.agentteams.controlplane.persistence.TeamRecord;
import io.agentteams.controlplane.service.TeamService;
import io.agentteams.controlplane.team.TeamDeploymentService;
import io.agentteams.controlplane.team.TeamDeployment;
import io.agentteams.controlplane.team.TeamRevision;
import io.agentteams.controlplane.team.TeamRevisionService;
import io.agentteams.controlplane.team.TeamRevisionStatus;
import java.time.Instant;
import java.util.List;
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
class TeamControllerTest {
    @Mock
    private TeamService service;
    @Mock
    private TeamRevisionService revisions;
    @Mock
    private TeamDeploymentService deployments;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new TeamController(service))
                .setControllerAdvice(new ApiErrorHandler()).build();
    }

    @Test
    void exposesTeamAndMembershipLifecycle() throws Exception {
        UUID teamId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        Instant now = Instant.parse("2026-08-23T00:00:00Z");
        TeamRecord team = new TeamRecord(teamId, "research", "Research", "ACTIVE", now, now, 0);
        TeamMemberRecord member = new TeamMemberRecord(UUID.randomUUID(), teamId, agentId, "LEADER", "ACTIVE",
                now, now, 0);
        TeamPolicyRecord policy = new TeamPolicyRecord(teamId, 3, true, List.of("java"), List.of("gpu"), now, 2);
        when(service.get(teamId)).thenReturn(team);
        when(service.members(teamId)).thenReturn(List.of(member));
        when(service.addMember(eq(teamId), eq(agentId), eq("LEADER"), any(), eq("member-key"))).thenReturn(member);
        when(service.policy(teamId)).thenReturn(policy);
        when(service.updatePolicy(eq(teamId), eq(3), eq(true), eq(List.of("java")), eq(List.of("gpu")), eq(2L), any(),
                eq("policy-key")))
                .thenReturn(policy);

        mockMvc.perform(get("/api/v1/teams/{teamId}", teamId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("research"));
        mockMvc.perform(get("/api/v1/teams/{teamId}/members", teamId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].agentId").value(agentId.toString()))
                .andExpect(jsonPath("$[0].role").value("LEADER"));
        mockMvc.perform(post("/api/v1/teams/{teamId}/members", teamId)
                        .header("Idempotency-Key", "member-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"agentId\":\"" + agentId + "\",\"role\":\"LEADER\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        mockMvc.perform(get("/api/v1/teams/{teamId}/policy", teamId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.maxConcurrentTasks").value(3))
                .andExpect(jsonPath("$.version").value(2));
        mockMvc.perform(put("/api/v1/teams/{teamId}/policy", teamId)
                        .header("Idempotency-Key", "policy-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"maxConcurrentTasks\":3,\"requireHumanApproval\":true,"
                                + "\"allowedRuntimes\":[\"java\"],\"requiredCapabilities\":[\"gpu\"],"
                                + "\"expectedVersion\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requireHumanApproval").value(true));

        mockMvc.perform(delete("/api/v1/teams/{teamId}/members/{agentId}", teamId, agentId)
                        .header("Idempotency-Key", "remove-key"))
                .andExpect(status().isOk());
        verify(service).removeMember(eq(teamId), eq(agentId), any(), eq("remove-key"));
    }

    @Test
    void createsTeamWithDefaultPolicyValues() throws Exception {
        UUID teamId = UUID.randomUUID();
        Instant now = Instant.parse("2026-08-23T00:00:00Z");
        TeamRecord team = new TeamRecord(teamId, "research", "Research", "ACTIVE", now, now, 0);
        when(service.create(eq("team-key"), eq("research"), eq("Research"), any(), any())).thenReturn(team);

        mockMvc.perform(post("/api/v1/teams")
                        .header("Idempotency-Key", "team-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"research\",\"displayName\":\"Research\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(teamId.toString()));
    }

    @Test
    void rejectsTeamMutationsWithoutIdempotencyKey() throws Exception {
        UUID teamId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        mockMvc.perform(post("/api/v1/teams/{teamId}/members", teamId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"agentId\":\"" + agentId + "\",\"role\":\"MEMBER\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(put("/api/v1/teams/{teamId}/policy", teamId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"maxConcurrentTasks\":1,\"expectedVersion\":0}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rollbackPassesTargetRevisionAndExpectedVersionToTheGuardedServiceOverload() throws Exception {
        UUID teamId = UUID.randomUUID();
        UUID leaderAgentId = UUID.randomUUID();
        TeamRevision rollback = new TeamRevision(teamId, 8, leaderAgentId, "{}", "digest",
                TeamRevisionStatus.DRAFT, 5L, "alice", Instant.parse("2026-08-23T00:00:00Z"), 0,
                List.of(leaderAgentId));
        revisions = mock(TeamRevisionService.class, invocation -> {
            if (invocation.getMethod().getName().equals("rollback") && invocation.getArguments().length == 6) {
                return rollback;
            }
            return null;
        });

        mockMvc = MockMvcBuilders.standaloneSetup(new TeamController(service, revisions, deployments))
                .setControllerAdvice(new ApiErrorHandler()).build();

        mockMvc.perform(post("/api/v1/teams/{teamId}/rollback", teamId)
                        .header("Idempotency-Key", "rollback-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetRevision\":5,\"expectedVersion\":3,\"actor\":\"alice\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(8))
                .andExpect(jsonPath("$.rollbackOfRevision").value(5));

        var invocation = mockingDetails(revisions).getInvocations().stream()
                .filter(call -> call.getMethod().getName().equals("rollback"))
                .findFirst().orElseThrow();
        Object[] arguments = invocation.getArguments();
        assertThat(arguments).hasSize(6);
        assertThat(arguments[0]).isEqualTo(teamId);
        assertThat(arguments[1]).isEqualTo(5L);
        assertThat(arguments[2]).isEqualTo(3L);
        assertThat(arguments[3]).isEqualTo("alice");
        assertThat(arguments[4]).isEqualTo("rollback-key");
        assertThat(arguments[5]).isInstanceOf(Instant.class);
    }

    @Test
    void rollbackRejectsMissingTargetRevisionOrExpectedVersionAsBadRequest() throws Exception {
        UUID teamId = UUID.randomUUID();

        mockMvc = MockMvcBuilders.standaloneSetup(new TeamController(service, revisions, deployments))
                .setControllerAdvice(new ApiErrorHandler()).build();

        mockMvc.perform(post("/api/v1/teams/{teamId}/rollback", teamId)
                        .header("Idempotency-Key", "rollback-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetRevision\":5}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/v1/teams/{teamId}/rollback", teamId)
                        .header("Idempotency-Key", "rollback-key-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":3}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(revisions);
    }

    @Test
    void keepsLegacyTeamListAsAnArray() throws Exception {
        Instant now = Instant.parse("2026-08-23T00:00:00Z");
        TeamRecord team = new TeamRecord(UUID.randomUUID(), "research", "Research", "ACTIVE", now, now, 0);
        when(service.list()).thenReturn(List.of(team));

        mockMvc.perform(get("/api/v1/teams"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("research"))
                .andExpect(jsonPath("$.items").doesNotExist());
        verify(service).list();
    }

    @Test
    void exposesManagementTeamPageOnAnExplicitPath() throws Exception {
        Instant now = Instant.parse("2026-08-23T00:00:00Z");
        TeamRecord team = new TeamRecord(UUID.randomUUID(), "research", "Research", "ACTIVE", now, now, 0);
        when(service.list(any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new CursorPage<>(List.of(team), "next", true, now));

        mockMvc.perform(get("/api/v1/teams/page").param("pageSize", "20").param("q", "research")
                .param("status", "ACTIVE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].name").value("research"))
                .andExpect(jsonPath("$.nextCursor").value("next"))
                .andExpect(jsonPath("$.hasMore").value(true));
        verify(service).list(any(), org.mockito.ArgumentMatchers.eq("ACTIVE"),
                org.mockito.ArgumentMatchers.eq("research"));
    }

    @Test
    void serializesDeploymentListAndDetailWithoutInternalMemberConfiguration() throws Exception {
        UUID teamId = UUID.randomUUID();
        UUID deploymentId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        Instant now = Instant.parse("2026-08-23T00:00:00Z");
        TeamDeployment deployment = TeamDeployment.create(deploymentId, teamId, 8,
                List.of(new TeamDeployment.Member(agentId, "{}", "{}", UUID.randomUUID(), "FAILED", "APPLY_FAILED")),
                now, "deploy-key");
        when(deployments.list(teamId)).thenReturn(List.of(deployment));
        when(deployments.find(deploymentId, teamId)).thenReturn(deployment);

        mockMvc = MockMvcBuilders.standaloneSetup(new TeamController(service, revisions, deployments))
                .setControllerAdvice(new ApiErrorHandler()).build();

        mockMvc.perform(get("/api/v1/teams/{teamId}/deployments", teamId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(deploymentId.toString()))
                .andExpect(jsonPath("$[0].teamId").value(teamId.toString()))
                .andExpect(jsonPath("$[0].teamRevision").value(8))
                .andExpect(jsonPath("$[0].status").value("PENDING"))
                .andExpect(jsonPath("$[0].members[0].agentId").value(agentId.toString()))
                .andExpect(jsonPath("$[0].members[0].status").value("FAILED"))
                .andExpect(jsonPath("$[0].members[0].failureCode").value("APPLY_FAILED"))
                .andExpect(jsonPath("$[0].members[0].baseManifest").doesNotExist())
                .andExpect(jsonPath("$[0].members[0].taskOverlay").doesNotExist())
                .andExpect(jsonPath("$[0].members[0].bindingId").doesNotExist());
        mockMvc.perform(get("/api/v1/teams/{teamId}/deployments/{deploymentId}", teamId, deploymentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(deploymentId.toString()))
                .andExpect(jsonPath("$.members[0].agentId").value(agentId.toString()))
                .andExpect(jsonPath("$.members[0].status").value("FAILED"))
                .andExpect(jsonPath("$.members[0].failureCode").value("APPLY_FAILED"))
                .andExpect(jsonPath("$.members[0].baseManifest").doesNotExist())
                .andExpect(jsonPath("$.members[0].taskOverlay").doesNotExist())
                .andExpect(jsonPath("$.members[0].bindingId").doesNotExist());

        verify(deployments).list(teamId);
        verify(deployments).find(deploymentId, teamId);
    }
}
