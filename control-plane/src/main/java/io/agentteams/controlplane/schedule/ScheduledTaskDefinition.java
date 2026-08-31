package io.agentteams.controlplane.schedule;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Durable schedule projection; execution instances are ordinary Tasks. */
public record ScheduledTaskDefinition(UUID id, String name, ScheduledTaskScope scope,
        String cronExpression, String timeZone, String title, String description, String specJson,
        String actor, String source, boolean enabled, Instant nextRunAt, Instant lastRunAt,
        UUID lastTaskId, long version, Instant createdAt, Instant updatedAt) {
    public ScheduledTaskDefinition {
        Objects.requireNonNull(id, "id");
        name = required(name, "name");
        Objects.requireNonNull(scope, "scope");
        cronExpression = required(cronExpression, "cronExpression");
        timeZone = required(timeZone, "timeZone");
        title = required(title, "title");
        description = Objects.requireNonNull(description, "description");
        specJson = required(specJson, "specJson");
        actor = required(actor, "actor");
        source = required(source, "source");
        Objects.requireNonNull(nextRunAt, "nextRunAt");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (version < 0) throw new IllegalArgumentException("version must not be negative");
    }

    public ScheduledTaskDefinition withExecution(UUID taskId, Instant dueAt, Instant nextRun, Instant now) {
        return new ScheduledTaskDefinition(id, name, scope, cronExpression, timeZone, title, description, specJson,
                actor, source, enabled, nextRun, dueAt, taskId, version + 1, createdAt, now);
    }

    public ScheduledTaskDefinition withEnabled(boolean value, Instant now) {
        return new ScheduledTaskDefinition(id, name, scope, cronExpression, timeZone, title, description, specJson,
                actor, source, value, nextRunAt, lastRunAt, lastTaskId, version + 1, createdAt, now);
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }
}
