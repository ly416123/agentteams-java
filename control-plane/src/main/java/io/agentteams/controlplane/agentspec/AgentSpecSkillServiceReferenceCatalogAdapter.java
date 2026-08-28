package io.agentteams.controlplane.agentspec;

import io.agentteams.controlplane.skill.SkillRecord;
import io.agentteams.controlplane.skill.SkillPackageStorageService;
import io.agentteams.controlplane.skill.SkillService;
import io.agentteams.controlplane.skill.SkillVersionRecord;
import java.net.URL;
import java.time.Duration;
import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;

/** AgentSpec adapter backed by the real SkillService and its published versions. */
public final class AgentSpecSkillServiceReferenceCatalogAdapter implements AgentSpecReferenceCatalogPort {

    private static final Duration DEFAULT_ARTIFACT_EXPIRY = Duration.ofMinutes(15);

    private final SkillService service;
    private final AgentSpecReferenceVisibility visibility;
    private final SkillPackageStorageService packageStorage;
    private final Duration artifactExpiry;

    public AgentSpecSkillServiceReferenceCatalogAdapter(SkillService service) {
        this(service, AgentSpecReferenceVisibility.allowAll());
    }

    public AgentSpecSkillServiceReferenceCatalogAdapter(SkillService service,
            AgentSpecReferenceVisibility visibility) {
        this(service, visibility, null, DEFAULT_ARTIFACT_EXPIRY);
    }

    public AgentSpecSkillServiceReferenceCatalogAdapter(SkillService service,
            AgentSpecReferenceVisibility visibility, SkillPackageStorageService packageStorage,
            Duration artifactExpiry) {
        this.service = Objects.requireNonNull(service, "service");
        this.visibility = Objects.requireNonNull(visibility, "visibility");
        this.packageStorage = packageStorage;
        this.artifactExpiry = Objects.requireNonNull(artifactExpiry, "artifactExpiry");
        if (artifactExpiry.isZero() || artifactExpiry.isNegative()) {
            throw new IllegalArgumentException("artifactExpiry must be positive");
        }
    }

    @Override
    public AgentSpecReferenceType type() {
        return AgentSpecReferenceType.SKILL;
    }

    @Override
    public Optional<AgentSpecReferenceCatalog.ReferenceMetadata> find(String referenceValue,
            AgentSpecReferenceCatalog.Scope scope) {
        SkillReference reference = SkillReference.parse(referenceValue);
        if (reference == null || scope == null) {
            return Optional.empty();
        }
        return service.listSkills().stream()
                .filter(skill -> skill.name().equals(reference.name())
                        || skill.id().toString().equals(reference.name()))
                .findFirst()
                .filter(skill -> visibility.visible("SKILL", skill.id(), scope))
                .flatMap(skill -> publishedVersion(skill, reference.version()))
                .map(version -> metadata(version, scope));
    }

    private Optional<SkillVersionRecord> publishedVersion(SkillRecord skill, String requestedVersion) {
        return service.listVersions(skill.id()).stream()
                .filter(version -> "PUBLISHED".equalsIgnoreCase(version.lifecycle()))
                .filter(version -> requestedVersion == null || version.version().equals(requestedVersion))
                .max(Comparator.comparing(SkillVersionRecord::createdAt));
    }

    private AgentSpecReferenceCatalog.ReferenceMetadata metadata(SkillVersionRecord version,
            AgentSpecReferenceCatalog.Scope scope) {
        AgentSpecReferenceCatalog.Visibility resourceVisibility =
                "PUBLIC".equalsIgnoreCase(version.visibility())
                        ? AgentSpecReferenceCatalog.Visibility.PUBLIC
                        : AgentSpecReferenceCatalog.Visibility.PROJECT;
        String tenant = resourceVisibility == AgentSpecReferenceCatalog.Visibility.PROJECT
                ? scope.tenantId() : null;
        String project = resourceVisibility == AgentSpecReferenceCatalog.Visibility.PROJECT
                ? scope.projectId() : null;
        String team = resourceVisibility == AgentSpecReferenceCatalog.Visibility.PROJECT
                ? scope.teamId() : null;
        String digest = version.digest();
        String artifactRef = null;
        Long sizeBytes = null;
        if (packageStorage != null && "COMPLETED".equalsIgnoreCase(version.packageUploadStatus())) {
            URL downloadUrl = packageStorage.prepareDownload(version.skillId(), version.id(), artifactExpiry);
            artifactRef = downloadUrl.toString();
            sizeBytes = version.packageSizeBytes();
            digest = version.packageSha256();
        }
        return new AgentSpecReferenceCatalog.ReferenceMetadata(tenant, project, team, resourceVisibility,
                version.lifecycle(), version.version(), digest, artifactRef, sizeBytes);
    }

    private record SkillReference(String name, String version) {
        static SkillReference parse(String value) {
            if (value == null || value.isBlank()) {
                return null;
            }
            String normalized = value.trim();
            int separator = normalized.lastIndexOf('@');
            if (separator <= 0 || separator == normalized.length() - 1) {
                return new SkillReference(normalized, null);
            }
            return new SkillReference(normalized.substring(0, separator), normalized.substring(separator + 1));
        }
    }
}
