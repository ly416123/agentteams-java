package io.agentteams.operator;

import static org.assertj.core.api.Assertions.assertThat;

import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import java.util.List;
import org.junit.jupiter.api.Test;

class TeamResourceFactoryTest {
    @Test
    void projectsTeamPolicyAndMembershipIntoNamespacedConfig() {
        Team team = new Team();
        team.setMetadata(new ObjectMetaBuilder().withName("platform").withNamespace("agentteams").build());
        team.setSpec(new TeamSpec("agent-lead", List.of(new TeamMember("agent-worker", "worker", List.of("java"))),
                new TeamPolicy(4, true), "workspace-main", "room-main"));

        var config = TeamResourceFactory.configMap(team);

        assertThat(config.getMetadata().getName()).isEqualTo("platform-config");
        assertThat(config.getData().get("team.json")).contains("agent-lead", "workspace-main");
    }
}
