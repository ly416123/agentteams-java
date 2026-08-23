package io.agentteams.controlplane.quota;

import io.agentteams.application.api.QuotaReservationPort;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Control-plane backend for the Gateway quota gRPC application port. */
@Component
public final class ProjectQuotaReservationAdapter implements QuotaReservationPort {
    private final ProjectQuotaService quotas;
    private final Clock clock;
    private final JdbcQuotaReservationRepository durable;
    private final Map<AcquireKey, Reservation> reservationsByAcquire = new ConcurrentHashMap<>();
    private final Map<String, Reservation> reservationsById = new ConcurrentHashMap<>();
    private final Map<String, ReleaseDecision> releasedReservations = new ConcurrentHashMap<>();
    private final Map<ReleaseKey, ReleaseDecision> releases = new ConcurrentHashMap<>();

    /** Spring production path: claims and release decisions survive process restarts. */
    @Autowired
    public ProjectQuotaReservationAdapter(ProjectQuotaService quotas,
            JdbcQuotaReservationRepository durable) {
        this(quotas, Clock.systemUTC(), durable);
    }

    /** Compatibility constructor for unit tests that do not boot a database. */
    public ProjectQuotaReservationAdapter(ProjectQuotaService quotas) {
        this(quotas, Clock.systemUTC(), null);
    }

    ProjectQuotaReservationAdapter(ProjectQuotaService quotas, Clock clock) {
        this(quotas, clock, null);
    }

    private ProjectQuotaReservationAdapter(ProjectQuotaService quotas, Clock clock,
            JdbcQuotaReservationRepository durable) {
        this.quotas = Objects.requireNonNull(quotas, "quotas");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.durable = durable;
    }

    @Override
    @Transactional
    public AcquireDecision acquire(AcquireRequest request) {
        Objects.requireNonNull(request, "request");
        if (expired(request.deadline())) return new AcquireDecision(false, "", "", 0, "DEADLINE_EXCEEDED");
        return durable == null ? acquireInMemory(request) : acquireDurably(request);
    }

    private AcquireDecision acquireDurably(AcquireRequest request) {
        JdbcQuotaReservationRepository.ReservationRecord existing = durable.findByAcquire(
                request.tenantId(), request.projectId(), request.idempotencyKey()).orElse(null);
        if (existing != null && ("ACQUIRED".equals(existing.state()) || "RELEASED".equals(existing.state()))) {
            return accepted(existing.id().toString());
        }

        UUID id = existing == null ? UUID.randomUUID() : existing.id();
        if (existing == null) {
            durable.insertPending(id, request.tenantId(), request.projectId(), request.idempotencyKey(),
                    request.estimatedTokens(), clock.instant());
        }
        try {
            quotas.acquire(request.tenantId(), request.projectId(), request.estimatedTokens());
            durable.markAcquired(id, clock.instant());
            return accepted(id.toString());
        } catch (QuotaExceededException rejected) {
            durable.delete(id);
            return new AcquireDecision(false, "", rejected.dimension(), 0, "");
        } catch (IllegalArgumentException invalid) {
            durable.delete(id);
            return new AcquireDecision(false, "", "", 0, "INVALID_ARGUMENT");
        }
    }

    private AcquireDecision acquireInMemory(AcquireRequest request) {
        AcquireKey key = new AcquireKey(request.tenantId(), request.projectId(), request.idempotencyKey());
        Reservation existing = reservationsByAcquire.get(key);
        if (existing != null) return accepted(existing.id());
        try {
            ProjectQuotaLease lease = quotas.acquire(request.tenantId(), request.projectId(), request.estimatedTokens());
            String id = UUID.randomUUID().toString();
            Reservation reservation = new Reservation(id, request.tenantId(), request.projectId(), lease);
            reservationsByAcquire.put(key, reservation);
            reservationsById.put(id, reservation);
            return accepted(id);
        } catch (QuotaExceededException rejected) {
            return new AcquireDecision(false, "", rejected.dimension(), 0, "");
        } catch (IllegalArgumentException invalid) {
            return new AcquireDecision(false, "", "", 0, "INVALID_ARGUMENT");
        }
    }

