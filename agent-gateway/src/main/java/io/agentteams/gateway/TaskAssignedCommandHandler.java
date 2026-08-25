package io.agentteams.gateway;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.ByteString;
import com.google.protobuf.Timestamp;
import io.agentteams.contracts.v1.EventMetadata;
import io.agentteams.contracts.v1.SandboxAssignment;
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
        OperationalDimensions dimensions = taskFields.dimensions().isEmpty()
                ? dimensionsFromSpec(payload.spec()) : taskFields.dimensions();
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
        TaskAssigned.Builder assignedBuilder = TaskAssigned.newBuilder()
                .setMetadata(metadata)
                .setTaskType(taskFields.taskType())
                .setInputJson(taskFields.inputJson())
                .addAllRequiredCapabilities(taskFields.requiredCapabilities())
                .setLeaseExpiresAt(timestamp(taskFields.leaseExpiresAt()))
                .setTenantId(dimensions.tenantIdOrEmpty())
                .setProjectId(dimensions.projectIdOrEmpty())
                .setTeamId(dimensions.teamIdOrEmpty())
                .setToolId(dimensions.toolIdOrEmpty())
                .setQuotaId(dimensions.quotaIdOrEmpty())
                .setQuotaDimension(dimensions.quotaDimensionOrEmpty());
        if (payload.sandbox() != null) {
            TaskAssignedCommandPayload.SandboxAssignmentPayload sandbox = payload.sandbox();
            assignedBuilder.setSandbox(SandboxAssignment.newBuilder()
                    .setSandboxId(sandbox.sandboxId())
                    .setProviderSandboxId(sandbox.providerSandboxId())
                    .setProfile(sandbox.profile())
                    .setStatus(sandbox.status())
                    .setEndpointRef(sandbox.endpointRef())
                    .setExpiresAt(timestamp(sandbox.expiresAt()))
                    .setOwnerTaskId(sandbox.ownerTaskId().toString())
                    .setOwnerAttemptId(sandbox.ownerAttemptId().toString())
                    .build());
        }
        TaskAssigned assigned = assignedBuilder.build();
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
            JsonNode spec = root.get("spec");
            OperationalDimensions dimensions = new OperationalDimensions(
                    optionalText(root, spec, "tenantId", "tenant_id", "tenant"),
                    optionalText(root, spec, "projectId", "project_id", "project"),
                    optionalText(root, spec, "teamId", "team_id", "team"),
                    optionalText(root, spec, "toolId", "tool_id"),
                    optionalText(root, spec, "quotaId", "quota_id"),
                    optionalText(root, spec, "quotaDimension", "quota_dimension"));
            return new KnownTaskFields(taskType, ByteString.copyFrom(MAPPER.writeValueAsBytes(inputJson)),
                    requiredCapabilities, leaseExpiresAt, dimensions);
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
            TaskAssignedCommandPayload.SandboxAssignmentPayload sandbox = parseSandbox(
                    root.get("sandbox"), taskId, attemptId);
            String requestedProfile = requestedSandboxProfile(root, spec);
            if (requestedProfile != null) {
                io.agentteams.application.api.SandboxProfile profile;
                try {
                    profile = io.agentteams.application.api.SandboxProfile.valueOf(
                            requestedProfile.toUpperCase(java.util.Locale.ROOT));
                } catch (IllegalArgumentException error) {
                    throw new IllegalArgumentException("sandboxProfile is invalid", error);
                }
                if (profile == io.agentteams.application.api.SandboxProfile.NONE && sandbox != null) {
                    throw new IllegalArgumentException("SandboxProfile.NONE must not carry sandbox assignment");
                }
                if (profile != io.agentteams.application.api.SandboxProfile.NONE && sandbox == null) {
                    throw new IllegalArgumentException("sandbox assignment is required for " + profile);
                }
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
                    spec, sandbox, extensions);
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("payloadJson is invalid JSON", error);
        }
    }

    private static TaskAssignedCommandPayload.SandboxAssignmentPayload parseSandbox(JsonNode node,
            UUID taskId, UUID attemptId) {
        if (node == null) {
            return null;
        }
        if (!node.isObject()) {
            throw new IllegalArgumentException("sandbox must be a JSON object");
        }
        String sandboxId = requiredAnyText(node, "sandboxId", "id");
        UUID ownerTaskId = parseUuid(requiredText(node, "ownerTaskId"), "sandbox.ownerTaskId");
        UUID ownerAttemptId = parseUuid(requiredText(node, "ownerAttemptId"), "sandbox.ownerAttemptId");
        if (!taskId.equals(ownerTaskId) || !attemptId.equals(ownerAttemptId)) {
            throw new IllegalArgumentException("sandbox owner does not match top-level task/attempt");
        }
        Instant expiresAt;
        try {
            expiresAt = Instant.parse(requiredText(node, "expiresAt"));
        } catch (java.time.DateTimeException error) {
            throw new IllegalArgumentException("sandbox.expiresAt must be ISO-8601", error);
        }
        String profile = requiredText(node, "profile").toUpperCase(java.util.Locale.ROOT);
        if ("NONE".equals(profile)) {
            throw new IllegalArgumentException("sandbox profile must be isolated or hardened");
        }
        String status = requiredText(node, "status").toUpperCase(java.util.Locale.ROOT);
        if (!"READY".equals(status) && !"RUNNING".equals(status)) {
            throw new IllegalArgumentException("sandbox status is not executable: " + status);
        }
        return new TaskAssignedCommandPayload.SandboxAssignmentPayload(sandboxId,
                requiredText(node, "providerSandboxId"), profile, status,
                requiredText(node, "endpointRef"), expiresAt, ownerTaskId, ownerAttemptId);
    }

    private static String requestedSandboxProfile(JsonNode root, JsonNode spec) {
        String profile = optionalText(root, "sandboxProfile");
        if (profile == null) profile = optionalText(root, "sandbox_profile");
        if (profile == null) profile = optionalText(spec, "sandboxProfile");
        if (profile == null) profile = optionalText(spec, "sandbox_profile");
        JsonNode sandbox = spec == null ? null : spec.get("sandbox");
        if (profile == null) profile = optionalText(sandbox, "profile");
        return profile;
    }

    private static String requiredAnyText(JsonNode object, String... fields) {
        for (String field : fields) {
            JsonNode value = object.get(field);
            if (value != null && value.isTextual() && !value.asText().isBlank()) {
                return value.asText();
            }
        }
        throw new IllegalArgumentException(fields[0] + " must be a non-blank string");
    }

    private static String requiredText(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new IllegalArgumentException(field + " must be a non-blank string");
        }
        return value.asText();
    }

    private static String optionalText(JsonNode root, JsonNode spec, String... names) {
        for (String name : names) {
            String value = optionalText(root, name);
            if (value != null) return value;
            value = optionalText(spec, name);
            if (value != null) return value;
        }
        JsonNode scope = spec == null ? null : spec.get("scope");
        if (scope != null && scope.isObject()) {
            for (String name : names) {
                String value = optionalText(scope, name);
                if (value != null) return value;
            }
        }
        return null;
    }

    private static String optionalText(JsonNode object, String field) {
        if (object == null || !object.isObject()) return null;
        JsonNode value = object.get(field);
        return value != null && value.isTextual() && !value.asText().isBlank() ? value.asText().trim() : null;
    }

    private static OperationalDimensions dimensionsFromSpec(JsonNode spec) {
        JsonNode scope = spec == null ? null : spec.get("scope");
        return new OperationalDimensions(firstOptionalText(spec, scope, "tenantId", "tenant_id", "tenant"),
                firstOptionalText(spec, scope, "projectId", "project_id", "project"),
                firstOptionalText(spec, scope, "teamId", "team_id", "team"),
                firstOptionalText(spec, null, "toolId", "tool_id"),
                firstOptionalText(spec, null, "quotaId", "quota_id"),
                firstOptionalText(spec, null, "quotaDimension", "quota_dimension"));
    }

    private static String firstOptionalText(JsonNode object, JsonNode fallback, String... names) {
        for (String name : names) {
            String value = optionalText(object, name);
            if (value != null) return value;
            value = optionalText(fallback, name);
            if (value != null) return value;
        }
        return null;
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
            Instant leaseExpiresAt, OperationalDimensions dimensions) {
        public KnownTaskFields(String taskType, ByteString inputJson, List<String> requiredCapabilities,
                Instant leaseExpiresAt) {
            this(taskType, inputJson, requiredCapabilities, leaseExpiresAt, OperationalDimensions.empty());
        }

        public KnownTaskFields {
            if (taskType == null || taskType.isBlank()) {
                throw new IllegalArgumentException("taskType must not be blank");
            }
            Objects.requireNonNull(inputJson, "inputJson");
            requiredCapabilities = List.copyOf(Objects.requireNonNull(requiredCapabilities,
                    "requiredCapabilities"));
            Objects.requireNonNull(leaseExpiresAt, "leaseExpiresAt");
            Objects.requireNonNull(dimensions, "dimensions");
        }
    }

    /** Safe, optional dimensions copied from the task specification to the Worker. */
    public record OperationalDimensions(String tenantId, String projectId, String teamId, String toolId,
            String quotaId, String quotaDimension) {
        public OperationalDimensions {
            tenantId = normalize(tenantId);
            projectId = normalize(projectId);
            teamId = normalize(teamId);
            toolId = normalize(toolId);
            quotaId = normalize(quotaId);
            quotaDimension = normalize(quotaDimension);
            if ((tenantId == null) != (projectId == null)) {
                throw new IllegalArgumentException("tenantId and projectId must be supplied together");
            }
        }

        public static OperationalDimensions empty() {
            return new OperationalDimensions(null, null, null, null, null, null);
        }

        boolean isEmpty() {
            return tenantId == null && projectId == null && teamId == null && toolId == null
                    && quotaId == null && quotaDimension == null;
        }

        String tenantIdOrEmpty() { return tenantId == null ? "" : tenantId; }
        String projectIdOrEmpty() { return projectId == null ? "" : projectId; }
        String teamIdOrEmpty() { return teamId == null ? "" : teamId; }
        String toolIdOrEmpty() { return toolId == null ? "" : toolId; }
        String quotaIdOrEmpty() { return quotaId == null ? "" : quotaId; }
        String quotaDimensionOrEmpty() { return quotaDimension == null ? "" : quotaDimension; }

        private static String normalize(String value) {
            return value == null || value.isBlank() ? null : value.trim();
        }
    }

    private static final class SetOfKnownFields {
        private static final java.util.Set<String> NAMES = java.util.Set.of(
                "taskId", "agentId", "attemptId", "assignmentId", "leaseId", "spec", "taskType",
                "inputJson", "requiredCapabilities", "leaseExpiresAt", "expectedVersion", "tenantId",
                "tenant_id", "projectId", "project_id", "teamId", "team_id", "toolId", "tool_id",
                "quotaId", "quota_id", "quotaDimension", "quota_dimension", "sandboxProfile",
                "sandbox_profile", "sandbox");
    }
}
