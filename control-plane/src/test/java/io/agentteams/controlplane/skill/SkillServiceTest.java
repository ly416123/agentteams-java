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
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
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
        service = new SkillService(repository, new IdempotencyService(), Clock.fixed(NOW, ZoneOffset.UTC),
                new SkillPackageValidator());
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
                new SkillService.VersionInput("1.0.0", validDigest(),
                        "{\"name\":\"code-review\",\"description\":\"Reviews code\","
                                + "\"entry\":\"SKILL.md\",\"sizeBytes\":128}", null));

        assertThat(version.skillId()).isEqualTo(skillId);
        assertThat(version.visibility()).isEqualTo("PRIVATE");
        assertThat(version.lifecycle()).isEqualTo("DRAFT");
        assertThat(version.manifestJson()).contains("\"entry\":\"SKILL.md\"");
    }

    @Test
    void createsVersionWithInheritedOrganizationAndTenantScope() {
        UUID skillId = UUID.randomUUID();
        SkillRecord skill = new SkillRecord(skillId, "code-review", "Code Review", "", "PRIVATE", "DRAFT",
                NOW, NOW, 0, "org-1", "tenant-1");
        when(repository.findById(skillId)).thenReturn(Optional.of(skill));
        when(repository.createVersion(any(SkillVersionRecord.class), eq("version-key"), any()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        SkillVersionRecord version = service.createVersion(skillId, "version-key",
                new SkillService.VersionInput("1.0.0", validDigest(),
                        "{\"name\":\"code-review\",\"description\":\"Reviews code\","
                                + "\"entry\":\"SKILL.md\",\"sizeBytes\":128}", null));

        assertThat(version.organizationId()).isEqualTo("org-1");
        assertThat(version.tenantId()).isEqualTo("tenant-1");
    }

    @Test
    void rejectsNonObjectManifestBeforePersistence() {
        UUID skillId = UUID.randomUUID();
        when(repository.findById(skillId)).thenReturn(java.util.Optional.of(new SkillRecord(skillId, "skill",
                "Skill", "", "PRIVATE", "DRAFT", NOW, NOW, 0)));

        assertThatThrownBy(() -> service.createVersion(skillId, "version-key",
                new SkillService.VersionInput("1.0.0", validDigest(), "[]", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("manifest must be a JSON object");

        verify(repository, never()).createVersion(any(), any(), any());
    }

    @Test
    void delegatesPublishAndDisableToTheRepository() {
        UUID skillId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        SkillRecord skill = new SkillRecord(skillId, "skill", "Skill", "", "PRIVATE", "DRAFT", NOW, NOW, 0);
        SkillVersionRecord version = new SkillVersionRecord(versionId, skillId, "1.0.0", validDigest(),
                "{\"name\":\"skill\",\"description\":\"desc\",\"entry\":\"SKILL.md\",\"sizeBytes\":1}",
                "PRIVATE", "PUBLISHED", NOW, NOW, 1, "PASSED", "APPROVED",
                SkillPackageStoragePaths.versionPackage(skillId, versionId), 1L,
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef", "COMPLETED");
        when(repository.findById(skillId)).thenReturn(java.util.Optional.of(skill));
        when(repository.findVersionById(versionId)).thenReturn(java.util.Optional.of(version));
        when(repository.markSecurityScan(eq(skillId), eq(versionId), eq("PASSED"), eq(NOW))).thenReturn(version);
        when(repository.publish(eq(skillId), eq(versionId), eq(NOW))).thenReturn(version);
        when(repository.disable(eq(skillId), eq(versionId), eq(NOW))).thenReturn(version);

        assertThat(service.publish(skillId, versionId)).isSameAs(version);
        assertThat(service.disable(skillId, versionId)).isSameAs(version);
        verify(repository).publish(skillId, versionId, NOW);
        verify(repository).disable(skillId, versionId, NOW);
    }

    @Test
    void rejectsInvalidPackageBeforeVersionPersistence() {
        UUID skillId = UUID.randomUUID();
        when(repository.findById(skillId)).thenReturn(java.util.Optional.of(new SkillRecord(skillId, "skill",
                "Skill", "", "PRIVATE", "DRAFT", NOW, NOW, 0)));

        assertThatThrownBy(() -> service.createVersion(skillId, "version-key",
                new SkillService.VersionInput("1.0.0", "sha256:abc", "{\"name\":\"skill\"}", null)))
                .isInstanceOf(SkillPackageValidationException.class)
                .hasMessage("digest must use sha256:<64 hexadecimal characters> format");

        verify(repository, never()).createVersion(any(), any(), any());
    }

    @Test
    void reviewRequiredFailsClosedWithSafeDefaultApprovalPolicy() {
        UUID skillId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        SkillRecord skill = new SkillRecord(skillId, "skill", "Skill", "", "PRIVATE", "DRAFT",
                NOW, NOW, 0);
        SkillVersionRecord version = completedVersion(skillId, versionId, "PENDING");
        SkillSecurityScanner scanner = manifest -> new SkillSecurityScanner.ScanResult(
                SkillSecurityScanner.ScanResult.Status.REVIEW_REQUIRED, "NEEDS_REVIEW", "not persisted");
        when(repository.findById(skillId)).thenReturn(Optional.of(skill));
        when(repository.findVersionById(versionId)).thenReturn(Optional.of(version));
        when(repository.markSecurityScan(eq(skillId), eq(versionId), eq("FAILED"), eq(NOW))).thenReturn(version);

        SkillService defaultService = new SkillService(repository, new IdempotencyService(),
                Clock.fixed(NOW, ZoneOffset.UTC), new SkillPackageValidator(), scanner);

        assertThatThrownBy(() -> defaultService.publish(skillId, versionId))
                .isInstanceOf(SkillPackageValidationException.class)
                .hasMessage("skill security scan requires an approved security review: NEEDS_REVIEW");
        verify(repository, never()).publish(any(), any(), any());
        verify(repository, never()).review(any(), any(), any(), any());
    }

    @Test
    void reviewRequiredUsesReplaceableApprovalPortWithoutExposingPackageData() {
        UUID skillId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        SkillRecord skill = new SkillRecord(skillId, "skill", "Skill", "", "PRIVATE", "DRAFT",
                NOW, NOW, 0);
        SkillVersionRecord pending = completedVersion(skillId, versionId, "PENDING");
        SkillVersionRecord approved = completedVersion(skillId, versionId, "APPROVED");
        AtomicReference<SkillScanApprovalPort.ApprovalRequest> request = new AtomicReference<>();
        SkillSecurityScanner scanner = manifest -> new SkillSecurityScanner.ScanResult(
                SkillSecurityScanner.ScanResult.Status.REVIEW_REQUIRED, "vendor review", "vendor secret");
        SkillScanApprovalPort approval = input -> {
            request.set(input);
            return SkillScanApprovalPort.ApprovalStatus.APPROVED;
        };
        when(repository.findById(skillId)).thenReturn(Optional.of(skill));
        when(repository.findVersionById(versionId)).thenReturn(Optional.of(pending));
        when(repository.markSecurityScan(eq(skillId), eq(versionId), eq("FAILED"), eq(NOW))).thenReturn(pending);
        when(repository.review(eq(skillId), eq(versionId), eq("APPROVED"), eq(NOW))).thenReturn(approved);
        when(repository.publish(eq(skillId), eq(versionId), eq(NOW))).thenReturn(approved);

        SkillService approvingService = new SkillService(repository, new IdempotencyService(),
                Clock.fixed(NOW, ZoneOffset.UTC), new SkillPackageValidator(), scanner, approval);

        assertThat(approvingService.publish(skillId, versionId)).isSameAs(approved);
        assertThat(request).hasValueSatisfying(value -> {
            assertThat(value.skillId()).isEqualTo(skillId);
            assertThat(value.versionId()).isEqualTo(versionId);
            assertThat(value.classification()).isEqualTo("vendor_review");
            assertThat(value.digest()).isEqualTo(pending.digest());
        });
        verify(repository).review(skillId, versionId, "APPROVED", NOW);
        verify(repository).publish(skillId, versionId, NOW);
    }

    private static SkillVersionRecord completedVersion(UUID skillId, UUID versionId, String reviewStatus) {
        return new SkillVersionRecord(versionId, skillId, "1.0.0", validDigest(),
                "{\"name\":\"skill\",\"description\":\"desc\",\"entry\":\"SKILL.md\",\"sizeBytes\":1}",
                "PRIVATE", "DRAFT", NOW, NOW, 1, "NOT_SCANNED", reviewStatus,
                SkillPackageStoragePaths.versionPackage(skillId, versionId), 1L,
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef", "COMPLETED");
    }

    private static String validDigest() {
        return "sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    }
}
