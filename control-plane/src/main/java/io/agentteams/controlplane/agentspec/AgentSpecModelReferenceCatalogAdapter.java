package io.agentteams.controlplane.agentspec;

import java.util.Objects;
import java.util.Optional;

/**
 * Metadata-only adapter over the existing model catalog. It never reads or copies credentials.
 */
public final class AgentSpecModelReferenceCatalogAdapter implements AgentSpecReferenceCatalogPort {

    private final AgentSpecModelCatalog catalog;

    public AgentSpecModelReferenceCatalogAdapter(AgentSpecModelCatalog catalog) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
    }

    @Override
    public AgentSpecReferenceType type() {
        return AgentSpecReferenceType.MODEL;
    }

    @Override
    public Optional<AgentSpecReferenceCatalog.ReferenceMetadata> find(
            String referenceValue, AgentSpecReferenceCatalog.Scope scope) {
        Objects.requireNonNull(scope, "scope");
        int separator = referenceValue == null ? -1 : referenceValue.indexOf('/');
        if (separator <= 0 || separator == referenceValue.length() - 1) {
            return Optional.empty();
        }
        String providerName = referenceValue.substring(0, separator);
        String modelId = referenceValue.substring(separator + 1);
        AgentSpecReference reference = new AgentSpecReference(AgentSpecReferenceType.MODEL,
                providerName + "/" + modelId);
        return catalog.findProviderByName(providerName).flatMap(provider ->
                catalog.findModelById(provider.id(), modelId).map(model ->
                        new AgentSpecReferenceCatalog.ReferenceMetadata(
                                null, null, null, AgentSpecReferenceCatalog.Visibility.PUBLIC,
                                provider.enabled() && model.enabled() ? "PUBLISHED" : "DISABLED",
                                revision(provider.revision(), model.revision()),
                                AgentSpecReferenceDigest.derived(reference,
                                        revision(provider.revision(), model.revision())))));
    }

    private static String revision(long providerRevision, long modelRevision) {
        if (providerRevision <= 0 && modelRevision <= 0) {
            return null;
        }
        return providerRevision + ":" + modelRevision;
    }
}
