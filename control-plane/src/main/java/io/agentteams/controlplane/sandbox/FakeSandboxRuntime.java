package io.agentteams.controlplane.sandbox;

import io.agentteams.application.api.SandboxFailure;
import io.agentteams.application.api.SandboxFailureCategory;
import io.agentteams.application.api.SandboxHandle;
import io.agentteams.application.api.SandboxObservation;
import io.agentteams.application.api.SandboxProvisionCommand;
import io.agentteams.application.api.SandboxProvisionReceipt;
import io.agentteams.application.api.SandboxProviderException;
import io.agentteams.application.api.SandboxProviderPhase;
import io.agentteams.application.api.SandboxProviderRef;
import io.agentteams.application.api.SandboxRenewCommand;
import io.agentteams.application.api.SandboxRenewReceipt;
import io.agentteams.application.api.SandboxRequest;
import io.agentteams.application.api.SandboxRuntimePort;
import io.agentteams.application.api.SandboxStatus;
import io.agentteams.application.api.SandboxTerminationCommand;
import io.agentteams.application.api.SandboxTerminationReceipt;
import io.agentteams.application.api.SandboxTerminationReason;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Deterministic in-memory provider used by unit tests and Kind contract tests. */
public final class FakeSandboxRuntime implements SandboxRuntimePort {

    private final Map<String, SandboxHandle> byProviderId = new ConcurrentHashMap<>();
    private final Map<String, SandboxProviderRef> refsByProviderId = new ConcurrentHashMap<>();
    private final Map<String, SandboxHandle> byIdempotencyKey = new ConcurrentHashMap<>();
    private final Map<UUID, SandboxHandle> byAttemptId = new ConcurrentHashMap<>();
    private final Map<String, SandboxProvisionCommand> commandsByIdempotencyKey = new ConcurrentHashMap<>();
    private final Map<String, Long> generationsByProviderId = new ConcurrentHashMap<>();
    private final AtomicInteger provisionCalls = new AtomicInteger();
    private final AtomicInteger renewCalls = new AtomicInteger();
    private final AtomicInteger terminateCalls = new AtomicInteger();
    private volatile SandboxTerminationReason lastTerminationReason;

    @Override
    public synchronized SandboxProvisionReceipt ensureProvisioned(SandboxProvisionCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        SandboxHandle existing = byIdempotencyKey.get(command.idempotencyKey());
        if (existing != null) {
            assertSameProvision(existing, command);
            return provisionReceipt(existing);
        }
        if (byAttemptId.containsKey(command.attemptId())) {
            throw conflict("attempt already has a different sandbox provision request");
        }

        String resourceId = "fake-" + UUID.randomUUID();
        SandboxProviderRef providerRef = new SandboxProviderRef("fake", resourceId, UUID.randomUUID().toString());
        SandboxHandle handle = new SandboxHandle(resourceId, command.profile(), SandboxStatus.READY,
                "sandbox://fake/sandbox/" + command.attemptId(), command.expiresAt(),
                command.taskId(), command.attemptId());
        byProviderId.put(resourceId, handle);
        refsByProviderId.put(resourceId, providerRef);
        byIdempotencyKey.put(command.idempotencyKey(), handle);
        byAttemptId.put(command.attemptId(), handle);
        commandsByIdempotencyKey.put(command.idempotencyKey(), command);
        generationsByProviderId.put(resourceId, 1L);
        provisionCalls.incrementAndGet();
        return provisionReceipt(handle);
    }

    @Override
    public synchronized SandboxObservation inspect(SandboxProviderRef providerRef) {
        SandboxHandle handle = requireHandle(providerRef);
        SandboxStatus status = handle.status();
        return new SandboxObservation(providerRef, phase(status), handle.endpointRef(), handle.expiresAt(),
                generation(handle.providerSandboxId()), null,
                status == SandboxStatus.FAILED
                        ? new SandboxFailure(SandboxFailureCategory.PROVIDER_RESPONSE_INVALID,
                                "fake provider failure")
                        : null);
    }

    @Override
    public synchronized SandboxRenewReceipt ensureExpiry(SandboxRenewCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        SandboxHandle current = requireHandle(command.providerRef());
        if (current.status() == SandboxStatus.DESTROYED || current.status() == SandboxStatus.FAILED
                || current.status() == SandboxStatus.EXPIRED || current.status() == SandboxStatus.LOST) {
            throw conflict("terminal sandbox cannot be renewed");
        }
        if (command.expiresAt().equals(current.expiresAt())) {
            return new SandboxRenewReceipt(command.providerRef(), phase(current.status()), current.expiresAt(),
                    generation(current.providerSandboxId()));
        }
        if (command.expiresAt().isBefore(current.expiresAt())) {
            throw conflict("requested sandbox expiry cannot shorten the existing expiry");
        }
        SandboxHandle renewed = new SandboxHandle(current.providerSandboxId(), current.profile(), current.status(),
                current.endpointRef(), command.expiresAt(), current.taskId(), current.attemptId());
        replace(renewed);
        incrementGeneration(renewed.providerSandboxId());
        renewCalls.incrementAndGet();
        return new SandboxRenewReceipt(command.providerRef(), phase(renewed.status()), renewed.expiresAt(),
                generation(renewed.providerSandboxId()));
    }

