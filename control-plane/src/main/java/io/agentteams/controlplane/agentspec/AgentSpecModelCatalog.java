package io.agentteams.controlplane.agentspec;

import java.util.Optional;
import java.util.UUID;

/** Read-only model catalog view used when an AgentSpec references a model. */
public interface AgentSpecModelCatalog {

    Optional<ProviderReference> findProviderByName(String name);

    Optional<ModelReference> findModelById(UUID providerId, String modelId);

    record ProviderReference(UUID id, boolean enabled) { }

    record ModelReference(boolean enabled) { }
}
