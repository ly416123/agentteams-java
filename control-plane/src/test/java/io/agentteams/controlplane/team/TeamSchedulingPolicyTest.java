package io.agentteams.controlplane.team;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentteams.controlplane.persistence.AgentRecord;
import io.agentteams.controlplane.persistence.TeamMemberRecord;
import io.agentteams.controlplane.persistence.TeamPolicyRecord;
import io.agentteams.domain.agent.AgentPhase;
import io.agentteams.domain.agent.WorkerType;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TeamSchedulingPolicyTest {
    private static final UUID TEAM_ID = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-09-29T00:00:00Z");

    @Test
    void onlyLeaderMemberCanReceiveLeaderAssignment() {
        AgentRecord agent = agent();
        TeamMemberRecord executor = member("EXECUTOR", agent.id());
        TeamSchedulingPolicy.AssignmentRequest request = new TeamSchedulingPolicy.AssignmentRequest(
                UUID.randomUUID(), List.of(), false, "LEADER");

        TeamSchedulingPolicy.Decision decision = new TeamSchedulingPolicy().evaluate(policy(), executor, agent,
                request, 0);

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).isEqualTo("TEAM_ROLE_MISMATCH");
    }

    @Test
    void executorMemberCanReceiveExecutorAssignment() {
        AgentRecord agent = agent();
        TeamMemberRecord executor = member("EXECUTOR", agent.id());
        TeamSchedulingPolicy.AssignmentRequest request = new TeamSchedulingPolicy.AssignmentRequest(
                UUID.randomUUID(), List.of(), false, "EXECUTOR");

        TeamSchedulingPolicy.Decision decision = new TeamSchedulingPolicy().evaluate(policy(), executor, agent,
                request, 0);

        assertThat(decision.allowed()).isTrue();
    }

    private static TeamPolicyRecord policy() {
        return new TeamPolicyRecord(TEAM_ID, 4, false, List.of("AGENTSCOPE"), List.of(), NOW, 0);
    }

    private static TeamMemberRecord member(String role, UUID agentId) {
        return new TeamMemberRecord(UUID.randomUUID(), TEAM_ID, agentId, role, "ACTIVE", NOW, NOW, 0);
    }

    private static AgentRecord agent() {
        return new AgentRecord(UUID.randomUUID(), "worker", WorkerType.EXECUTOR, AgentPhase.READY,
                "AGENTSCOPE", "{}", "{}", NOW, NOW, 0);
    }
}
