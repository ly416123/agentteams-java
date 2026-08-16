package io.agentteams.controlplane.team;

public final class TeamMembershipChangePolicy {
    public Decision onMemberRemoval(RemovalAction action, int activeAttempts) {
        if (action == null) throw new IllegalArgumentException("removal action is required");
        if (activeAttempts < 0) throw new IllegalArgumentException("activeAttempts must not be negative");
        if (activeAttempts == 0) return new Decision(Outcome.REMOVED, "no active attempts");
        return switch (action) {
            case REQUEUE -> new Decision(Outcome.REQUEUE_ACTIVE_ATTEMPTS, "active attempts return to scheduler");
            case KEEP_ACTIVE -> new Decision(Outcome.KEEP_ACTIVE_ATTEMPTS, "member remains responsible until completion");
            case CANCEL -> new Decision(Outcome.CANCEL_ACTIVE_ATTEMPTS, "active attempts are cancelled");
        };
    }

    public enum RemovalAction { REQUEUE, KEEP_ACTIVE, CANCEL }
    public enum Outcome { REMOVED, REQUEUE_ACTIVE_ATTEMPTS, KEEP_ACTIVE_ATTEMPTS, CANCEL_ACTIVE_ATTEMPTS }
    public record Decision(Outcome outcome, String reason) { }
}
