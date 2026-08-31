package io.agentteams.controlplane.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import java.util.List;
import io.agentteams.controlplane.team.TeamResourceBinding;
import io.agentteams.controlplane.team.TeamResourceType;
import org.junit.jupiter.api.Test;

class EffectiveConfigComposerTest {
    private static final UUID AGENT_ID = UUID.randomUUID();
    private static final UUID TEAM_ID = UUID.randomUUID();
    private static final UUID TASK_ID = UUID.randomUUID();

    @Test
    void equivalentInputOrderingProducesOneCanonicalManifestAndDigest() {
        EffectiveConfigComposer composer = new EffectiveConfigComposer();
        EffectiveConfigRequest first = request(
                "{\"z\":1,\"skillRefs\":[\"search\",\"search\"],\"nested\":{\"b\":2,\"a\":1}}",
                "{\"skillRefs\":[\"review\",\"search\"]}", "{\"skillRefs\":[\"review\"]}");
        EffectiveConfigRequest second = request(
                "{\"nested\":{\"a\":1,\"b\":2},\"skillRefs\":[\"search\"] ,\"z\":1}",
                "{\"skillRefs\":[\"search\",\"review\"]}", "{\"skillRefs\":[\"review\"]}");

        EffectiveConfig left = composer.compose(first);
        EffectiveConfig right = composer.compose(second);

        assertThat(left.canonicalManifest()).isEqualTo(right.canonicalManifest());
        assertThat(left.sha256()).isEqualTo(right.sha256());
        assertThat(left.canonicalManifest()).contains("\"skillRefs\":[\"review\",\"search\"]");
        assertThat(left.provenance().teamRevision()).isEqualTo(7);
    }

    @Test
    void permissionsMayOnlyBeRestrictedAndSandboxMayOnlyBecomeMoreSecure() {
        EffectiveConfigComposer composer = new EffectiveConfigComposer();

        EffectiveConfig result = composer.compose(request(
                "{\"permissions\":[\"task:read\",\"task:write\"],\"sandboxProfile\":\"ISOLATED\"}",
                "{\"permissions\":[\"task:read\"],\"sandboxProfile\":\"HARDENED\"}",
                "{\"permissions\":[],\"sandboxProfile\":\"NONE\"}"));

        assertThat(result.canonicalManifest()).contains("\"permissions\":[]");
        assertThat(result.canonicalManifest()).contains("\"sandboxProfile\":\"HARDENED\"");
    }

    @Test
    void rejectsPermissionEscalation() {
        EffectiveConfigComposer composer = new EffectiveConfigComposer();

        assertThatThrownBy(() -> composer.compose(request(
                "{\"permissions\":[\"task:read\"]}",
                "{\"permissions\":[\"task:read\",\"task:write\"]}", "{}")))
                .isInstanceOf(EffectiveConfigConflictException.class)
                .extracting(error -> ((EffectiveConfigConflictException) error).code())
                .isEqualTo("EFFECTIVE_CONFIG_ESCALATION_REJECTED");
    }

    @Test
    void rejectsSecurityFieldsWithInvalidTypesAndRequiresCompleteProvenance() {
        EffectiveConfigComposer composer = new EffectiveConfigComposer();
        assertThatThrownBy(() -> composer.compose(request(
                "{\"permissions\":{}}", "{}", "{}")))
                .isInstanceOf(EffectiveConfigConflictException.class);
        assertThatThrownBy(() -> composer.compose(request(
                "{}", "{\"sandboxProfile\":false}", "{}")))
                .isInstanceOf(EffectiveConfigConflictException.class);

        EffectiveConfigRequest complete = new EffectiveConfigRequest(UUID.randomUUID(), AGENT_ID, TEAM_ID, 7,
                TASK_ID, List.of("sha256:skill"), "{}", "{}", "{}");
        EffectiveConfig result = composer.compose(complete);
        assertThat(result.provenance().agentBaseSnapshotId()).isEqualTo(complete.agentBaseSnapshotId());
        assertThat(result.provenance().bindingDigests()).containsExactly("sha256:skill");
        assertThat(result.provenance().taskId()).isEqualTo(TASK_ID);
    }

    @Test
    void canonicalizesResourceBindingsAndRetainsRevisionAndDigestInProvenance() {
        UUID modelId = UUID.randomUUID();
        TeamResourceBinding model = new TeamResourceBinding(TeamResourceType.MODEL, modelId, "9", "sha256:model");
        TeamResourceBinding skill = new TeamResourceBinding(TeamResourceType.SKILL, UUID.randomUUID(), "3",
                "sha256:skill");
        EffectiveConfig first = new EffectiveConfigComposer().compose(new EffectiveConfigRequest(
                UUID.randomUUID(), AGENT_ID, TEAM_ID, 7, TASK_ID, List.of(), List.of(skill, model), "{}", "{}", "{}"));
        EffectiveConfig second = new EffectiveConfigComposer().compose(new EffectiveConfigRequest(
                first.provenance().agentBaseSnapshotId(), AGENT_ID, TEAM_ID, 7, TASK_ID, List.of(), List.of(model, skill),
                "{}", "{}", "{}"));

        assertThat(first.sha256()).isEqualTo(second.sha256());
        assertThat(first.provenance().resourceBindings()).containsExactly(model, skill);
    }

    @Test
    void rejectsConflictingBindingDigestForSameRevision() {
        UUID resourceId = UUID.randomUUID();
        List<TeamResourceBinding> bindings = List.of(
                new TeamResourceBinding(TeamResourceType.MCP_SERVER, resourceId, "4", "sha256:old"),
                new TeamResourceBinding(TeamResourceType.MCP_SERVER, resourceId, "4", "sha256:new"));

        assertThatThrownBy(() -> new EffectiveConfigComposer().compose(new EffectiveConfigRequest(
                UUID.randomUUID(), AGENT_ID, TEAM_ID, 7, TASK_ID, List.of(), bindings, "{}", "{}", "{}")))
                .isInstanceOf(EffectiveConfigConflictException.class)
                .extracting(error -> ((EffectiveConfigConflictException) error).code())
                .isEqualTo("EFFECTIVE_CONFIG_BINDING_CONFLICT");
    }

    private static EffectiveConfigRequest request(String base, String team, String task) {
        return new EffectiveConfigRequest(AGENT_ID, TEAM_ID, 7, TASK_ID, base, team, task);
    }
}