    @Override
    @Transactional
    public ReleaseDecision release(ReleaseRequest request) {
        Objects.requireNonNull(request, "request");
        if (expired(request.deadline())) return new ReleaseDecision(false, request.reservationId(), "DEADLINE_EXCEEDED");
        return durable == null ? releaseInMemory(request) : releaseDurably(request);
    }

    private ReleaseDecision releaseDurably(ReleaseRequest request) {
        JdbcQuotaReservationRepository.ReleaseRecord previous = durable.findRelease(
                request.tenantId(), request.projectId(), request.idempotencyKey()).orElse(null);
        if (previous != null) return new ReleaseDecision(previous.accepted(), request.reservationId(), previous.protocolError());

        UUID id;
        try {
            id = UUID.fromString(request.reservationId());
        } catch (IllegalArgumentException invalid) {
            ReleaseDecision result = notFound(request.reservationId());
            durable.insertRelease(request.tenantId(), request.projectId(), UUID.nameUUIDFromBytes(
                    request.reservationId().getBytes(StandardCharsets.UTF_8)), request.idempotencyKey(),
                    false, result.protocolError(), clock.instant());
            return result;
        }
        JdbcQuotaReservationRepository.ReservationRecord reservation = durable.findById(
                id, request.tenantId(), request.projectId()).orElse(null);
        if (reservation == null) {
            ReleaseDecision result = notFound(request.reservationId());
            durable.insertRelease(request.tenantId(), request.projectId(), id, request.idempotencyKey(),
                    false, result.protocolError(), clock.instant());
            return result;
        }
        if ("RELEASED".equals(reservation.state())) {
            ReleaseDecision result = acceptedRelease(request.reservationId());
            durable.insertRelease(request.tenantId(), request.projectId(), id, request.idempotencyKey(),
                    true, "", clock.instant());
            return result;
        }
        if (!"ACQUIRED".equals(reservation.state())) {
            ReleaseDecision result = new ReleaseDecision(false, request.reservationId(), "RESERVATION_NOT_READY");
            durable.insertRelease(request.tenantId(), request.projectId(), id, request.idempotencyKey(),
                    false, result.protocolError(), clock.instant());
            return result;
        }
        quotas.release(new ProjectQuotaLease(reservation.tenantId(), reservation.projectId(), true));
        durable.markReleased(id, clock.instant());
        durable.insertRelease(request.tenantId(), request.projectId(), id, request.idempotencyKey(),
                true, "", clock.instant());
        return acceptedRelease(request.reservationId());
    }

    private ReleaseDecision releaseInMemory(ReleaseRequest request) {
        ReleaseKey key = new ReleaseKey(request.tenantId(), request.projectId(), request.idempotencyKey());
        ReleaseDecision previous = releases.get(key);
        if (previous != null) return previous;
        Reservation reservation = reservationsById.get(request.reservationId());
        ReleaseDecision alreadyReleased = releasedReservations.get(request.reservationId());
        if (alreadyReleased != null) {
            releases.put(key, alreadyReleased);
            return alreadyReleased;
        }
        if (reservation == null || !reservation.matches(request.tenantId(), request.projectId())) {
            ReleaseDecision result = notFound(request.reservationId());
            releases.put(key, result);
            return result;
        }
        quotas.release(reservation.lease());
        ReleaseDecision result = acceptedRelease(request.reservationId());
        reservationsById.remove(request.reservationId());
        releasedReservations.put(request.reservationId(), result);
        releases.put(key, result);
        return result;
    }

    private boolean expired(Instant deadline) { return !deadline.isAfter(clock.instant()); }

    private static AcquireDecision accepted(String id) { return new AcquireDecision(true, id, "", 0, ""); }
    private static ReleaseDecision acceptedRelease(String id) { return new ReleaseDecision(true, id, ""); }
    private static ReleaseDecision notFound(String id) { return new ReleaseDecision(false, id, "RESERVATION_NOT_FOUND"); }

    private record AcquireKey(String tenantId, String projectId, String idempotencyKey) { }
    private record ReleaseKey(String tenantId, String projectId, String idempotencyKey) { }
    private record Reservation(String id, String tenantId, String projectId, ProjectQuotaLease lease) {
        private boolean matches(String tenant, String project) {
            return tenantId.equals(tenant) && projectId.equals(project);
        }
    }
}
