package io.agentteams.controlplane.team;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentteams.controlplane.persistence.AgentRecord;
import io.agentteams.controlplane.persistence.TeamMemberRecord;
import io.agentteams.controlplane.persistence.TeamPolicyRecord;
import io.agentteams.domain.agent.AgentPhase;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TeamSchedulingPolicyTest {
    private static final Instant NOW = Instant.parse("2026-08-16T00:00:00Z");
    private static final UUID TEAM = UUID.randomUUID();
    private static final UUID AGENT = UUID.randomUUID();

    @Test
    void acceptsReadyMemberWhenCapabilitiesRuntimeAndApprovalMatch() {
        TeamSchedulingPolicy.Decision decision = new TeamSchedulingPolicy().evaluate(
                new TeamPolicyRecord(TEAM, 2, true, List.of("qwenpaw"), List.of("python"), NOW, 0),
                member("ACTIVE"), agent(AgentPhase.READY, "qwenpaw", "{\"python\":true}"),
                new TeamSchedulingPolicy.AssignmentRequest(UUID.randomUUID(), List.of(), true), 0);
        assertThat(decision).isEqualTo(new TeamSchedulingPolicy.Decision(true, "OK"));
    }

    @Test
    void rejectsWhenConcurrencyOrApprovalWouldBeViolated() {
        TeamSchedulingPolicy policy = new TeamSchedulingPolicy();
        TeamPolicyRecord record = new TeamPolicyRecord(TEAM, 1, true, List.of(), List.of(), NOW, 0);
        var request = new TeamSchedulingPolicy.AssignmentRequest(UUID.randomUUID(), List.of(), false);
        assertThat(policy.evaluate(record, member("ACTIVE"), agent(AgentPhase.READY, "openclaw", "{}"), request, 1).reason())
                .isEqualTo("TEAM_CONCURRENCY_LIMIT");
        assertThat(policy.evaluate(record, member("ACTIVE"), agent(AgentPhase.READY, "openclaw", "{}"), request, 0).reason())
                .isEqualTo("APPROVAL_REQUIRED");
    }

    @Test
    void rejectsOfflineMemberAndMissingCapability() {
        var policy = new TeamSchedulingPolicy();
        var record = new TeamPolicyRecord(TEAM, 1, false, List.of(), List.of("gpu"), NOW, 0);
        var request = new TeamSchedulingPolicy.AssignmentRequest(UUID.randomUUID(), List.of(), true);
        assertThat(policy.evaluate(record, member("ACTIVE"), agent(AgentPhase.OFFLINE, "openclaw", "{\"gpu\":true}"), request, 0).reason())
                .isEqualTo("AGENT_NOT_READY");
        assertThat(policy.evaluate(record, member("ACTIVE"), agent(AgentPhase.READY, "openclaw", "{}"), request, 0).reason())
                .isEqualTo("CAPABILITY_MISMATCH");
    }

    private static TeamMemberRecord member(String status) {
        return new TeamMemberRecord(UUID.randomUUID(), TEAM, AGENT, "worker", status, NOW, NOW, 0);
    }

    private static AgentRecord agent(AgentPhase phase, String runtime, String capabilities) {
        return AgentRecord.create(AGENT, "agent", phase, runtime, capabilities, NOW);
    }
}
