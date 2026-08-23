package io.agentteams.controlplane.agentspec;

import io.agentteams.controlplane.skill.SkillRecord;
import io.agentteams.controlplane.skill.SkillService;
import io.agentteams.controlplane.skill.SkillVersionRecord;
import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;

/** AgentSpec adapter backed by the real SkillService and its published versions. */
public final class AgentSpecSkillServiceReferenceCatalogAdapter implements AgentSpecReferenceCatalogPort {

    private final SkillService service;
    private final AgentSpecReferenceVisibility visibility;

    public AgentSpecSkillServiceReferenceCatalogAdapter(SkillService service) {
        this(service, AgentSpecReferenceVisibility.allowAll());
    }

    public AgentSpecSkillServiceReferenceCatalogAdapter(SkillService service,
            AgentSpecReferenceVisibility visibility) {
        this.service = Objects.requireNonNull(service, "service");
        this.visibility = Objects.requireNonNull(visibility, "visibility");
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
        return new AgentSpecReferenceCatalog.ReferenceMetadata(tenant, project, team, resourceVisibility,
                version.lifecycle(), version.version(), version.digest());
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
