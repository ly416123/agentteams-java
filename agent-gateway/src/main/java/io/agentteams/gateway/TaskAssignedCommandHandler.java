package io.agentteams.gateway;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.ByteString;
import com.google.protobuf.Timestamp;
import io.agentteams.contracts.v1.EventMetadata;
import io.agentteams.contracts.v1.ServerMessage;
import io.agentteams.contracts.v1.TaskAssigned;
import io.agentteams.application.api.TraceContext;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Converts a TaskAssigned outbox payload into a durable Gateway command. */
public final class TaskAssignedCommandHandler {

    private static final String EVENT_TYPE = "TaskAssigned";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final CommandDeliveryService delivery;

    public TaskAssignedCommandHandler(CommandDeliveryService delivery) {
        this.delivery = Objects.requireNonNull(delivery, "delivery");
    }

    CommandDeliveryService delivery() {
        return delivery;
    }

    public boolean handle(String eventType, String aggregateId, String payloadJson, Instant occurredAt,
            KnownTaskFields taskFields) {
        return handle(eventType, aggregateId, payloadJson, occurredAt, taskFields, 0, TraceContext.empty());
    }

    private boolean handle(String eventType, String aggregateId, String payloadJson, Instant occurredAt,
            KnownTaskFields taskFields, long expectedVersion, TraceContext context) {
        if (!EVENT_TYPE.equals(eventType)) {
            return false;
        }
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(taskFields, "taskFields");
        UUID taskId = parseUuid(aggregateId, "aggregateId");
        TaskAssignedCommandPayload payload = parsePayload(taskId.toString(), payloadJson, occurredAt);
        if (!taskId.equals(payload.taskId())) {
            throw new IllegalArgumentException("payload taskId does not match aggregateId");
        }
        UUID eventId = UUID.nameUUIDFromBytes((EVENT_TYPE + ":" + payload.assignmentId())
                .getBytes(StandardCharsets.UTF_8));
        EventMetadata metadata = EventMetadata.newBuilder()
                .setEventId(eventId.toString())
                .setAgentId(payload.agentId())
                .setTaskId(payload.taskId().toString())
                .setAttemptId(payload.attemptId().toString())
                .setLeaseId(payload.leaseId().toString())
                .setExpectedVersion(expectedVersion)
                .setOccurredAt(timestamp(occurredAt))
                .setCorrelationId(context.correlationId())
                .setTraceparent(context.traceparent())
                .setTracestate(context.tracestate())
                .build();
        TaskAssigned assigned = TaskAssigned.newBuilder()
                .setMetadata(metadata)
                .setTaskType(taskFields.taskType())
                .setInputJson(taskFields.inputJson())
                .addAllRequiredCapabilities(taskFields.requiredCapabilities())
                .setLeaseExpiresAt(timestamp(taskFields.leaseExpiresAt()))
                .build();
        delivery.deliver(payload.agentId(), ServerMessage.newBuilder().setTaskAssigned(assigned).build());
        return true;
    }

    /** Handles the canonical assignment payload emitted by the Control Plane outbox. */
    public boolean handle(String eventType, String aggregateId, String payloadJson, Instant occurredAt) {
        return handle(eventType, aggregateId, payloadJson, occurredAt, TraceContext.empty());
    }

    public boolean handle(String eventType, String aggregateId, String payloadJson, Instant occurredAt,
            TraceContext context) {
        if (!EVENT_TYPE.equals(eventType)) {
            return false;
        }
        return handle(eventType, aggregateId, payloadJson, occurredAt, parseKnownTaskFields(payloadJson),
                parseExpectedVersion(payloadJson), context == null ? TraceContext.empty() : context);
    }

    private long parseExpectedVersion(String payloadJson) {
        try {
            JsonNode root = MAPPER.readTree(payloadJson);
            JsonNode value = root == null ? null : root.get("expectedVersion");
            if (value == null) {
                return 0;
            }
            if (!value.isIntegralNumber() || value.asLong() < 0) {
                throw new IllegalArgumentException("expectedVersion must be a non-negative integer");
            }
            return value.asLong();
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("payloadJson is invalid JSON", error);
        }
    }

