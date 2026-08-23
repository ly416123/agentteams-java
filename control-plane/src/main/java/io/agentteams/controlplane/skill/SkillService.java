package io.agentteams.controlplane.skill;

import io.agentteams.controlplane.service.IdempotencyService;
import io.agentteams.controlplane.service.ResourceNotFoundException;
import io.agentteams.controlplane.observability.ControlPlaneMetrics;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public final class SkillService {

    private static final String PRIVATE = "PRIVATE";
    private static final String DRAFT = "DRAFT";
    private final SkillRepository repository;
    private final IdempotencyService idempotency;
    private final Clock clock;
    private final SkillPackageValidator packageValidator;
    private final SkillSecurityScanner securityScanner;
    private final ControlPlaneMetrics metrics;

    public SkillService(SkillRepository repository, IdempotencyService idempotency, Clock clock,
            SkillPackageValidator packageValidator, SkillSecurityScanner securityScanner) {
        this(repository, idempotency, clock, packageValidator, securityScanner, null);
    }

    private SkillService(SkillRepository repository, IdempotencyService idempotency, Clock clock,
            SkillPackageValidator packageValidator, SkillSecurityScanner securityScanner,
            ControlPlaneMetrics metrics) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.idempotency = Objects.requireNonNull(idempotency, "idempotency");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.packageValidator = Objects.requireNonNull(packageValidator, "packageValidator");
        this.securityScanner = Objects.requireNonNull(securityScanner, "securityScanner");
        this.metrics = metrics;
    }

    @Autowired
    public SkillService(SkillRepository repository, IdempotencyService idempotency, Clock clock,
            SkillPackageValidator packageValidator, ObjectProvider<SkillSecurityScanner> scanners,
            ObjectProvider<ControlPlaneMetrics> metrics) {
        this(repository, idempotency, clock, packageValidator,
                scanners.getIfAvailable(ValidationOnlySkillSecurityScanner::new), metrics.getIfAvailable());
    }

    public SkillService(SkillRepository repository, IdempotencyService idempotency, Clock clock) {
        this(repository, idempotency, clock, new SkillPackageValidator(), new ValidationOnlySkillSecurityScanner());
    }

    public SkillService(SkillRepository repository, IdempotencyService idempotency, Clock clock,
            SkillPackageValidator packageValidator) {
        this(repository, idempotency, clock, packageValidator, new ValidationOnlySkillSecurityScanner());
    }

    @Transactional
    public SkillRecord createSkill(String idempotencyKey, SkillInput input) {
        Objects.requireNonNull(input, "input");
        String key = idempotency.requireKey(idempotencyKey);
        String name = required(input.name(), "name");
        String displayName = required(input.displayName(), "displayName");
        String description = input.description() == null ? "" : input.description().trim();
        String visibility = visibility(input.visibility());
        Instant now = clock.instant();
        SkillRecord skill = new SkillRecord(UUID.randomUUID(), name, displayName, description, visibility, DRAFT,
                now, now, 0);
        return repository.createSkill(skill, key,
                idempotency.requestHash(name, displayName, description, visibility));
    }

    public List<SkillRecord> listSkills() {
        return repository.findAll();
    }

    public SkillRecord getSkill(UUID skillId) {
        return repository.findById(Objects.requireNonNull(skillId, "skillId"))
                .orElseThrow(() -> new ResourceNotFoundException("skill", skillId));
    }

    @Transactional
    public SkillVersionRecord createVersion(UUID skillId, String idempotencyKey, VersionInput input) {
        Objects.requireNonNull(input, "input");
        SkillRecord skill = getSkill(skillId);
        String key = idempotency.requireKey(idempotencyKey);
        String version = required(input.version(), "version");
        String digest = required(input.digest(), "digest");
        String manifest = manifest(input.manifestJson());
        packageValidator.validate(version, digest, manifest);
        String visibility = visibility(input.visibility() == null ? skill.visibility() : input.visibility());
        Instant now = clock.instant();
        SkillVersionRecord skillVersion = new SkillVersionRecord(UUID.randomUUID(), skillId, version, digest,
                manifest, visibility, DRAFT, now, now, 0);
        return repository.createVersion(skillVersion, key,
                idempotency.requestHash(skillId.toString(), version, digest, manifest, visibility));
    }

    public List<SkillVersionRecord> listVersions(UUID skillId) {
        getSkill(skillId);
        return repository.findVersions(skillId);
    }

    @Transactional
    public SkillVersionRecord publish(UUID skillId, UUID versionId) {
        getSkill(skillId);
        SkillVersionRecord version = repository.findVersionById(Objects.requireNonNull(versionId, "versionId"))
                .orElseThrow(() -> new IllegalArgumentException("skill version " + versionId + " was not found"));
        if (!skillId.equals(version.skillId())) {
            throw new IllegalArgumentException("skill version does not belong to skill");
        }
        packageValidator.validate(version.version(), version.digest(), version.manifestJson());
        SkillSecurityScanner.ScanResult scan = securityScanner.scan(version.manifestJson());
        if (scan.status() == SkillSecurityScanner.ScanResult.Status.PASSED) {
            if (metrics != null) metrics.skillScanPassed();
        } else if (metrics != null) {
            metrics.skillScanFailed();
        }
        SkillVersionRecord scanned = repository.markSecurityScan(skillId, versionId, scan.status().name(), clock.instant());
        if (scan.status() != SkillSecurityScanner.ScanResult.Status.PASSED) {
            throw new SkillPackageValidationException("skill security scan failed: " + scan.classification());
        }
        if (!"APPROVED".equals(scanned.reviewStatus())) {
            throw new SkillPackageValidationException("skill version requires an approved security review");
        }
        return repository.publish(skillId, versionId, clock.instant());
    }

    @Transactional
    public SkillVersionRecord review(UUID skillId, UUID versionId, String status) {
        getSkill(skillId);
        String normalized = status == null ? "" : status.trim().toUpperCase();
        if (!"APPROVED".equals(normalized) && !"REJECTED".equals(normalized)) {
            throw new IllegalArgumentException("review status must be APPROVED or REJECTED");
        }
        SkillVersionRecord reviewed = repository.review(skillId, versionId, normalized, clock.instant());
        if (metrics != null) {
            if ("APPROVED".equals(normalized)) metrics.skillReviewApproved();
            else metrics.skillReviewRejected();
        }
        return reviewed;
    }

    @Transactional
    public SkillVersionRecord disable(UUID skillId, UUID versionId) {
        getSkill(skillId);
        return repository.disable(skillId, versionId, clock.instant());
    }

    public record SkillInput(String name, String displayName, String description, String visibility) {
    }

    public record VersionInput(String version, String digest, String manifestJson, String visibility) {
    }

    private static String visibility(String value) {
        String normalized = value == null || value.isBlank() ? PRIVATE : value.trim().toUpperCase();
        if (!"PUBLIC".equals(normalized) && !PRIVATE.equals(normalized)) {
            throw new IllegalArgumentException("visibility must be PUBLIC or PRIVATE");
        }
        return normalized;
    }

    private static String manifest(String value) {
        String normalized = value == null || value.isBlank() ? "{}" : value.trim();
        return normalized;
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }
}
