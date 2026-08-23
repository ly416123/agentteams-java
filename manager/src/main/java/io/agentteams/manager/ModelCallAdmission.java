package io.agentteams.manager;

import java.util.Objects;

/**
 * Admission port for reserving model-call capacity before a provider call.
 *
 * <p>An implementation should throw {@link ModelCallAdmissionRejectedException}
 * when the call cannot be admitted. In that case the manager does not invoke
 * the provider. A successful acquire must return a non-null lease.</p>
 */
@FunctionalInterface
public interface ModelCallAdmission {
    ModelCallLease acquire(ModelCallAdmissionRequest request);

    /** Default adapter used by legacy constructors: no quota is enforced. */
    static ModelCallAdmission noop() {
        return request -> {
            Objects.requireNonNull(request, "request");
            return ModelCallLease.noop();
        };
    }
}
