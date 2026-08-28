package io.agentteams.operator;

import static org.assertj.core.api.Assertions.assertThat;

import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import java.util.List;
import org.junit.jupiter.api.Test;

class TeamReconcilerBehaviorTest {

    @Test
    void reconcileProjectsOwnedTeamConfigAndAppliedStatus() {
        ReconcilerBehaviorTestSupport fake = new ReconcilerBehaviorTestSupport();
        Team team = team();

        var control = new TeamReconciler(fake.client()).reconcile(team, null);

        assertThat(fake.configMap).isNotNull();
        assertThat(fake.configMap.getMetadata().getName()).isEqualTo("team-a-config");
        assertThat(fake.configMap.getMetadata().getOwnerReferences()).singleElement()
                .satisfies(owner -> assertThat(owner.getUid()).isEqualTo("team-uid"));
        assertThat(fake.configMap.getData().get("team.json")).contains("leader-a", "agent-a");
        assertThat(team.getStatus().getPhase()).isEqualTo("Applied");
        assertThat(team.getStatus().getObservedGeneration()).isEqualTo(3L);
        assertThat(control.isPatchStatus()).isTrue();
    }

    @Test
    void repeatedReconcileRepairsTamperedTeamConfigIdempotently() {
        ReconcilerBehaviorTestSupport fake = new ReconcilerBehaviorTestSupport();
        Team team = team();
        new TeamReconciler(fake.client()).reconcile(team, null);
        fake.configMap.getData().put("team.json", "{\"leaderRef\":\"tampered\"}");

        new TeamReconciler(fake.client()).reconcile(team, null);

        assertThat(fake.configMapWrites).isEqualTo(2);
        assertThat(fake.configMap.getData().get("team.json")).contains("leader-a").doesNotContain("tampered");
    }

    private static Team team() {
        Team team = new Team();
        team.setMetadata(new ObjectMetaBuilder().withName("team-a").withNamespace("agentteams")
                .withGeneration(3L).withUid("team-uid").build());
        team.setSpec(new TeamSpec("leader-a", List.of(new TeamMember("agent-a", "worker", List.of("chat"))),
                new TeamPolicy(2, true), "workspace-a", "channel-a"));
        return team;
    }
}
