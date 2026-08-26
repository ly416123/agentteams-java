package io.agentteams.controlplane.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
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

    private static EffectiveConfigRequest request(String base, String team, String task) {
        return new EffectiveConfigRequest(AGENT_ID, TEAM_ID, 7, TASK_ID, base, team, task);
    }
}
