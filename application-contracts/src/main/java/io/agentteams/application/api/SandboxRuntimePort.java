package io.agentteams.application.api;

import java.time.Instant;

/**
 * Port for a disposable task sandbox provider.
 *
 * <p>Implementations may be backed by a fake provider, Kubernetes, gVisor,
 * Kata, or another runtime. The application layer must not depend on any
 * provider SDK or on Docker/Kubernetes sockets.</p>
 */
public interface SandboxRuntimePort {

    SandboxHandle provision(SandboxRequest request);

    SandboxStatus inspect(String providerSandboxId);

    void renew(String providerSandboxId, Instant expiresAt);

    void terminate(String providerSandboxId, SandboxTerminationReason reason);
}
