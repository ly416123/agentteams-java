package io.agentteams.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.Timestamp;
import io.agentteams.contracts.v1.ConfigFile;
import io.agentteams.contracts.v1.ConfigChanged;
import io.agentteams.contracts.v1.EventMetadata;
import io.agentteams.contracts.v1.ServerMessage;
import io.agentteams.application.api.TraceContext;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Converts a Control Plane ConfigChanged outbox event into a durable command. */
public final class ConfigChangedCommandHandler {
    public static final String EVENT_TYPE = "ConfigChanged";

    private final CommandDeliveryService delivery;
    private final ObjectMapper mapper;

    public ConfigChangedCommandHandler(CommandDeliveryService delivery, ObjectMapper mapper) {
        this.delivery = Objects.requireNonNull(delivery, "delivery");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    public boolean handle(String eventType, String aggregateId, String payloadJson, Instant occurredAt) {
        return handle(eventType, aggregateId, payloadJson, occurredAt, TraceContext.empty());
    }

    public boolean handle(String eventType, String aggregateId, String payloadJson, Instant occurredAt,
            TraceContext context) {
        if (!EVENT_TYPE.equals(eventType)) return false;
        JsonNode root = parse(payloadJson);
        UUID agentId = uuid(root, "agentId");
        if (!agentId.toString().equals(aggregateId)) {
            throw new IllegalArgumentException("ConfigChanged aggregateId does not match agentId");
        }
        UUID bindingId = uuid(root, "bindingId");
        UUID snapshotId = uuid(root, "snapshotId");
        long version = positive(root, "configVersion");
        String manifestJson = optionalText(root, "manifestJson", "");
        String manifestUri = optionalText(root, "manifestUri", "urn:agentteams:config:" + snapshotId);
        String checksum = text(root, "manifestSha256");
        long size = nonNegative(root, "sizeBytes");
        UUID eventId = uuid(root, "eventId");
        EventMetadata metadata = EventMetadata.newBuilder()
                .setEventId(eventId.toString())
                .setAgentId(agentId.toString())
                .setExpectedVersion(nonNegative(root, "expectedVersion", 0))
                .setOccurredAt(timestamp(occurredAt))
                .setCorrelationId(context == null ? "unknown" : context.correlationId())
                .setTraceparent(context == null ? "" : context.traceparent())
                .setTracestate(context == null ? "" : context.tracestate())
                .build();
        ConfigChanged changed = ConfigChanged.newBuilder()
                .setMetadata(metadata)
                .setConfigVersion(version)
                .setManifestUri(manifestUri)
                .setManifestSha256(checksum)
                .setSizeBytes(size)
                .setBindingId(bindingId.toString())
                .setSnapshotId(snapshotId.toString())
                .build();
        JsonNode files = root.get("files");
        if (files != null) {
            if (!files.isArray()) throw new IllegalArgumentException("files must be an array");
            ConfigChanged.Builder builder = changed.toBuilder();
            for (JsonNode file : files) {
                if (!file.isObject()) throw new IllegalArgumentException("config file must be an object");
                builder.addFiles(ConfigFile.newBuilder()
                        .setPath(text(file, "path"))
                        .setUri(text(file, "uri"))
                        .setSha256(text(file, "sha256"))
                        .setSizeBytes(nonNegative(file, "sizeBytes"))
                        .setContentType(text(file, "contentType"))
                        .build());
            }
            changed = builder.build();
        }
        if (!manifestJson.isBlank()) {
            changed = changed.toBuilder().setManifestJson(manifestJson).build();
        }
        delivery.deliver(agentId.toString(), ServerMessage.newBuilder().setConfigChanged(changed).build());
        return true;
    }

    private JsonNode parse(String payloadJson) {
        try {
            JsonNode root = mapper.readTree(payloadJson);
            if (root == null || !root.isObject()) throw new IllegalArgumentException("payload must be an object");
            return root;
        } catch (Exception error) {
            throw new IllegalArgumentException("ConfigChanged payload is invalid", error);
        }
    }

    private static UUID uuid(JsonNode root, String field) {
        try { return UUID.fromString(text(root, field)); }
        catch (IllegalArgumentException error) { throw new IllegalArgumentException(field + " must be a UUID", error); }
    }

    private static String text(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new IllegalArgumentException(field + " must be a non-blank string");
        }
        return value.asText();
    }

    private static String optionalText(JsonNode root, String field, String fallback) {
        JsonNode value = root.get(field);
        return value == null ? fallback : text(root, field);
    }

    private static long positive(JsonNode root, String field) {
        long value = nonNegative(root, field);
        if (value <= 0) throw new IllegalArgumentException(field + " must be positive");
        return value;
    }

    private static long nonNegative(JsonNode root, String field) { return nonNegative(root, field, -1); }

    private static long nonNegative(JsonNode root, String field, long fallback) {
        JsonNode value = root.get(field);
        if (value == null && fallback >= 0) return fallback;
        if (value == null || !value.canConvertToLong() || value.asLong() < 0) {
            throw new IllegalArgumentException(field + " must be a non-negative integer");
        }
        return value.asLong();
    }

    private static Timestamp timestamp(Instant instant) {
        Objects.requireNonNull(instant, "occurredAt");
        return Timestamp.newBuilder().setSeconds(instant.getEpochSecond()).setNanos(instant.getNano()).build();
    }
}
