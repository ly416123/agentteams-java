package io.agentteams.controlplane.team;

/** Validates the live Team scope and references immediately before the publish CAS. */
@FunctionalInterface
public interface TeamRevisionPublishValidator {
    void validate(TeamRevision revision);
}
