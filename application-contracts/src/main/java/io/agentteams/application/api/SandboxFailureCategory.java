package io.agentteams.application.api;

/** Stable, provider-neutral categories for sandbox failures. */
public enum SandboxFailureCategory {
    RUNTIME_CLASS_NOT_FOUND,
    POLICY_REJECTED,
    RESOURCE_QUOTA_EXCEEDED,
    KUBERNETES_UNAVAILABLE,
    STATUS_TIMEOUT,
    PROVIDER_RESOURCE_LOST,
    IDEMPOTENCY_CONFLICT,
    PROVIDER_RESPONSE_INVALID
}
