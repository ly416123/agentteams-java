package io.agentteams.controlplane.service;

import io.agentteams.controlplane.persistence.SchedulerLeaseRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.function.Supplier;

/** Executes scheduler work only while this replica owns the database lease. */
public final class SchedulerLeaseService {
    private final SchedulerLeaseRepository leases;

    public SchedulerLeaseService(SchedulerLeaseRepository leases) {
        this.leases = Objects.requireNonNull(leases, "leases");
    }

    public <T> Result<T> run(String leaseName, String owner, Instant now, Duration duration, Supplier<T> work) {
        Objects.requireNonNull(work, "work");
        if (!leases.tryAcquire(leaseName, owner, now, duration)) return new Result<>(false, null);
        try {
            return new Result<>(true, work.get());
        } finally {
            leases.release(leaseName, owner, now);
        }
    }

    public record Result<T>(boolean leader, T value) { }
}
