package io.agentteams.controlplane.agentspec;

import java.util.Objects;

/** Immutable resource binding captured when an AgentSpec is validated or published. */
public record AgentSpecReferenceBinding(
        AgentSpecReferenceType type,
        String reference,
        String tenantId,
        String projectId,
        String teamId,
        String revision,
        String digest,
        String artifactRef,
        Long sizeBytes,
        McpRuntimeMetadata mcpRuntime) {

    public AgentSpecReferenceBinding {
        Objects.requireNonNull(type, "type");
        requireText(reference, "reference");
        requireText(revision, "revision");
        requireText(digest, "digest");
        reference = reference.trim();
        revision = revision.trim();
        digest = digest.trim();
        artifactRef = artifactRef == null || artifactRef.isBlank() ? null : artifactRef.trim();
        if (sizeBytes != null && sizeBytes < 0) {
            throw new IllegalArgumentException("sizeBytes must not be negative");
        }
        if ((artifactRef == null) != (sizeBytes == null)) {
            throw new IllegalArgumentException("artifactRef and sizeBytes must be provided together");
        }
        tenantId = normalize(tenantId);
        projectId = normalize(projectId);
        teamId = normalize(teamId);
    }

    public static AgentSpecReferenceBinding from(AgentSpecReference reference,
            AgentSpecReferenceCatalog.ReferenceMetadata metadata) {
        Objects.requireNonNull(reference, "reference");
        Objects.requireNonNull(metadata, "metadata");
        if (metadata.revision() == null || metadata.revision().isBlank()) {
            throw new IllegalArgumentException("published reference must expose revision");
        }
        String digest = metadata.digest() == null
                ? AgentSpecReferenceDigest.derived(reference, metadata.revision()) : metadata.digest();
        return new AgentSpecReferenceBinding(reference.type(), reference.value(), metadata.tenantId(),
                metadata.projectId(), metadata.teamId(), metadata.revision(), digest,
                metadata.artifactRef(), metadata.sizeBytes(), metadata.mcpRuntime());
    }

    /** Compatibility constructor for bindings that carry artifact metadata only. */
    public AgentSpecReferenceBinding(AgentSpecReferenceType type, String reference, String tenantId,
            String projectId, String teamId, String revision, String digest, String artifactRef, Long sizeBytes) {
        this(type, reference, tenantId, projectId, teamId, revision, digest, artifactRef, sizeBytes, null);
    }

    /** Compatibility constructor for bindings that predate artifact delivery metadata. */
    public AgentSpecReferenceBinding(AgentSpecReferenceType type, String reference, String tenantId,
            String projectId, String teamId, String revision, String digest) {
        this(type, reference, tenantId, projectId, teamId, revision, digest, null, null);
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
