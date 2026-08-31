package io.agentteams.controlplane.agentspec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentteams.controlplane.config.ConfigBindingRecord;
import io.agentteams.controlplane.config.ConfigDeploymentService;
import io.agentteams.controlplane.config.ConfigSnapshot;
import io.agentteams.controlplane.config.ConfigSnapshotService;
import io.agentteams.controlplane.security.AuthorizationException;
import io.agentteams.controlplane.security.AuthorizationService;
import io.agentteams.controlplane.security.Principal;
import io.agentteams.controlplane.security.PrincipalContext;
import io.agentteams.controlplane.security.ResourceScopeRepository;
import io.agentteams.application.api.SandboxPolicy;
import io.agentteams.application.api.SandboxProfile;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.Optional;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AgentSpecDeploymentServiceTest {
    @Mock private AgentSpecService specs;
    @Mock private ConfigSnapshotService snapshots;
    @Mock private ConfigDeploymentService deployments;
    @Mock private ResourceScopeRepository resourceScopes;

    @Test
    void convertsSpecToVersionedConfigAndDeploysIt() {
        UUID specId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID snapshotId = UUID.randomUUID();
        UUID bindingId = UUID.randomUUID();
        Instant now = Instant.parse("2026-08-23T00:00:00Z");
        AgentSpecRecord spec = new AgentSpecRecord(specId, "analyst", "qwenpaw", "deepseek", "deepseek-chat",
                "research", "RUNNING", "DRAFT", "{\"skillRefs\":[\"search-v1\"]}", now, now, 2);
        ConfigSnapshot snapshot = new ConfigSnapshot(snapshotId, "agent-spec:" + specId, 1, "{}", "checksum",
                "operator", now);
        ConfigBindingRecord binding = new ConfigBindingRecord(bindingId, snapshot.subject(), agentId, snapshotId, now);
        ConfigDeploymentService.ConfigDeployment deployment = new ConfigDeploymentService.ConfigDeployment(binding,
                snapshot, UUID.randomUUID());
        when(specs.get(specId)).thenReturn(spec);
        when(snapshots.create(any(), any(), any())).thenReturn(snapshot);
        when(deployments.deploy(agentId, snapshot.subject(), snapshot)).thenReturn(deployment);

        AgentSpecDeploymentService service = new AgentSpecDeploymentService(specs, snapshots, deployments,
                new ObjectMapper());
        AgentSpecDeploymentService.AgentSpecDeployment result = service.deploy(specId, agentId, "operator");

        assertThat(result.spec()).isSameAs(spec);
        assertThat(result.snapshot()).isSameAs(snapshot);
        verify(snapshots).create("agent-spec:" + specId,
                "{\"apiVersion\":\"agentteams.io/v1\",\"kind\":\"AgentSpec\",\"agentSpecId\":\""
                        + specId + "\",\"agentSpecVersion\":2,\"name\":\"analyst\",\"runtime\":\"qwenpaw\","
                        + "\"modelProvider\":\"deepseek\",\"modelName\":\"deepseek-chat\",\"teamRef\":\"research\","
                        + "\"scope\":{\"tenant\":\"default\",\"project\":\"default\",\"team\":\"research\"},"
                        + "\"desiredState\":\"RUNNING\",\"spec\":{\"skillRefs\":[\"search-v1\"]}}", "operator");
    }

    @Test
    void rejectsDeploymentToWorkerOutsideCallerProject() {
        UUID specId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        Instant now = Instant.parse("2026-08-23T00:00:00Z");
        AgentSpecRecord spec = new AgentSpecRecord(specId, "analyst", "qwenpaw", "deepseek", "deepseek-chat",
                null, "RUNNING", "DRAFT", "{}", now, now, 1);
        when(specs.get(specId)).thenReturn(spec);
        doThrow(new AuthorizationException("resource is outside caller project"))
                .when(resourceScopes).requireVisible("WORKER", agentId);
        PrincipalContext.set(new Principal("alice",
                new AuthorizationService.Scope("tenant-a", "project-a", "team-a"), java.util.Set.of()));
        try {
            AgentSpecDeploymentService secured = new AgentSpecDeploymentService(specs, snapshots, deployments,
                    new ObjectMapper(), resourceScopes);
            assertThatThrownBy(() -> secured.deploy(specId, agentId, "alice"))
                    .isInstanceOf(AuthorizationException.class);
            verify(deployments, never()).deploy(any(), any(), any());
        } finally {
            PrincipalContext.clear();
        }
    }

    @Test
    void publishesStableSkillAndMcpBindingsForWorkerAndTeam() throws Exception {
        UUID specId = UUID.randomUUID();
        UUID workerId = UUID.randomUUID();
        UUID snapshotId = UUID.randomUUID();
        Instant now = Instant.parse("2026-08-23T00:00:00Z");
        AgentSpecRecord spec = new AgentSpecRecord(specId, "analyst", "qwenpaw", "deepseek", "deepseek-chat",
                "research", "RUNNING", "DRAFT",
                "{\"skillRefs\":[\"review@2\"],\"mcpRefs\":[\"search\"]}", now, now, 2,
                "tenant-a", "project-a");
        ConfigSnapshot snapshot = new ConfigSnapshot(snapshotId, "agent-spec:" + specId, 1, "{}", "checksum",
                "operator", now);
        ConfigBindingRecord binding = new ConfigBindingRecord(UUID.randomUUID(), snapshot.subject(), workerId,
                snapshotId, now);
        ConfigDeploymentService.ConfigDeployment deployment = new ConfigDeploymentService.ConfigDeployment(binding,
                snapshot, UUID.randomUUID());
        when(specs.get(specId)).thenReturn(spec);
        when(snapshots.create(any(), any(), any())).thenReturn(snapshot);
        when(deployments.deploy(workerId, snapshot.subject(), snapshot)).thenReturn(deployment);

        AgentSpecReferenceCatalog catalog = reference -> Optional.of(
                new AgentSpecReferenceCatalog.ReferenceMetadata("tenant-a", "project-a", "research",
                        AgentSpecReferenceCatalog.Visibility.PROJECT, "PUBLISHED",
                        reference.type() == AgentSpecReferenceType.SKILL ? "skill-2" : "mcp-7",
                        reference.type() == AgentSpecReferenceType.SKILL ? "sha256:skill" : "sha256:mcp",
                        reference.type() == AgentSpecReferenceType.SKILL ? "https://objects.example.test/skill.tar.gz" : null,
                        reference.type() == AgentSpecReferenceType.SKILL ? 12L : null,
                        reference.type() == AgentSpecReferenceType.MCP
                                ? new McpRuntimeMetadata("2d85e034-1486-4df0-b4b9-6d8e622ace61",
                                        "STREAMABLE_HTTP", "https://mcp.example.test/http", "MCP_SERVER_TOKEN")
                                : null,
                        reference.type() == AgentSpecReferenceType.SKILL
                                ? new io.agentteams.application.api.SkillCapabilityPolicy(SandboxProfile.ISOLATED,
                                        500, 512, 1024, Duration.ofMinutes(10), java.util.Set.of(), java.util.Set.of(),
                                        false, SandboxPolicy.NetworkPolicy.RESTRICTED)
                                : null));
        AgentSpecDeploymentService service = new AgentSpecDeploymentService(specs, snapshots, deployments,
                new ObjectMapper(), null, new CatalogAgentSpecReferenceValidator(catalog));

        service.deploy(specId, workerId, "operator");

        ArgumentCaptor<String> manifest = ArgumentCaptor.forClass(String.class);
        verify(snapshots).create(any(), manifest.capture(), any());
        JsonNode root = new ObjectMapper().readTree(manifest.getValue());
        assertThat(root.path("resourceBindings").size()).isEqualTo(3);
        assertThat(root.path("resourceBindings").get(0).path("type").asText()).isEqualTo("MODEL");
        assertThat(root.path("resourceBindings").get(1).path("type").asText()).isEqualTo("SKILL");
        assertThat(root.path("resourceBindings").get(1).path("revision").asText()).isEqualTo("skill-2");
        assertThat(root.path("resourceBindings").get(1).path("digest").asText()).isEqualTo("sha256:skill");
        assertThat(root.path("resourceBindings").get(1).path("artifactRef").asText())
                .isEqualTo("https://objects.example.test/skill.tar.gz");
        assertThat(root.path("resourceBindings").get(1).path("sizeBytes").asLong()).isEqualTo(12L);
        assertThat(root.path("resourceBindings").get(1).path("skillCapabilities").path("profile").asText())
                .isEqualTo("ISOLATED");
        assertThat(root.path("resourceBindings").get(1).path("skillCapabilities").path("networkPolicy").asText())
                .isEqualTo("RESTRICTED");
        assertThat(root.path("resourceBindings").get(1).path("workerId").asText()).isEqualTo(workerId.toString());
        assertThat(root.path("resourceBindings").get(1).path("teamRef").asText()).isEqualTo("research");
        assertThat(root.path("resourceBindings").get(2).path("type").asText()).isEqualTo("MCP");
        assertThat(root.path("resourceBindings").get(2).path("revision").asText()).isEqualTo("mcp-7");
        assertThat(root.path("resourceBindings").get(2).path("digest").asText()).isEqualTo("sha256:mcp");
        assertThat(root.path("resourceBindings").get(2).path("serverId").asText())
                .isEqualTo("2d85e034-1486-4df0-b4b9-6d8e622ace61");
        assertThat(root.path("resourceBindings").get(2).path("transport").asText())
                .isEqualTo("STREAMABLE_HTTP");
        assertThat(root.path("resourceBindings").get(2).path("endpoint").asText())
                .isEqualTo("https://mcp.example.test/http");
        assertThat(root.path("resourceBindings").get(2).path("credentialRef").asText())
                .isEqualTo("MCP_SERVER_TOKEN");
        assertThat(root.path("resourceBindings").get(2).toString())
                .doesNotContain("Authorization", "token", "secret");
    }

    @Test
    void rejectsCrossProjectResourceBindingBeforeCreatingSnapshot() {
        UUID specId = UUID.randomUUID();
        UUID workerId = UUID.randomUUID();
        Instant now = Instant.parse("2026-08-23T00:00:00Z");
        AgentSpecRecord spec = new AgentSpecRecord(specId, "analyst", "qwenpaw", "deepseek", "deepseek-chat",
                "research", "RUNNING", "DRAFT", "{\"skillRefs\":[\"private-skill\"]}", now, now, 1,
                "tenant-a", "project-a");
        AgentSpecReferenceCatalog catalog = reference -> Optional.of(
                new AgentSpecReferenceCatalog.ReferenceMetadata("tenant-a", "project-b", "research",
                        AgentSpecReferenceCatalog.Visibility.PROJECT, "PUBLISHED", "2", "sha256:private"));
        AgentSpecDeploymentService service = new AgentSpecDeploymentService(specs, snapshots, deployments,
                new ObjectMapper(), null, new CatalogAgentSpecReferenceValidator(catalog));
        when(specs.get(specId)).thenReturn(spec);

        assertThatThrownBy(() -> service.deploy(specId, workerId, "operator"))
                .isInstanceOf(AgentSpecReferenceValidationException.class)
                .extracting(error -> ((AgentSpecReferenceValidationException) error).category())
                .isEqualTo(AgentSpecReferenceValidationResult.Category.PROJECT_NOT_VISIBLE);
        verify(snapshots, never()).create(any(), any(), any());
        verify(deployments, never()).deploy(any(), any(), any());
    }
}
