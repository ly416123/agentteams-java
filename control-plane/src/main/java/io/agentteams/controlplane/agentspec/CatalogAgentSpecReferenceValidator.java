package io.agentteams.controlplane.agentspec;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Default catalog-backed implementation of the AgentSpec reference validation port. */
public final class CatalogAgentSpecReferenceValidator implements AgentSpecReferenceValidator {

    private static final String AVAILABLE_LIFECYCLE = "PUBLISHED";

    private final AgentSpecReferenceCatalog catalog;

    public CatalogAgentSpecReferenceValidator(AgentSpecReferenceCatalog catalog) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
    }

    /** Convenience constructor for the typed model/skill/MCP composition boundary. */
    public CatalogAgentSpecReferenceValidator(AgentSpecReferenceCatalogPort model,
            AgentSpecReferenceCatalogPort skill, AgentSpecReferenceCatalogPort mcp) {
        this(new CompositeAgentSpecReferenceCatalog(model, skill, mcp));
    }

    @Override
    public AgentSpecReferenceValidationResult validate(AgentSpecReferenceValidationRequest request) {
        Objects.requireNonNull(request, "request");
        List<AgentSpecReferenceValidationResult.Violation> violations = new ArrayList<>();
        List<AgentSpecReferenceBinding> bindings = new ArrayList<>();
        request.references().stream().forEach(reference -> validateOne(request, reference, violations, bindings));
        if (violations.isEmpty()) {
            bindings.sort(java.util.Comparator.comparing(AgentSpecReferenceBinding::type)
                    .thenComparing(AgentSpecReferenceBinding::reference));
            return AgentSpecReferenceValidationResult.valid(bindings);
        }
        return new AgentSpecReferenceValidationResult(violations.get(0).category(), violations, bindings);
    }

    private void validateOne(AgentSpecReferenceValidationRequest request, AgentSpecReference reference,
            List<AgentSpecReferenceValidationResult.Violation> violations,
            List<AgentSpecReferenceBinding> bindings) {
        AgentSpecReferenceCatalog.ReferenceMetadata metadata = catalog.find(reference, request.scope()).orElse(null);
        if (metadata == null) {
            String detail = catalog.isConfigured(reference.type())
                    ? "reference does not exist"
                    : "reference catalog adapter is not configured: " + reference.type();
            add(violations, reference, AgentSpecReferenceValidationResult.Category.REFERENCE_NOT_FOUND,
                    detail);
            return;
        }
        if (!metadata.visibleTo(request.scope())) {
            add(violations, reference, AgentSpecReferenceValidationResult.Category.PROJECT_NOT_VISIBLE,
                    "reference is outside the caller project");
            return;
        }
        if (!AVAILABLE_LIFECYCLE.equals(metadata.lifecycle().toUpperCase(Locale.ROOT))) {
            add(violations, reference, AgentSpecReferenceValidationResult.Category.LIFECYCLE_NOT_AVAILABLE,
                    "reference lifecycle is not available: " + metadata.lifecycle());
            return;
        }
        try {
            bindings.add(AgentSpecReferenceBinding.from(reference, metadata));
        } catch (IllegalArgumentException error) {
            add(violations, reference, AgentSpecReferenceValidationResult.Category.BINDING_METADATA_INCOMPLETE,
                    error.getMessage());
        }
    }

    private static void add(List<AgentSpecReferenceValidationResult.Violation> violations,
            AgentSpecReference reference, AgentSpecReferenceValidationResult.Category category, String detail) {
        violations.add(new AgentSpecReferenceValidationResult.Violation(reference, category, detail));
    }
}
