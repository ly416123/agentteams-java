package io.agentteams.controlplane.template;

import java.util.UUID;

/** Adapter boundary to the existing AgentSpec and Worker creation services. */
@FunctionalInterface
public interface TemplateInstanceProvisioner {
    ProvisionedInstance provision(WorkerTemplateRevision revision, UUID instanceId, String idempotencyKey);

    /**
     * Reconciles an existing instance to a newer published revision. Adapters may override this when
     * the underlying AgentSpec/Worker can be updated in place; the default keeps the first cut usable
     * with existing provisioners while preserving the explicit upgrade boundary.
     */
    default ProvisionedInstance upgrade(WorkerTemplateInstance instance, WorkerTemplateRevision revision,
            String idempotencyKey) {
        return provision(revision, instance.id(), idempotencyKey);
    }

    record ProvisionedInstance(UUID agentSpecId, UUID workerId) { }
}
