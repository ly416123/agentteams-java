package io.agentteams.manager;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/** Small in-memory admission port used by Manager unit tests. */
final class FakeModelCallAdmission implements ModelCallAdmission {
    private final List<ModelCallAdmissionRequest> requests = new ArrayList<>();
    private final Consumer<String> lifecycle;
    private boolean reject;
    private int releases;

    FakeModelCallAdmission() {
        this(event -> { });
    }

    FakeModelCallAdmission(Consumer<String> lifecycle) {
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
    }

    @Override
    public ModelCallLease acquire(ModelCallAdmissionRequest request) {
        requests.add(request);
        lifecycle.accept("acquire");
        if (reject) {
            throw new ModelCallAdmissionRejectedException("fake quota rejected");
        }
        return ModelCallLease.idempotent(() -> {
            releases++;
            lifecycle.accept("release");
        });
    }

    void reject() {
        reject = true;
    }

    List<ModelCallAdmissionRequest> requests() {
        return List.copyOf(requests);
    }

    int releases() {
        return releases;
    }
}
