package io.agentteams.controlplane.memory;

/** Authenticated actor used for memory governance; admin status comes from authorization. */
public record MemoryGovernanceActor(String subjectId, boolean administrator) {
    public MemoryGovernanceActor {
        if (subjectId == null || subjectId.isBlank()) throw new IllegalArgumentException("subjectId must not be blank");
        subjectId = subjectId.trim();
    }
}
