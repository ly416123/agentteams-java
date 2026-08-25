package io.agentteams.controlplane.sandbox;

import io.agentteams.application.api.SandboxHandle;
import io.agentteams.application.api.SandboxRequest;
import io.agentteams.application.api.SandboxRuntimePort;
import io.agentteams.application.api.SandboxStatus;
import io.agentteams.application.api.SandboxTerminationReason;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Deterministic in-memory provider used by unit tests and Kind contract tests.
 */
public final class FakeSandboxRuntime implements SandboxRuntimePort {

    private final Map<String, SandboxHandle> byProviderId = new ConcurrentHashMap<>();
    private final Map<String, SandboxHandle> byIdempotencyKey = new ConcurrentHashMap<>();
    private final AtomicInteger provisionCalls = new AtomicInteger();
    private final AtomicInteger renewCalls = new AtomicInteger();
    private final AtomicInteger terminateCalls = new AtomicInteger();

    @Override
    public synchronized SandboxHandle provision(SandboxRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        SandboxHandle existing = byIdempotencyKey.get(request.idempotencyKey());
        if (existing != null) {
            return existing;
        }

        SandboxHandle handle = new SandboxHandle(
                "fake-" + UUID.randomUUID(),
                request.profile(),
                SandboxStatus.READY,
                "sandbox://fake/sandbox/" + request.attemptId(),
                request.expiresAt(), request.taskId(), request.attemptId());
        byIdempotencyKey.put(request.idempotencyKey(), handle);
        byProviderId.put(handle.providerSandboxId(), handle);
        provisionCalls.incrementAndGet();
        return handle;
    }

    @Override
    public SandboxStatus inspect(String providerSandboxId) {
        return requireHandle(providerSandboxId).status();
    }

    @Override
    public synchronized void renew(String providerSandboxId, Instant expiresAt) {
        Objects.requireNonNull(expiresAt, "expiresAt must not be null");
        SandboxHandle current = requireHandle(providerSandboxId);
        if (current.status() == SandboxStatus.DESTROYED) {
            throw new IllegalStateException("cannot renew a destroyed sandbox");
        }
        SandboxHandle renewed = new SandboxHandle(current.providerSandboxId(), current.profile(), current.status(),
                current.endpointRef(), expiresAt, current.taskId(), current.attemptId());
        replace(renewed);
        renewCalls.incrementAndGet();
    }

    @Override
    public synchronized void terminate(String providerSandboxId, SandboxTerminationReason reason) {
        Objects.requireNonNull(reason, "reason must not be null");
        SandboxHandle current = requireHandle(providerSandboxId);
        if (current.status() == SandboxStatus.DESTROYED) {
            return;
        }
        SandboxHandle terminated = new SandboxHandle(current.providerSandboxId(), current.profile(),
                SandboxStatus.DESTROYED, current.endpointRef(), current.expiresAt(), current.taskId(),
                current.attemptId());
        replace(terminated);
        terminateCalls.incrementAndGet();
    }

    public SandboxHandle handle(String providerSandboxId) {
        return requireHandle(providerSandboxId);
    }

    public int provisionCalls() {
        return provisionCalls.get();
    }

    public int renewCalls() {
        return renewCalls.get();
    }

    public int terminateCalls() {
        return terminateCalls.get();
    }

    private SandboxHandle requireHandle(String providerSandboxId) {
        if (providerSandboxId == null || providerSandboxId.isBlank()) {
            throw new IllegalArgumentException("providerSandboxId must be non-blank");
        }
        SandboxHandle handle = byProviderId.get(providerSandboxId);
        if (handle == null) {
            throw new IllegalArgumentException("unknown provider sandbox: " + providerSandboxId);
        }
        return handle;
    }

    private void replace(SandboxHandle handle) {
        byProviderId.put(handle.providerSandboxId(), handle);
        byIdempotencyKey.replaceAll((key, value) -> value.providerSandboxId().equals(handle.providerSandboxId())
                ? handle : value);
    }
}
