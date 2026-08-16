package io.agentteams.controlplane.outbox;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

public final class EventSubjects {

    public static final String CONTROL_EVENTS = "control.events";
    public static final String DEADLETTER_EVENTS = "deadletter.events";

    private EventSubjects() {
    }

    public static String forAggregate(String aggregateType, UUID aggregateId) {
        Objects.requireNonNull(aggregateType, "aggregateType");
        Objects.requireNonNull(aggregateId, "aggregateId");
        return switch (aggregateType.toLowerCase(Locale.ROOT)) {
            case "agent" -> "agent.events." + aggregateId;
            case "task" -> "task.events." + aggregateId;
            default -> CONTROL_EVENTS;
        };
    }
}