    public KnownTaskFields parseKnownTaskFields(String payloadJson) {
        if (payloadJson == null || payloadJson.isBlank()) {
            throw new IllegalArgumentException("payloadJson must not be blank");
        }
        try {
            JsonNode root = MAPPER.readTree(payloadJson);
            if (root == null || !root.isObject()) {
                throw new IllegalArgumentException("payloadJson must contain a JSON object");
            }
            String taskType = requiredText(root, "taskType");
            JsonNode inputJson = root.get("inputJson");
            if (inputJson == null) {
                throw new IllegalArgumentException("inputJson must be present");
            }
            JsonNode capabilities = root.get("requiredCapabilities");
            if (capabilities == null || !capabilities.isArray()) {
                throw new IllegalArgumentException("requiredCapabilities must be an array");
            }
            List<String> requiredCapabilities = new java.util.ArrayList<>();
            for (JsonNode capability : capabilities) {
                if (!capability.isTextual() || capability.asText().isBlank()) {
                    throw new IllegalArgumentException("requiredCapabilities must contain non-blank strings");
                }
                requiredCapabilities.add(capability.asText());
            }
            Instant leaseExpiresAt = Instant.parse(requiredText(root, "leaseExpiresAt"));
            return new KnownTaskFields(taskType, ByteString.copyFrom(MAPPER.writeValueAsBytes(inputJson)),
                    requiredCapabilities, leaseExpiresAt);
        } catch (JsonProcessingException | java.time.DateTimeException error) {
            throw new IllegalArgumentException("canonical assignment fields are invalid", error);
        }
    }

    public TaskAssignedCommandPayload parsePayload(String aggregateId, String payloadJson, Instant occurredAt) {
        Objects.requireNonNull(occurredAt, "occurredAt");
        UUID aggregate = parseUuid(aggregateId, "aggregateId");
        if (payloadJson == null || payloadJson.isBlank()) {
            throw new IllegalArgumentException("payloadJson must not be blank");
        }
        try {
            JsonNode root = MAPPER.readTree(payloadJson);
            if (root == null || !root.isObject()) {
                throw new IllegalArgumentException("payloadJson must contain a JSON object");
            }
            UUID taskId = parseUuid(requiredText(root, "taskId"), "taskId");
            if (!aggregate.equals(taskId)) {
                throw new IllegalArgumentException("payload taskId does not match aggregateId");
            }
            String agentId = requiredText(root, "agentId");
            UUID attemptId = parseUuid(requiredText(root, "attemptId"), "attemptId");
            UUID assignmentId = parseUuid(requiredText(root, "assignmentId"), "assignmentId");
            UUID leaseId = parseUuid(requiredText(root, "leaseId"), "leaseId");
            JsonNode spec = root.get("spec");
            if (spec == null || !spec.isObject()) {
                throw new IllegalArgumentException("spec must be a JSON object");
            }
            Map<String, JsonNode> extensions = new LinkedHashMap<>();
            Iterator<Map.Entry<String, JsonNode>> fields = root.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (!SetOfKnownFields.NAMES.contains(field.getKey())) {
                    extensions.put(field.getKey(), field.getValue());
                }
            }
            return new TaskAssignedCommandPayload(taskId, agentId, attemptId, assignmentId, leaseId,
                    spec, extensions);
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("payloadJson is invalid JSON", error);
        }
    }

    private static String requiredText(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new IllegalArgumentException(field + " must be a non-blank string");
        }
        return value.asText();
    }

    private static UUID parseUuid(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must be a UUID");
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException(field + " must be a UUID", error);
        }
    }

    private static Timestamp timestamp(Instant instant) {
        return Timestamp.newBuilder().setSeconds(instant.getEpochSecond()).setNanos(instant.getNano()).build();
    }

    public record KnownTaskFields(String taskType, ByteString inputJson, List<String> requiredCapabilities,
            Instant leaseExpiresAt) {
        public KnownTaskFields {
            if (taskType == null || taskType.isBlank()) {
                throw new IllegalArgumentException("taskType must not be blank");
            }
            Objects.requireNonNull(inputJson, "inputJson");
            requiredCapabilities = List.copyOf(Objects.requireNonNull(requiredCapabilities,
                    "requiredCapabilities"));
            Objects.requireNonNull(leaseExpiresAt, "leaseExpiresAt");
        }
    }

    private static final class SetOfKnownFields {
        private static final java.util.Set<String> NAMES = java.util.Set.of(
                "taskId", "agentId", "attemptId", "assignmentId", "leaseId", "spec", "taskType",
                "inputJson", "requiredCapabilities", "leaseExpiresAt", "expectedVersion");
    }
}
