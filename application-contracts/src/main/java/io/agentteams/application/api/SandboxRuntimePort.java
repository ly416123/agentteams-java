package io.agentteams.application.api;

/**
 * Port for a disposable task sandbox provider.
 *
 * <p>Implementations may be backed by a fake provider, Kubernetes, gVisor,
 * Kata, or another runtime. The application layer must not depend on any
 * provider SDK or on Docker/Kubernetes sockets.</p>
 */
public interface SandboxRuntimePort {

    /** Ensures that the provider resource for a task attempt exists. */
    default SandboxProvisionReceipt ensureProvisioned(SandboxProvisionCommand command) {
        throw new UnsupportedOperationException("sandbox provider does not implement ensureProvisioned");
    }

    /** Reads provider state without changing the resource. */
    default SandboxObservation inspect(SandboxProviderRef providerRef) {
        throw new UnsupportedOperationException("sandbox provider does not implement inspect");
    }

    /** Ensures that the provider resource has the requested expiry. */
    default SandboxRenewReceipt ensureExpiry(SandboxRenewCommand command) {
        throw new UnsupportedOperationException("sandbox provider does not implement ensureExpiry");
    }

    /** Ensures that the provider resource is being terminated. */
    default SandboxTerminationReceipt ensureTerminated(SandboxTerminationCommand command) {
        throw new UnsupportedOperationException("sandbox provider does not implement ensureTerminated");
    }

    /** Compatibility hook for callers that still use the pre-provider-reference API. */
    @Deprecated(forRemoval = false)
    default SandboxHandle provision(SandboxRequest request) {
        throw new UnsupportedOperationException("sandbox provider does not implement legacy provision");
    }

    /** Compatibility hook for the pre-provider-reference API. */
    @Deprecated(forRemoval = false)
    default SandboxStatus inspect(String providerSandboxId) {
        throw new UnsupportedOperationException("sandbox provider does not implement legacy inspect");
    }

    /** Compatibility hook for the pre-provider-reference API. */
    @Deprecated(forRemoval = false)
    default void renew(String providerSandboxId, java.time.Instant expiresAt) {
        throw new UnsupportedOperationException("sandbox provider does not implement legacy renew");
    }

    /** Compatibility hook for the pre-provider-reference API. */
    @Deprecated(forRemoval = false)
    default void terminate(String providerSandboxId, SandboxTerminationReason reason) {
        throw new UnsupportedOperationException("sandbox provider does not implement legacy terminate");
    }
}
