package io.agentteams.controlplane.skill;

import io.agentteams.controlplane.service.IdempotencyService;
import io.agentteams.controlplane.service.ResourceNotFoundException;
import io.agentteams.controlplane.observability.ControlPlaneMetrics;
import io.agentteams.controlplane.security.PrincipalContext;
import io.agentteams.controlplane.security.ResourceScopeRepository;
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
public class SkillService {

    private static final String PRIVATE = "PRIVATE";
    private static final String DRAFT = "DRAFT";
    private final SkillRepository repository;
    private final IdempotencyService idempotency;
    private final Clock clock;
    private final SkillPackageValidator packageValidator;
    private final SkillSecurityScanner securityScanner;
    private final SkillScanApprovalPort scanApproval;
    private final ControlPlaneMetrics metrics;
    private final ResourceScopeRepository resourceScopes;
    private final SkillPackageStorageService packageStorage;

    public SkillService(SkillRepository repository, IdempotencyService idempotency, Clock clock,
            SkillPackageValidator packageValidator, SkillSecurityScanner securityScanner) {
        this(repository, idempotency, clock, packageValidator, securityScanner,
                new SafeDefaultSkillScanApprovalPort(), null, null, null);
    }

    public SkillService(SkillRepository repository, IdempotencyService idempotency, Clock clock,
            SkillPackageValidator packageValidator, SkillSecurityScanner securityScanner,
            SkillScanApprovalPort scanApproval) {
        this(repository, idempotency, clock, packageValidator, securityScanner,
                scanApproval, null, null, null);
    }

    private SkillService(SkillRepository repository, IdempotencyService idempotency, Clock clock,
            SkillPackageValidator packageValidator, SkillSecurityScanner securityScanner,
            SkillScanApprovalPort scanApproval, ControlPlaneMetrics metrics) {
        this(repository, idempotency, clock, packageValidator, securityScanner,
                scanApproval, metrics, null, null);
    }

