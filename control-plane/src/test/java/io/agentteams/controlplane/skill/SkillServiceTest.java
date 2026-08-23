package io.agentteams.controlplane.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentteams.controlplane.service.IdempotencyService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SkillServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-23T00:00:00Z");

    @Mock
    private SkillRepository repository;

    private SkillService service;

    @BeforeEach
    void setUp() {
        service = new SkillService(repository, new IdempotencyService(), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void createsDraftSkillWithNormalizedVisibilityAndStableTimestamp() {
        when(repository.createSkill(any(SkillRecord.class), eq("skill-key"), any()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        SkillRecord skill = service.createSkill("skill-key",
                new SkillService.SkillInput(" code-review ", "Code Review", " review code ", "public"));

        assertThat(skill.name()).isEqualTo("code-review");
        assertThat(skill.visibility()).isEqualTo("PUBLIC");
        assertThat(skill.lifecycle()).isEqualTo("DRAFT");
        assertThat(skill.createdAt()).isEqualTo(NOW);
        verify(repository).createSkill(any(SkillRecord.class), eq("skill-key"), any());
    }

    @Test
    void createsVersionWithInheritedVisibilityAndManifest() {
        UUID skillId = UUID.randomUUID();
        SkillRecord skill = new SkillRecord(skillId, "code-review", "Code Review", "", "PRIVATE", "DRAFT",
                NOW, NOW, 0);
        when(repository.findById(skillId)).thenReturn(java.util.Optional.of(skill));
        when(repository.createVersion(any(SkillVersionRecord.class), eq("version-key"), any()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        SkillVersionRecord version = service.createVersion(skillId, "version-key",
                new SkillService.VersionInput("1.0.0", "sha256:abc", "{\"name\":\"code-review\"}", null));

        assertThat(version.skillId()).isEqualTo(skillId);
        assertThat(version.visibility()).isEqualTo("PRIVATE");
        assertThat(version.lifecycle()).isEqualTo("DRAFT");
        assertThat(version.manifestJson()).isEqualTo("{\"name\":\"code-review\"}");
    }

    @Test
    void rejectsNonObjectManifestBeforePersistence() {
        UUID skillId = UUID.randomUUID();
        when(repository.findById(skillId)).thenReturn(java.util.Optional.of(new SkillRecord(skillId, "skill",
                "Skill", "", "PRIVATE", "DRAFT", NOW, NOW, 0)));

        assertThatThrownBy(() -> service.createVersion(skillId, "version-key",
                new SkillService.VersionInput("1.0.0", "sha256:abc", "[]", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("manifest must be a JSON object");

        verify(repository, never()).createVersion(any(), any(), any());
    }

    @Test
    void delegatesPublishAndDisableToTheRepository() {
        UUID skillId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        SkillRecord skill = new SkillRecord(skillId, "skill", "Skill", "", "PRIVATE", "DRAFT", NOW, NOW, 0);
        SkillVersionRecord version = new SkillVersionRecord(versionId, skillId, "1.0.0", "sha256:abc", "{}",
                "PRIVATE", "PUBLISHED", NOW, NOW, 1);
        when(repository.findById(skillId)).thenReturn(java.util.Optional.of(skill));
        when(repository.publish(eq(skillId), eq(versionId), eq(NOW))).thenReturn(version);
        when(repository.disable(eq(skillId), eq(versionId), eq(NOW))).thenReturn(version);

        assertThat(service.publish(skillId, versionId)).isSameAs(version);
        assertThat(service.disable(skillId, versionId)).isSameAs(version);
        verify(repository).publish(skillId, versionId, NOW);
        verify(repository).disable(skillId, versionId, NOW);
    }
}
