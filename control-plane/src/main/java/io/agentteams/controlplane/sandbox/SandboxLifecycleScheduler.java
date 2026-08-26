package io.agentteams.controlplane.sandbox;

import io.agentteams.controlplane.service.SchedulerLeaseService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.function.IntSupplier;
import org.springframework.scheduling.annotation.Scheduled;

/** Leader-only scheduler for durable sandbox lifecycle reconciliation. */
public final class SandboxLifecycleScheduler {
    private final SandboxLifecycleService lifecycle;
    private final SchedulerLeaseService lease;
    private final Clock clock;
    private final String owner;
    private final Duration leaseDuration;
    private final SandboxRuntimeProperties properties;

    public SandboxLifecycleScheduler(SandboxLifecycleService lifecycle, SchedulerLeaseService lease, Clock clock,
            String owner, Duration leaseDuration, SandboxRuntimeProperties properties) {
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        this.lease = Objects.requireNonNull(lease, "lease");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.owner = requireText(owner, "owner");
        this.leaseDuration = positive(leaseDuration, "leaseDuration");
        this.properties = Objects.requireNonNull(properties, "properties");
        if (properties.getBatchSize() <= 0 || properties.getBatchSize() > 1000) {
            throw new IllegalArgumentException("batchSize must be between 1 and 1000");
        }
    }

    @Scheduled(fixedDelayString = "${agentteams.sandbox.poll-interval-ms:1000}")
    public void scheduledRun() {
        runOnce();
    }

    public RunResult runOnce() {
        Instant now = clock.instant();
        return lease.run("sandbox-lifecycle", owner, now, leaseDuration, () -> {
            int recovered = safe(() -> lifecycle.recoverStaleOperations(now));
            int provisioned = safe(() -> lifecycle.provisionRequested(now, properties.getBatchSize()));
            int observed = safe(() -> lifecycle.observeActive(now, properties.getBatchSize()));
            int renewed = safe(() -> lifecycle.renewExpiring(now, properties.getRenewBefore(), properties.getRenewExtension(),
                    properties.getBatchSize()));
            int expired = safe(() -> lifecycle.expire(now, properties.getBatchSize()));
            int terminated = safe(() -> lifecycle.terminateStopping(now, properties.getBatchSize()));
            int destroyed = safe(() -> lifecycle.observeStopping(now, properties.getBatchSize()));
            return new RunResult(true, recovered, provisioned, observed, renewed, expired, terminated, destroyed);
        }).valueOr(new RunResult(false, 0, 0, 0, 0, 0, 0, 0));
    }

    public record RunResult(boolean leader, int recovered, int provisioned, int observed, int renewed,
            int expired, int terminationRequested, int destroyed) {
        public RunResult {
            if (recovered < 0 || provisioned < 0 || observed < 0 || renewed < 0 || expired < 0
                    || terminationRequested < 0 || destroyed < 0) {
                throw new IllegalArgumentException("scheduler counts must not be negative");
            }
        }
    }

    private static Duration positive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value.trim();
    }

    private static int safe(IntSupplier stage) {
        try {
            return stage.getAsInt();
        } catch (RuntimeException ignored) {
            return 0;
        }
    }
}
