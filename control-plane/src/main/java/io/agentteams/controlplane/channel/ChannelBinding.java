package io.agentteams.controlplane.channel;

import java.util.Objects;

public record ChannelBinding(ChannelType type, String bindingId, String organizationId, String tenantId,
        String projectId) {
    public ChannelBinding {
        Objects.requireNonNull(type, "type");
        bindingId = required(bindingId, "bindingId");
        organizationId = required(organizationId, "organizationId");
        tenantId = required(tenantId, "tenantId");
        projectId = required(projectId, "projectId");
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }
}
