package io.agentteams.controlplane.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import io.agentteams.application.api.SandboxPolicy;
import io.agentteams.application.api.SandboxProfile;
import io.agentteams.controlplane.security.ExecutionContext;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SkillCapabilityPolicyParserTest {

    @Test
    void parsesCapabilityDeclarationAndUsesSafeDefaultsForOmittedFields() {
        SkillCapabilityPolicyParser parser = new SkillCapabilityPolicyParser();

        var policy = parser.parse("""
                {"capabilities":{"profile":"ISOLATED","cpuMillicores":750,"memoryMiB":768,
                "ephemeralStorageMiB":2048,"ttlSeconds":600,"networkPolicy":"RESTRICTED",
                "allowedMcp":["github"],"allowedDomains":["api.github.com"]}}
                """);

        assertThat(policy.profile()).isEqualTo(SandboxProfile.ISOLATED);
        assertThat(policy.cpuMillicores()).isEqualTo(750);
        assertThat(policy.memoryMiB()).isEqualTo(768);
        assertThat(policy.ephemeralStorageMiB()).isEqualTo(2048);
        assertThat(policy.ttl()).isEqualTo(Duration.ofMinutes(10));
        assertThat(policy.networkPolicy()).isEqualTo(SandboxPolicy.NetworkPolicy.RESTRICTED);
        assertThat(policy.allowedMcp()).containsExactly("github");
        assertThat(policy.allowedDomains()).containsExactly("api.github.com");
        assertThat(policy.allowSecretReferences()).isFalse();
    }

    @Test
    void rejectsUnknownOrWronglyTypedCapabilityFields() {
        SkillCapabilityPolicyParser parser = new SkillCapabilityPolicyParser();

        assertThatThrownBy(() -> parser.parse("{\"capabilities\":{\"cpuMillicores\":\"750\"}}"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("cpuMillicores");
        assertThatThrownBy(() -> parser.parse("{\"capabilities\":{\"secret\":true}}"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("unknown");
    }

    @Test
    void bindingFromManifestRechecksCapabilitiesAgainstTheEffectivePolicy() {
        SkillRepository skills = org.mockito.Mockito.mock(SkillRepository.class);
        UUID skillId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        String digest = "sha256:skill-v1";
        when(skills.findVersionById(versionId)).thenReturn(Optional.of(new SkillVersionRecord(versionId, skillId,
                "1.0.0", digest, "{}", "PRIVATE", "PUBLISHED", Instant.parse("2026-08-31T00:00:00Z"),
                Instant.parse("2026-08-31T00:00:00Z"), 0)));
        SkillBindingService service = new SkillBindingService(skills, new SkillCapabilityPolicyParserTestBindings());

        assertThatThrownBy(() -> service.bind(new ExecutionContext("org-1", "tenant-1", "project-1", "team-1", "user-1"),
                "project-1", "team-1", skillId, versionId, digest,
                "{\"capabilities\":{\"networkPolicy\":\"OPEN\"}}", SandboxPolicy.defaults(), "user-1"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("capabilities");
    }

    private static final class SkillCapabilityPolicyParserTestBindings implements SkillBindingRepository {
        @Override
        public SkillBindingRecord bind(SkillBindingRecord record) {
            return record;
        }

        @Override
        public List<SkillBindingRecord> find(String organizationId, String tenantId, String projectId, String teamId) {
            return List.of();
        }
    }
}
