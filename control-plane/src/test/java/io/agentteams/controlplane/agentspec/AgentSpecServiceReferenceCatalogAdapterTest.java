package io.agentteams.controlplane.agentspec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import io.agentteams.controlplane.mcp.McpHealthStatus;
import io.agentteams.controlplane.mcp.McpServerRecord;
import io.agentteams.controlplane.mcp.McpServerService;
import io.agentteams.controlplane.mcp.McpTransport;
import io.agentteams.controlplane.persistence.ModelProviderRecord;
import io.agentteams.controlplane.persistence.ModelRecord;
import io.agentteams.controlplane.service.ModelCatalogService;
import io.agentteams.controlplane.skill.SkillRecord;
import io.agentteams.controlplane.skill.SkillPackageStoragePaths;
import io.agentteams.controlplane.skill.SkillPackageStorageService;
import io.agentteams.controlplane.skill.SkillRepository;
import io.agentteams.controlplane.skill.SkillService;
import io.agentteams.controlplane.skill.SkillVersionRecord;
import io.agentteams.controlplane.storage.ObjectStorage;
import java.net.URL;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AgentSpecServiceReferenceCatalogAdapterTest {

    private static final Instant NOW = Instant.parse("2026-08-23T00:00:00Z");
    private static final AgentSpecReferenceCatalog.Scope SCOPE =
            new AgentSpecReferenceCatalog.Scope("tenant-a", "project-a", "team-a");

    @Mock
    private ModelCatalogService models;

    @Mock
    private SkillService skills;

    @Mock
    private SkillRepository skillRepository;

    @Mock
    private ObjectStorage objectStorage;

    @Mock
    private McpServerService mcp;

    @Test
    void modelAdapterReadsPublishedStateAndBothProviderAndModelRevisions() {
        UUID providerId = UUID.randomUUID();
        UUID modelId = UUID.randomUUID();
        ModelProviderRecord provider = new ModelProviderRecord(providerId, "qwen", "openai",
                "https://models.example.test", null, "{}", true, NOW, NOW, 3);
        ModelRecord model = new ModelRecord(modelId, providerId, "Qwen", "qwen-max", "{}",
                true, NOW, NOW, 7);
        when(models.listProviders()).thenReturn(List.of(provider));
        when(models.listModels(providerId)).thenReturn(List.of(model));

        AgentSpecReferenceCatalogPort adapter = new AgentSpecModelServiceReferenceCatalogAdapter(models,
                (type, id, scope) -> SCOPE.equals(scope));

        assertThat(adapter.find("qwen/qwen-max", SCOPE)).hasValueSatisfying(metadata -> {
            assertThat(metadata.lifecycle()).isEqualTo("PUBLISHED");
            assertThat(metadata.visibility()).isEqualTo(AgentSpecReferenceCatalog.Visibility.PROJECT);
            assertThat(metadata.projectId()).isEqualTo("project-a");
            assertThat(metadata.revision()).isEqualTo("3:7");
        });
    }

    @Test
    void skillAdapterResolvesExplicitPublishedVersionAndRejectsInvisibleProject() {
        UUID skillId = UUID.randomUUID();
        SkillRecord skill = new SkillRecord(skillId, "code-review", "Code Review", "review",
                "PRIVATE", "PUBLISHED", NOW, NOW, 4);
        SkillVersionRecord version = new SkillVersionRecord(UUID.randomUUID(), skillId, "2.1.0", "sha256:abc",
                "{\"name\":\"code-review\"}", "PRIVATE", "PUBLISHED", NOW, NOW, 2);
        when(skills.listSkills()).thenReturn(List.of(skill));
        when(skills.listVersions(skillId)).thenReturn(List.of(version));

        AgentSpecReferenceCatalogPort adapter = new AgentSpecSkillServiceReferenceCatalogAdapter(skills,
                (type, id, scope) -> false);

        assertThat(adapter.find("code-review@2.1.0", SCOPE)).isEmpty();

        AgentSpecReferenceCatalogPort visibleAdapter = new AgentSpecSkillServiceReferenceCatalogAdapter(skills,
                (type, id, scope) -> true);
        assertThat(visibleAdapter.find("code-review@2.1.0", SCOPE)).hasValueSatisfying(metadata -> {
            assertThat(metadata.lifecycle()).isEqualTo("PUBLISHED");
            assertThat(metadata.revision()).isEqualTo("2.1.0");
            assertThat(metadata.digest()).isEqualTo("sha256:abc");
        });
    }

    @Test
    void skillAdapterGeneratesShortLivedArtifactReferenceForCompletedPackage() throws Exception {
        UUID skillId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        String packageSha256 = "a".repeat(64);
        SkillRecord skill = new SkillRecord(skillId, "code-review", "Code Review", "review",
                "PRIVATE", "PUBLISHED", NOW, NOW, 4);
        SkillVersionRecord version = new SkillVersionRecord(versionId, skillId, "2.1.0", "sha256:" + packageSha256,
                "{\"name\":\"code-review\"}", "PRIVATE", "PUBLISHED", NOW, NOW, 2,
                "PASSED", "APPROVED", SkillPackageStoragePaths.versionPackage(skillId, versionId), 12L,
                packageSha256, "COMPLETED");
        when(skills.listSkills()).thenReturn(List.of(skill));
        when(skills.listVersions(skillId)).thenReturn(List.of(version));
        when(skillRepository.findVersionById(versionId)).thenReturn(java.util.Optional.of(version));
        URL downloadUrl = new URL("https://objects.example.test/signed/skill.tar.gz?expires=900");
        when(objectStorage.presignGet(SkillPackageStoragePaths.versionPackage(skillId, versionId),
                java.time.Duration.ofMinutes(10))).thenReturn(downloadUrl);

        SkillPackageStorageService packageStorage = new SkillPackageStorageService(skillRepository, objectStorage,
                Clock.fixed(NOW, java.time.ZoneOffset.UTC));
        AgentSpecReferenceCatalogPort adapter = new AgentSpecSkillServiceReferenceCatalogAdapter(skills,
                (type, id, scope) -> true, packageStorage, java.time.Duration.ofMinutes(10));

        assertThat(adapter.find("code-review@2.1.0", SCOPE)).hasValueSatisfying(metadata -> {
            assertThat(metadata.artifactRef()).isEqualTo(downloadUrl.toString());
            assertThat(metadata.sizeBytes()).isEqualTo(12L);
            assertThat(metadata.digest()).isEqualTo(packageSha256);
        });
    }

    @Test
    void mcpAdapterResolvesNameAndUsesRegistryVersionAsBindingRevision() {
        UUID serverId = UUID.randomUUID();
        McpServerRecord server = new McpServerRecord(serverId, "search", McpTransport.SSE,
                "https://mcp.example.test", null, true, McpHealthStatus.HEALTHY, NOW, NOW, NOW, 9);
        when(mcp.list()).thenReturn(List.of(server));

        AgentSpecReferenceCatalogPort adapter = new AgentSpecMcpServiceReferenceCatalogAdapter(mcp,
                (type, id, scope) -> true);

        String nameDigest = adapter.find("search", SCOPE).orElseThrow().digest();
        assertThat(adapter.find(serverId.toString(), SCOPE)).hasValueSatisfying(metadata -> {
            assertThat(metadata.lifecycle()).isEqualTo("PUBLISHED");
            assertThat(metadata.revision()).isEqualTo("9");
            assertThat(metadata.visibility()).isEqualTo(AgentSpecReferenceCatalog.Visibility.PROJECT);
            assertThat(metadata.projectId()).isEqualTo("project-a");
            assertThat(metadata.digest()).isEqualTo(nameDigest).startsWith("sha256:");
        });
    }
}
