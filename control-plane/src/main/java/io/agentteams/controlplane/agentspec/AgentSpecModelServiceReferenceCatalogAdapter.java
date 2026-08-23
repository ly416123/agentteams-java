package io.agentteams.controlplane.agentspec;

import io.agentteams.controlplane.persistence.ModelProviderRecord;
import io.agentteams.controlplane.persistence.ModelRecord;
import io.agentteams.controlplane.service.ModelCatalogService;
import java.util.Objects;
import java.util.Optional;

/** AgentSpec adapter backed by the real ModelCatalogService. */
public final class AgentSpecModelServiceReferenceCatalogAdapter implements AgentSpecReferenceCatalogPort {

    private final ModelCatalogService service;
    private final AgentSpecReferenceVisibility visibility;

    public AgentSpecModelServiceReferenceCatalogAdapter(ModelCatalogService service) {
        this(service, AgentSpecReferenceVisibility.allowAll());
    }

    public AgentSpecModelServiceReferenceCatalogAdapter(ModelCatalogService service,
            AgentSpecReferenceVisibility visibility) {
        this.service = Objects.requireNonNull(service, "service");
        this.visibility = Objects.requireNonNull(visibility, "visibility");
    }

    @Override
    public AgentSpecReferenceType type() {
        return AgentSpecReferenceType.MODEL;
    }

    @Override
    public Optional<AgentSpecReferenceCatalog.ReferenceMetadata> find(String referenceValue,
            AgentSpecReferenceCatalog.Scope scope) {
        ModelReference reference = ModelReference.parse(referenceValue);
        if (reference == null || scope == null) {
            return Optional.empty();
        }
        return service.listProviders().stream()
                .filter(provider -> provider.name().equals(reference.provider()))
                .findFirst()
                .filter(provider -> visibility.visible("MODEL_PROVIDER", provider.id(), scope))
                .flatMap(provider -> service.listModels(provider.id()).stream()
                        .filter(model -> model.modelId().equals(reference.model()))
                        .findFirst()
                        .filter(model -> visibility.visible("MODEL", model.id(), scope))
                        .map(model -> metadata(provider, model, scope)));
    }

    private static AgentSpecReferenceCatalog.ReferenceMetadata metadata(ModelProviderRecord provider,
            ModelRecord model, AgentSpecReferenceCatalog.Scope scope) {
        String revision = provider.version() + ":" + model.version();
        AgentSpecReference reference = new AgentSpecReference(AgentSpecReferenceType.MODEL,
                provider.name() + "/" + model.modelId());
        return new AgentSpecReferenceCatalog.ReferenceMetadata(scope.tenantId(), scope.projectId(), scope.teamId(),
                AgentSpecReferenceCatalog.Visibility.PROJECT,
                provider.enabled() && model.enabled() ? "PUBLISHED" : "DISABLED", revision,
                AgentSpecReferenceDigest.derived(reference, revision));
    }

    private record ModelReference(String provider, String model) {
        static ModelReference parse(String value) {
            if (value == null) {
                return null;
            }
            int separator = value.indexOf('/');
            if (separator <= 0 || separator == value.length() - 1 || value.indexOf('/', separator + 1) >= 0) {
                return null;
            }
            return new ModelReference(value.substring(0, separator).trim(), value.substring(separator + 1).trim());
        }
    }
}
