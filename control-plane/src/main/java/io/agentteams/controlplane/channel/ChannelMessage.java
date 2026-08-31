package io.agentteams.controlplane.channel;

import java.util.Objects;
import java.util.UUID;

/** Outbound channel command. It contains rendered content, never credentials or raw task state. */
public record ChannelMessage(UUID messageId, String organizationId, String tenantId, String projectId,
        String bindingId, String eventType, String renderedBody, String correlationId, ChannelType channelType) {
    public ChannelMessage(UUID messageId, String organizationId, String tenantId, String projectId,
            String bindingId, String eventType, String renderedBody, String correlationId) {
        this(messageId, organizationId, tenantId, projectId, bindingId, eventType, renderedBody, correlationId,
                ChannelType.WEBHOOK);
    }

    public ChannelMessage {
        Objects.requireNonNull(messageId, "messageId");
        organizationId = required(organizationId, "organizationId");
        tenantId = required(tenantId, "tenantId");
        projectId = required(projectId, "projectId");
        bindingId = required(bindingId, "bindingId");
        eventType = required(eventType, "eventType");
        renderedBody = required(renderedBody, "renderedBody");
        correlationId = correlationId == null || correlationId.isBlank() ? "unknown" : correlationId.trim();
        Objects.requireNonNull(channelType, "channelType");
        if (renderedBody.length() > 64_000) throw new IllegalArgumentException("renderedBody is too large");
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }
}
