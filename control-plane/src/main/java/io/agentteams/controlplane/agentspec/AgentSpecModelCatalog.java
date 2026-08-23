package io.agentteams.controlplane.agentspec;

import java.util.Optional;
import java.util.UUID;

/** Read-only model catalog view used when an AgentSpec references a model. */
public interface AgentSpecModelCatalog {

    Optional<ProviderReference> findProviderByName(String name);

    Optional<ModelReference> findModelById(UUID providerId, String modelId);

    record ProviderReference(UUID id, boolean enabled, long revision) {
        /** Compatibility constructor for catalog implementations without revisions. */
        public ProviderReference(UUID id, boolean enabled) {
            this(id, enabled, 0);
        }
    }

    record ModelReference(boolean enabled, long revision) {
        /** Compatibility constructor for catalog implementations without revisions. */
        public ModelReference(boolean enabled) {
            this(enabled, 0);
        }
    }
}
