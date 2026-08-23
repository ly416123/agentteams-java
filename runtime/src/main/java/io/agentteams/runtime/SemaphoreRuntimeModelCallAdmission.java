package io.agentteams.runtime;

import java.util.Objects;
import java.util.concurrent.Semaphore;

/**
 * Small in-process admission implementation used by the Worker assembly.
 * A future control-plane adapter can implement the same runtime port without
 * changing the QwenPaw runtime or transport.
 */
public final class SemaphoreRuntimeModelCallAdmission implements RuntimeModelCallAdmission {
    private final Semaphore permits;

    public SemaphoreRuntimeModelCallAdmission(int maxConcurrentCalls) {
        if (maxConcurrentCalls <= 0) {
            throw new IllegalArgumentException("maxConcurrentCalls must be positive");
        }
        this.permits = new Semaphore(maxConcurrentCalls, true);
    }

    @Override
    public RuntimeModelCallLease acquire(RuntimeModelCallAdmissionRequest request) {
        Objects.requireNonNull(request, "request");
        if (!permits.tryAcquire()) {
            throw new RuntimeModelCallAdmissionRejectedException(
                    "maximum admitted model calls reached for provider " + request.provider());
        }
        return RuntimeModelCallLease.idempotent(permits::release);
    }

    public int availablePermits() {
        return permits.availablePermits();
    }
}
