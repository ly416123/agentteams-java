package io.agentteams.controlplane.team;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TeamCrdParserTest {

    private final TeamCrdParser parser = new TeamCrdParser();

    @Test
    void parsesTeamWithStableIdentityAndNormalizedPolicy() {
        TeamCrdSnapshot snapshot = parser.parse(resource("agentteams", "platform", "42", Map.of(
                "leaderRef", "00000000-0000-0000-0000-000000000001",
                "members", List.of(member("00000000-0000-0000-0000-000000000002", "worker")),
                "policy", Map.of(
                        "maxConcurrentTasks", 4,
                        "requireApproval", false,
                        "allowedRuntimes", List.of("qwenpaw", "qwenpaw"),
                        "requiredCapabilities", List.of("python", "python")))));

        assertThat(snapshot.id()).isEqualTo(TeamCrdParser.stableId("agentteams", "platform"));
        assertThat(snapshot.name()).isEqualTo("agentteams/platform");
        assertThat(snapshot.resourceVersion()).isEqualTo("42");
        assertThat(snapshot.members()).singleElement().satisfies(member -> {
            assertThat(member.agentId()).isEqualTo(UUID.fromString("00000000-0000-0000-0000-000000000002"));
            assertThat(member.role()).isEqualTo("worker");
        });
        assertThat(snapshot.policy().allowedRuntimes()).containsExactly("qwenpaw");
        assertThat(snapshot.policy().requiredCapabilities()).containsExactly("python");
    }

    @Test
    void appliesPolicyDefaultsWhenOptionalArraysAreAbsent() {
        TeamCrdSnapshot snapshot = parser.parse(resource("agentteams", "platform", "7", Map.of(
                "leaderRef", "00000000-0000-0000-0000-000000000001",
                "members", List.of(),
                "policy", Map.of("maxConcurrentTasks", 1, "requireApproval", false))));

        assertThat(snapshot.policy().allowedRuntimes()).isEmpty();
        assertThat(snapshot.policy().requiredCapabilities()).isEmpty();
    }

    @Test
    void rejectsMemberWithoutUuid() {
        assertThatThrownBy(() -> parser.parse(resource("agentteams", "platform", "8", Map.of(
                "leaderRef", "00000000-0000-0000-0000-000000000001",
                "members", List.of(member("not-a-uuid", "worker")),
                "policy", Map.of("maxConcurrentTasks", 1, "requireApproval", false)))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("agentRef");
    }

    private static GenericKubernetesResource resource(String namespace, String name, String resourceVersion,
            Map<String, Object> spec) {
        GenericKubernetesResource resource = new GenericKubernetesResource();
        resource.setApiVersion("agentteams.io/v1alpha1");
        resource.setKind("Team");
        resource.setMetadata(new ObjectMetaBuilder().withNamespace(namespace).withName(name)
                .withResourceVersion(resourceVersion).build());
        resource.setAdditionalProperties(new LinkedHashMap<>(Map.of("spec", spec)));
        return resource;
    }

    private static Map<String, Object> member(String agentRef, String role) {
        return Map.of("agentRef", agentRef, "role", role, "capabilities", List.of("java"));
    }
}
