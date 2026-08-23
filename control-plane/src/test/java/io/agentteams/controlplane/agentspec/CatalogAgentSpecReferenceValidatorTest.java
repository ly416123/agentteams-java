package io.agentteams.controlplane.agentspec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CatalogAgentSpecReferenceValidatorTest {

    @Test
    void returnsStableCategoriesForMissingInvisibleAndUnavailableReferences() {
        Map<String, AgentSpecReferenceCatalog.ReferenceMetadata> metadata = Map.of(
                "SKILL:missing", new AgentSpecReferenceCatalog.ReferenceMetadata(
                        "tenant-a", "project-a", "PROJECT", "PUBLISHED"),
                "SKILL:private", new AgentSpecReferenceCatalog.ReferenceMetadata(
                        "tenant-a", "project-b", "PROJECT", "PUBLISHED"),
                "MCP:draft", new AgentSpecReferenceCatalog.ReferenceMetadata(
                        "tenant-a", "project-a", "PROJECT", "DRAFT"));
        AgentSpecReferenceCatalog catalog = reference -> {
            if (reference.type() == AgentSpecReferenceType.SKILL && reference.value().equals("missing")) {
                return Optional.empty();
            }
            return Optional.ofNullable(metadata.get(reference.type().name() + ":" + reference.value()));
        };
        CatalogAgentSpecReferenceValidator validator = new CatalogAgentSpecReferenceValidator(catalog);

        AgentSpecReferenceValidationResult missing = validator.validate(request(
                new AgentSpecReferences(null, java.util.List.of("missing"), java.util.List.of())));
        AgentSpecReferenceValidationResult invisible = validator.validate(request(
                new AgentSpecReferences(null, java.util.List.of("private"), java.util.List.of())));
        AgentSpecReferenceValidationResult unavailable = validator.validate(request(
                new AgentSpecReferences(null, java.util.List.of(), java.util.List.of("draft"))));

        assertThat(missing.category()).isEqualTo(AgentSpecReferenceValidationResult.Category.REFERENCE_NOT_FOUND);
        assertThat(invisible.category()).isEqualTo(AgentSpecReferenceValidationResult.Category.PROJECT_NOT_VISIBLE);
        assertThat(unavailable.category())
                .isEqualTo(AgentSpecReferenceValidationResult.Category.LIFECYCLE_NOT_AVAILABLE);
        assertThat(unavailable.code()).isEqualTo("LIFECYCLE_NOT_AVAILABLE");
    }

    @Test
    void defaultValidatorAllowsAssemblyWithoutRegistryDependencies() {
        AgentSpecReferenceValidator validator = new NoopAgentSpecReferenceValidator();
        assertThat(validator.validate(request(new AgentSpecReferences(
                new AgentSpecReferences.ModelRef("provider", "model"),
                java.util.List.of("skill"), java.util.List.of("mcp")))).isValid()).isTrue();
    }

    @Test
    void returnsImmutableSkillAndMcpBindingsWithRevisionDigestAndScope() {
        AgentSpecReferenceCatalog catalog = reference -> {
            if (reference.type() == AgentSpecReferenceType.SKILL) {
                return Optional.of(new AgentSpecReferenceCatalog.ReferenceMetadata(
                        "tenant-a", "project-a", "team-a", AgentSpecReferenceCatalog.Visibility.PROJECT,
                        "PUBLISHED", "skill-2", "sha256:skill"));
            }
            return Optional.of(new AgentSpecReferenceCatalog.ReferenceMetadata(
                    "tenant-a", "project-a", "team-a", AgentSpecReferenceCatalog.Visibility.PROJECT,
                    "PUBLISHED", "mcp-7", "sha256:mcp"));
        };

        AgentSpecReferenceValidationResult result = new CatalogAgentSpecReferenceValidator(catalog)
                .validate(new AgentSpecReferenceValidationRequest("tenant-a", "project-a", "team-a",
                        new AgentSpecReferences(null, java.util.List.of("review@2"),
                                java.util.List.of("search"))));

        assertThat(result.isValid()).isTrue();
        assertThat(result.bindings()).extracting(AgentSpecReferenceBinding::type)
                .containsExactly(AgentSpecReferenceType.SKILL, AgentSpecReferenceType.MCP);
        assertThat(result.bindings().get(0).revision()).isEqualTo("skill-2");
        assertThat(result.bindings().get(0).digest()).isEqualTo("sha256:skill");
        assertThat(result.bindings().get(1).projectId()).isEqualTo("project-a");
        assertThatThrownBy(() -> result.bindings().add(null)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsPublishedBindingOutsideCallerProject() {
        AgentSpecReferenceCatalog catalog = reference -> Optional.of(
                new AgentSpecReferenceCatalog.ReferenceMetadata("tenant-a", "project-b", "team-b",
                        AgentSpecReferenceCatalog.Visibility.PROJECT, "PUBLISHED", "skill-3", "sha256:x"));

        AgentSpecReferenceValidationResult result = new CatalogAgentSpecReferenceValidator(catalog)
                .validate(request(new AgentSpecReferences(null, java.util.List.of("private"), java.util.List.of())));

        assertThat(result.category()).isEqualTo(AgentSpecReferenceValidationResult.Category.PROJECT_NOT_VISIBLE);
        assertThat(result.bindings()).isEmpty();
    }

    private static AgentSpecReferenceValidationRequest request(AgentSpecReferences references) {
        return new AgentSpecReferenceValidationRequest("tenant-a", "project-a", references);
    }
}
