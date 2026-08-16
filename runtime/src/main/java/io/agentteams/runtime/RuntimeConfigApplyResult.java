package io.agentteams.runtime;

import java.util.Objects;

public record RuntimeConfigApplyResult(Status status, RuntimeConfigSnapshot activeSnapshot) {
    public RuntimeConfigApplyResult {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(activeSnapshot, "activeSnapshot");
    }

    public enum Status {
        ACTIVATED,
        ALREADY_ACTIVE
    }
}
