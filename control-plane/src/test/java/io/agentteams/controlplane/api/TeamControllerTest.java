package io.agentteams.controlplane.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.agentteams.controlplane.persistence.TeamMemberRecord;
import io.agentteams.controlplane.persistence.TeamRecord;
import io.agentteams.controlplane.service.TeamService;
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
        when(service.get(teamId)).thenReturn(team);
        when(service.members(teamId)).thenReturn(List.of(member));
        when(service.addMember(eq(teamId), eq(agentId), eq("LEADER"), any())).thenReturn(member);

        mockMvc.perform(get("/api/v1/teams/{teamId}", teamId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("research"));
        mockMvc.perform(get("/api/v1/teams/{teamId}/members", teamId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].agentId").value(agentId.toString()))
                .andExpect(jsonPath("$[0].role").value("LEADER"));
        mockMvc.perform(post("/api/v1/teams/{teamId}/members", teamId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"agentId\":\"" + agentId + "\",\"role\":\"LEADER\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        mockMvc.perform(delete("/api/v1/teams/{teamId}/members/{agentId}", teamId, agentId))
                .andExpect(status().isOk());
        verify(service).removeMember(eq(teamId), eq(agentId), any());
    }

    @Test
    void createsTeamWithDefaultPolicyValues() throws Exception {
        UUID teamId = UUID.randomUUID();
        Instant now = Instant.parse("2026-08-23T00:00:00Z");
        TeamRecord team = new TeamRecord(teamId, "research", "Research", "ACTIVE", now, now, 0);
        when(service.create(eq("research"), eq("Research"), any(), any())).thenReturn(team);

        mockMvc.perform(post("/api/v1/teams")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"research\",\"displayName\":\"Research\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(teamId.toString()));
    }
}
