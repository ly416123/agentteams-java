package io.agentteams.controlplane.agentspec;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class CompositeAgentSpecReferenceCatalogTest {

    @Test
    void dispatchesModelSkillAndMcpToTypedPortsWithCallerScope() {
        AtomicReference<AgentSpecReferenceCatalog.Scope> modelScope = new AtomicReference<>();
        AtomicReference<AgentSpecReferenceCatalog.Scope> skillScope = new AtomicReference<>();
        AtomicReference<AgentSpecReferenceCatalog.Scope> mcpScope = new AtomicReference<>();
        AgentSpecReferenceCatalog.Scope scope = new AgentSpecReferenceCatalog.Scope(
                "tenant-a", "project-a", "team-a");

        CompositeAgentSpecReferenceCatalog catalog = new CompositeAgentSpecReferenceCatalog(
                port(AgentSpecReferenceType.MODEL, "provider/model", modelScope,
                        metadata("tenant-a", "project-a", "team-a", "PUBLISHED")),
                port(AgentSpecReferenceType.SKILL, "skill-a", skillScope,
                        metadata("tenant-a", "project-a", "team-a", "PUBLISHED")),
                port(AgentSpecReferenceType.MCP, "mcp-a", mcpScope,
                        metadata("tenant-a", "project-a", "team-a", "PUBLISHED")));

        assertThat(catalog.find(new AgentSpecReference(AgentSpecReferenceType.MODEL, "provider/model"), scope))
                .isPresent();
        assertThat(catalog.find(new AgentSpecReference(AgentSpecReferenceType.SKILL, "skill-a"), scope))
                .isPresent();
        assertThat(catalog.find(new AgentSpecReference(AgentSpecReferenceType.MCP, "mcp-a"), scope))
                .isPresent();
        assertThat(modelScope).hasValue(scope);
        assertThat(skillScope).hasValue(scope);
        assertThat(mcpScope).hasValue(scope);
    }

    @Test
    void validatorKeepsProjectVisibilityAndLifecycleCategoriesAcrossCompositeAdapters() {
        CompositeAgentSpecReferenceCatalog catalog = new CompositeAgentSpecReferenceCatalog(
                port(AgentSpecReferenceType.MODEL, "provider/model", new AtomicReference<>(),
                        metadata("tenant-a", "project-a", null, "PUBLISHED")),
                port(AgentSpecReferenceType.SKILL, "private-skill", new AtomicReference<>(),
                        metadata("tenant-a", "project-b", null, "PUBLISHED")),
                port(AgentSpecReferenceType.MCP, "draft-mcp", new AtomicReference<>(),
                        metadata("tenant-a", "project-a", null, "DRAFT")));
        CatalogAgentSpecReferenceValidator validator = new CatalogAgentSpecReferenceValidator(catalog);

        AgentSpecReferenceValidationResult invisible = validator.validate(request(
                new AgentSpecReference(AgentSpecReferenceType.SKILL, "private-skill")));
        AgentSpecReferenceValidationResult unavailable = validator.validate(request(
                new AgentSpecReference(AgentSpecReferenceType.MCP, "draft-mcp")));

        assertThat(invisible.category())
                .isEqualTo(AgentSpecReferenceValidationResult.Category.PROJECT_NOT_VISIBLE);
        assertThat(unavailable.category())
                .isEqualTo(AgentSpecReferenceValidationResult.Category.LIFECYCLE_NOT_AVAILABLE);
    }

    @Test
    void missingAdapterUsesStableNotFoundCategoryAndExplainsConfigurationGap() {
        AgentSpecReferenceCatalog catalog = new CompositeAgentSpecReferenceCatalog(
                port(AgentSpecReferenceType.MODEL, "provider/model", new AtomicReference<>(),
                        metadata(null, null, null, "PUBLISHED")),
                null, null);
        CatalogAgentSpecReferenceValidator validator = new CatalogAgentSpecReferenceValidator(catalog);

        AgentSpecReferenceValidationResult result = validator.validate(request(
                new AgentSpecReference(AgentSpecReferenceType.MCP, "not-configured")));

        assertThat(result.category()).isEqualTo(AgentSpecReferenceValidationResult.Category.REFERENCE_NOT_FOUND);
        assertThat(result.violations().get(0).detail()).contains("not configured");
    }

    @Test
    void modelAdapterUsesExistingModelCatalogMetadataOnly() {
        UUID providerId = UUID.randomUUID();
        AgentSpecModelCatalog modelCatalog = new AgentSpecModelCatalog() {
            @Override
            public Optional<ProviderReference> findProviderByName(String name) {
                return Optional.of(new ProviderReference(providerId, true));
            }

            @Override
            public Optional<ModelReference> findModelById(UUID id, String modelId) {
                return Optional.of(new ModelReference(true));
            }
        };

        AgentSpecReferenceCatalogPort adapter = new AgentSpecModelReferenceCatalogAdapter(modelCatalog);
        Optional<AgentSpecReferenceCatalog.ReferenceMetadata> result = adapter.find(
                "provider/model", new AgentSpecReferenceCatalog.Scope("tenant-a", "project-a", "team-a"));

        assertThat(result).hasValueSatisfying(metadata -> {
            assertThat(metadata.lifecycle()).isEqualTo("PUBLISHED");
            assertThat(metadata.tenantId()).isNull();
            assertThat(metadata.projectId()).isNull();
            assertThat(metadata.teamId()).isNull();
        });
    }

    private static AgentSpecReferenceCatalogPort port(AgentSpecReferenceType type, String expectedValue,
            AtomicReference<AgentSpecReferenceCatalog.Scope> observedScope,
            AgentSpecReferenceCatalog.ReferenceMetadata metadata) {
        return new AgentSpecReferenceCatalogPort() {
            @Override
            public AgentSpecReferenceType type() {
                return type;
            }

            @Override
            public Optional<AgentSpecReferenceCatalog.ReferenceMetadata> find(String value,
                    AgentSpecReferenceCatalog.Scope scope) {
                observedScope.set(scope);
                return expectedValue.equals(value) ? Optional.of(metadata) : Optional.empty();
            }
        };
    }

    private static AgentSpecReferenceCatalog.ReferenceMetadata metadata(String tenant, String project,
            String team, String lifecycle) {
        return new AgentSpecReferenceCatalog.ReferenceMetadata(tenant, project, team,
                AgentSpecReferenceCatalog.Visibility.PROJECT, lifecycle);
    }

    private static AgentSpecReferenceValidationRequest request(AgentSpecReference reference) {
        AgentSpecReferences references = switch (reference.type()) {
            case MODEL -> {
                int separator = reference.value().indexOf('/');
                yield new AgentSpecReferences(new AgentSpecReferences.ModelRef(
                        reference.value().substring(0, separator), reference.value().substring(separator + 1)),
                        List.of(), List.of());
            }
            case SKILL -> new AgentSpecReferences(null, List.of(reference.value()), List.of());
            case MCP -> new AgentSpecReferences(null, List.of(), List.of(reference.value()));
        };
        return new AgentSpecReferenceValidationRequest("tenant-a", "project-a", "team-a",
                references);
    }
}
