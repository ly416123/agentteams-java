package io.agentteams.controlplane.agentspec;

import java.util.Optional;

/** Read-only adapter port for resource registries used by AgentSpec reference validation. */
@FunctionalInterface
public interface AgentSpecReferenceCatalog {

    Optional<ReferenceMetadata> find(AgentSpecReference reference);

    /**
     * Scope-aware lookup hook for catalogs that can enforce scope at their own boundary.
     * The default keeps the original functional catalog contract source-compatible.
     */
    default Optional<ReferenceMetadata> find(AgentSpecReference reference, Scope scope) {
        return find(reference);
    }

    /** Returns whether a catalog adapter exists for the supplied reference kind. */
    default boolean isConfigured(AgentSpecReferenceType type) {
        return true;
    }

    record Scope(String tenantId, String projectId, String teamId) {
        public Scope {
            tenantId = normalize(tenantId);
            projectId = normalize(projectId);
            teamId = normalize(teamId);
        }

        public static Scope unscoped() {
            return new Scope(null, null, null);
        }

        private static String normalize(String value) {
            return value == null ? null : value.trim();
        }
    }

    record ReferenceMetadata(String tenantId, String projectId, String teamId, Visibility visibility,
            String lifecycle, String revision, String digest, String artifactRef, Long sizeBytes) {
        /** Compatibility constructor for catalogs that do not expose a digest yet. */
        public ReferenceMetadata(String tenantId, String projectId, String teamId, Visibility visibility,
                String lifecycle, String revision) {
            this(tenantId, projectId, teamId, visibility, lifecycle, revision, null, null, null);
        }

        /** Compatibility constructor for catalogs that expose a digest but no artifact. */
        public ReferenceMetadata(String tenantId, String projectId, String teamId, Visibility visibility,
                String lifecycle, String revision, String digest) {
            this(tenantId, projectId, teamId, visibility, lifecycle, revision, digest, null, null);
        }

        /** Compatibility constructor for catalogs that predate team scope. */
        public ReferenceMetadata(String tenantId, String projectId, Visibility visibility, String lifecycle) {
            this(tenantId, projectId, null, visibility, lifecycle, null, null, null, null);
        }

        /** Compatibility constructor for catalogs that already carry team scope. */
        public ReferenceMetadata(String tenantId, String projectId, String teamId, Visibility visibility,
                String lifecycle) {
            this(tenantId, projectId, teamId, visibility, lifecycle, null, null, null, null);
        }

        /** Compatibility constructor for string visibility callers. */
        public ReferenceMetadata(String tenantId, String projectId, String visibility, String lifecycle) {
            this(tenantId, projectId, null, Visibility.from(visibility), lifecycle, null, null, null, null);
        }

        public ReferenceMetadata {
            if (tenantId != null) {
                tenantId = tenantId.trim();
            }
            if (projectId != null) {
                projectId = projectId.trim();
            }
            if (teamId != null) {
                teamId = teamId.trim();
            }
            if (visibility == null) {
                visibility = Visibility.PROJECT;
            }
            if (lifecycle == null || lifecycle.isBlank()) {
                throw new IllegalArgumentException("lifecycle must not be blank");
            }
            lifecycle = lifecycle.trim();
            if (revision != null) {
                revision = revision.trim();
                if (revision.isBlank()) {
                    revision = null;
                }
            }
            if (digest != null) {
                digest = digest.trim();
                if (digest.isBlank()) {
                    digest = null;
                }
            }
            if (artifactRef != null) {
                artifactRef = artifactRef.trim();
                if (artifactRef.isBlank()) {
                    artifactRef = null;
                }
            }
            if (sizeBytes != null && sizeBytes < 0) {
                throw new IllegalArgumentException("sizeBytes must not be negative");
            }
            if ((artifactRef == null) != (sizeBytes == null)) {
                throw new IllegalArgumentException("artifactRef and sizeBytes must be provided together");
            }
        }

        public boolean visibleTo(String callerTenantId, String callerProjectId) {
            return visibleTo(new Scope(callerTenantId, callerProjectId, null));
        }

        public boolean visibleTo(Scope caller) {
            if (caller == null) {
                return false;
            }
            if (visibility == Visibility.PUBLIC) {
                return (tenantId == null || tenantId.equals(caller.tenantId()))
                        && (teamId == null || teamId.equals(caller.teamId()));
            }
            return tenantId != null && tenantId.equals(caller.tenantId())
                    && projectId != null && projectId.equals(caller.projectId())
                    && (teamId == null || teamId.equals(caller.teamId()));
        }
    }

    enum Visibility {
        PUBLIC,
        PROJECT;

        static Visibility from(String value) {
            if (value == null || value.isBlank()) {
                return PROJECT;
            }
            try {
                return valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException error) {
                throw new IllegalArgumentException("unsupported reference visibility: " + value, error);
            }
        }
    }
}
