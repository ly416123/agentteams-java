package io.agentteams.controlplane.artifact;

import io.agentteams.controlplane.audit.AuditEvent;
import io.agentteams.controlplane.audit.AuditRecorder;
import io.agentteams.controlplane.security.AuthorizationException;
import io.agentteams.controlplane.security.PrincipalContext;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Project-scoped artifact retention policy metadata; it never exposes artifact bytes. */
@RestController
@RequestMapping("/api/v1/artifacts/retention")
public final class ArtifactRetentionManagementController {
    private static final AuditRecorder NOOP_AUDIT = event -> { };
    private final ArtifactRetentionRepository repository;
    private final AuditRecorder auditRecorder;
    private final ClockSource clock;

    public ArtifactRetentionManagementController(ArtifactRetentionRepository repository) {
        this(repository, NOOP_AUDIT, Instant::now);
    }

    @Autowired
    public ArtifactRetentionManagementController(ArtifactRetentionRepository repository, AuditRecorder auditRecorder) {
        this(repository, auditRecorder, Instant::now);
    }

    ArtifactRetentionManagementController(ArtifactRetentionRepository repository, AuditRecorder auditRecorder,
            ClockSource clock) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.auditRecorder = Objects.requireNonNull(auditRecorder, "auditRecorder");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @GetMapping
    public RetentionResponse get() {
        var scope = callerScope();
        return repository.findProjectPolicy(scope.tenant(), scope.project())
                .map(RetentionResponse::configured)
                .orElseGet(() -> RetentionResponse.unconfigured(scope.project()));
    }

    @PutMapping
    public RetentionResponse update(@RequestBody RetentionRequest request) {
        if (request == null || request.successfulTaskRetentionSeconds() == null
                || request.failedTaskRetentionSeconds() == null || request.temporaryUploadRetentionSeconds() == null
                || request.legalHold() == null || request.expectedVersion() == null) {
            throw new IllegalArgumentException(
                    "successfulTaskRetentionSeconds, failedTaskRetentionSeconds, temporaryUploadRetentionSeconds, legalHold and expectedVersion are required");
        }
        ArtifactRetentionPolicy policy = new ArtifactRetentionPolicy(
                Duration.ofSeconds(request.successfulTaskRetentionSeconds()),
                Duration.ofSeconds(request.failedTaskRetentionSeconds()),
                Duration.ofSeconds(request.temporaryUploadRetentionSeconds()), request.legalHold());
        var scope = callerScope();
        ArtifactRetentionProjectPolicy updated = repository.upsertProjectPolicy(scope.tenant(), scope.project(), policy,
                clock.now(), request.expectedVersion());
        auditRecorder.record(new AuditEvent(java.util.UUID.randomUUID(), PrincipalContext.actorOr("unknown"),
                "ARTIFACT_RETENTION_POLICY_UPDATED", "artifact_retention_policy", scope.project(),
                Map.of("tenantId", scope.tenant(), "projectId", scope.project(),
                        "version", Long.toString(updated.version())), clock.now()));
        return RetentionResponse.configured(updated);
    }

    private static io.agentteams.controlplane.security.AuthorizationService.Scope callerScope() {
        return PrincipalContext.current().map(principal -> principal.scope())
                .orElseThrow(() -> new AuthorizationException("authenticated project scope is required"));
    }

    interface ClockSource {
        Instant now();
    }

    public record RetentionRequest(Long successfulTaskRetentionSeconds, Long failedTaskRetentionSeconds,
            Long temporaryUploadRetentionSeconds, Boolean legalHold, Long expectedVersion) { }

    public record RetentionResponse(String projectId, boolean configured, long successfulTaskRetentionSeconds,
            long failedTaskRetentionSeconds, long temporaryUploadRetentionSeconds, boolean legalHold, long version) {
        static RetentionResponse configured(ArtifactRetentionProjectPolicy value) {
            ArtifactRetentionPolicy policy = value.policy();
            return new RetentionResponse(value.projectId(), true, policy.successfulTaskRetentionSeconds(),
                    policy.failedTaskRetentionSeconds(), policy.temporaryUploadRetentionSeconds(), policy.legalHold(),
                    value.version());
        }

        static RetentionResponse unconfigured(String projectId) {
            return new RetentionResponse(projectId, false, Duration.ofDays(30).toSeconds(),
                    Duration.ofDays(90).toSeconds(), Duration.ofHours(2).toSeconds(), false, 0);
        }
    }
}
