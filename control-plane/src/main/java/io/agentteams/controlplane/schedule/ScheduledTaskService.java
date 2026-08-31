package io.agentteams.controlplane.schedule;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Iterator;
import java.util.Objects;
import java.util.UUID;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;

/** Validates schedule definitions and enforces their organization/tenant boundary. */
@Service
public final class ScheduledTaskService {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final ScheduledTaskRepository repository;
    private final Clock clock;

    public ScheduledTaskService(ScheduledTaskRepository repository, Clock clock) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public ScheduledTaskDefinition create(CreateRequest request) {
        return create(request, clock.instant());
    }

    public ScheduledTaskDefinition create(CreateRequest request, Instant now) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(now, "now");
        String name = required(request.name(), "name");
        ScheduledTaskScope scope = Objects.requireNonNull(request.scope(), "scope");
        String cron = required(request.cronExpression(), "cronExpression");
        try {
            CronExpression.parse(cron);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("cronExpression is invalid", error);
        }
        ZoneId zone = parseZone(request.timeZone());
        String title = required(request.title(), "title");
        String description = request.description() == null ? "" : request.description().trim();
        rejectSensitive("description", description);
        String spec = objectJson(request.specJson());
        rejectSensitiveJson(spec);
        Instant nextRun = nextRun(cron, zone, now);
        ScheduledTaskDefinition definition = new ScheduledTaskDefinition(UUID.randomUUID(), name, scope, cron,
                zone.getId(), title, description, spec, defaultText(request.actor(), "scheduler"),
                defaultText(request.source(), "api"), true, nextRun, null, null, 0, now, now);
        return repository.insert(definition);
    }

    public ScheduledTaskDefinition find(ScheduledTaskScope scope, UUID id) {
        return repository.find(Objects.requireNonNull(scope, "scope"), Objects.requireNonNull(id, "id"))
                .orElseThrow(ScheduledTaskNotFoundException::new);
    }

    public java.util.List<ScheduledTaskDefinition> list(ScheduledTaskScope scope) {
        return repository.list(Objects.requireNonNull(scope, "scope"));
    }

    public ScheduledTaskDefinition pause(ScheduledTaskScope scope, UUID id, String operationKey, Instant now) {
        return repository.transition(scope, id, true, false, required(operationKey, "operationKey"), now);
    }

    public ScheduledTaskDefinition pause(ScheduledTaskScope scope, UUID id, String operationKey) {
        return pause(scope, id, operationKey, clock.instant());
    }

    public ScheduledTaskDefinition resume(ScheduledTaskScope scope, UUID id, String operationKey, Instant now) {
        ScheduledTaskDefinition current = find(scope, id);
        if (!current.enabled()) {
            Instant nextRun = nextRun(current.cronExpression(), ZoneId.of(current.timeZone()), now);
            return repository.resume(scope, id, required(operationKey, "operationKey"), nextRun, now);
        }
        return repository.resume(scope, id, required(operationKey, "operationKey"), current.nextRunAt(), now);
    }

    public ScheduledTaskDefinition resume(ScheduledTaskScope scope, UUID id, String operationKey) {
        return resume(scope, id, operationKey, clock.instant());
    }

    private static Instant nextRun(String cron, ZoneId zone, Instant now) {
        java.time.ZonedDateTime next = CronExpression.parse(cron).next(now.atZone(zone));
        if (next == null) throw new IllegalArgumentException("cron has no future execution");
        return next.toInstant();
    }

    private static String objectJson(String value) {
        String json = value == null || value.isBlank() ? "{}" : value.trim();
        try {
            JsonNode root = JSON.readTree(json);
            if (root == null || !root.isObject()) throw new IllegalArgumentException("specJson must be a JSON object");
            return root.toString();
        } catch (java.io.IOException error) {
            throw new IllegalArgumentException("specJson must be valid JSON", error);
        }
    }

    private static void rejectSensitiveJson(String value) {
        try {
            rejectNode(JSON.readTree(value));
        } catch (java.io.IOException error) {
            throw new IllegalArgumentException("specJson must be valid JSON", error);
        }
    }

    private static void rejectNode(JsonNode node) {
        if (node == null) return;
        if (node.isObject()) {
            Iterator<java.util.Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                if (isSensitive(field.getKey())) throw new IllegalArgumentException("specJson contains sensitive field");
                rejectNode(field.getValue());
            }
        } else if (node.isArray()) {
            node.forEach(ScheduledTaskService::rejectNode);
        }
    }

    private static void rejectSensitive(String field, String value) {
        if (isSensitive(value)) throw new IllegalArgumentException(field + " must not contain sensitive material");
    }

    private static boolean isSensitive(String value) {
        String normalized = value.toLowerCase(java.util.Locale.ROOT);
        return normalized.contains("prompt") || normalized.contains("secret")
                || normalized.contains("credential") || normalized.contains("password")
                || normalized.contains("authorization") || normalized.contains("api_key");
    }

    private static ZoneId parseZone(String value) {
        try {
            return ZoneId.of(required(value, "timeZone"));
        } catch (java.time.DateTimeException error) {
            throw new IllegalArgumentException("timeZone is invalid", error);
        }
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }

    private static String defaultText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    public record CreateRequest(String name, ScheduledTaskScope scope, String cronExpression, String timeZone,
            String title, String description, String specJson, String actor, String source) {
    }
}
