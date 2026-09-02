package io.agentteams.controlplane.artifact;

public final class ArtifactRetentionPolicyConflictException extends RuntimeException {
    public ArtifactRetentionPolicyConflictException(String message) {
        super(message);
    }
}
