package io.agentteams.manager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ProjectScopedModelCallAdmissionTest {
    @Test
    void legacyUnscopedRequestDoesNotCallQuotaPort() {
        AtomicInteger calls = new AtomicInteger();
        ProjectScopedModelCallAdmission admission = new ProjectScopedModelCallAdmission((tenant, project, tokens) -> {
            calls.incrementAndGet();
            return QuotaLease.noop();
        });

        admission.acquire(new ModelCallAdmissionRequest("qwen", "qwen-plus", 100)).close();

        assertThat(calls).hasValue(0);
    }

    @Test
    void forwardsProjectScopeAndTokenEstimate() {
        String[] values = new String[3];
        ProjectScopedModelCallAdmission admission = new ProjectScopedModelCallAdmission((tenant, project, tokens) -> {
            values[0] = tenant;
            values[1] = project;
            values[2] = Long.toString(tokens);
            return QuotaLease.noop();
        });

        admission.acquire(new ModelCallAdmissionRequest("qwen", "qwen-plus", 321, "tenant-a", "project-a"));

        assertThat(values).containsExactly("tenant-a", "project-a", "321");
    }

    @Test
    void translatesQuotaRejectionAndDoesNotReleaseMissingLease() {
        ProjectScopedModelCallAdmission admission = new ProjectScopedModelCallAdmission((tenant, project, tokens) -> {
            throw new QuotaRejectedException("daily_tokens");
        });

        assertThatThrownBy(() -> admission.acquire(
                new ModelCallAdmissionRequest("qwen", "qwen-plus", 321, "tenant-a", "project-a")))
                .isInstanceOf(ModelCallAdmissionRejectedException.class)
                .hasMessage("project quota rejected model call: daily_tokens")
                .hasCauseInstanceOf(QuotaRejectedException.class);
    }

    @Test
    void translatesQuotaDependencyFailureToRetryableClassification() {
        ProjectScopedModelCallAdmission admission = new ProjectScopedModelCallAdmission((tenant, project, tokens) -> {
            throw new IllegalStateException("gateway unavailable");
        });

        assertThatThrownBy(() -> admission.acquire(
                new ModelCallAdmissionRequest("qwen", "qwen-plus", 321, "tenant-a", "project-a")))
                .isInstanceOf(ModelCallAdmissionTemporaryFailureException.class)
                .hasMessage("project quota service is unavailable");
    }

    @Test
    void releaseIsIdempotentAcrossManagerAndQuotaWrappers() {
        AtomicInteger releases = new AtomicInteger();
        ProjectScopedModelCallAdmission admission = new ProjectScopedModelCallAdmission((tenant, project, tokens) ->
                QuotaLease.idempotent(releases::incrementAndGet));

        ModelCallLease lease = admission.acquire(
                new ModelCallAdmissionRequest("qwen", "qwen-plus", 321, "tenant-a", "project-a"));
        lease.close();
        lease.close();

        assertThat(releases).hasValue(1);
    }

    @Test
    void requestCompatibilityConstructorLeavesScopeUnset() {
        ModelCallAdmissionRequest request = new ModelCallAdmissionRequest("qwen", "qwen-plus", 100);

        assertThat(request.tenantId()).isNull();
        assertThat(request.projectId()).isNull();
        assertThat(request.hasProjectScope()).isFalse();
    }
}
