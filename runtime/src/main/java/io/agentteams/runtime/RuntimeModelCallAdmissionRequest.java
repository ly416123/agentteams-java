package io.agentteams.runtime;

import java.util.Objects;

/** Immutable model-call estimate passed from a runtime task to admission. */
public record RuntimeModelCallAdmissionRequest(String provider, String model, int maxTokens,
        String tenantId, String projectId, RuntimeModelCallDimensions dimensions) {
    /** Compatibility constructor for callers that do not carry project scope. */
    public RuntimeModelCallAdmissionRequest(String provider, String model, int maxTokens) {
        this(provider, model, maxTokens, null, null, RuntimeModelCallDimensions.empty());
    }

    /** Compatibility constructor for callers that carry only project scope. */
    public RuntimeModelCallAdmissionRequest(String provider, String model, int maxTokens,
            String tenantId, String projectId) {
        this(provider, model, maxTokens, tenantId, projectId, RuntimeModelCallDimensions.empty());
    }

    public RuntimeModelCallAdmissionRequest {
        provider = requireText(provider, "provider");
        model = requireText(model, "model");
        if (maxTokens <= 0) {
            throw new IllegalArgumentException("maxTokens must be positive");
        }
        if ((tenantId == null) != (projectId == null)) {
            throw new IllegalArgumentException("tenantId and projectId must be supplied together");
        }
        if (tenantId != null) {
            tenantId = requireText(tenantId, "tenantId");
            projectId = requireText(projectId, "projectId");
        }
        dimensions = Objects.requireNonNull(dimensions, "dimensions");
    }

    public boolean hasProjectScope() {
        return tenantId != null;
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
