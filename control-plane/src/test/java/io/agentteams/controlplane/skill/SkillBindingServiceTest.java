package io.agentteams.controlplane.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import io.agentteams.controlplane.security.ExecutionContext;
import io.agentteams.application.api.SandboxPolicy;
import io.agentteams.application.api.SandboxProfile;
import io.agentteams.application.api.SkillCapabilityPolicy;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SkillBindingServiceTest {
    private static final ExecutionContext CONTEXT = new ExecutionContext("org-1", "tenant-1", "project-1", "team-1", "user-1");
    private static final UUID SKILL_ID = UUID.randomUUID();
    private static final UUID VERSION_ID = UUID.randomUUID();
    private static final String DIGEST = "sha256:skill-v1";

    @Test
    void bindsOnlyThePublishedVersionAndKeepsTheDigestSnapshot() {
        SkillRepository skills = org.mockito.Mockito.mock(SkillRepository.class);
        SkillBindingRepository bindings = new InMemoryBindingRepository();
        when(skills.findVersionById(VERSION_ID)).thenReturn(Optional.of(version("PUBLISHED")));
        SkillBindingService service = new SkillBindingService(skills, bindings);

        SkillBindingRecord result = service.bind(CONTEXT, "project-1", "team-1", SKILL_ID, VERSION_ID, DIGEST, "user-1");

        assertThat(result.organizationId()).isEqualTo("org-1");
        assertThat(result.tenantId()).isEqualTo("tenant-1");
        assertThat(result.digest()).isEqualTo(DIGEST);
        assertThat(service.list(CONTEXT, "project-1", "team-1")).containsExactly(result);
    }

    @Test
    void rejectsWrongSkillVersionDigestAndUnpublishedVersion() {
        SkillRepository skills = org.mockito.Mockito.mock(SkillRepository.class);
        when(skills.findVersionById(VERSION_ID)).thenReturn(Optional.of(version("DRAFT")));
        SkillBindingService service = new SkillBindingService(skills, new InMemoryBindingRepository());

        assertThatThrownBy(() -> service.bind(CONTEXT, "project-1", "team-1", SKILL_ID, VERSION_ID, DIGEST, "user-1"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("published");

        when(skills.findVersionById(VERSION_ID)).thenReturn(Optional.of(version("PUBLISHED")));
        assertThatThrownBy(() -> service.bind(CONTEXT, "project-1", "team-1", SKILL_ID, VERSION_ID,
                "sha256:other", "user-1")).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("digest");
    }

    @Test
    void rejectsSkillCapabilitiesThatWidenTheEffectiveSandbox() {
        SkillRepository skills = org.mockito.Mockito.mock(SkillRepository.class);
        when(skills.findVersionById(VERSION_ID)).thenReturn(Optional.of(version("PUBLISHED")));
        SkillBindingService service = new SkillBindingService(skills, new InMemoryBindingRepository());
        SkillCapabilityPolicy capability = new SkillCapabilityPolicy(SandboxProfile.ISOLATED, 500, 512, 1024,
                Duration.ofMinutes(30), Set.of("private-mcp"), Set.of(), false);

        assertThatThrownBy(() -> service.bind(CONTEXT, "project-1", "team-1", SKILL_ID, VERSION_ID, DIGEST,
                capability, io.agentteams.application.api.SandboxPolicy.defaults(), "user-1"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("capabilities");
    }

    private static SkillVersionRecord version(String lifecycle) {
        return new SkillVersionRecord(VERSION_ID, SKILL_ID, "1.0.0", DIGEST, "{}", "PRIVATE", lifecycle,
                Instant.parse("2026-08-31T00:00:00Z"), Instant.parse("2026-08-31T00:00:00Z"), 0);
    }

    private static final class InMemoryBindingRepository implements SkillBindingRepository {
        private final java.util.List<SkillBindingRecord> records = new java.util.ArrayList<>();

        @Override
        public SkillBindingRecord bind(SkillBindingRecord record) {
            records.add(record);
            return record;
        }

        @Override
        public List<SkillBindingRecord> find(String organizationId, String tenantId, String projectId, String teamId) {
            return records.stream().filter(record -> record.organizationId().equals(organizationId)
                    && record.tenantId().equals(tenantId) && java.util.Objects.equals(record.projectId(), projectId)
                    && java.util.Objects.equals(record.teamId(), teamId)).toList();
        }
    }
}
