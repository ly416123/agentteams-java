package io.agentteams.manager;

import java.util.Objects;

/**
 * The admission estimate for one model call.
 *
 * <p>{@code maxTokens} is deliberately the request upper bound rather than
 * the eventual completion usage. A control-plane implementation can reserve
 * that upper bound before the provider is invoked and settle/release it when
 * the call lifecycle finishes.</p>
 */
public record ModelCallAdmissionRequest(String provider, String model, int maxTokens,
        String tenantId, String projectId, ModelCallDimensions dimensions) {
    /** Compatibility constructor for callers that do not carry project scope. */
    public ModelCallAdmissionRequest(String provider, String model, int maxTokens) {
        this(provider, model, maxTokens, null, null, ModelCallDimensions.empty());
    }

    /** Compatibility constructor for callers that carry only project scope. */
    public ModelCallAdmissionRequest(String provider, String model, int maxTokens,
            String tenantId, String projectId) {
        this(provider, model, maxTokens, tenantId, projectId, ModelCallDimensions.empty());
    }

    public ModelCallAdmissionRequest {
        provider = requireName(provider, "provider");
        model = requireName(model, "model");
        if (maxTokens <= 0) {
            throw new IllegalArgumentException("maxTokens must be positive");
        }
        if ((tenantId == null) != (projectId == null)) {
            throw new IllegalArgumentException("tenantId and projectId must be supplied together");
        }
        if (tenantId != null) {
            tenantId = requireName(tenantId, "tenantId");
            projectId = requireName(projectId, "projectId");
        }
        dimensions = Objects.requireNonNull(dimensions, "dimensions");
    }

    public boolean hasProjectScope() {
        return tenantId != null;
    }

    private static String requireName(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
