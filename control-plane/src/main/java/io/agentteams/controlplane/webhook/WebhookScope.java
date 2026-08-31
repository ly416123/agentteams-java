package io.agentteams.controlplane.webhook;

/** Organization/tenant boundary for a Webhook subscription. */
public record WebhookScope(String organizationId, String tenantId, String projectId) {
    public WebhookScope {
        organizationId = required(organizationId, "organizationId");
        tenantId = required(tenantId, "tenantId");
        projectId = projectId == null || projectId.isBlank() ? null : projectId.trim();
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }
}
