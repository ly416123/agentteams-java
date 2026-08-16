package io.agentteams.domain.task;

public final class IllegalTaskTransitionException extends DomainException {

    private static final long serialVersionUID = 1L;

    private final TaskPhase currentPhase;
    private final TaskPhase requestedPhase;

    public IllegalTaskTransitionException(TaskPhase currentPhase, TaskPhase requestedPhase) {
        super("Illegal task transition from " + currentPhase + " to " + requestedPhase);
        this.currentPhase = currentPhase;
        this.requestedPhase = requestedPhase;
    }

    public TaskPhase currentPhase() {
        return currentPhase;
    }

    public TaskPhase requestedPhase() {
        return requestedPhase;
    }
}
