package io.agentteams.controlplane.team;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TeamMembershipChangePolicyTest {
    @Test
    void makesActiveAttemptRemovalExplicit() {
        TeamMembershipChangePolicy policy = new TeamMembershipChangePolicy();
        assertThat(policy.onMemberRemoval(TeamMembershipChangePolicy.RemovalAction.REQUEUE, 2).outcome())
                .isEqualTo(TeamMembershipChangePolicy.Outcome.REQUEUE_ACTIVE_ATTEMPTS);
        assertThat(policy.onMemberRemoval(TeamMembershipChangePolicy.RemovalAction.KEEP_ACTIVE, 1).outcome())
                .isEqualTo(TeamMembershipChangePolicy.Outcome.KEEP_ACTIVE_ATTEMPTS);
        assertThat(policy.onMemberRemoval(TeamMembershipChangePolicy.RemovalAction.CANCEL, 0).outcome())
                .isEqualTo(TeamMembershipChangePolicy.Outcome.REMOVED);
    }
}
