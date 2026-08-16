package io.agentteams.domain.task;

public final class LeaseNotActiveException extends DomainException {

    private static final long serialVersionUID = 1L;

    private final LeaseNotActiveReason reason;

    public LeaseNotActiveException(LeaseNotActiveReason reason, String message) {
        super(message);
        this.reason = java.util.Objects.requireNonNull(reason, "reason");
    }

    public LeaseNotActiveReason reason() {
        return reason;
    }
}
