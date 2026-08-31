package io.agentteams.controlplane.schedule;

import io.agentteams.controlplane.persistence.TaskRecord;
import io.agentteams.controlplane.security.AuthorizationService;
import io.agentteams.controlplane.security.Principal;
import io.agentteams.controlplane.security.PrincipalContext;
import io.agentteams.controlplane.service.TaskService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.scheduling.annotation.Scheduled;

/** Leader-only trigger; deterministic task idempotency makes restart replay safe. */
public final class ScheduledTaskScheduler {
    private final ScheduledTaskRepository schedules;
    private final TaskService tasks;
    private final io.agentteams.controlplane.service.SchedulerLeaseService lease;
    private final Clock clock;
    private final String owner;
    private final Duration leaseDuration;
    private final int batchSize;

    public ScheduledTaskScheduler(ScheduledTaskRepository schedules, TaskService tasks,
            io.agentteams.controlplane.service.SchedulerLeaseService lease, Clock clock,
            String owner, Duration leaseDuration, int batchSize) {
        this.schedules = Objects.requireNonNull(schedules, "schedules");
        this.tasks = Objects.requireNonNull(tasks, "tasks");
        this.lease = Objects.requireNonNull(lease, "lease");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (owner == null || owner.isBlank()) throw new IllegalArgumentException("owner must not be blank");
        this.owner = owner.trim();
        if (leaseDuration == null || leaseDuration.isNegative() || leaseDuration.isZero()) {
            throw new IllegalArgumentException("leaseDuration must be positive");
        }
        if (batchSize <= 0 || batchSize > 1000) throw new IllegalArgumentException("batchSize must be between 1 and 1000");
        this.leaseDuration = leaseDuration;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${agentteams.scheduled-tasks.poll-interval-ms:1000}")
    public void scheduledRun() {
        runOnce();
    }

    public RunResult runOnce() {
        Instant now = clock.instant();
        return lease.run("scheduled-tasks", owner, now, leaseDuration, () -> {
            int triggered = 0;
            int failed = 0;
            for (ScheduledTaskDefinition schedule : schedules.findDue(now, batchSize)) {
                try {
                    UUID taskId = trigger(schedule, now);
                    Instant nextRun = org.springframework.scheduling.support.CronExpression
                            .parse(schedule.cronExpression()).next(schedule.nextRunAt().atZone(ZoneId.of(schedule.timeZone())))
                            .toInstant();
                    if (schedules.advance(schedule.id(), schedule.nextRunAt(), taskId, nextRun, now)) triggered++;
                } catch (RuntimeException error) {
                    failed++;
                }
            }
            return new RunResult(true, triggered, failed);
        }).valueOr(new RunResult(false, 0, 0));
    }

    private UUID trigger(ScheduledTaskDefinition schedule, Instant now) {
        String project = schedule.scope().projectId() == null ? "scheduled" : schedule.scope().projectId();
        Principal principal = new Principal(schedule.actor(), new AuthorizationService.Scope(
                schedule.scope().tenantId(), project, "scheduled"),
                Set.of("task:create", "task:read", "task:cancel", "task:retry", "task:pause", "task:approve"));
        String scopeJson = withScope(schedule.specJson(), schedule.scope().tenantId(), project);
        try {
            PrincipalContext.set(principal);
            TaskRecord created = tasks.create("schedule:" + schedule.id() + ":" + schedule.nextRunAt(),
                    new TaskService.TaskInput(schedule.title(), schedule.description(), scopeJson,
                            schedule.actor(), schedule.source()));
            return created.id();
        } finally {
            PrincipalContext.clear();
        }
    }

    private static String withScope(String specJson, String tenant, String project) {
        try {
            com.fasterxml.jackson.databind.node.ObjectNode root = (com.fasterxml.jackson.databind.node.ObjectNode)
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(specJson);
            com.fasterxml.jackson.databind.node.ObjectNode scope = root.putObject("scope");
            scope.put("tenant", tenant);
            scope.put("project", project);
            scope.put("team", "scheduled");
            return root.toString();
        } catch (java.io.IOException error) {
            throw new IllegalArgumentException("scheduled task spec is invalid", error);
        }
    }

    public record RunResult(boolean leader, int triggeredTasks, int failedSchedules) {
        public RunResult {
            if (triggeredTasks < 0 || failedSchedules < 0) throw new IllegalArgumentException("counts must not be negative");
        }
    }
}
