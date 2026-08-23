package io.agentteams.runtime;

import java.util.Objects;

/**
 * Runtime-side admission boundary for an external model invocation.
 *
 * <p>The runtime module intentionally does not depend on the manager module.
 * The Worker can therefore provide a local or remote adapter without making
 * the QwenPaw transport aware of a particular control-plane implementation.</p>
 */
@FunctionalInterface
public interface RuntimeModelCallAdmission {
    RuntimeModelCallLease acquire(RuntimeModelCallAdmissionRequest request);

    /** Legacy/default adapter: the call path is still traversed, but no quota is enforced. */
    static RuntimeModelCallAdmission noop() {
        return request -> {
            Objects.requireNonNull(request, "request");
            return RuntimeModelCallLease.noop();
        };
    }
}