    @Override
    public synchronized SandboxTerminationReceipt ensureTerminated(SandboxTerminationCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        SandboxHandle current = requireHandle(command.providerRef());
        if (current.status() == SandboxStatus.DESTROYED) {
            return new SandboxTerminationReceipt(command.providerRef(), SandboxProviderPhase.DESTROYED,
                    generation(current.providerSandboxId()));
        }
        SandboxHandle terminated = new SandboxHandle(current.providerSandboxId(), current.profile(),
                SandboxStatus.DESTROYED, current.endpointRef(), current.expiresAt(), current.taskId(),
                current.attemptId());
        replace(terminated);
        incrementGeneration(terminated.providerSandboxId());
        lastTerminationReason = command.reason();
        terminateCalls.incrementAndGet();
        return new SandboxTerminationReceipt(command.providerRef(), SandboxProviderPhase.DESTROYED,
                generation(terminated.providerSandboxId()));
    }

    /** Compatibility implementation for callers that have not migrated to provider references. */
    @Override
    @Deprecated(forRemoval = false)
    public synchronized SandboxHandle provision(SandboxRequest request) {
        SandboxProvisionCommand command = SandboxProvisionCommand.from(request);
        SandboxHandle existing = byIdempotencyKey.get(command.idempotencyKey());
        if (existing == null) {
            ensureProvisioned(command);
        } else {
            assertSameProvision(existing, command);
        }
        return byIdempotencyKey.get(command.idempotencyKey());
    }

    /** Compatibility implementation for callers that have not migrated to provider references. */
    @Override
    @Deprecated(forRemoval = false)
    public synchronized SandboxStatus inspect(String providerSandboxId) {
        return requireHandle(providerSandboxId).status();
    }

    /** Compatibility implementation for callers that have not migrated to provider references. */
    @Override
    @Deprecated(forRemoval = false)
    public synchronized void renew(String providerSandboxId, Instant expiresAt) {
        Objects.requireNonNull(expiresAt, "expiresAt must not be null");
        ensureExpiry(new SandboxRenewCommand(requireRef(providerSandboxId), expiresAt));
    }

    /** Compatibility implementation for callers that have not migrated to provider references. */
    @Override
    @Deprecated(forRemoval = false)
    public synchronized void terminate(String providerSandboxId, SandboxTerminationReason reason) {
        ensureTerminated(new SandboxTerminationCommand(requireRef(providerSandboxId), reason));
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

    public SandboxTerminationReason lastTerminationReason() {
        return lastTerminationReason;
    }

    private SandboxProvisionReceipt provisionReceipt(SandboxHandle handle) {
        return new SandboxProvisionReceipt(refsByProviderId.get(handle.providerSandboxId()),
                phase(handle.status()), generation(handle.providerSandboxId()));
    }

    private SandboxHandle requireHandle(SandboxProviderRef providerRef) {
        Objects.requireNonNull(providerRef, "providerRef must not be null");
        SandboxProviderRef actualRef = refsByProviderId.get(providerRef.resourceId());
        if (!providerRef.equals(actualRef)) {
            throw new SandboxProviderException(SandboxFailureCategory.PROVIDER_RESOURCE_LOST,
                    "provider sandbox reference is unknown");
        }
        return requireHandle(providerRef.resourceId());
    }

    private SandboxProviderRef requireRef(String providerSandboxId) {
        requireHandle(providerSandboxId);
        return refsByProviderId.get(providerSandboxId);
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

    private void assertSameProvision(SandboxHandle existing, SandboxProvisionCommand command) {
        SandboxProvisionCommand original = commandsByIdempotencyKey.get(command.idempotencyKey());
        if (original == null || !sameProvision(original, command)
                || !existing.attemptId().equals(command.attemptId())) {
            throw conflict("sandbox provision request conflicts with the existing idempotency key");
        }
    }

    private static boolean sameProvision(SandboxProvisionCommand first, SandboxProvisionCommand second) {
        return first.taskId().equals(second.taskId())
                && first.attemptId().equals(second.attemptId())
                && first.profile() == second.profile()
                && first.ttl().equals(second.ttl())
                && first.template().equals(second.template())
                && first.requestedAt().equals(second.requestedAt())
                && first.idempotencyKey().equals(second.idempotencyKey());
    }

    private static SandboxProviderException conflict(String message) {
        return new SandboxProviderException(SandboxFailureCategory.IDEMPOTENCY_CONFLICT, message);
    }

    private void replace(SandboxHandle handle) {
        byProviderId.put(handle.providerSandboxId(), handle);
        if (handle.attemptId() != null) {
            byAttemptId.put(handle.attemptId(), handle);
        }
        byIdempotencyKey.replaceAll((key, value) -> value.providerSandboxId().equals(handle.providerSandboxId())
                ? handle : value);
    }

    private long generation(String providerSandboxId) {
        return generationsByProviderId.getOrDefault(providerSandboxId, 0L);
    }

    private void incrementGeneration(String providerSandboxId) {
        generationsByProviderId.compute(providerSandboxId, (key, value) -> value == null ? 1L : value + 1L);
    }

    private static SandboxProviderPhase phase(SandboxStatus status) {
        return SandboxProviderPhase.valueOf(status.name());
    }
}
