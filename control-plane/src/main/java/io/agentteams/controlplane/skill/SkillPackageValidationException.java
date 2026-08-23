package io.agentteams.controlplane.skill;

public final class SkillPackageValidationException extends IllegalArgumentException {

    public SkillPackageValidationException(String message) {
        super(message);
    }

    public SkillPackageValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