    private SkillService(SkillRepository repository, IdempotencyService idempotency, Clock clock,
            SkillPackageValidator packageValidator, SkillSecurityScanner securityScanner,
            SkillScanApprovalPort scanApproval, ControlPlaneMetrics metrics, ResourceScopeRepository resourceScopes,
            SkillPackageStorageService packageStorage) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.idempotency = Objects.requireNonNull(idempotency, "idempotency");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.packageValidator = Objects.requireNonNull(packageValidator, "packageValidator");
        this.securityScanner = Objects.requireNonNull(securityScanner, "securityScanner");
        this.scanApproval = Objects.requireNonNull(scanApproval, "scanApproval");
        this.metrics = metrics;
        this.resourceScopes = resourceScopes;
        this.packageStorage = packageStorage;
    }

    @Autowired
    public SkillService(SkillRepository repository, IdempotencyService idempotency, Clock clock,
            SkillPackageValidator packageValidator, ObjectProvider<SkillSecurityScanner> scanners,
            ObjectProvider<ControlPlaneMetrics> metrics, ObjectProvider<ResourceScopeRepository> scopes,
            ObjectProvider<SkillPackageStorageService> packageStorage,
            ObjectProvider<SkillScanApprovalPort> approvals) {
        this(repository, idempotency, clock, packageValidator,
                scanners.getIfAvailable(ValidationOnlySkillSecurityScanner::new),
                approvals.getIfAvailable(SafeDefaultSkillScanApprovalPort::new), metrics.getIfAvailable(),
                scopes.getIfAvailable(), packageStorage.getIfAvailable());
    }

    /** Compatibility constructor for integrations that predate the package and approval ports. */
    public SkillService(SkillRepository repository, IdempotencyService idempotency, Clock clock,
            SkillPackageValidator packageValidator, ObjectProvider<SkillSecurityScanner> scanners,
            ObjectProvider<ControlPlaneMetrics> metrics, ObjectProvider<ResourceScopeRepository> scopes) {
        this(repository, idempotency, clock, packageValidator,
                scanners.getIfAvailable(ValidationOnlySkillSecurityScanner::new),
                new SafeDefaultSkillScanApprovalPort(), metrics.getIfAvailable(), scopes.getIfAvailable(), null);
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
        SkillRecord created = repository.createSkill(skill, key,
                idempotency.requestHash(name, displayName, description, visibility));
        bindIfAuthenticated(created.id());
        return created;
    }

    public List<SkillRecord> listSkills() {
        return repository.findAll().stream().filter(skill -> visible(skill.id())).toList();
    }

    public SkillRecord getSkill(UUID skillId) {
        SkillRecord skill = repository.findById(Objects.requireNonNull(skillId, "skillId"))
                .orElseThrow(() -> new ResourceNotFoundException("skill", skillId));
        if (resourceScopes != null) resourceScopes.requireVisible("SKILL", skill.id());
        return skill;
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
        if (!"COMPLETED".equals(version.packageUploadStatus())) {
            throw new SkillPackageValidationException("skill package upload must be completed before publishing");
        }
        packageValidator.validate(version.version(), version.digest(), version.manifestJson());
        if (packageStorage != null && packageStorage.archiveScanningAvailable()) {
            SkillSecurityScanner.ScanResult archiveScan = packageStorage.scanCompletedPackage(skillId, versionId);
            version = persistAndResolveScan(skillId, versionId, version, archiveScan, "skill package security scan");
        }
        SkillSecurityScanner.ScanResult scan = securityScanner.scan(version.manifestJson());
        SkillVersionRecord scanned = persistAndResolveScan(skillId, versionId, version, scan,
                "skill security scan");
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

    private void bindIfAuthenticated(UUID resourceId) {
        if (resourceScopes != null) {
            PrincipalContext.current().ifPresent(principal ->
                    resourceScopes.bind("SKILL", resourceId, principal, clock.instant()));
        }
    }

    private boolean visible(UUID resourceId) {
        return resourceScopes == null || resourceScopes.visible("SKILL", resourceId);
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

    private static boolean isPassed(SkillSecurityScanner.ScanResult.Status status) {
        return status == SkillSecurityScanner.ScanResult.Status.PASS
                || status == SkillSecurityScanner.ScanResult.Status.PASSED;
    }

    private SkillVersionRecord persistAndResolveScan(UUID skillId, UUID versionId, SkillVersionRecord version,
            SkillSecurityScanner.ScanResult scan, String failurePrefix) {
        SkillSecurityScanner.ScanResult safeScan = scan == null
                ? new SkillSecurityScanner.ScanResult(SkillSecurityScanner.ScanResult.Status.REVIEW_REQUIRED,
                        "SCAN_INVALID_RESULT", null)
                : scan;
        if (isPassed(safeScan.status())) {
            if (metrics != null) metrics.skillScanPassed();
        } else if (metrics != null) {
            metrics.skillScanFailed();
        }
        SkillVersionRecord scanned = repository.markSecurityScan(skillId, versionId,
                persistedScanStatus(safeScan.status()), clock.instant());
        if (isPassed(safeScan.status())) {
            return scanned;
        }
        if (safeScan.status() != SkillSecurityScanner.ScanResult.Status.REVIEW_REQUIRED) {
            throw new SkillPackageValidationException(failurePrefix + " failed: " + safeScan.classification());
        }

        // An explicit operator approval remains valid for this immutable version. This keeps the
        // existing /review endpoint useful when an asynchronous approval system returns PENDING.
        if ("APPROVED".equals(version.reviewStatus()) || "APPROVED".equals(scanned.reviewStatus())) {
            return scanned;
        }
        SkillScanApprovalPort.ApprovalStatus approval = requestApproval(skillId, versionId, version,
                safeScan.classification());
        if (approval == SkillScanApprovalPort.ApprovalStatus.APPROVED) {
            return recordApproval(skillId, versionId);
        }
        if (approval == SkillScanApprovalPort.ApprovalStatus.REJECTED) {
            recordRejection(skillId, versionId);
            throw new SkillPackageValidationException(failurePrefix + " review rejected: "
                    + safeScan.classification());
        }
        throw new SkillPackageValidationException(failurePrefix + " requires an approved security review: "
                + safeScan.classification());
    }

    private SkillScanApprovalPort.ApprovalStatus requestApproval(UUID skillId, UUID versionId,
            SkillVersionRecord version, String classification) {
        String safeClassification = safeClassification(classification);
        try {
            SkillScanApprovalPort.ApprovalStatus status = scanApproval.onReviewRequired(
                    new SkillScanApprovalPort.ApprovalRequest(skillId, versionId, safeClassification,
                            version.digest()));
            return status == null ? SkillScanApprovalPort.ApprovalStatus.PENDING : status;
        } catch (RuntimeException ignored) {
            // Approval integrations are fail-closed: an unavailable or malformed callback cannot
            // turn a review-required result into an approval.
            return SkillScanApprovalPort.ApprovalStatus.PENDING;
        }
    }

    private SkillVersionRecord recordApproval(UUID skillId, UUID versionId) {
        SkillVersionRecord approved = repository.review(skillId, versionId, "APPROVED", clock.instant());
        if (metrics != null) metrics.skillReviewApproved();
        return approved;
    }

    private void recordRejection(UUID skillId, UUID versionId) {
        repository.review(skillId, versionId, "REJECTED", clock.instant());
        if (metrics != null) metrics.skillReviewRejected();
    }

    private static String safeClassification(String value) {
        if (value == null || value.isBlank()) return "REVIEW_REQUIRED";
        String normalized = value.replaceAll("[^A-Za-z0-9_.:-]", "_");
        return normalized.length() <= 120 ? normalized : normalized.substring(0, 120);
    }

    private static String persistedScanStatus(SkillSecurityScanner.ScanResult.Status status) {
        return isPassed(status) ? "PASSED" : "FAILED";
    }
}
